package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import zombie.ZomboidFileSystem;
import zombie.debug.DebugLog;

/**
 * Timestamps every console log line into Zomboid/pzopt-loadtrace.out
 * ("epochMs<TAB>line"), so the game's own phase markers ("X start" / "X end",
 * "STATE: ...", "game loading took", the [pzopt] harness lines) can be turned
 * into a load-phase table (harness/loadtime.py). console.txt is untouched.
 *
 * Hooks DebugLog's "recording" output: every line DebugLog echoes to
 * console.txt is also printed to that stream. The animation-player recorder
 * (a debug tool) sets the same stream; it is chained if present.
 *
 * Installed when the first override loads, only for harness runs or with
 * instrument=true.
 */
public final class LoadTrace {
   private static volatile boolean installed;

   private LoadTrace() {
   }

   public static void install() {
      if (installed) {
         return;
      }
      if (!Config.INSTRUMENT && HarnessFlags.get("mode") == null) {
         return;
      }
      installed = true;
      try {
         File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-loadtrace.out");
         BufferedWriter w = new BufferedWriter(new FileWriter(f, false), 1 << 14);
         PrintStream previous = DebugLog.getInstance().getRecordingOut();
         DebugLog.getInstance().setRecordingOut(new Tee(w, previous));
         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (w) {
               try {
                  w.flush();
               } catch (IOException ignored) {
               }
            }
         }, "pzopt-loadtrace-flush"));
      } catch (Throwable t) {
         Log.warn("load trace not installed: " + t);
      }
   }

   /** Stamp an event of our own into the trace (e.g. state changes seen by the auto-start thread). */
   public static void mark(String what) {
      Log.info(what); // DebugLog echoes it into the trace with the stamp
   }

   private static final class Tee extends PrintStream {
      private final BufferedWriter w;
      private final PrintStream previous;
      private long lastFlushMs;

      Tee(BufferedWriter w, PrintStream previous) {
         super(OutputStream.nullOutputStream(), false);
         this.w = w;
         this.previous = previous;
      }

      @Override
      public void println(String line) {
         if (previous != null) {
            previous.println(line);
         }
         long now = System.currentTimeMillis();
         synchronized (w) {
            try {
               w.write(Long.toString(now));
               w.write('\t');
               w.write(line);
               w.newLine();
               if (now - lastFlushMs > 1000L) { // a crash mid-load still leaves the trace on disk
                  w.flush();
                  lastFlushMs = now;
               }
            } catch (IOException ignored) {
            }
         }
      }

      @Override
      public void println(Object o) {
         println(String.valueOf(o));
      }

      @Override
      public void flush() {
         synchronized (w) {
            try {
               w.flush();
            } catch (IOException ignored) {
            }
         }
      }
   }
}
