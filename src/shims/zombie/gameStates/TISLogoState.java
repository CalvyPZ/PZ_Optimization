package zombie.gameStates;

import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.ui.UIManager;

/**
 * Drop-in replacement for the game's start-up logo state (The Indie Stone,
 * then the FMOD / General Arcade / Arrival / VertexBreak attribution screen).
 * The stock state fades each screen in, holds it, fades it out: ~5 s of
 * nothing before the main menu, which the benchmark harness only waits through.
 * This copy draws one black frame and leaves the state on its first update,
 * so the state machine moves straight on to the terms-of-service / main-menu
 * states. Written from scratch against the public shape of the stock class
 * (the fields below are kept so anything linking against them still links);
 * scripts/build.sh checks that shape against the installed jar.
 */
public final class TISLogoState extends GameState {
   public float alpha = 0.0f;
   public float alphaStep = 0.02f;
   public float logoDisplayTime = 0.0f;
   public int screenNumber = 1;
   public int stage = 3; // the stock class's "exit" stage
   public float targetAlpha = 0.0f;

   @Override
   public void enter() {
      UIManager.suspend = true;
   }

   @Override
   public void exit() {
      UIManager.suspend = false;
   }

   @Override
   public void render() {
      Core core = Core.getInstance();
      core.StartFrame();
      SpriteRenderer.instance.renderi(null, 0, 0, core.getOffscreenWidth(0), core.getOffscreenHeight(0), 0.0f, 0.0f, 0.0f, 1.0f, null);
      core.EndFrame();
   }

   @Override
   public GameStateMachine.StateAction update() {
      return GameStateMachine.StateAction.Continue;
   }

   /** Present only so the jar's TISLogoState$LogoElement.class has a loose counterpart (see scripts/build.sh). */
   private static final class LogoElement {
   }
}
