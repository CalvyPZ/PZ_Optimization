package pzopt;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.iso.weather.ClimateManager;

/**
 * Scene conditions for a harness run (the run.sh presets): time of day, weather and the player's
 * torch. Read from the flag file:
 * <ul>
 *   <li>{@code time_of_day=H} — game hour (0-24, fractional ok) forced when the world is up, so the
 *       lighting rebake a jump to night causes is over before the route;</li>
 *   <li>{@code weather=storm|clear} — {@code storm} stops the save's own weather period and
 *       weather generation, then pins the climate overrides a stock STAGE_STORM sets (rain 1.0,
 *       full cloud, wind, dark ambient) every frame, plus a lightning strike next to the player every
 *       {@code thunder_secs} seconds (default 6) so the flash + forced grid-stack recalc is part of
 *       every run at the same instants; {@code clear} stops weather and pins the fair-weather values;</li>
 *   <li>{@code torch=on|off} — {@code on} puts a lit Base.HandTorch in the player's primary hand
 *       (a cone light that follows the facing, so with {@code turn} it sweeps the lighting grid);
 *       {@code off} makes sure no light item is equipped;</li>
 *   <li>{@code visible=true} — the bench player is not made invisible (the harness default, so zombies
 *       ignore the player, is what {@code LightingJNI.playerSet} receives as ghost mode). Not needed for the
 *       torch: the beam draws with the invisible player too (seen live on every night-torch run of
 *       2026-09-20); it only never shows in the {@code --shot-at} captures, which hold the player still for
 *       2 s first.</li>
 * </ul>
 * Unset keys leave the save as it is. Nothing here is written back: runs use the bench save copy.
 */
final class Scene {
   private static float timeOfDay = -1f;
   private static String weather = "";
   private static String torch = "";
   private static boolean visible;
   private static float thunderSecs = 6f;
   private static long lastThunderNs;
   private static int thunderCount;
   private static InventoryItem torchItem;
   private static long lastTorchLogNs;
   private static java.lang.reflect.Field jniActiveTorches;

   private Scene() {
   }

   /** Read the flags once (called at world-ready) and apply them to the player and the climate. */
   static void apply(IsoPlayer p) {
      timeOfDay = Float.parseFloat(HarnessFlags.get("time_of_day", "-1"));
      weather = HarnessFlags.get("weather", "").trim().toLowerCase(java.util.Locale.ROOT);
      torch = HarnessFlags.get("torch", "").trim().toLowerCase(java.util.Locale.ROOT);
      thunderSecs = Float.parseFloat(HarnessFlags.get("thunder_secs", "6"));
      visible = Boolean.parseBoolean(HarnessFlags.get("visible", "false"));
      if (!requested()) {
         return;
      }
      if (visible) {
         p.setInvisible(false, true); // ghost mode off for the native lighting (opt-in; zombies then react to the player)
         Log.info("harness: player visible (ghost mode off; god mode " + p.isGodMod() + ")");
      }
      if (timeOfDay >= 0f) {
         GameTime gt = GameTime.getInstance();
         float h = timeOfDay % 24f;
         gt.setLastTimeOfDay(h);
         gt.setTimeOfDay(h);
         // the cached day info (dawn/dusk) is per date, which does not change; night strength is recomputed
         // from the hour on every climate tick. (forceDayInfoUpdate() here NPEs: currentDay is still null before
         // the first climate tick, preset-night-torch-1.)
         Log.info("harness: time of day forced to " + h + " h");
      }
      if (!weather.isEmpty()) {
         ClimateManager cm = ClimateManager.getInstance();
         if ("storm".equals(weather) || "clear".equals(weather)) {
            cm.stopWeatherAndThunder();          // the save's own weather period would fight the overrides
            cm.setEnabledWeatherGeneration(false); // ... and no new one may start mid-route
            assertWeather(cm);
            Log.info("harness: weather forced to " + weather + ("storm".equals(weather) ? ", lightning every " + thunderSecs + " s" : ""));
         } else {
            Log.warn("harness: unknown weather '" + weather + "' (storm|clear); leaving the save's weather");
            weather = "";
         }
      }
      if ("on".equals(torch) || "off".equals(torch)) {
         // strip every light the character already carries: the bench save's pistol has an always-on weapon light
         // (setActivated(false) does nothing for it) and getActiveLightItem prefers the secondary hand, so torch=off
         // would still leave a player light
         java.util.ArrayList<InventoryItem> lights = p.getActiveLightItems(new java.util.ArrayList<>());
         for (InventoryItem lit : lights) {
            lit.setActivated(false);
            if (lit.isEmittingLight()) {
               if (p.getPrimaryHandItem() == lit) p.setPrimaryHandItem(null);
               if (p.getSecondaryHandItem() == lit) p.setSecondaryHandItem(null);
               p.removeAttachedItem(lit);
               Log.info("harness: unequipped always-on light " + lit.getFullType());
            } else {
               Log.info("harness: deactivated light " + lit.getFullType());
            }
         }
      }
      if ("on".equals(torch)) {
         try {
            torchItem = p.getInventory().AddItem("Base.HandTorch");
            if (torchItem == null) {
               Log.warn("harness: could not create Base.HandTorch");
            } else {
               p.setPrimaryHandItem(torchItem);
               torchItem.setActivated(true);
               Log.info("harness: torch on (Base.HandTorch, strength " + torchItem.getLightStrength() + ", distance " + torchItem.getLightDistance()
                     + ", cone " + torchItem.isTorchCone() + ", emitting " + torchItem.isEmittingLight() + "); player torch strength "
                     + p.getTorchStrength() + " distance " + p.getLightDistance() + " cone " + p.isTorchCone());
            }
         } catch (Exception e) {
            Log.warn("harness: torch on failed: " + e);
         }
      } else if ("off".equals(torch)) {
         Log.info("harness: torch off; player torch strength " + p.getTorchStrength() + " (active light item " + (p.getActiveLightItem() == null ? "none" : p.getActiveLightItem().getFullType()) + ")");
      } else if (!torch.isEmpty()) {
         Log.warn("harness: unknown torch '" + torch + "' (on|off)");
         torch = "";
      }
   }

   /** Per-frame upkeep while the run is live: keep the overrides pinned and fire the scheduled lightning. */
   static void tick(IsoPlayer p, long nowNs) {
      if (!torch.isEmpty() && nowNs - lastTorchLogNs >= 5_000_000_000L) {
         lastTorchLogNs = nowNs;
         Log.info("harness: torch check: player strength " + p.getTorchStrength() + " dist " + p.getLightDistance() + " cone " + p.isTorchCone()
               + " primary=" + (p.getPrimaryHandItem() == null ? "none" : p.getPrimaryHandItem().getFullType())
               + " activated=" + (torchItem != null && torchItem.isActivated()) + " inInventory=" + (torchItem != null && p.getInventory().contains(torchItem))
               + " jniTorches=" + jniTorchCount() + " invisible=" + p.isInvisible() + " night=" + GameTime.getInstance().getNight()
               + " | square lighting (player, +3, +6 tiles ahead): " + squareLight(p, 0) + " " + squareLight(p, 3) + " " + squareLight(p, 6));
      }
      if (weather.isEmpty()) {
         return;
      }
      ClimateManager cm = ClimateManager.getInstance();
      assertWeather(cm);
      if ("storm".equals(weather) && thunderSecs > 0f) {
         if (lastThunderNs == 0L) {
            lastThunderNs = nowNs; // first strike one interval after the route starts, not on frame one
         } else if (nowNs - lastThunderNs >= (long)(thunderSecs * 1e9)) {
            lastThunderNs = nowNs;
            thunderCount++;
            // a strike 60 tiles from the player: inside the 7500-tile lightning range at near-full strength,
            // so ThunderStorm applies the flash (dayLightStrength ramp + dirtyRecalcGridStackTime = 1 for ~100 frames)
            cm.getThunderStorm().triggerThunderEvent((int)p.getX() + 60, (int)p.getY() - 60, true, true, true);
         }
      }
   }

   /** The JNI lighting of the square n tiles along the player's facing: canSee/darkMulti/target/rgb. */
   private static String squareLight(IsoPlayer p, int ahead) {
      try {
         float fx = p.getForwardDirectionX(), fy = p.getForwardDirectionY();
         zombie.iso.IsoGridSquare sq = p.getCell().getGridSquare((int)(p.getX() + fx * ahead), (int)(p.getY() + fy * ahead), (int)p.getZ());
         if (sq == null) return "null";
         zombie.iso.IsoGridSquare.ILighting l = sq.lighting[p.getIndex()];
         if (l == null) return "nolighting";
         return String.format(java.util.Locale.ROOT, "[see=%b dark=%.2f target=%.2f rgb=%.2f,%.2f,%.2f]", l.bCanSee(), l.darkMulti(), l.targetDarkMulti(),
               l.lightInfo().r, l.lightInfo().g, l.lightInfo().b);
      } catch (Exception e) {
         return "err:" + e;
      }
   }

   /** Number of torches LightingJNI handed to the native lighting on its last pass (private static list, read reflectively). */
   private static int jniTorchCount() {
      try {
         if (jniActiveTorches == null) {
            jniActiveTorches = zombie.iso.LightingJNI.class.getDeclaredField("activeTorches");
            jniActiveTorches.setAccessible(true);
         }
         return ((java.util.List<?>)jniActiveTorches.get(null)).size();
      } catch (Exception e) {
         return -1;
      }
   }

   /** Reset the per-run counters (route start), so the strike cadence is counted from the route. */
   static void routeStart(long nowNs) {
      lastThunderNs = nowNs;
      thunderCount = 0;
   }

   private static void assertWeather(ClimateManager cm) {
      // the values WeatherPeriod.update pins during STAGE_STORM with a strength-0.95 front (see the decompiled
      // zombie.iso.weather.WeatherPeriod, case 3): full rain and cloud, strong wind, dim desaturated light
      boolean storm = "storm".equals(weather);
      set(cm, ClimateManager.FLOAT_PRECIPITATION_INTENSITY, storm ? 1.0f : 0.0f);
      set(cm, ClimateManager.FLOAT_CLOUD_INTENSITY, storm ? 1.0f : 0.0f);
      set(cm, ClimateManager.FLOAT_WIND_INTENSITY, storm ? 0.9f : 0.1f);
      set(cm, ClimateManager.FLOAT_WIND_ANGLE_INTENSITY, storm ? 0.7f : 0.0f);
      set(cm, ClimateManager.FLOAT_FOG_INTENSITY, 0.0f);
      set(cm, ClimateManager.FLOAT_DESATURATION, storm ? 0.3f : 0.0f);
      set(cm, ClimateManager.FLOAT_GLOBAL_LIGHT_INTENSITY, storm ? 0.4f : 1.0f);
      set(cm, ClimateManager.FLOAT_AMBIENT, storm ? 0.45f : 1.0f);
      set(cm, ClimateManager.FLOAT_DAYLIGHT_STRENGTH, storm ? 0.45f : 1.0f);
      cm.getClimateBool(ClimateManager.BOOL_IS_SNOW).setOverride(false);
   }

   private static void set(ClimateManager cm, int id, float value) {
      cm.getClimateFloat(id).setOverride(value, 1.0f);
   }

   static boolean requested() {
      return timeOfDay >= 0f || !weather.isEmpty() || !torch.isEmpty() || visible;
   }

   /** Lines for pzopt-bench.out. */
   static String summary() {
      float night = -1f;
      float precip = -1f;
      try {
         ClimateManager cm = ClimateManager.getInstance();
         night = cm.getNightStrength();
         precip = cm.getPrecipitationIntensity();
      } catch (Exception ignored) {
      }
      return "time_of_day=" + (timeOfDay >= 0f ? Float.toString(timeOfDay) : "save")
            + "\ngame_hour=" + GameTime.getInstance().getTimeOfDay()
            + "\nweather=" + (weather.isEmpty() ? "save" : weather)
            + "\ntorch=" + (torch.isEmpty() ? "save" : torch) + "\nvisible=" + visible
            + "\nnight_strength=" + night + "\nprecipitation=" + precip + "\nlightning_strikes=" + thunderCount;
   }
}
