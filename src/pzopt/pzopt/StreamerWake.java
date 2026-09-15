package pzopt;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Replaces WorldStreamer.threadLoop()'s fixed Thread.sleep(140L) calls with a
 * park that addJob can cut short. LockSupport keeps a permit, so a signal
 * that arrives before the park makes the park return immediately: no lost
 * wake-ups. With Config.WAKE off, idle() is exactly the stock sleep.
 */
public final class StreamerWake {
   private static volatile Thread streamer;

   private StreamerWake() {
   }

   /** Called once from the streamer thread so signal() knows whom to unpark. */
   public static void register() {
      streamer = Thread.currentThread();
   }

   /** Stock: sleep the full time. Wake mode: sleep at most that long, less if work arrives. */
   public static void idle(long millis) throws InterruptedException {
      if (!Config.effectiveWake() || streamer == null) {
         Thread.sleep(millis);
         return;
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
      if (Thread.interrupted()) {
         throw new InterruptedException();
      }
   }

   /** Called from addJob (game thread); cheap, never blocks. */
   public static void signal() {
      Thread t = streamer;
      if (Config.effectiveWake() && t != null) {
         LockSupport.unpark(t);
      }
   }
}
