package pzopt;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import zombie.GameWindow;

/**
 * Keeps the game's async file system busy during boot
 * (docs/plan-instant-load.md, B10).
 *
 * The file pool (texture decode, model and animation import) is only driven
 * by FileSystemImpl.updateAsyncTransactions, which the stock game calls once
 * per frame from GameWindow.logic. Frames start when the main menu appears,
 * so during the 4-5 s of GameWindow.init the pool threads sit idle although
 * every texture pack page and, after ModelManager.create, all 3,990
 * animations are already queued; the load after Continue then waits for them
 * (phase D). This thread pumps the file system every few milliseconds from
 * the start of GameWindow.init until the main loop takes over. Concurrent
 * pumping is made safe by a lock inside updateAsyncTransactions (the main
 * thread pumps too while fonts load).
 */
public final class BootPump {
   private static volatile boolean running;
   private static Thread thread;
   private static long startNs;
   private static int failures;
   private static volatile boolean paused;

   /**
    * The main thread's own pump loops (font loading) must run alone: AngelCodeFont's asset observer reads fields the
    * main thread is still filling in, so a completion from this thread there ended in a NullPointerException.
    */
   public static void pause() {
      paused = true;
   }

   public static void resume() {
      paused = false;
   }

   private BootPump() {
   }

   public static boolean enabled() {
      return Config.BOOT_PUMP && Overrides.enabled();
   }

   public static synchronized void start() {
      if (!enabled() || thread != null) {
         return;
      }
      startNs = System.nanoTime();
      running = true;
      thread = new Thread(() -> {
         while (running) {
            try {
               if (!paused) {
                  GameWindow.fileSystem.updateAsyncTransactions();
               }
            } catch (Throwable t) {
               if (++failures <= 3) {
                  java.io.StringWriter sw = new java.io.StringWriter();
                  t.printStackTrace(new java.io.PrintWriter(sw));
                  Log.warn("boot pump: " + sw);
               }
            }
            try {
               Thread.sleep(3L);
            } catch (InterruptedException e) {
               return;
            }
         }
      }, "pzopt-boot-pump");
      thread.setDaemon(true);
      thread.start();
      Log.info("boot pump started (file pool " + Config.BOOT_FILE_THREADS + " wide until the load starts)");
   }

   public static synchronized void stop() {
      if (thread == null) {
         return;
      }
      running = false;
      thread.interrupt();
      try {
         thread.join(2000L);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
      thread = null;
      Log.info(String.format("boot pump stopped after %.2f s; file system %s", (System.nanoTime() - startNs) / 1e9,
            GameWindow.fileSystem.hasWork() ? "still has work" : "idle"));
      Log.info(FileTaskStats.summary());
      if (AnimClipCache.enabled()) {
         Log.info(AnimClipCache.stats());
      }
   }

   /**
    * Called when the loading screen starts: the meta-grid loaders and the recalc pool need the cores now, so
    * the file pool shrinks back to its play width (idle threads exit as they finish their task).
    */
   public static void onLoadStart(ExecutorService executor) {
      if (!enabled() || !(executor instanceof ThreadPoolExecutor)) {
         return;
      }
      ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
      int want = Config.FILE_THREADS;
      if (pool.getCorePoolSize() != want) {
         pool.setCorePoolSize(want);
         pool.setMaximumPoolSize(Math.max(want, pool.getMaximumPoolSize()));
         Log.info("file pool " + pool.getMaximumPoolSize() + " -> " + want + " threads for the load; "
               + (GameWindow.fileSystem.hasWork() ? "work remains" : "no work pending"));
      }
      Log.info(FileTaskStats.summary());
      if (AnimClipCache.enabled()) {
         Log.info(AnimClipCache.stats());
      }
   }
}
