package pzopt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.core.Core;

/**
 * Hands-off benchmark driver, run on the game thread once per frame from
 * Stats.frameTick. Reads Zomboid/Lua/pzopt-harness.txt (written by
 * harness/run.sh, also read by the Lua mod that auto-continues the save).
 *
 * Keys it uses:
 *   mode     bench|parity  drive the route below and quit when done (parity also captures chunk state); anything else: inactive
 *   route    legs      e.g. "E:600,S:600,W:600,N:600" — direction and length in tiles
 *   speed    tiles/s   default 18 (about car speed on a road)
 *   settle   seconds   wait after the world is up before moving (default 15)
 *
 * The player is teleported along the route in sub-tile steps every frame, in
 * god mode and invisible so zombies cannot interrupt the run. Teleporting
 * rather than driving keeps the route identical across runs, which is what
 * makes two runs comparable; chunk streaming reacts to position only.
 */
public final class Harness {
   /** True when the flag file asks for a bench run; decided once at class init so the frame hook stays a boolean test. */
   public static final boolean REQUESTED = "bench".equals(HarnessFlags.get("mode")) || "parity".equals(HarnessFlags.get("mode"));
   private static final int IDLE = 0, WAIT_WORLD = 1, SETTLE = 2, RUN = 3, DONE = 4;
   private static int state = IDLE;
   private static boolean started;
   private static final String DEFAULT_ROUTE = "E:400,S:500,W:400,N:500";
   private static long stateSinceNs;
   private static long lastFrameNs;
   private static float speed = 18f;
   private static float settle = 15f;
   private static final List<float[]> legs = new ArrayList<>(); // {dx, dy, length}
   private static int leg = 0;
   private static float legDone = 0f;
   private static float x, y;
   private static float startX, startY;
   private static int chunksAtStart;
   private static long runStartNs;
   private static long runStartEpochMs, runEndEpochMs; // wall clock, to align external (MangoHud) logs

   private Harness() {
   }

   static boolean active() {
      return state != IDLE && state != DONE;
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
         settle = Float.parseFloat(HarnessFlags.get("settle", "15"));
         parseRoute(HarnessFlags.get("route", DEFAULT_ROUTE));
         Log.info("harness: " + HarnessFlags.get("mode") + " mode, route=" + HarnessFlags.get("route", DEFAULT_ROUTE) + " speed=" + speed + " tiles/s settle=" + settle + "s");
         state = WAIT_WORLD;
         stateSinceNs = nowNs;
      }
      float dt = lastFrameNs == 0L ? 0f : (nowNs - lastFrameNs) / 1e9f;
      lastFrameNs = nowNs;
      IsoPlayer p = IsoPlayer.getInstance();
      switch (state) {
         case WAIT_WORLD -> {
            if (p != null && p.getCurrentSquare() != null) {
               p.setGodMod(true, true);
               p.setInvisible(true, true);
               p.ensureNotInVehicle();
               startX = x = p.getX();
               startY = y = p.getY();
               Log.info("harness: world ready, player at " + (int)x + "," + (int)y + "," + (int)p.getZ() + "; settling " + settle + "s");
               state = SETTLE;
               stateSinceNs = nowNs;
            }
         }
         case SETTLE -> {
            if ((nowNs - stateSinceNs) / 1e9f >= settle) {
               Log.info("harness: route start");
               chunksAtStart = Stats.chunkCount();
               runStartNs = nowNs;
               runStartEpochMs = System.currentTimeMillis();
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
            if (leg >= legs.size()) {
               float secs = (nowNs - runStartNs) / 1e9f;
               int chunks = Stats.chunkCount() - chunksAtStart;
               Stats.mark("route-end");
               runEndEpochMs = System.currentTimeMillis();
               Log.info("harness: route done in " + secs + "s, " + chunks + " chunks loaded (" + chunks / secs + "/s); quitting");
               writeSummary(secs, chunks);
               Stats.flush();
               state = DONE;
               Core.getInstance().quit(); // to the main menu (saves); the Lua mod then quits the process
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
         w.write("route=" + HarnessFlags.get("route", DEFAULT_ROUTE) + "\nspeed=" + speed + "\nstart=" + (int)startX + "," + (int)startY
               + "\nroute_start_epoch_ms=" + runStartEpochMs + "\nroute_end_epoch_ms=" + runEndEpochMs
               + "\nroute_seconds=" + secs + "\nchunks_loaded=" + chunks + "\nchunks_per_second=" + chunks / secs
               + "\nsettings=" + Config.describe() + "\n");
      } catch (IOException e) {
         Log.warn("harness: could not write summary: " + e);
      }
   }
}
