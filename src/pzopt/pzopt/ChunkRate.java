package pzopt;

/**
 * How fast chunks are entering the world (hand-offs to the game thread per second), for decisions
 * that depend on how long a chunk texture will live: walking through a town loads ~9 chunks/s,
 * driving at 60 km/h ~32/s, at 120 km/h ~72/s. {@link #loaded()} is called from
 * IsoChunk.loadInMainThread; {@link #perSecond()} is an exponential average over half-second
 * windows (time constant about a second), so one chunk row does not flip a mode and a stop shows
 * within a second or two. Game thread only.
 */
public final class ChunkRate {
   private static final long WINDOW_NS = 500_000_000L;
   private static long windowStartNs = System.nanoTime();
   private static int inWindow;
   private static float perSecond;

   private ChunkRate() {
   }

   public static void loaded() {
      roll(System.nanoTime());
      inWindow++;
   }

   /** Chunks per second, averaged over roughly the last second (0 while nothing streams). */
   public static float perSecond() {
      roll(System.nanoTime());
      return perSecond;
   }

   private static void roll(long now) {
      long elapsed = now - windowStartNs;
      if (elapsed < WINDOW_NS) {
         return;
      }
      if (elapsed >= 10 * WINDOW_NS) {
         // a long pause (menu, load): start over instead of decaying window by window
         perSecond = 0f;
         inWindow = 0;
         windowStartNs = now;
         return;
      }
      while (now - windowStartNs >= WINDOW_NS) {
         perSecond = 0.5f * perSecond + 0.5f * (inWindow * 2); // a half-second count, per second
         inWindow = 0;
         windowStartNs += WINDOW_NS;
      }
   }
}
