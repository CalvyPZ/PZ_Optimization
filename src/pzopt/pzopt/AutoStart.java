package pzopt;

import java.lang.reflect.Field;
import zombie.GameWindow;
import zombie.gameStates.GameLoadingState;
import zombie.gameStates.GameState;

/**
 * Presses "click to start" for a harness run. GameLoadingState.update() keeps the
 * loading screen up after the world is loaded until Mouse button 0 is down (or its
 * private forceDone flag is set); nothing in Lua fires during that state, so a
 * daemon thread watches GameWindow.states and sets forceDone by reflection while a
 * GameLoadingState is current. The flag is only read once loading is done, the
 * streamer is idle and the prompt has been shown, so setting it early is harmless.
 * Started once from Overrides.onClassLoaded when the flag file names a mode.
 */
final class AutoStart {
   private static Thread thread;

   private AutoStart() {
   }

   static synchronized void start() {
      // any harness mode, verify included: a verify run must enter the world too, not sit under "click to start"
      if (thread != null || (!Harness.REQUESTED && HarnessFlags.get("mode") == null)) {
         return;
      }
      thread = new Thread(AutoStart::run, "pzopt-autostart");
      thread.setDaemon(true);
      thread.start();
      Log.info("harness: auto-start armed (click-to-start will be pressed)");
   }

   private static void run() {
      Field forceDone = null;
      try {
         forceDone = GameLoadingState.class.getDeclaredField("forceDone");
         forceDone.setAccessible(true);
      } catch (Exception e) {
         Log.warn("harness: cannot reach GameLoadingState.forceDone: " + e);
         return;
      }
      boolean pressed = false;
      while (true) {
         try {
            // 20 ms polls until the loading state is seen, so the "loading screen up" line in the load
            // trace (pzopt-loadtrace.out) stamps the Continue press to that precision; 100 ms after
            Thread.sleep(pressed ? 100L : 20L);
            GameState cur = GameWindow.states == null ? null : GameWindow.states.current;
            if (cur instanceof GameLoadingState) {
               forceDone.setBoolean(cur, true);
               if (!pressed) {
                  Log.info("harness: loading screen up; pressing click-to-start when it is ready");
                  pressed = true;
               }
            } else if (pressed) {
               Log.info("harness: loading screen left");
               if (!Harness.REQUESTED) {
                  // verify mode has no route state machine to do it: mark the world as reached so the
                  // Lua mod ends the process at the main menu after quit_after instead of sitting there
                  HarnessFlags.markStarted();
               }
               return; // one world per process
            }
         } catch (InterruptedException e) {
            return;
         } catch (Exception e) {
            Log.warn("harness: auto-start: " + e);
            return;
         }
      }
   }
}
