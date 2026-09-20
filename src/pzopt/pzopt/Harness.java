package pzopt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.input.GameKeyboard;
import zombie.iso.IsoChunkMap;
import zombie.vehicles.BaseVehicle;

/**
 * Hands-off benchmark driver, run on the game thread once per frame from
 * Stats.frameTick. Reads Zomboid/Lua/pzopt-harness.txt (written by
 * harness/run.sh, also read by the Lua mod that auto-continues the save).
 *
 * Keys it uses:
 *   mode     bench|parity|drive  drive the legacy route or validate and run a vehicle route; anything else: inactive
 *   route    legs      e.g. "E:600,S:600,W:600,N:600" — direction and length in tiles (drive: total distance; first leg = spawn heading)
 *   vehicle  script    drive mode: vehicle spawned on the nearest road when the player is on foot (default the race car; "none" = fixture required)
 *   kmh      km/h      drive mode: cruise-control speed (default 60); the route follows the road (roadFollow)
 *   speed    tiles/s   default 18 (about car speed on a road)
 *   turn     deg/s     bench/parity: spin the player's facing at this rate along the route so the view cone, lighting
 *                      cone and cutaways keep changing (default 0 = keep the save's facing)
 *   settle   seconds   wait after the world is up before moving (default 15; harness/run.sh passes 5: the
 *                      load burst and the forced-zoom bake are over ~2 s after the world is up)
 *   zoom     max|level  force the camera zoom before the route (auto-zoom off); drive mode defaults to max, other modes keep the save's zoom
 *   max_seconds        drive mode: give up (route_status=timeout) after this long on the route (default 90)
 *   route_start_epoch  unix seconds: do not start the route before this instant (puts the route on the
 *                      schedule the external MangoHud log was configured for); absent = the route starts
 *                      settle seconds after the world is up, whenever that is
 *   mangohud_end_epoch unix seconds at which the MangoHud log closes; the game stays up until then
 *                      (a few seconds past the route end on schedule), because MangoHud only writes
 *                      its CSV if the log ends while the game runs
 *   mangohud_secs      length of the external log; when mangohud_end_epoch is absent the log is assumed to
 *                      start 3 s before the route (run.sh starts it via mangohudctl) and the linger is derived
 *
 * Whatever the flags, the instant the route will start is published as soon as the world is up in
 * Zomboid/pzopt-schedule.out (world_ready_epoch_ms, route_start_epoch_ms, log_end_epoch_ms): run.sh
 * waits for that file and times the external log and the fps-metrics reset off it. When run.sh has
 * stopped the log itself it drops Zomboid/pzopt-logdone, which ends the linger early.
 *
 * bench and parity retain the teleport control route. drive uses the vehicle's
 * normal CarController input path and completes from observed vehicle
 * displacement, so it never teleports the player or removes them from the
 * vehicle.
 */
public final class Harness {
   /** True when the flag file asks for a bench run; decided once at class init so the frame hook stays a boolean test. */
   public static final boolean REQUESTED = "bench".equals(HarnessFlags.get("mode")) || "parity".equals(HarnessFlags.get("mode")) || "drive".equals(HarnessFlags.get("mode"));
   private static final int IDLE = 0, WAIT_WORLD = 1, SETTLE = 2, RUN = 3, LINGER = 5, DONE = 4;
   private static long lingerUntilEpochMs;
   /** Instant the route starts (unix ms), fixed at world-ready: max(route_start_epoch, world ready + settle). */
   private static long plannedStartEpochMs;
   /** Derived end of the external log when mangohud_end_epoch is absent (0 = none). */
   private static long derivedLogEndEpochMs;
   private static int state = IDLE;
   private static boolean started;
   private static final String DEFAULT_ROUTE = "E:400,S:500,W:400,N:500";
   /** drive mode: vehicle script spawned under the player when the save has them on foot ("none" = require a fixture). */
   private static final String DEFAULT_VEHICLE = "Base.RaceCar12"; // fastest script: CarRacecar template, maxSpeed 120, engineForce 7000
   private static boolean vehicleSpawned;
   private static long lastTelemetryNs;
   private static boolean reseated;
   private static long quitRequestedEpochMs;
   private static long stateSinceNs;
   private static long lastFrameNs;
   private static float speed = 18f;
   /** bench: degrees per second the player facing rotates while on the route (flag turn, 0 = off). */
   private static float turnDegPerSec = 0f;
   private static float turnAngle = 0f;
   private static float settle = 15f;
   private static final List<float[]> legs = new ArrayList<>(); // {dx, dy, length}
   private static int leg = 0;
   private static float legDone = 0f;
   private static float x, y;
   private static float startX, startY;
   private static int chunksAtStart;
   private static long runStartNs;
   private static long runStartEpochMs, runEndEpochMs; // wall clock, to align external (MangoHud) logs
   private static boolean driving;
   private static BaseVehicle vehicle;
   private static float vehicleStartX, vehicleStartY;
   private static float drivenDistance;
   private static boolean rejected;
   private static String routeStatus = "complete";
   private static float maxSeconds = 90f;
   /** drive mode: cruise-control (regulator) speed in km/h the vehicle is driven at (flag kmh, default 60). */
   private static float cruiseKmh = 60f; // road speed; curves are followed by roadFollow(), so flat out is not needed
   /** Per-thread CPU time at route start (thread id -> ns), for pzopt-threads.out. */
   private static Map<Long, Long> cpuAtStart;
   private static long processCpuAtStart;

   private Harness() {
   }

   static boolean active() {
      return state != IDLE && state != DONE && state != LINGER;
   }

   /** True when the MangoHud overlay library is mapped into this process (Linux only). */
   static boolean mangoHudLoaded() {
      try {
         return java.nio.file.Files.lines(java.nio.file.Path.of("/proc/self/maps")).anyMatch(l -> l.contains("MangoHud") || l.contains("mangohud"));
      } catch (Exception e) {
         return false;
      }
   }

   /**
    * Leave the world. Core.quit() only sets Core.exiting for IngameState to act on; the Lua mod then
    * ends the process at the main menu (started=1 in the flag file). If the process is still in the
    * world 10 s later, quitToDesktop() (GameWindow.closeRequested), and 10 s after that System.exit:
    * a benchmark run must never sit in the world waiting for a click.
    */
   /** Publishes the instants run.sh needs to time the external log; written whole, then renamed into place. */
   private static void writeSchedule(long worldReadyMs) {
      File dir = new File(ZomboidFileSystem.instance.getCacheDir());
      File tmp = new File(dir, "pzopt-schedule.out.tmp");
      File f = new File(dir, "pzopt-schedule.out");
      try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
         w.write("world_ready_epoch_ms=" + worldReadyMs + "\n");
         w.write("route_start_epoch_ms=" + plannedStartEpochMs + "\n");
         w.write("log_end_epoch_ms=" + derivedLogEndEpochMs + "\n");
         w.write("settle=" + settle + "\n");
      } catch (java.io.IOException e) {
         Log.warn("harness: could not write " + tmp + ": " + e);
         return;
      }
      if (!tmp.renameTo(f)) Log.warn("harness: could not rename " + tmp + " to " + f);
   }

   private static void requestQuit() {
      quitRequestedEpochMs = System.currentTimeMillis();
      Log.info("harness: quit requested");
      Overlay.flushLog();
      Core.getInstance().quit();
      // frames stop once the world is gone, so the escalation cannot rely on onFrame:
      // a timer thread closes the window after 15 s and ends the process after 30 s
      Thread t = new Thread(() -> {
         try {
            Thread.sleep(15_000L);
            Log.warn("harness: process still alive 15 s after quit; requesting window close");
            Core.getInstance().quitToDesktop();
            Thread.sleep(15_000L);
            Log.warn("harness: process still alive 30 s after quit; exiting");
            Stats.flush();
            System.exit(0);
         } catch (InterruptedException ignored) {
         }
      }, "pzopt-quit");
      t.setDaemon(true);
      t.start();
   }

   /** Quit now, or after the external log has closed if the runner asked for that and the logger is actually present. */
   private static void quitWhenLogsAreDone() {
      long end = Long.parseLong(HarnessFlags.get("mangohud_end_epoch", "0")) * 1000L;
      if (end == 0) end = derivedLogEndEpochMs;
      long wait = end - System.currentTimeMillis();
      if (wait > 0 && wait < 600_000L && mangoHudLoaded()) {
         Log.info("harness: lingering " + wait / 1000 + "s for the MangoHud log to close");
         lingerUntilEpochMs = end;
         state = LINGER;
         return;
      }
      state = DONE;
      requestQuit(); // to the main menu (saves); the Lua mod then quits the process
   }

   /** First call decides whether a harness run is requested; afterwards drives the state machine. */
   public static void onFrame(long nowNs) {
      if (state == DONE) {
         return;
      }
      if (!started) {
         started = true;
         if (!REQUESTED) {
            state = DONE;
            return;
         }
         speed = Float.parseFloat(HarnessFlags.get("speed", "18"));
         turnDegPerSec = Float.parseFloat(HarnessFlags.get("turn", "0"));
         settle = Float.parseFloat(HarnessFlags.get("settle", "15"));
         maxSeconds = Float.parseFloat(HarnessFlags.get("max_seconds", "90"));
         cruiseKmh = Float.parseFloat(HarnessFlags.get("kmh", "60"));
          parseRoute(HarnessFlags.get("route", DEFAULT_ROUTE));
          driving = "drive".equals(HarnessFlags.get("mode"));
         Log.info("harness: " + HarnessFlags.get("mode") + " mode, route=" + HarnessFlags.get("route", DEFAULT_ROUTE) + (driving ? " cruise=" + cruiseKmh + " km/h" : " speed=" + speed + " tiles/s") + " settle=" + settle + "s");
         state = WAIT_WORLD;
         stateSinceNs = nowNs;
      }
      float dt = lastFrameNs == 0L ? 0f : (nowNs - lastFrameNs) / 1e9f;
      lastFrameNs = nowNs;
      IsoPlayer p = IsoPlayer.getInstance();
      switch (state) {
         case WAIT_WORLD -> {
            if (p != null && p.getCurrentSquare() != null) {
                HarnessFlags.markStarted();
                p.setGodMod(true, true);
                p.setInvisible(true, true);
                if (driving) {
                   vehicle = p.getVehicle();
                   if (vehicle == null && !"none".equals(HarnessFlags.get("vehicle", DEFAULT_VEHICLE))) {
                      // no fixture with the player behind the wheel: put a fresh vehicle on the player's
                      // square and seat them, the same steps as the debug menu's "spawn vehicle"
                      vehicle = spawnAndEnter(p, HarnessFlags.get("vehicle", DEFAULT_VEHICLE));
                   }
                   if (vehicle == null || !vehicle.isDriver(p)) {
                      reject("player is not driving a vehicle");
                      return;
                   }
                   vehicleStartX = vehicle.getX();
                   vehicleStartY = vehicle.getY();
                   startX = x = vehicleStartX;
                   startY = y = vehicleStartY;
                   Log.info("harness: driving fixture valid, vehicle=" + vehicle.getScriptName() + " at " + (int)vehicleStartX + "," + (int)vehicleStartY);
                } else {
                   p.ensureNotInVehicle();
                   startX = x = p.getX();
                   startY = y = p.getY();
                }
                Log.info("harness: world ready, player at " + (int)x + "," + (int)y + "," + (int)p.getZ() + "; settling " + settle + "s");
                Log.info("harness: MangoHud is " + (mangoHudLoaded() ? "loaded" : "NOT loaded") + " in this process");
                Log.info(ModelShaders.summary()); // how many model loads blocked on the render thread during boot + load
                long scheduled = Long.parseLong(HarnessFlags.get("route_start_epoch", "0")) * 1000L;
                long nowMs = System.currentTimeMillis();
                long earliest = nowMs + (long)(settle * 1000);
                plannedStartEpochMs = Math.max(scheduled, earliest);
                if (scheduled > 0) {
                   // slack between the earliest possible route start (now + settle) and the fixed schedule; negative = the
                   // route starts LATE and the harness --lead needs to grow
                   Log.info("harness: route scheduled in " + (scheduled - nowMs) / 1000 + "s; lead margin after the settle time " + (scheduled - earliest) / 1000 + "s");
                } else {
                   Log.info("harness: route starts in " + settle + "s (no fixed schedule)");
                }
                long logSecs = Long.parseLong(HarnessFlags.get("mangohud_secs", "0"));
                if (logSecs > 0 && HarnessFlags.get("mangohud_end_epoch", "").isEmpty()) {
                   derivedLogEndEpochMs = plannedStartEpochMs - 3000L + logSecs * 1000L + 1000L;
                }
                writeSchedule(nowMs);
                // zoom flag: "max" (drive mode default) or a level such as 2.5 / 1.0; unset = whatever the save had.
                // Forced here, at the start of the settle time, so the burst of chunk-texture bakes a zoom change
                // causes (hundreds of chunk levels come on screen at once: a 280 ms frame) is over before the route.
                String zoomFlag = HarnessFlags.get("zoom", driving ? "max" : "");
                if (!zoomFlag.isEmpty() && !forceZoom(p, zoomFlag)) {
                   reject("could not force zoom " + zoomFlag);
                   return;
                }
               state = SETTLE;
               stateSinceNs = nowNs;
            }
         }
         case SETTLE -> {
             long notBefore = Long.parseLong(HarnessFlags.get("route_start_epoch", "0")) * 1000L;
             if (System.currentTimeMillis() >= plannedStartEpochMs) {
                if (notBefore > 0) {
                   long late = System.currentTimeMillis() - notBefore;
                   Log.info("harness: route start " + (late > 1500 ? "LATE by " + late / 1000 + "s (world load took longer than the lead; the external log window is short)" : "on schedule"));
                }
                // the zoom was forced when the world came up (see WORLD_READY); re-assert it in case auto-zoom or a
                // vehicle entry retargeted it during the settle time
                String zoomFlag = HarnessFlags.get("zoom", driving ? "max" : "");
                if (!zoomFlag.isEmpty() && !forceZoom(p, zoomFlag)) {
                   reject("could not force zoom " + zoomFlag);
                   return;
                }
                if (driving && !vehicle.isEngineRunning()) {
                   // the fixture may have been saved with the engine off; the
                   // route needs it running, and the ignition key check is what
                   // the auto-shutdown would otherwise trip on
                   vehicle.setKeysInIgnition(true);
                   vehicle.engineDoRunning();
                   Log.info("harness: started the vehicle engine (running=" + vehicle.isEngineRunning() + ")");
                }
                if (driving) {
                   vehicleStartX = vehicle.getX();
                   vehicleStartY = vehicle.getY();
                   lastTelemetryNs = 0L;
                }
                Log.info("harness: route start");
               chunksAtStart = Stats.chunkCount();
               runStartNs = nowNs;
               runStartEpochMs = System.currentTimeMillis();
               sampleThreadCpu();
               Stats.mark("route-start");
               state = RUN;
            }
         }
          case RUN -> {
            if (p == null) {
               Log.warn("harness: player vanished mid-route (world reloaded?); aborting run");
               state = DONE;
               return;
            }
             if (driving) {
                if (vehicle == null || vehicle.getController() == null || p.getVehicle() != vehicle || !vehicle.isDriver(p)) {
                   String why = "vehicle=" + vehicle + " controller=" + (vehicle == null ? null : vehicle.getController()) + " playerVehicle=" + p.getVehicle()
                         + " isDriver=" + (vehicle != null && vehicle.isDriver(p)) + " seat0=" + (vehicle == null ? null : vehicle.getCharacter(0)) + " player=" + p + " sameInstance=" + (p == IsoPlayer.players[0]);
                   if (vehicle != null && vehicle.getController() != null && !reseated) {
                      reseated = true;
                      boolean ok = vehicle.enter(0, p);
                      Log.warn("harness: driver check failed (" + why + "); re-seated=" + ok);
                      if (ok) return;
                   }
                   reject("driver or vehicle changed during route: " + why);
                   return;
                }
                // CarController.updateControls() overwrites clientControls from the keyboard every
                // frame, so input injection does not stick. The game's cruise control does: with the
                // regulator on and no pedal pressed the controller opens the throttle until the
                // regulator speed is reached (CarController.update), exactly like the player pressing
                // the cruise-control key. Re-asserted every frame in case something switched it off.
                if (!vehicle.isRegulator() || vehicle.getRegulatorSpeed() != cruiseKmh) {
                   vehicle.setRegulator(true);
                   vehicle.setRegulatorSpeed(cruiseKmh);
                }
                if (!vehicle.isEngineRunning()) {
                   vehicle.setKeysInIgnition(true);
                   vehicle.engineDoRunning();
                }
                 float steer = roadFollow(dt);
                 if (nowNs - lastTelemetryNs >= 1_000_000_000L) {
                    lastTelemetryNs = nowNs;
                    Log.info(String.format(java.util.Locale.ROOT, "harness: drive t=%.0fs pos=%.1f,%.1f dist=%.1f lateral=%.2f steer=%+.0f speed=%.1fkm/h engine=%s regulator=%s/%.0f throttle=%.2f gear=%s paused=%s zoom=%.2f",
                          (nowNs - runStartNs) / 1e9, vehicle.getX(), vehicle.getY(), drivenDistance, lateralError(), steer, vehicle.getCurrentSpeedKmHour(), vehicle.isEngineRunning(),
                          vehicle.isRegulator(), vehicle.getRegulatorSpeed(), vehicle.throttle, vehicle.transmissionNumber, zombie.GameTime.isGamePaused(), Core.getInstance().getZoom(p.getIndex())));
                 }
                 // path length: the road turns, so the distance is accumulated per frame
                 float ddx = vehicle.getX() - x;
                 float ddy = vehicle.getY() - y;
                 x = vehicle.getX();
                 y = vehicle.getY();
                 drivenDistance += (float)Math.sqrt(ddx * ddx + ddy * ddy);
                if (drivenDistance >= routeDistance()) {
                   finish(p, 0);
                } else if ((nowNs - runStartNs) / 1e9f >= maxSeconds) {
                   routeStatus = "timeout";
                   Log.warn("harness: vehicle covered " + drivenDistance + " of " + routeDistance() + " tiles in " + maxSeconds + "s (speed now " + vehicle.getCurrentSpeedKmHour() + " km/h, engine running=" + vehicle.isEngineRunning() + ")");
                   finish(p, 0);
                }
                return;
             }
             float step = speed * Math.min(dt, 0.1f);
            while (step > 0f && leg < legs.size()) {
               float[] l = legs.get(leg);
               float remain = l[2] - legDone;
               float take = Math.min(step, remain);
               x += l[0] * take;
               y += l[1] * take;
               legDone += take;
               step -= take;
               if (legDone >= l[2]) {
                  leg++;
                  legDone = 0f;
               }
            }
            // whole-tile teleports through the game's own API (what the debug
            // teleport tools use); it also takes the player out of a vehicle,
            // which plain setX/setY does not survive
            if ((int)x != p.getXi() || (int)y != p.getYi()) {
               p.teleportTo((int)x, (int)y, 0);
            }
            if (turnDegPerSec != 0f) {
               // spin the facing so the vision cone, lighting cone and buildings-in-front scans keep changing
               turnAngle = (turnAngle + turnDegPerSec * Math.min(dt, 0.1f)) % 360f;
               p.setDirectionAngle(turnAngle);
            }
            if (leg >= legs.size()) {
               float secs = (nowNs - runStartNs) / 1e9f;
               int chunks = Stats.chunkCount() - chunksAtStart;
               Stats.mark("route-end");
               runEndEpochMs = System.currentTimeMillis();
               Log.info("harness: route done in " + secs + "s, " + chunks + " chunks loaded (" + chunks / secs + "/s); quitting");
               writeThreadCpu(secs);
               writeSummary(secs, chunks);
               Stats.flush();
               quitWhenLogsAreDone();
            }
         }
         case LINGER -> {
            // run.sh drops pzopt-logdone once it has closed the external log itself (control socket)
            if (System.currentTimeMillis() >= lingerUntilEpochMs || new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-logdone").isFile()) {
               state = DONE;
               requestQuit();
            }
         }
         default -> {
         }
      }
   }

   private static void parseRoute(String route) {
      legs.clear();
      for (String part : route.split(",")) {
         String[] kv = part.trim().split(":");
         float len = Float.parseFloat(kv[1]);
         switch (kv[0].trim().toUpperCase()) {
            case "E" -> legs.add(new float[]{1, 0, len});
            case "W" -> legs.add(new float[]{-1, 0, len});
            case "N" -> legs.add(new float[]{0, -1, len});
            case "S" -> legs.add(new float[]{0, 1, len});
            default -> Log.warn("harness: unknown route direction " + kv[0]);
         }
      }
   }

   private static void writeSummary(float secs, int chunks) {
      File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-bench.out");
      try (FileWriter w = new FileWriter(f)) {
          Core core = Core.getInstance();
          int playerIndex = IsoPlayer.getInstance() == null ? 0 : IsoPlayer.getInstance().getIndex();
          float zoom = core.getZoom(playerIndex);
          w.write("mode=" + HarnessFlags.get("mode") + "\nroute=" + HarnessFlags.get("route", DEFAULT_ROUTE) + "\nspeed=" + speed + "\nstart=" + (int)startX + "," + (int)startY
                + "\nroute_status=" + (rejected ? "rejected" : routeStatus)
                + "\nroute_reason=" + HarnessFlags.get("reject_reason", "")
                 + "\nvehicle=" + (vehicle == null ? "none" : vehicle.getScriptName()) + "\nvehicle_spawned=" + vehicleSpawned
                + "\nmovement_source=" + (driving ? "vehicle-controller" : "teleport-control")
                + "\nvehicle_distance=" + drivenDistance + "\ncruise_kmh=" + (driving ? cruiseKmh : 0f)
                + "\nzoom=" + zoom + "\nmax_zoom=" + core.getMaxZoom() + "\nauto_zoom_option=" + autoZoomWasOn
                + "\noffscreen_width=" + core.getOffscreenWidth(playerIndex) + "\noffscreen_height=" + core.getOffscreenHeight(playerIndex)
                + "\npan_camera=" + HarnessFlags.get("pan_camera", Boolean.toString(GameKeyboard.isKeyDown("PanCamera")))
                + "\nchunk_map_width=" + IsoChunkMap.chunkGridWidth
                + "\nresolution=" + core.getScreenWidth() + "x" + core.getScreenHeight()
                + "\nrenderer_backend=OpenGL\nrenderer_opengl33=" + (!core.getUseOpenGL21())
                + "\ndashboard=" + HarnessFlags.get("dashboard", "enabled")
                + "\nroute_start_epoch_ms=" + runStartEpochMs + "\nroute_end_epoch_ms=" + runEndEpochMs
                + "\nroute_seconds=" + secs + "\nchunks_loaded=" + chunks + "\nchunks_per_second=" + (secs > 0f ? chunks / secs : 0f)
                + "\nsettings=" + Config.describe() + "\n");
      } catch (IOException e) {
         Log.warn("harness: could not write summary: " + e);
      }
   }

   /**
    * Spawn a repaired vehicle facing the route's first leg on the player's square and seat the
    * player as its driver. Mirrors LuaManager.GlobalObject.addVehicleDebug plus the enter path the
    * "Enter vehicle" action takes (BaseVehicle.enter -> setPassenger + setVehicle). Returns null if
    * the vehicle could not be placed or entered.
    */
   private static BaseVehicle spawnAndEnter(IsoPlayer p, String script) {
      try {
         zombie.iso.IsoGridSquare sq = p.getCurrentSquare();
         // a vehicle driven straight from a field ends in the first tree line (drive-rec-1): put it on
         // the nearest road, centred, pointing along the longest straight run of street tiles
         zombie.iso.IsoGridSquare road = findRoad(sq, 40);
         if (road == null) {
            Log.warn("harness: no road within 40 tiles of " + sq.x + "," + sq.y + "; spawning where the player stands");
            road = sq;
         }
         int[] heading = bestHeading(road, HarnessFlags.get("heading", "auto"));
         if (heading == null) {
            Log.warn("harness: no straight road run from " + road.x + "," + road.y);
            heading = new int[]{1, 0, 0, 0};
         }
         headingX = heading[0];
         headingY = heading[1];
         zombie.iso.IsoDirections dir = headingX > 0 ? zombie.iso.IsoDirections.E : headingX < 0 ? zombie.iso.IsoDirections.W : headingY > 0 ? zombie.iso.IsoDirections.S : zombie.iso.IsoDirections.N;
         Log.info("harness: road at " + road.x + "," + road.y + " (player " + sq.x + "," + sq.y + "), heading " + dir + ", straight run " + heading[2] + " tiles" + (heading[3] == 1 ? " to the edge of the loaded map (continues)" : ""));
         // the route length is a path length now (roadFollow keeps the car on the road through curves), so a
         // requested distance longer than the first straight run is fine
         BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(script, dir, 0, road);
         if (v == null || v.getSquare() == null) {
            Log.warn("harness: could not place " + script + " at " + sq.x + "," + sq.y);
            return null;
         }
         // addVehicleDebug adds a random +-0.2 rad (11 deg) to the heading; a straight route needs it exact
         float angle = (float)(dir.toAngle() + Math.PI);
         while (angle > Math.PI * 2) angle -= (float)(Math.PI * 2);
         v.savedRot.setAngleAxis(angle, 0f, 1f, 0f);
         v.jniTransform.setRotation(v.savedRot);
         v.repair(); // every part at 100 % condition
         // engine quality 100 (script default 80 for the race car), stock loudness and force
         v.setEngineFeature(100, v.getEngineLoudness(), (int)v.getScript().getEngineForce());
         p.getInventory().AddItem(v.createVehicleKey());
         if (!v.enter(0, p)) {
            Log.warn("harness: could not seat the player in " + script);
            return null;
         }
         vehicleSpawned = true;
         Log.info("harness: spawned " + script + " facing " + dir + " (engine quality " + v.getEngineQuality() + ", condition 100) and seated the player as driver");
         return v;
      } catch (Exception e) {
         Log.warn("harness: vehicle spawn failed: " + e);
         return null;
      }
   }

   private static int headingX = 1, headingY = 0;
   private static float prevLateral;
   private static int steerFrame;
   private static java.lang.reflect.Field keyDown;
   private static int keyLeft = -1, keyRight = -1;

   /** Signed distance (tiles) of the vehicle from the line it started on: + = to the right of the heading. */
   private static float lateralError() {
      // heading E (+x): right is +y; W: right is -y; S (+y): right is -x; N (-y): right is +x
      float ox = vehicle.getX() - vehicleStartX, oy = vehicle.getY() - vehicleStartY;
      return headingX != 0 ? headingX * oy : -headingY * ox;
   }

   /**
    * Lane keeping through the keyboard: CarController.updateControls() reads the Left/Right bindings
    * from GameKeyboard's polled state, which is refreshed at the start of the frame and consumed by
    * IsoPlayer.update() after this hook ran (IsoCell.updateInternal updates the chunk map first), so a
    * key set here is seen this frame and cleared by the next poll. Proportional control by
    * duty-cycling the (binary) key over frames. Returns the steering applied (-1 left, +1 right, 0).
    */
   // road following: heading from the vehicle's own motion, road centre sampled ahead of it
   private static float hdgX = 1f, hdgY = 0f;
   private static float lastRoadOffset;
   private static float roadOffsetFiltered, roadOffsetRate;
   private static int noRoadFrames;

   /**
    * Steer toward the centre of the street some tiles ahead. The heading is the smoothed direction of motion
    * (falls back to the spawn heading while stationary); across that heading the street tiles at the look-ahead
    * point are scanned and their middle taken as the target. Returns the steering applied (-1 left, +1 right, 0).
    */
   private static float roadFollow(float dt) {
      try {
         if (keyDown == null) {
            keyDown = zombie.input.GameKeyboard.class.getDeclaredField("down");
            keyDown.setAccessible(true);
            keyLeft = Core.getInstance().getKeyBinding("Left").keyValue();
            keyRight = Core.getInstance().getKeyBinding("Right").keyValue();
            hdgX = headingX;
            hdgY = headingY;
         }
         boolean[] down = (boolean[])keyDown.get(null);
         if (down == null || keyLeft < 0 || keyRight < 0) return 0f;
         float vx = vehicle.getX() - x, vy = vehicle.getY() - y; // this frame's motion (x,y still hold last frame's position)
         float vlen = (float)Math.sqrt(vx * vx + vy * vy);
         if (vlen > 0.02f && vehicle.getCurrentSpeedKmHour() > 3f) {
            float k = 0.25f; // smoothing
            hdgX = hdgX * (1 - k) + (vx / vlen) * k;
            hdgY = hdgY * (1 - k) + (vy / vlen) * k;
            float hl = (float)Math.sqrt(hdgX * hdgX + hdgY * hdgY);
            if (hl > 1e-4f) { hdgX /= hl; hdgY /= hl; }
         }
         float kmh = Math.max(0f, vehicle.getCurrentSpeedKmHour());
         float look = Math.max(4f, Math.min(14f, kmh / 6f)); // tiles ahead: ~10 at 60 km/h
         float rx = -hdgY, ry = hdgX; // right-hand perpendicular (heading E -> right is +y)
         Float offset = roadCentreOffset(look, rx, ry);
         if (offset == null) offset = roadCentreOffset(look * 0.5f, rx, ry);
         if (offset == null) offset = roadCentreOffset(look * 1.6f, rx, ry);
         if (offset == null) {
            // nothing straight ahead: the road turns. Fan of bearings at the look-ahead radius, most street tiles wins
            int bestScore = 0; float bestA = 0f;
            for (int a = -90; a <= 90; a += 15) {
               if (a == 0) continue;
               double r = Math.toRadians(a);
               float dx = (float)(hdgX * Math.cos(r) - hdgY * Math.sin(r)), dy = (float)(hdgX * Math.sin(r) + hdgY * Math.cos(r));
               float cx = vehicle.getX() + dx * look, cy = vehicle.getY() + dy * look;
               int score = 0;
               for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) if (isStreet(square(Math.round(cx) + i, Math.round(cy) + j, (int)vehicle.getZ()))) score++;
               if (score > bestScore || (score == bestScore && score > 0 && Math.abs(a) < Math.abs(bestA))) { bestScore = score; bestA = a; }
            }
            if (bestScore > 0) offset = (float)(look * Math.sin(Math.toRadians(bestA))) * (bestA > 0 ? 1f : 1f);
         }
         if (offset == null) {
            noRoadFrames++;
            if (noRoadFrames == 120) Log.warn("harness: no street found ahead for 120 frames at " + vehicle.getX() + "," + vehicle.getY());
            return 0f;
         }
         noRoadFrames = 0;
         // The road scan quantises the target to half tiles, so a raw per-frame derivative is a step of
         // +-0.5 / dt (3.75 tiles at 240 fps) on every scan change: it slammed the wheel and the car
         // oscillated across the road until it hit a yard (2026-09-19 evening, three timeouts in a row).
         // Low-pass the offset over ~0.15 s and damp with the smoothed rate in tiles per second.
         float a = Math.min(1f, Math.max(dt, 1e-3f) / 0.15f);
         float prev = roadOffsetFiltered;
         roadOffsetFiltered += (offset - roadOffsetFiltered) * a;
         float rate = dt > 1e-3f ? (roadOffsetFiltered - prev) / dt : 0f;
         roadOffsetRate += (rate - roadOffsetRate) * a;
         float u = roadOffsetFiltered + 0.35f * roadOffsetRate;
         lastRoadOffset = offset;
         steerFrame++;
         float mag = Math.abs(u);
         if (mag < 0.5f) return 0f;
         int duty = mag > 3f ? 1 : mag > 1.5f ? 2 : 4; // press every frame / every 2nd / every 4th
         if (steerFrame % duty != 0) return 0f;
         if (u > 0) { down[keyRight] = true; return 1f; }
         down[keyLeft] = true;
         return -1f;
      } catch (Exception e) {
         Log.warn("harness: road following unavailable: " + e);
         keyLeft = keyRight = -2;
         return 0f;
      }
   }

   /** Signed offset (tiles, + = to the right of the heading) of the middle of the street `look` tiles ahead, or null if no street tile is there. */
   private static Float roadCentreOffset(float look, float rx, float ry) {
      float px = vehicle.getX() + hdgX * look, py = vehicle.getY() + hdgY * look;
      int z = (int)vehicle.getZ();
      int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
      for (int w = -7; w <= 7; w++) {
         int sx = Math.round(px + rx * w), sy = Math.round(py + ry * w);
         if (isStreet(square(sx, sy, z))) { min = Math.min(min, w); max = Math.max(max, w); }
      }
      if (min == Integer.MAX_VALUE) return null;
      if (max - min >= 13) return 0f; // street all across the scan (a wide junction): hold course
      return (min + max) / 2f;
   }

   @SuppressWarnings("unused")
   private static float laneKeep(float dt) {
      try {
         if (keyDown == null) {
            keyDown = zombie.input.GameKeyboard.class.getDeclaredField("down");
            keyDown.setAccessible(true);
            keyLeft = Core.getInstance().getKeyBinding("Left").keyValue();
            keyRight = Core.getInstance().getKeyBinding("Right").keyValue();
         }
         boolean[] down = (boolean[])keyDown.get(null);
         if (down == null || keyLeft < 0 || keyRight < 0) return 0f;
         float lat = lateralError();
         float vel = dt > 0f ? (lat - prevLateral) / dt : 0f; // tiles/s across the lane
         prevLateral = lat;
         float u = lat + 0.7f * vel; // predicted offset ~0.7 s ahead
         steerFrame++;
         float mag = Math.abs(u);
         if (mag < 0.15f) return 0f;
         int duty = mag > 1.5f ? 1 : mag > 0.6f ? 2 : 4; // press every frame / every 2nd / every 4th
         if (steerFrame % duty != 0) return 0f;
         if (u > 0) { down[keyLeft] = true; return -1f; }
         down[keyRight] = true;
         return 1f;
      } catch (Exception e) {
         Log.warn("harness: lane keeping unavailable: " + e);
         keyLeft = keyRight = -2;
         return 0f;
      }
   }

   static boolean isStreet(zombie.iso.IsoGridSquare sq) {
      if (sq == null) return false;
      zombie.iso.IsoObject floor = sq.getFloor();
      if (floor == null || floor.getSprite() == null || floor.getSprite().getName() == null) return false;
      String n = floor.getSprite().getName();
      return n.contains("blends_street") || n.contains("floors_exterior_street");
   }

   private static zombie.iso.IsoGridSquare square(int x, int y, int z) {
      return zombie.iso.IsoWorld.instance.getCell().getGridSquare(x, y, z);
   }

   /** Nearest street square (Chebyshev rings), moved to the middle of the road's width across its axis. */
   static zombie.iso.IsoGridSquare findRoad(zombie.iso.IsoGridSquare from, int radius) {
      for (int r = 0; r <= radius; r++) {
         for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
               if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
               zombie.iso.IsoGridSquare sq = square(from.x + dx, from.y + dy, from.z);
               if (isStreet(sq)) return centreOfRoad(sq);
            }
         }
      }
      return null;
   }

   /** Width of the street run through sq along (dx,dy) in both directions, capped. */
   private static int run(zombie.iso.IsoGridSquare sq, int dx, int dy, int cap) {
      int n = 0;
      for (int i = 1; i <= cap; i++) {
         if (!isStreet(square(sq.x + dx * i, sq.y + dy * i, sq.z))) break;
         n++;
      }
      return n;
   }

   private static zombie.iso.IsoGridSquare centreOfRoad(zombie.iso.IsoGridSquare sq) {
      // the road axis is the direction with the longer street run; centre across the other one
      int alongX = run(sq, 1, 0, 30) + run(sq, -1, 0, 30);
      int alongY = run(sq, 0, 1, 30) + run(sq, 0, -1, 30);
      int px = alongX >= alongY ? 0 : 1, py = alongX >= alongY ? 1 : 0;
      int plus = run(sq, px, py, 12), minus = run(sq, -px, -py, 12);
      int shift = (plus - minus) / 2;
      zombie.iso.IsoGridSquare c = square(sq.x + px * shift, sq.y + py * shift, sq.z);
      return c != null ? c : sq;
   }

   /**
    * {dx, dy, straightTiles}: the axis direction with the longest run of street tiles ahead of sq
    * (a lateral wobble of 2 tiles is tolerated), or the requested one (N/S/E/W) with its run.
    */
   static int[] bestHeading(zombie.iso.IsoGridSquare sq, String want) {
      int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      String[] names = {"E", "W", "S", "N"};
      int[] best = null;
      for (int d = 0; d < 4; d++) {
         if (!"auto".equalsIgnoreCase(want) && !names[d].equalsIgnoreCase(want)) continue;
         int dx = dirs[d][0], dy = dirs[d][1], px = dy, py = dx; // perpendicular for the tolerance
         int n = 0;
         boolean edge = false; // the scan reached unloaded chunks: the road may well continue
         for (int i = 1; i <= 1500; i++) {
            boolean ok = false;
            zombie.iso.IsoGridSquare centre = square(sq.x + dx * i, sq.y + dy * i, sq.z);
            if (centre == null) { edge = true; break; }
            for (int w = -2; w <= 2 && !ok; w++) ok = isStreet(square(sq.x + dx * i + px * w, sq.y + dy * i + py * w, sq.z));
            if (!ok) break;
            n++;
         }
         if (best == null || n > best[2]) best = new int[]{dx, dy, n, edge ? 1 : 0};
      }
      return best;
   }

   private static float routeDistance() {
      float distance = 0f;
      for (float[] leg : legs) distance += leg[2];
      return distance;
   }

   /**
    * Pin the camera at the configured maximum zoom for the route. The game's
    * auto-zoom (MultiTextureFBO2.update) retargets the zoom every frame while the
    * camera character is in a vehicle, so it is switched off for the run — the
    * value it would pick is the maximum anyway, but it would keep the zoom
    * sliding for the first seconds of the route and re-arm on every re-entry.
    */
   private static boolean forceZoom(IsoPlayer p, String level) {
      Core core = Core.getInstance();
      if (core.offscreenBuffer == null) return false;
      float target = level.equals("max") ? core.offscreenBuffer.getMaxZoom() : Float.parseFloat(level);
      autoZoomWasOn = core.getAutoZoom(p.getIndex());
      core.setAutoZoom(p.getIndex(), false); // auto-zoom retargets the zoom every frame (and forces max in a vehicle)
      core.offscreenBuffer.setZoomAndTargetZoom(p.getIndex(), target);
      Log.info("harness: zoom forced to " + core.getZoom(p.getIndex()) + " (requested " + level + ", max " + core.getMaxZoom() + ", auto-zoom was " + autoZoomWasOn + ")");
      return Math.abs(core.getZoom(p.getIndex()) - target) < 0.01f;
   }
   private static boolean autoZoomWasOn;

   private static void finish(IsoPlayer p, int chunks) {
      float secs = (System.nanoTime() - runStartNs) / 1e9f;
      chunks = Stats.chunkCount() - chunksAtStart;
      Stats.mark("route-end");
      runEndEpochMs = System.currentTimeMillis();
      Log.info("harness: route " + routeStatus + " in " + secs + "s, vehicle distance=" + drivenDistance + "; quitting");
      writeThreadCpu(secs);
      writeSummary(secs, chunks);
      Stats.flush();
      if (driving && vehicle != null && vehicle.getController() != null) {
         vehicle.setRegulator(false);
         vehicle.setRegulatorSpeed(0f);
         vehicle.getController().clientControls.forceBrake = System.currentTimeMillis(); // updateControls brakes for 1 s
      }
      quitWhenLogsAreDone();
   }

   /**
    * Snapshot every thread's CPU time. With the route wall time this says which
    * threads are saturated (game thread at ~100 % = main-loop bound) and how much
    * of the machine the process used — the utilization side of the objective.
    */
   private static void sampleThreadCpu() {
      ThreadMXBean mx = ManagementFactory.getThreadMXBean();
      cpuAtStart = new HashMap<>();
      try {
         if (!mx.isThreadCpuTimeEnabled()) mx.setThreadCpuTimeEnabled(true);
         for (long id : mx.getAllThreadIds()) {
            long t = mx.getThreadCpuTime(id);
            if (t >= 0) cpuAtStart.put(id, t);
         }
         processCpuAtStart = processCpuNs();
      } catch (Exception e) {
         Log.warn("harness: thread cpu sampling unavailable: " + e);
         cpuAtStart = null;
      }
   }

   private static long processCpuNs() {
      java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
      if (os instanceof com.sun.management.OperatingSystemMXBean sun) return sun.getProcessCpuTime();
      return -1L;
   }

   /** pzopt-threads.out: "name\tcpu_ms\tshare" per thread over the route (share = cpu / wall), plus the process total. */
   private static void writeThreadCpu(float secs) {
      if (cpuAtStart == null || secs <= 0f) return;
      ThreadMXBean mx = ManagementFactory.getThreadMXBean();
      List<String[]> rows = new ArrayList<>();
      long sum = 0;
      for (long id : mx.getAllThreadIds()) {
         long t = mx.getThreadCpuTime(id);
         if (t < 0) continue;
         long d = t - cpuAtStart.getOrDefault(id, 0L);
         if (d <= 0) continue;
         ThreadInfo info = mx.getThreadInfo(id);
         String name = info == null ? ("thread-" + id) : info.getThreadName();
         rows.add(new String[]{name, Long.toString(d)});
         sum += d;
      }
      rows.sort((a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
      File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-threads.out");
      try (FileWriter w = new FileWriter(f)) {
         w.write("# route_seconds=" + secs + " cores=" + Runtime.getRuntime().availableProcessors() + "\n");
         long proc = processCpuNs();
         if (proc >= 0 && processCpuAtStart >= 0) {
            w.write("# process_cpu_ms=" + (proc - processCpuAtStart) / 1_000_000 + " process_share=" + ((proc - processCpuAtStart) / 1e9 / secs) + "\n");
         }
         w.write("# live_threads_cpu_ms=" + sum / 1_000_000 + "\n");
         w.write("thread\tcpu_ms\tshare_of_wall\n");
         for (String[] r : rows) {
            long ns = Long.parseLong(r[1]);
            w.write(r[0] + "\t" + ns / 1_000_000 + "\t" + String.format(java.util.Locale.ROOT, "%.3f", ns / 1e9 / secs) + "\n");
         }
      } catch (IOException e) {
         Log.warn("harness: could not write thread cpu summary: " + e);
      }
   }

   private static void reject(String reason) {
      rejected = true;
      HarnessFlags.setRejectReason(reason);
      runEndEpochMs = System.currentTimeMillis();
      writeSummary(0f, 0);
      Log.warn("harness: rejected driving run: " + reason);
      Stats.flush();
      state = DONE;
      requestQuit();
   }
}
