package pzopt;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
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
 * worker threads and the game thread together and is joined before anything (attachments, the render data) reads a bone.
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
   private static final int THREADS = Math.max(1, Math.min(Config.ANIM_BONES_THREADS, Runtime.getRuntime().availableProcessors() - 1));

   private static boolean active; // game thread only: submissions accepted
   private static AnimationPlayer[] players = new AnimationPlayer[1024];
   private static float[] deltas = new float[1024];
   private static int count;
   private static final AtomicInteger cursor = new AtomicInteger();
   private static final AtomicInteger finished = new AtomicInteger();
   private static final Object gate = new Object();
   private static int generation; // guarded by gate
   private static Thread[] workers;
   private static volatile boolean failed;

   public static long batched, inline, frames, maxBatch, waitNanos, workNanos; // counters for the log

   /** Game thread: from here on the eligible zombies' bone math is queued instead of run. */
   public static void begin() {
      if (!ENABLED || failed) {
         return;
      }
      if (workers == null) {
         start();
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

   /** Game thread: run everything queued since begin() on the workers and this thread, and wait for all of it. */
   public static void flush() {
      if (!active) {
         return;
      }
      active = false;
      if (count == 0) {
         return;
      }
      long t0 = System.nanoTime();
      frames++;
      if (count > maxBatch) {
         maxBatch = count;
      }
      cursor.set(0);
      finished.set(0);
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
      run();
      long t1 = System.nanoTime();
      int spins = 0;
      while (finished.get() < count) {
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            LockSupport.parkNanos(20_000L);
         }
      }
      long t2 = System.nanoTime();
      workNanos += t1 - t0;
      waitNanos += t2 - t1;
      java.util.Arrays.fill(players, 0, count, null);
      count = 0;
   }

   private static void run() {
      int i;
      while ((i = cursor.getAndIncrement()) < count) {
         try {
            players[i].pzoptRunDeferred(deltas[i]);
         } catch (Throwable t) {
            if (!failed) {
               failed = true; // back to the inline path from the next frame on
               Log.warn("animBonesParallel: deferred bone update failed, batching off: " + t);
            }
         } finally {
            finished.incrementAndGet();
         }
      }
   }

   private static void start() {
      workers = new Thread[THREADS];
      for (int k = 0; k < THREADS; k++) {
         Thread t = new Thread(AnimBatch::workerLoop, "pzopt-anim-" + k);
         t.setDaemon(true);
         t.setPriority(Thread.NORM_PRIORITY);
         workers[k] = t;
         t.start();
      }
      Log.info("animBonesParallel: " + THREADS + " worker threads for the zombies' bone math");
   }

   private static void workerLoop() {
      int seen = 0;
      while (true) {
         synchronized (gate) {
            while (generation == seen) {
               try {
                  gate.wait();
               } catch (InterruptedException e) {
                  return;
               }
            }
            seen = generation;
         }
         run();
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "anim batch: frames=" + frames + " batched=" + batched + " inline=" + inline + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L) + (failed ? " FAILED" : "");
   }
}
