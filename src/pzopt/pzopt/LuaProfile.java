package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import se.krka.kahlua.vm.Coroutine;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import zombie.Lua.LuaManager;
import zombie.ZomboidFileSystem;

/**
 * luaProfile (2026-09-23, the Dell pass): which Lua functions the game thread runs. The game-thread sampler
 * (GameThreadProfile) only sees Java frames, and ~13 % of the Dell's game thread was "ui update" / "ui draw" going
 * through KahluaThread with no hint of the UI element. When a sampled game-thread stack is inside Kahlua, this reads the
 * main Lua thread's current coroutine call frames (racy, read-only: a torn read only mislabels one sample) and counts
 * the innermost Lua function with two callers as {@code function@file:line < caller@file < caller@file}.
 * One line a second in Zomboid/pzopt-lua.out: {@code epoch_ms<TAB>samples<TAB>key=count...} (top 40 keys).
 */
public final class LuaProfile {
   private static final HashMap<String, int[]> counts = new HashMap<>();
   private static int samples;
   private static long secondEnd;
   private static BufferedWriter out;
   private static boolean failed;

   private LuaProfile() {
   }

   /** Sampler thread, right after a game-thread stack that contains Kahlua frames. */
   static void sample() {
      if (failed) {
         return;
      }
      try {
         KahluaThread t = LuaManager.thread;
         Coroutine c = t == null ? null : t.currentCoroutine;
         if (c == null) {
            return;
         }
         LuaCallFrame[] stack = c.getCallframeStack();
         int top = Math.min(c.getCallframeTop(), stack.length);
         StringBuilder key = new StringBuilder(96);
         int found = 0;
         for (int i = top - 1; i >= 0 && found < 3; i--) {
            LuaCallFrame f = stack[i];
            LuaClosure cl = f == null ? null : f.closure;
            Prototype p = cl == null ? null : cl.prototype;
            if (p == null) {
               continue;
            }
            if (found > 0) {
               key.append(" < ");
            }
            key.append(p.name == null ? "?" : p.name).append('@').append(shortFile(p.filename));
            if (found == 0 && p.lines != null && f.pc > 0 && f.pc <= p.lines.length) {
               key.append(':').append(p.lines[f.pc - 1]);
            }
            found++;
         }
         if (found == 0) {
            return;
         }
         counts.computeIfAbsent(key.toString(), k -> new int[1])[0]++;
         samples++;
      } catch (Throwable e) {
         // a torn read of the live frames: drop this sample
      }
   }

   private static String shortFile(String f) {
      if (f == null) {
         return "?";
      }
      int i = f.lastIndexOf('/');
      return i >= 0 ? f.substring(i + 1) : f;
   }

   /** Sampler thread, once per loop pass: flush the finished second. */
   static void tick(long nowMs) {
      if (nowMs < secondEnd) {
         return;
      }
      if (secondEnd != 0 && samples > 0 && !failed) {
         try {
            if (out == null) {
               out = new BufferedWriter(new FileWriter(new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-lua.out"), false), 1 << 16);
               out.write("# pzopt Lua profile: epoch_ms, game-thread samples inside Kahlua this second, then function@file:line < caller < caller = count (top 40)\n");
            }
            ArrayList<Map.Entry<String, int[]>> rows = new ArrayList<>(counts.entrySet());
            rows.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            StringBuilder sb = new StringBuilder().append(secondEnd - 1000).append('\t').append(samples);
            for (int i = 0; i < rows.size() && i < 40; i++) {
               sb.append('\t').append(rows.get(i).getKey().replace('\t', ' ').replace('=', ':')).append('=').append(rows.get(i).getValue()[0]);
            }
            out.write(sb.append('\n').toString());
            out.flush();
         } catch (Exception e) {
            failed = true;
            Log.warn("luaProfile: " + e);
         }
      }
      counts.clear();
      samples = 0;
      secondEnd = nowMs - nowMs % 1000 + 1000;
   }
}
