package pzopt;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * The zombies' animation bone math on the other cores ({@code animBonesParallel}, 2026-09-22).
 *
 * <p>Every zombie with a 3D model spends its postupdate in {@code AnimationPlayer.Update}: the multi-track tick (which
 * fires the animation events) and then the standard-animation math, i.e. blending every track's keyframes into the bone
 * transforms, the body angles, the twist bones, the model-space and skin matrices. On the Louisville horde preset that
 * math is 9 % of the game thread (17 % for the whole zombie postupdate, 98 % busy). It only touches the player's own
 * arrays, the read-only clips, thread-safe pools and the scratch objects the override made thread-local, so it can run
 * anywhere: during {@code MovingObjectUpdateScheduler.postupdate()} the override's {@code updateInternal} keeps the
 * multi-track tick on the game thread and hands the rest to this batch; after the last object the batch runs on the
 * {@link FrameBatch} workers and the game thread together and is joined before anything (attachments, the render data)
 * reads a bone.
 *
 * <p>Not batched: players and animals, a zombie without a model (its update is non-visual), a zombie being grappled or
 * grappling (the other side reads its bones in the same loop), one that reanimated a dead player (the player copies its
 * bones right after), a ragdoll, a recording player, a child player (copies its parent's bones). Those run inline.
 *
 * <p>The batch runs after the loop rather than overlapping it because {@code IsoGameCharacter.updateAnimPlayer} (the
 * model-less path, most of a horde) flips {@code PerformanceSettings.interpolateAnims} around each call and the keyframe
 * sampling reads that flag.
 */
public final class AnimBatch {
   private AnimBatch() {
   }

   private static final boolean ENABLED = Config.ANIM_BONES_PARALLEL && Config.effectiveWorkers() > 1;

   private static boolean active; // game thread only: submissions accepted
   private static AnimationPlayer[] players = new AnimationPlayer[1024];
   private static float[] deltas = new float[1024];
   private static int count;
   private static volatile boolean failed;

   public static long batched, inline, frames, maxBatch, waitNanos, workNanos; // counters for the log

   /** Game thread: from here on the eligible zombies' bone math is queued instead of run. */
   public static void begin() {
      if (!ENABLED || failed) {
         return;
      }
      count = 0;
      active = true;
   }

   /** Called by AnimationPlayer.updateInternal in place of the standard-animation math; true = queued, run it later. */
   public static boolean submit(AnimationPlayer player, float deltaT) {
      if (!active) {
         return false;
      }
      IsoGameCharacter character = player.getIsoGameCharacter();
      if (!(character instanceof IsoZombie) || !player.pzoptBatchable()) {
         inline++;
         return false;
      }
      IsoZombie zombie = (IsoZombie)character;
      if (zombie.isBeingGrappled() || zombie.isGrappling() || zombie.getReanimatedPlayer() != null) {
         inline++;
         return false;
      }
      if (count == players.length) {
         players = java.util.Arrays.copyOf(players, count * 2);
         deltas = java.util.Arrays.copyOf(deltas, count * 2);
      }
      players[count] = player;
      deltas[count] = deltaT;
      count++;
      batched++;
      return true;
   }

   /** Game thread: run everything queued since begin() on the workers and this thread (pzopt.FrameBatch), and wait for all of it. */
   public static void flush() {
      if (!active) {
         return;
      }
      active = false;
      if (count == 0) {
         return;
      }
      frames++;
      if (count > maxBatch) {
         maxBatch = count;
      }
      long w0 = FrameBatch.workNanos;
      long q0 = FrameBatch.waitNanos;
      Throwable t = FrameBatch.run(count, i -> players[i].pzoptRunDeferred(deltas[i]));
      workNanos += FrameBatch.workNanos - w0;
      waitNanos += FrameBatch.waitNanos - q0;
      if (t != null && !failed) {
         failed = true; // back to the inline path from the next frame on
         Log.warn("animBonesParallel: deferred bone update failed, batching off: " + t);
      }
      java.util.Arrays.fill(players, 0, count, null);
      count = 0;
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "anim batch: frames=" + frames + " batched=" + batched + " inline=" + inline + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L) + (failed ? " FAILED" : "")
            + " shadow computed=" + ShadowPrep.computed + " served=" + ShadowPrep.served + " fallback=" + ShadowPrep.fallback;
   }
}
