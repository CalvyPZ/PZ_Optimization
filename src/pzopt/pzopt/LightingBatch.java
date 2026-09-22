package pzopt;

import java.util.ArrayList;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;
import zombie.meta.Meta;
import zombie.network.GameClient;

/**
 * The per-square lighting reads on the other cores ({@code lightingReadParallel}, 2026-09-22,
 * docs/plan-zombie-multithread.md §3.4).
 *
 * <p>Every lighting pass the game thread re-reads every on-screen square of every dirty chunk level through two JNI
 * calls ({@code getSquareDirty}, {@code getSquareLighting}) and rewrites the square's Java-side light fields
 * ({@code LightingJNI.JNILighting.updateFBORenderChunk}); on the Louisville preset that is 12-13 % of the game
 * thread, most of it in {@code FBORenderCell.pzoptFlushPendingLighting} (the pre-pass drain of the budget queue),
 * and the native reports nearly every visible square dirty on nearly every pass, so a memo cannot help. Both natives
 * are pure reads of the lighting state (disassembly of libLighting64.so: an index computation and a byte / int load,
 * the second copies into the Java array through GetIntArrayElements), the fields written are the square's own, and
 * the per-level bookkeeping the read triggers (the level's dirty flags, the strong-light mark, the puddle batch
 * flag, the cutaway check reset) is per chunk level or idempotent, so one {@link FrameBatch} task per chunk level is
 * race-free by construction. What is not per level — the room-seen check and the meta "square seen" hook, which
 * touch rooms, zones and the music system — is recorded per task in an {@link Effects} list and applied by the
 * game thread after the join, in the order the serial code would have run it.
 *
 * <p>The scratch array the JNI fills is thread-local in the override; the lazily created per-chunk structures a
 * task would otherwise race to create (the per-player render levels, the cutaway level data, the once-per-frame
 * stamp rows) are touched by the game thread while it builds the task list. {@code devLightingReadCheck=true}
 * re-reads one square in sixteen on the game thread after the batch and counts a mismatch when the native's answer
 * differs from what the worker stored: the race rig of this phase.
 */
public final class LightingBatch {
   private LightingBatch() {
   }

   public static final boolean ENABLED = Config.LIGHTING_READ_PARALLEL && Config.effectiveWorkers() > 1;

   /** What a task defers to the game thread: the squares whose seen state needs the room / meta hooks. */
   public static final class Effects {
      final ArrayList<IsoGridSquare> seenSquares = new ArrayList<>();
      final ArrayList<Boolean> seenBefore = new ArrayList<>();
      int playerIndex;

      void clear() {
         this.seenSquares.clear();
         this.seenBefore.clear();
      }

      /** A square the pass reports as seen; recorded only when the serial hooks would do something. */
      public void seen(IsoGridSquare square, boolean wasSeen) {
         if (!wasSeen) {
            this.seenSquares.add(square);
            this.seenBefore.add(Boolean.FALSE);
            return;
         }
         IsoRoom room = square.getRoom();
         if (room != null && room.def != null && !room.def.explored) {
            this.seenSquares.add(square);
            this.seenBefore.add(Boolean.TRUE);
         }
      }

      /** Game thread: the deferred hooks, in square order. */
      void apply() {
         for (int i = 0; i < this.seenSquares.size(); i++) {
            IsoGridSquare square = this.seenSquares.get(i);
            square.checkRoomSeen(this.playerIndex);
            if (!this.seenBefore.get(i) && !GameClient.client) {
               Meta.instance.dealWithSquareSeen(square);
            }
         }
      }
   }

   private static final ThreadLocal<Effects> CURRENT = new ThreadLocal<>();
   private static Effects[] pool = new Effects[64];

   public static long batches, tasks, squaresDeferred, checks, mismatches;

   /** The effects sink of the running task on this thread, or null on the game thread outside a batch (apply the hooks directly). */
   public static Effects current() {
      return CURRENT.get();
   }

   /** Game thread: the effects list for task {@code i} of a batch of {@code n}, cleared. */
   public static Effects effectsFor(int i, int n, int playerIndex) {
      if (pool.length < n) {
         pool = java.util.Arrays.copyOf(pool, Math.max(n, pool.length * 2));
      }
      Effects e = pool[i];
      if (e == null) {
         e = new Effects();
         pool[i] = e;
      }
      e.clear();
      e.playerIndex = playerIndex;
      return e;
   }

   /** Worker: run {@code task} with {@code e} as the effects sink. */
   public static void withEffects(Effects e, Runnable task) {
      CURRENT.set(e);
      try {
         task.run();
      } finally {
         CURRENT.remove();
      }
   }

   /** Game thread, after the join: the deferred hooks of tasks 0..n-1 in order. */
   public static void applyAll(int n) {
      batches++;
      tasks += n;
      for (int i = 0; i < n; i++) {
         Effects e = pool[i];
         squaresDeferred += e.seenSquares.size();
         e.apply();
         e.clear();
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "lighting batch: batches=" + batches + " levels=" + tasks + " deferred squares=" + squaresDeferred
            + (Config.DEV_LIGHTING_READ_CHECK ? " checked=" + checks + " mismatches=" + mismatches : "");
   }
}
