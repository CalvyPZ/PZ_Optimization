package zombie.characters.action;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import zombie.ai.states.StateManager;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.action.conditions.CharacterVariableCondition;
import zombie.characters.action.conditions.CharacterVariableCondition.Factory;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableHandle;
import zombie.core.skinnedmodel.advancedanimation.IAnimatable;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot;
import zombie.debug.DebugType;
import zombie.network.GameClient;
import zombie.util.list.PZArrayUtil;

public final class ActionContext {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.characters.action.ActionContext");
   }

   // pzopt: actionEvalParallel (pzopt.ActionEval). The transition evaluation of an eligible zombie runs on a frame worker
   // between the postupdate loop and the animator updates: pzoptEvaluate() does the evaluate half of updateInternal
   // into the "next" container and stamps the batch generation; update() in the same generation, while the batch is
   // being applied, does the transfer half only. Anything else (another generation, an update() from elsewhere) is stock.
   private int pzoptEvaluatedGeneration;
   private ActionStateContainer pzoptCheckContainer; // devActionEvalCheck scratch
   private final java.util.IdentityHashMap<Object, Object> pzoptSnapshot = new java.util.IdentityHashMap<>(); // lookup -> value read on the game thread
   private static final java.util.IdentityHashMap<ActionState, Object[]> pzoptStateLookups = new java.util.IdentityHashMap<>(); // game thread only; null = a state with an unsupported condition

   /** Worker (or the game thread at the join): the evaluate half of updateInternal, result in nextActionStateContainer. */
   public void pzoptEvaluate() {
      this.nextActionStateContainer.set(this.actionStateContainer);
      pzopt.ActionEval.enterSnapshot(this.pzoptSnapshot);
      try {
         this.nextActionStateContainer.evaluateCurrentState(this);
      } finally {
         pzopt.ActionEval.exitSnapshot();
      }
      this.pzoptEvaluatedGeneration = pzopt.ActionEval.generation();
   }

   /**
    * Game thread, right where stock would have evaluated: reads every variable of the current state's (and sub-states')
    * conditions whose callback is not an audited pure read — the ones with side effects (a target cleared, a thump
    * target dropped, the eat-body scan) or shared scratch (the lunge line test's point pool, the turn-direction vectors)
    * — into the snapshot the worker serves instead of calling them. Their side effects thus happen here, in order,
    * as in stock; a callback that stock would have short-circuited past is called once more than stock.
    */
   public void pzoptSnapshot() {
      this.pzoptSnapshot.clear();
      IAnimatable owner = this.getOwner();
      this.pzoptSnapshotState(this.actionStateContainer.getRootState(), owner);

      for (int i = 0; i < this.actionStateContainer.childStateCount(); i++) {
         this.pzoptSnapshotState(this.actionStateContainer.getChildStateAt(i), owner);
      }
   }

   private void pzoptSnapshotState(ActionState state, IAnimatable owner) {
      Object[] lookups = pzoptStateLookups.get(state);
      if (lookups == null) {
         return;
      }

      for (Object lookup : lookups) {
         Object value = CharacterVariableCondition.pzoptSnapshotValue(lookup, owner);
         if (value != null) {
            this.pzoptSnapshot.put(lookup, value);
         }
      }
   }

   /**
    * Whether the current state and its sub-states only have transition conditions that read the character and this
    * context (variable compares, event tests), so the evaluation may run off the game thread. Cached per state: the
    * states are the action group's shared definitions.
    */
   public boolean pzoptOffThreadSafe() {
      ActionState root = this.actionStateContainer.getRootState();
      if (root == null || !pzoptStateSafe(root)) {
         return false;
      }

      for (int i = 0; i < this.actionStateContainer.childStateCount(); i++) {
         if (!pzoptStateSafe(this.actionStateContainer.getChildStateAt(i))) {
            return false;
         }
      }

      return true;
   }

   private static final Object[] PZOPT_UNSAFE_STATE = new Object[0];

   /** The state's variable lookups (for the snapshot), or the UNSAFE marker when a condition type cannot run on a worker. */
   private static boolean pzoptStateSafe(ActionState state) {
      Object[] known = pzoptStateLookups.get(state);
      if (known != null) {
         return known != PZOPT_UNSAFE_STATE;
      }

      java.util.ArrayList<Object> lookups = new java.util.ArrayList<>();
      boolean safe = true;

      for (int i = 0; safe && i < state.transitions.size(); i++) {
         ActionTransition transition = state.transitions.get(i);

         for (int c = 0; c < transition.conditions.size(); c++) {
            IActionCondition condition = transition.conditions.get(c);
            if (condition instanceof CharacterVariableCondition variableCondition) {
               Object lhs = variableCondition.pzoptLhsLookup();
               Object rhs = variableCondition.pzoptRhsLookup();
               if (lhs != null) {
                  lookups.add(lhs);
               }

               if (rhs != null) {
                  lookups.add(rhs);
               }
            } else if (!(condition instanceof zombie.characters.action.conditions.EventOccurred)
               && !(condition instanceof zombie.characters.action.conditions.EventNotOccurred)) {
               safe = false;
               break;
            }
         }
      }

      pzoptStateLookups.put(state, safe ? lookups.toArray() : PZOPT_UNSAFE_STATE);
      return safe;
   }

   /** Game thread, phase 2: apply the worker's evaluation (the transfer half of updateInternal); false = not evaluated this batch. */
   private boolean pzoptApplyEvaluated() {
      if (this.pzoptEvaluatedGeneration != pzopt.ActionEval.generation() || !pzopt.ActionEval.applying()) {
         return false;
      }

      this.pzoptEvaluatedGeneration = 0;
      if (pzopt.Config.DEV_ACTION_EVAL_CHECK) {
         if (this.pzoptCheckContainer == null) {
            this.pzoptCheckContainer = new ActionStateContainer();
         }

         this.pzoptCheckContainer.set(this.actionStateContainer);
         pzopt.ActionEval.enterSnapshot(this.pzoptSnapshot);
         try {
            this.pzoptCheckContainer.evaluateCurrentState(this);
         } finally {
            pzopt.ActionEval.exitSnapshot();
         }
         pzopt.ActionEval.checks++;
         if (!this.pzoptCheckContainer.equalTo(this.nextActionStateContainer)) {
            pzopt.ActionEval.mismatches++;
            if (pzopt.ActionEval.mismatches <= 20) {
               pzopt.Log.warn("devActionEvalCheck: " + this.getOwner() + " worker " + this.nextActionStateContainer.getRootState().getName()
                  + " vs game thread " + this.pzoptCheckContainer.getRootState().getName() + " (from " + this.actionStateContainer.getRootState().getName() + ")");
            }
         }
      }

      this.transferActionState(this.nextActionStateContainer);
      return true;
   }

   private final IAnimatable owner;
   private ActionGroup actionGroup;
   private final int actionStateHistoryMaxSize = 6;
   private final Stack<ActionState> actionStateHistory = new Stack<>();
   private final ActionStateContainer previousActionStateContainer = new ActionStateContainer();
   private final ActionStateContainer actionStateContainer = new ActionStateContainer();
   private final ActionStateContainer nextActionStateContainer = new ActionStateContainer();
   private boolean statesChanged;
   private final Set<IActionStateChanged> onStateChanged = new HashSet<>();
   private final ActionContextEvents occurredAnimEvents = new ActionContextEvents();
   private final PerformanceProfileProbe updateInternal = new PerformanceProfileProbe("ActionContext.update");
   private final PerformanceProfileProbe postUpdateInternal = new PerformanceProfileProbe("ActionContext.postUpdate");

   public ActionContext(IAnimatable owner) {
      this.owner = owner;
   }

   public IAnimatable getOwner() {
      return this.owner;
   }

   public void update() {
      AbstractPerformanceProfileProbe var1 = this.updateInternal.profile();

      try {
         this.updateInternal();
      } catch (Throwable var7) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var5) {
               var7.addSuppressed(var5);
            }
         }

         throw var7;
      }

      if (var1 != null) {
         var1.close();
      }

      var1 = this.postUpdateInternal.profile();

      try {
         this.postUpdateInternal();
      } catch (Throwable var6) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var6.addSuppressed(var4);
            }
         }

         throw var6;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void updateInternal() {
      if (this.pzoptApplyEvaluated()) { // pzopt: actionEvalParallel, evaluated on a worker already
         return;
      }

      this.nextActionStateContainer.set(this.actionStateContainer);
      this.nextActionStateContainer.evaluateCurrentState(this);
      this.transferActionState(this.nextActionStateContainer);
   }

   private void transferActionState(ActionStateContainer nextActionStateContainer) {
      if (!this.actionStateContainer.equalTo(nextActionStateContainer)) {
         this.previousActionStateContainer.set(this.actionStateContainer);
         this.actionStateContainer.set(nextActionStateContainer);
         this.updateHistory(
            this.previousActionStateContainer.getRootState(),
            this.actionStateContainer.getRootState(),
            this.actionStateContainer.getTransitionUsedForThisState()
         );
         boolean rootStateChanged = this.previousActionStateContainer.getRootState() != nextActionStateContainer.getRootState();
         boolean subStatesChanged = !this.previousActionStateContainer.subStatesEqual(nextActionStateContainer);
         if (rootStateChanged) {
            DebugType.ActionSystem
               .trace(
                  "%s>  State changed from \"%s\" to \"%s\",", new Object[]{this.getOwner().getUID(), this.peekPreviousStateName(), this.getCurrentStateName()}
               );
            if (GameClient.client) {
               StateManager.exitState(this.owner, this.previousActionStateContainer.getRootState());
               StateManager.enterState(this.owner, this.actionStateContainer.getRootState());
            }
         }

         if (subStatesChanged) {
            for (int subStatei = 0; subStatei < this.previousActionStateContainer.childStateCount(); subStatei++) {
               ActionState oldSubState = this.previousActionStateContainer.getChildStateAt(subStatei);
               if (!nextActionStateContainer.hasChildState(oldSubState)) {
                  DebugType.ActionSystem.trace("%s> SubState exited. \"%s\"", new Object[]{this.getOwner().getUID(), oldSubState.getName()});
                  if (GameClient.client) {
                     StateManager.exitSubState(this.owner, oldSubState);
                  }
               }
            }

            for (int subStatei = 0; subStatei < nextActionStateContainer.childStateCount(); subStatei++) {
               ActionState nextSubState = nextActionStateContainer.getChildStateAt(subStatei);
               if (!this.previousActionStateContainer.hasChildState(nextSubState)) {
                  ActionState upperState = subStatei > 0 ? nextActionStateContainer.getChildStateAt(subStatei - 1) : nextActionStateContainer.getRootState();
                  DebugType.ActionSystem
                     .trace(
                        "%s> Transition passes. SubState \"%s\" added to parent state: \"%s\"",
                        new Object[]{this.getOwner().getUID(), nextSubState.getName(), upperState.getName()}
                     );
                  if (GameClient.client) {
                     StateManager.enterSubState(this.owner, nextSubState);
                  }
               }
            }
         }

         this.onStatesChanged();
      }
   }

   private void updateHistory(ActionState previousState, ActionState currentState, ActionTransition transitionUsed) {
      if (currentState == null) {
         DebugType.ActionSystem.error("Current state is null.");
      } else if (previousState != currentState) {
         if (previousState == null) {
            DebugType.ActionSystem.debugln("Previous state null. Resetting history. Entering state: %s", new Object[]{currentState.getName()});
            this.actionStateHistory.clear();
         } else {
            if (!this.actionStateHistory.isEmpty() && transitionUsed != null && transitionUsed.transitionOut) {
               ActionState previousStateInHistory = this.peekPreviousState();
               if (previousStateInHistory == currentState) {
                  this.popPreviousState();
                  DebugType.ActionSystem
                     .debugln("TransitionOut success. Returning from: %s to: %s", new Object[]{previousState.getName(), currentState.getName()});
                  return;
               }

               DebugType.ActionSystem
                  .error(
                     "TransitionOut mismatch. Previous state \"%s\" != inHistory \"%s\"",
                     new Object[]{currentState.getName(), previousStateInHistory.getName()}
                  );
               this.actionStateHistory.clear();
            }

            this.pushPreviousState(previousState);
         }
      }
   }

   private void postUpdateInternal() {
      this.clearActionContextEvents();
      this.invokeAnyStateChangedEvents();
      this.logCurrentState();
   }

   public ActionState peekNextState() {
      return this.actionStateContainer.peekNextState(this);
   }

   public boolean canTransitionToState(String stateName) {
      return this.canTransitionToState(stateName, true);
   }

   public boolean canTransitionToState(String stateName, boolean allowSubState) {
      return this.actionStateContainer.canTransitionToState(this.getGroup(), stateName, allowSubState);
   }

   public void setPlaybackStateSnapshot(ActionStateSnapshot snapshot) {
      this.nextActionStateContainer.clear();
      this.nextActionStateContainer.setPlaybackStateSnapshot(this, snapshot);
      this.transferActionState(this.nextActionStateContainer);
   }

   public ActionStateSnapshot getPlaybackStateSnapshot() {
      return this.actionStateContainer.getPlaybackStateSnapshot();
   }

   public void setCurrentState(ActionState nextState) {
      this.nextActionStateContainer.clear();
      this.nextActionStateContainer.setCurrentState(nextState, null);
      this.transferActionState(this.nextActionStateContainer);
   }

   private void onStatesChanged() {
      this.statesChanged = true;
   }

   public void logCurrentState() {
      if (this.owner.isAnimationRecorderActive()) {
         this.owner
            .getAnimationRecorder()
            .logActionState(this.actionGroup, this.actionStateContainer.getRootState(), this.actionStateContainer.getChildStates());
         this.owner
            .getAnimationRecorder()
            .logVariable("actionStateHistory", PZArrayUtil.arrayToString(this.actionStateHistory, ActionState::getName, "", "", ";"));
      }
   }

   private void invokeAnyStateChangedEvents() {
      if (this.statesChanged) {
         this.statesChanged = false;

         for (IActionStateChanged callback : this.onStateChanged) {
            callback.actionStateChanged(this);
         }

         if (this.owner instanceof IsoZombie isoZombie) {
            isoZombie.getNetworkCharacterAI().extraUpdate();
         }
      }
   }

   public void clearActionContextEvents() {
      this.occurredAnimEvents.clear();
   }

   public ActionState getCurrentState() {
      return this.actionStateContainer.getRootState();
   }

   public void setGroup(ActionGroup group) {
      this.actionGroup = group;
      this.setCurrentState(group.getInitialState());
   }

   public ActionGroup getGroup() {
      return this.actionGroup;
   }

   public void reportEvent(String event) {
      this.reportEvent(null, event);
   }

   public void reportEvent(String state, String event) {
      this.occurredAnimEvents.add(event, state);
      if (state == null && GameClient.client && this.owner instanceof IsoPlayer player && player.isLocalPlayer()) {
         player.getNetworkCharacterAI().getState().reportEvent(state, event);
      }
   }

   public ActionState getChildStateAt(int idx) {
      return this.actionStateContainer.getChildStateAt(idx);
   }

   public List<ActionState> getChildStates() {
      return this.actionStateContainer.getChildStates();
   }

   public String getCurrentStateName() {
      return this.actionStateContainer.getRootState() != null ? this.actionStateContainer.getCurrentStateName() : this.actionGroup.getDefaultState().getName();
   }

   public String peekPreviousStateName() {
      return this.getStateNameOrDefault(this.peekPreviousState());
   }

   public ActionState popPreviousState() {
      return !this.actionStateHistory.isEmpty() ? this.actionStateHistory.pop() : null;
   }

   public ActionState peekPreviousState() {
      return !this.actionStateHistory.isEmpty() ? this.actionStateHistory.peek() : null;
   }

   private void pushPreviousState(ActionState currentState) {
      if (currentState != null && this.peekPreviousState() != currentState) {
         this.actionStateHistory.push(currentState);
      }

      while (this.actionStateHistory.size() >= 6) {
         this.actionStateHistory.removeFirst();
      }
   }

   private String getStateNameOrDefault(ActionState previousState) {
      return previousState != null ? previousState.getName() : this.actionGroup.getDefaultState().getName();
   }

   public boolean hasEventOccurred(String eventName) {
      return this.hasEventOccurred(eventName, null);
   }

   public boolean hasEventOccurred(String eventName, String stateName) {
      return this.occurredAnimEvents.contains(eventName, stateName);
   }

   public void clearEvent(String eventName) {
      this.occurredAnimEvents.clearEvent(eventName);
   }

   public void getEvents(HashMap<String, String> events) {
      this.occurredAnimEvents.get(events);
   }

   public IAnimationVariableSlot getVariable(AnimationVariableHandle handle) {
      return this.actionStateContainer.getVariable(handle);
   }

   public boolean hasStateVariables() {
      return this.actionStateContainer.hasStateVariables();
   }

   public void addOnStateChanged(IActionStateChanged callback) {
      this.onStateChanged.add(callback);
   }

   static {
      Factory factory = new Factory();
      IActionCondition.registerFactory("isTrue", factory);
      IActionCondition.registerFactory("isFalse", factory);
      IActionCondition.registerFactory("compare", factory);
      IActionCondition.registerFactory("gtr", factory);
      IActionCondition.registerFactory("less", factory);
      IActionCondition.registerFactory("equals", factory);
      IActionCondition.registerFactory("lessEqual", factory);
      IActionCondition.registerFactory("gtrEqual", factory);
      IActionCondition.registerFactory("notEquals", factory);
      IActionCondition.registerFactory("eventOccurred", new zombie.characters.action.conditions.EventOccurred.Factory());
      IActionCondition.registerFactory("eventNotOccurred", new zombie.characters.action.conditions.EventNotOccurred.Factory());
      IActionCondition.registerFactory("lua", new zombie.characters.action.conditions.LuaCall.Factory());
   }
}
