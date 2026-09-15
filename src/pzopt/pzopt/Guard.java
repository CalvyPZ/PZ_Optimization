package pzopt;

/**
 * Development-build assertion for code the game restricts to the game thread
 * or the server main thread (the addChunkToWorld gates in IsoChunk). The stock
 * gate silently skips registration from any other thread; in a dev build
 * (-Dpzopt.dev=true or dev=true in pzopt.properties) reaching it from a recalc
 * worker throws instead. Off, it costs one static boolean test.
 */
public final class Guard {
   public static final boolean DEV = Config.DEV;
   public static final String WORKER_PREFIX = "pzopt-recalc-";

   private Guard() {
   }

   public static boolean isWorkerThread() {
      return Thread.currentThread().getName().startsWith(WORKER_PREFIX);
   }

   public static void assertGameThread(String what) {
      if (DEV && isWorkerThread()) {
         throw new IllegalStateException("pzopt: " + what + " reached from recalc worker " + Thread.currentThread().getName());
      }
   }
}
