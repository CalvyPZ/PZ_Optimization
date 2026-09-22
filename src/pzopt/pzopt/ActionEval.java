package pzopt;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.characters.action.ActionContext;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * The zombies' action-context transition evaluation on the other cores ({@code actionEvalParallel}, 2026-09-22,
 * docs/plan-zombie-multithread.md §3.2 step 2).
 *
 * <p>Every frame every animating zombie's {@code ActionContext.update()} evaluates the transitions of its current
 * action state (and of its sub-states): tens of conditions, each a variable lookup and a callback into the zombie,
 * then a compare. It was 7-10 % of the game thread on the Louisville horde after the typed-operand rewrite. The
 * evaluation only reads the zombie (its variables, its target's position, the event set of the context) and writes
 * the context's own "next" container, so it can run on the {@link FrameBatch} workers; the state change itself
 * (history, enter / exit hooks, the events) and everything after it in {@code postUpdateAnimating} (the animator, the
 * model slot, the light info) must stay on the game thread in the original order.
 *
 * <p>The postupdate loop is split in two by the {@code MovingObjectUpdateScheduler} override:
 * <ol>
 * <li>the loop itself: every object's {@code postupdate()} as before, but an eligible zombie's {@code postUpdateAnimating}
 *     stops after the forward direction, aim angle and turning flags (the inputs the conditions read) and queues the
 *     zombie here ({@link #submit});</li>
 * <li>{@link #flush}: the queued contexts evaluate on the workers into their "next" containers; then, on the game
 *     thread and in queue order, each zombie runs the rest of its {@code postUpdateAnimating}
 *     ({@code pzoptPostUpdateAnimatingRest}: the {@code ActionContext.update()} override finds the evaluation done and
 *     only applies it, then the animator and the model update as stock).</li>
 * </ol>
 * The bone batch ({@code AnimBatch}) collects its work during step 2 and runs after it, as before.
 *
 * <p>Not queued (stock path inline): players and animals, multiplayer, a zombie being grappled / grappling / that
 * reanimated a player (its counterpart reads it in the same loop), and any zombie whose current state or sub-states
 * have a transition condition other than a variable compare or an event test (a {@code LuaCall} runs Lua). A
 * condition callback reached from a worker must not touch shared scratch: {@code IsoZombie.isFacingTarget} is the
 * one that did (static vectors), fixed in the IsoZombie override. {@code devActionEvalCheck=true} re-evaluates every
 * queued context on the game thread at apply time and counts disagreements ({@code mismatches}), which is the
 * determinism rig of this phase (two runs of the game are never frame-aligned, so a run-to-run checksum cannot be).
 */
public final class ActionEval {
   private ActionEval() {
   }

   private static final boolean ENABLED = Config.ACTION_EVAL_PARALLEL && Config.effectiveWorkers() > 1;

   private static boolean active; // game thread only: submissions accepted
   private static boolean applying; // game thread only: inside the phase-2 loop
   private static int generation; // one per flush; a context stamps its evaluation with it
   private static IsoZombie[] queue = new IsoZombie[1024];
   private static int count;
   private static volatile boolean failed;

   public static long batched, inline, frames, maxBatch, waitNanos, workNanos, mismatches, checks; // counters for the log

   /** Marker for a null variable value in a snapshot map. */
   public static final Object NULL = new Object();

   /**
    * Callback variables audited as pure reads of the character (fields, its target's position, static settings), which a
    * worker may call; every other callback a state's conditions reference is read on the game thread into the snapshot
    * before the batch (stock's side effects stay there, in order). Keys are the slot keys, lower case. Stored slots need
    * no entry. Audit of 2026-09-22 (IsoZombie / IsoGameCharacter / IsoMovingObject callback bodies).
    */
   public static final java.util.Set<String> PURE_CALLBACKS = java.util.Set.of(
      "bonfloor", "bknockeddown", "hashitreaction", "bdead", "usephysichitreaction", "bclient", "bmultiplayer", "hitreaction",
      "playerattackposition", "bfakedead", "bstaggerback", "isvehiclecollision", "useragdollvehiclecollision", "canragdoll",
      "isfacingtarget", "realstate", "bumped", "bfalling", "isragdollfall", "issimulationactive", "fallonfront",
      "previousstate", "bcrawling", "issitting", "alerted", "isragdoll", "bbecomecrawler", "bknifedeath",
      "bisreanimatedforgrappleonly", "breanimate", "lasttargetseen", "bpathfindprediction", "bstoplunging", "zombiewalktype", "crawlertype",
      "eatspeed", "lungetimer", "reanimatetimer", "unbalancedlevel", "targetseentime", "bjawstabattach", "hitheadtype",
      "attackdiddamage", "attackoutcome", "bumptype", "collidetype", "footinjurytype", "onbed", "sittingonfurniture",
      "sitonground", "frombehind", "killedbyfall", "isprone", "isgettingup", "footinjury", "angle", "animangle", "twist",
      "targettwist", "shouldertwist", "excesstwist", "numtwistbones", "anglestepdelta", "angletwistdelta", "beenmovingfor",
      "momentumscalar", "isoverencumbered", "criticalhit", "falltime", "aim", "baimatfloor", "aimatflooramount",
      "verticalaimangle", "attackanim", "shoveanim", "stompanim", "isstompanim", "performinghostileanim", "firemode",
      "isanimatingbackwards", "iseditingragdoll", "isupright", "isonback", "bheadlookaround", "lookhorizontal",
      "lookvertical", "hitforce", "hitdir", "hideequippedhandl", "hideequippedhandr", "isunarmed", "ismeleeweaponequipped",
      "israngedweaponequipped", "isrendered", "stateeventdelaytimer");

   private static final ThreadLocal<java.util.IdentityHashMap<Object, Object>> SNAPSHOT = new ThreadLocal<>();

   /** The snapshot of the context being evaluated on this thread, or null (read the variables directly). */
   public static java.util.IdentityHashMap<Object, Object> currentSnapshot() {
      return SNAPSHOT.get();
   }

   public static void enterSnapshot(java.util.IdentityHashMap<Object, Object> snapshot) {
      SNAPSHOT.set(snapshot);
   }

   public static void exitSnapshot() {
      SNAPSHOT.remove();
   }

   /** Game thread: from here on eligible zombies stop their postUpdateAnimating after the turning flags and queue here. */
   public static void begin() {
      if (!ENABLED || failed) {
         return;
      }
      generation++;
      count = 0;
      active = true;
   }

   /** The generation of the batch being evaluated / applied; a context's evaluation is only applied in the same one. */
   public static int generation() {
      return generation;
   }

   /** True while the game thread applies the batch (phase 2); an evaluation is consumed by update() only then. */
   public static boolean applying() {
      return applying;
   }

   /**
    * Called by IsoGameCharacter.postUpdateAnimating right after the turning flags: true = the rest of it runs in
    * flush(), in the same order, after the parallel evaluation.
    */
   public static boolean submit(IsoGameCharacter character) {
      if (!active) {
         return false;
      }
      if (!(character instanceof IsoZombie)) {
         return false;
      }
      IsoZombie zombie = (IsoZombie)character;
      if (GameClient.client || GameServer.server || zombie.isBeingGrappled() || zombie.isGrappling() || zombie.getReanimatedPlayer() != null) {
         inline++;
         return false;
      }
      ActionContext context = zombie.getActionContext();
      if (context == null || context.getGroup() == null || !context.pzoptOffThreadSafe()) {
         inline++;
         return false;
      }
      context.pzoptSnapshot(); // the side-effect callbacks, here and now like stock
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
      }
      queue[count++] = zombie;
      batched++;
      return true;
   }

   /** Game thread, after the postupdate loop: evaluate the queued contexts on the workers, then finish the queued zombies in order. */
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
      Throwable t = FrameBatch.run(count, i -> queue[i].getActionContext().pzoptEvaluate());
      workNanos += FrameBatch.workNanos - w0;
      waitNanos += FrameBatch.waitNanos - q0;
      if (t != null && !failed) {
         failed = true; // the contexts that did not get their stamp evaluate on the game thread below; stock path from the next frame on
         Log.warn("actionEvalParallel: transition evaluation failed on a worker, batching off: " + t);
      }
      applying = true;
      try {
         for (int i = 0; i < count; i++) {
            queue[i].pzoptPostUpdateAnimatingRest();
         }
      } finally {
         applying = false;
         java.util.Arrays.fill(queue, 0, count, null);
         count = 0;
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "action eval: frames=" + frames + " batched=" + batched + " inline=" + inline + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L)
            + (Config.DEV_ACTION_EVAL_CHECK ? " checked=" + checks + " mismatches=" + mismatches : "") + (failed ? " FAILED" : "");
   }
}
