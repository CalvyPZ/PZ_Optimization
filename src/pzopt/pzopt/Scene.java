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
 *       {@code off} makes sure no light item is equipped.</li>
 * </ul>
 * Unset keys leave the save as it is. Nothing here is written back: runs use the bench save copy.
 */
final class Scene {
   private static float timeOfDay = -1f;
   private static String weather = "";
   private static String torch = "";
   private static float thunderSecs = 6f;
   private static long lastThunderNs;
   private static int thunderCount;
   private static InventoryItem torchItem;

   private Scene() {
   }

   /** Read the flags once (called at world-ready) and apply them to the player and the climate. */
   static void apply(IsoPlayer p) {
      timeOfDay = Float.parseFloat(HarnessFlags.get("time_of_day", "-1"));
      weather = HarnessFlags.get("weather", "").trim().toLowerCase(java.util.Locale.ROOT);
      torch = HarnessFlags.get("torch", "").trim().toLowerCase(java.util.Locale.ROOT);
      thunderSecs = Float.parseFloat(HarnessFlags.get("thunder_secs", "6"));
      if (!requested()) {
         return;
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
      if ("on".equals(torch)) {
         try {
            torchItem = p.getInventory().AddItem("Base.HandTorch");
            if (torchItem == null) {
               Log.warn("harness: could not create Base.HandTorch");
            } else {
               p.setPrimaryHandItem(torchItem);
               torchItem.setActivated(true);
               Log.info("harness: torch on (Base.HandTorch, strength " + torchItem.getLightStrength() + ", distance " + torchItem.getLightDistance()
                     + ", cone " + torchItem.isTorchCone() + ", emitting " + torchItem.isEmittingLight() + ")");
            }
         } catch (Exception e) {
            Log.warn("harness: torch on failed: " + e);
         }
      } else if ("off".equals(torch)) {
         InventoryItem lit = p.getActiveLightItem();
         if (lit != null) {
            lit.setActivated(false);
            Log.info("harness: torch off (deactivated " + lit.getFullType() + ")");
         } else {
            Log.info("harness: torch off (no light item equipped)");
         }
      } else if (!torch.isEmpty()) {
         Log.warn("harness: unknown torch '" + torch + "' (on|off)");
         torch = "";
      }
   }

   /** Per-frame upkeep while the run is live: keep the overrides pinned and fire the scheduled lightning. */
   static void tick(IsoPlayer p, long nowNs) {
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
      return timeOfDay >= 0f || !weather.isEmpty() || !torch.isEmpty();
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
            + "\ntorch=" + (torch.isEmpty() ? "save" : torch)
            + "\nnight_strength=" + night + "\nprecipitation=" + precip + "\nlightning_strikes=" + thunderCount;
   }
}
