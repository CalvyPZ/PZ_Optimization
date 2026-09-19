package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.luaj.kahluafork.compiler.LexState;
import se.krka.kahlua.vm.Prototype;
import zombie.ZomboidFileSystem;

/**
 * Compiles every Lua file of the game and the active mods on a thread pool
 * during boot, so the serial loads (LoadDirBase at boot, the server Lua at
 * Continue, the map objects.lua files of OnLoadMapZones) get their
 * prototypes from a cache instead of compiling one file at a time
 * (docs/plan-instant-load.md, B4).
 *
 * Exactness: the compiler (org.luaj.kahluafork LexState/FuncState) has no
 * mutable static state apart from the two "current file" strings that it
 * stamps into each prototype for stack traces; the workers do not touch
 * those, and the stamps are rewritten afterwards to what the game's own
 * compile would have produced (the file name and the absolute path). A
 * prototype is looked up by chunk name plus a SHA-256 of the exact character
 * sequence the game's reader yields (UTF-8 decode of the file), so a changed
 * or unknown file simply compiles as stock. Prototypes are immutable once
 * built, so sharing one between the cache and a closure is safe.
 */
public final class LuaPrecompiler {
   private static final ConcurrentHashMap<String, Future<Prototype>> cache = new ConcurrentHashMap<>();
   private static volatile boolean started;
   private static final AtomicInteger hits = new AtomicInteger();
   private static final AtomicInteger misses = new AtomicInteger();
   private static final AtomicInteger compiled = new AtomicInteger();
   private static final AtomicInteger failed = new AtomicInteger();

   private LuaPrecompiler() {
   }

   public static boolean enabled() {
      return Config.LUA_PRECOMPILE && Overrides.enabled();
   }

   /** Call once the mod list is known (after ZomboidFileSystem.loadModPackFiles). Returns at once. */
   public static synchronized void start() {
      if (!enabled() || started) {
         return;
      }
      started = true;
      long t0 = System.nanoTime();
      List<String> files = new ArrayList<>();
      for (String sub : new String[]{"shared", "client", "server"}) {
         collect("media/lua/" + sub, files);
      }
      collect("media/maps", files); // objects.lua of every map, loaded by OnLoadMapZones at Continue
      int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
      ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
         Thread t = new Thread(r, "pzopt-lua-precompile");
         t.setDaemon(true);
         t.setPriority(Thread.NORM_PRIORITY - 1);
         return t;
      });
      for (String relPath : files) {
         String abs = ZomboidFileSystem.instance.getString(relPath).replace("\\", "/");
         String name = abs.substring(abs.lastIndexOf('/') + 1);
         // the key needs the content, which needs the file read; the read happens on the worker too, so the
         // entry is registered under a provisional key and re-keyed when known (see compileOne)
         pool.submit(() -> compileOne(abs, name));
      }
      pool.shutdown();
      Log.info("lua precompile: " + files.size() + " files queued on " + threads + " threads ("
            + (System.nanoTime() - t0) / 1_000_000 + " ms to list)");
   }

   private static void collect(String relDir, List<String> out) {
      try {
         String[] found = ZomboidFileSystem.instance.resolveAllFiles(relDir, f -> f.getName().toLowerCase().endsWith(".lua"), true);
         for (String f : found) {
            out.add(f);
         }
      } catch (Throwable t) {
         Log.warn("lua precompile: listing " + relDir + ": " + t);
      }
   }

   private static void compileOne(String abs, String name) {
      try {
         String content = readAll(abs);
         String key = key(name, content);
         if (cache.containsKey(key)) {
            return;
         }
         java.util.concurrent.FutureTask<Prototype> task = new java.util.concurrent.FutureTask<>(() -> {
            StringReader reader = new StringReader(content);
            Prototype p = LexState.compile(reader.read(), reader, name, null);
            stamp(p, name, abs);
            compiled.incrementAndGet();
            return p;
         });
         if (cache.putIfAbsent(key, task) == null) {
            task.run();
         }
      } catch (Throwable t) {
         failed.incrementAndGet();
         if (failed.get() <= 5) {
            Log.warn("lua precompile: " + abs + ": " + t);
         }
      }
   }

   /** The exact characters the game's own reader produces for this file. */
   private static String readAll(String abs) throws Exception {
      try (Reader r = new BufferedReader(new InputStreamReader(new FileInputStream(new File(abs)), StandardCharsets.UTF_8))) {
         StringBuilder sb = new StringBuilder(1 << 14);
         char[] buf = new char[1 << 14];
         int n;
         while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
         }
         return sb.toString();
      }
   }

   private static void stamp(Prototype p, String file, String filename) {
      p.file = file;
      p.filename = filename;
      if (p.prototypes != null) {
         for (Prototype child : p.prototypes) {
            if (child != null) {
               stamp(child, file, filename);
            }
         }
      }
   }

   private static String key(String name, String content) throws Exception {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(content.getBytes(StandardCharsets.UTF_16BE));
      StringBuilder sb = new StringBuilder(name).append('|').append(content.length()).append('|');
      for (byte b : md.digest()) {
         sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
   }

   /**
    * The cached prototype for this chunk, or null (not precompiled, still compiling, or failed): the caller then
    * compiles as stock. Called by the LuaCompiler override with the whole file already read.
    */
   public static Prototype lookup(String name, String content) {
      if (!started) {
         return null;
      }
      try {
         Future<Prototype> f = cache.get(key(name, content));
         if (f != null && f.isDone()) {
            Prototype p = f.get();
            hits.incrementAndGet();
            return p;
         }
      } catch (Throwable t) {
         // a failed precompile: stock compile reports the error itself
      }
      misses.incrementAndGet();
      return null;
   }

   public static String stats() {
      return "lua precompile: compiled=" + compiled.get() + " failed=" + failed.get() + " hits=" + hits.get() + " misses=" + misses.get();
   }
}
