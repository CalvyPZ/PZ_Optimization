package pzopt;

/**
 * The game thread's frame number for the per-frame memos of the simulation phases (2026-09-22).
 *
 * <p>Bumped once per frame from {@code MovingObjectUpdateScheduler.update()}, which the game calls exactly once per
 * logic frame before any object updates. A memo stamped with {@link #frame()} is therefore valid for the rest of the
 * frame and recomputed on the next one — the granularity every "same answer for every zombie this frame" cache here
 * needs ({@link SeparateMask}, {@code IsoPlayer.allPlayersAsleep}).
 *
 * <p>Starts at 1 so a zero-initialised stamp field never looks current.
 */
public final class FrameTick {
   private FrameTick() {
   }

   private static int frame = 1;

   public static void next() {
      frame++;
   }

   public static int frame() {
      return frame;
   }
}
