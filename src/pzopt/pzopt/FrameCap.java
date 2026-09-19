package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Properties;
import zombie.GameWindow;
import zombie.SystemDisabler;
import zombie.ZomboidFileSystem;
import zombie.core.PerformanceSettings;
import zombie.gameStates.GameLoadingState;

/**
 * Frame limiter: makes the game's own "Uncapped" option usable and adds a separate cap for
 * the menus.
 *
 * Stock has an "Uncapped" entry but it is dead: nothing sets the SystemDisabler flag that puts
 * it into the options-screen combo, and Core.loadOptions resets a saved uncappedFPS=true back
 * to a 60 fps lock. GameWindow.InitDisplay calls {@link #afterLoadOptions()} right after
 * loadOptions; it enables the combo entry and re-applies the saved frameRate / uncappedFPS
 * pair from options.ini. The limiter itself is the stock accumulator in
 * GameWindow.mainThreadStep, which now asks {@link #uncappedNow()} / {@link #lockNow()}: the
 * in-game values while a world is up or loading, the menu values otherwise.
 *
 * The menu cap is one combo index ("Menu framerate" in Display options, added by
 * media/lua/client/pzopt/pzopt_framecap_options.lua): 1 = same as in-game, 2 = uncapped,
 * 3.. = the stock fps table. Persisted in Zomboid/pzopt/framecap.ini because Core.saveOptions
 * only writes the keys it knows.
 *
 * Config key {@code uncappedFps}: {@code auto} (default) honours options.ini; {@code true} /
 * {@code false} force the in-game cap off / on for a run without touching the player's settings.
 */
public final class FrameCap {
   /** Same order as Core.setFramerate indices 2..14. */
   static final int[] FPS_TABLE = {244, 240, 165, 144, 120, 95, 90, 75, 60, 55, 45, 30, 24};
   public static final int MENU_SAME = 1;
   public static final int MENU_UNCAPPED = 2;
   public static final int MENU_CHOICES = 2 + FPS_TABLE.length;

   private static volatile int menuIndex = MENU_SAME;

   private FrameCap() {
   }

   // --- main-loop queries -------------------------------------------------------------

   private static boolean inGame() {
      return GameWindow.isIngameState() || GameWindow.states.current instanceof GameLoadingState;
   }

   /** Main thread only: is the current state's cap "uncapped"? */
   public static boolean uncappedNow() {
      if (!Overrides.enabled() || menuIndex == MENU_SAME || inGame()) {
         return PerformanceSettings.instance.isFramerateUncapped();
      }
      return menuIndex == MENU_UNCAPPED;
   }

   /** Main thread only: the fps lock for the current state; only meaningful when not uncapped. */
   public static int lockNow() {
      if (!Overrides.enabled() || menuIndex == MENU_SAME || inGame()) {
         return Math.max(1, PerformanceSettings.getLockFPS());
      }
      return menuIndex == MENU_UNCAPPED ? Math.max(1, PerformanceSettings.getLockFPS()) : FPS_TABLE[menuIndex - 3];
   }

   // --- per-phase frame counter: one console line per menu/game transition ----------------

   private static boolean phaseInGame;
   private static long phaseStartNs;
   private static int phaseFrames;

   /** Main thread, after every frameStep: logs "menu 3.2 s, 144 frames, 45.0 fps" when the phase flips. */
   public static void onFrame(long nowNs) {
      boolean game = inGame();
      if (phaseStartNs == 0L) {
         phaseInGame = game;
         phaseStartNs = nowNs;
      } else if (game != phaseInGame) {
         double secs = (nowNs - phaseStartNs) / 1.0e9;
         Log.info(String.format(java.util.Locale.ROOT, "frame cap: %s phase %.1f s, %d frames, %.1f fps (cap %s)",
            phaseInGame ? "game" : "menu", secs, phaseFrames, secs > 0 ? phaseFrames / secs : 0.0,
            phaseInGame ? describe() : describeMenu()));
         phaseInGame = game;
         phaseStartNs = nowNs;
         phaseFrames = 0;
      }
      phaseFrames++;
   }

   // --- menu option --------------------------------------------------------------------

   public static int menuIndex() {
      return menuIndex;
   }

   /** From the options screen; persists immediately. */
   public static void setMenuIndex(int index) {
      int clamped = Math.max(MENU_SAME, Math.min(MENU_CHOICES, index));
      if (clamped == menuIndex) {
         return;
      }
      menuIndex = clamped;
      save();
      Log.info("frame cap: menu " + describeMenu());
   }

   private static File file() {
      return new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt" + File.separator + "framecap.ini");
   }

   private static void load() {
      File f = file();
      if (!f.isFile()) {
         return;
      }
      Properties p = new Properties();
      try (FileReader r = new FileReader(f)) {
         p.load(r);
         int idx = Integer.parseInt(p.getProperty("menuFramerateIndex", String.valueOf(MENU_SAME)).trim());
         menuIndex = Math.max(MENU_SAME, Math.min(MENU_CHOICES, idx));
      } catch (Exception e) {
         Log.warn("could not read " + f + ": " + e);
      }
   }

   private static void save() {
      File f = file();
      try {
         f.getParentFile().mkdirs();
         try (FileWriter w = new FileWriter(f)) {
            w.write("# pzopt frame limiter for the menus; index into the Display-options combo\n");
            w.write("# 1 = same as in-game, 2 = uncapped, 3.. = 244 240 165 144 120 95 90 75 60 55 45 30 24\n");
            w.write("menuFramerateIndex=" + menuIndex + "\n");
         }
      } catch (Exception e) {
         Log.warn("could not write " + f + ": " + e);
      }
   }

   // --- start-up ----------------------------------------------------------------------

   public static void afterLoadOptions() {
      if (!Overrides.enabled()) {
         return;
      }
      SystemDisabler.setUncappedFPS(true);
      String mode = Config.UNCAPPED_FPS;
      if ("true".equalsIgnoreCase(mode)) {
         PerformanceSettings.instance.setFramerateUncapped(true);
      } else if ("false".equalsIgnoreCase(mode)) {
         PerformanceSettings.instance.setFramerateUncapped(false);
      } else {
         applySaved();
      }
      load();
      Log.info("frame cap: game " + describe() + " (uncappedFps=" + mode + "), menu " + describeMenu());
   }

   /** Re-reads the two lines Core.loadOptions parsed and then overwrote. */
   private static void applySaved() {
      File ini = new File(ZomboidFileSystem.instance.getCacheDir(), "options.ini");
      if (!ini.isFile()) {
         return;
      }
      int lock = -1;
      Boolean uncapped = null;
      try (BufferedReader r = new BufferedReader(new FileReader(ini))) {
         String line;
         while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("frameRate=")) {
               try {
                  lock = Integer.parseInt(line.substring("frameRate=".length()).trim());
               } catch (NumberFormatException ignored) {
               }
            } else if (line.startsWith("uncappedFPS=")) {
               uncapped = Boolean.parseBoolean(line.substring("uncappedFPS=".length()).trim());
            }
         }
      } catch (Exception e) {
         Log.warn("could not read " + ini + ": " + e);
         return;
      }
      if (uncapped == null || !uncapped) {
         return; // Core already applied the capped value
      }
      PerformanceSettings.instance.setFramerateUncapped(true);
      if (lock >= 24 && lock <= 244) {
         PerformanceSettings.setLockFPS(lock); // keep the saved value instead of the 60 Core reset it to
      }
   }

   public static String describe() {
      return PerformanceSettings.instance.isFramerateUncapped() ? "uncapped" : PerformanceSettings.getLockFPS() + " fps";
   }

   public static String describeMenu() {
      if (menuIndex == MENU_SAME) {
         return "same as game";
      }
      return menuIndex == MENU_UNCAPPED ? "uncapped" : FPS_TABLE[menuIndex - 3] + " fps";
   }
}
