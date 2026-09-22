package pzopt;

import zombie.gameStates.GameState;
import zombie.gameStates.IngameState;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.ui.UIManager;

/**
 * noLoadingScreen (2026-09-22, maintainer's request): single player shows a plain black screen while the world loads
 * (GameLoadingState.render) instead of the loading screen, and the world then appears at once: the 0.35 s fade from
 * black that IngameState.enter starts is cancelled right after the state machine enters it, before its first frame.
 */
public final class NoLoadingScreen {
   private static GameState last;

   private NoLoadingScreen() {
   }

   public static boolean active() {
      return Config.NO_LOADING_SCREEN && Overrides.enabled() && !GameClient.client && !GameServer.server;
   }

   /** From GameWindow.logic right after states.update(), on the main thread. */
   public static void afterStateUpdate(GameState current) {
      if (current == last) {
         return;
      }
      last = current;
      if (current instanceof IngameState && active()) {
         UIManager.setFadeInTime(0);
         UIManager.setFadeAlpha(0);
      }
   }

}
