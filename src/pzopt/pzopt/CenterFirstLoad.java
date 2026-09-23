package pzopt;

import java.util.ArrayList;
import java.util.HashSet;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * centerFirstLoad (2026-09-22, the maintainer's "the world builds and loads itself from the center outwards"): the initial
 * chunk map (19 x 19 chunks here) loads nearest-first and the loader enters the world as soon as the chunks within
 * RADIUS of the player's chunk are loaded, instead of waiting for all of them. The rest keep streaming in and are handed
 * to the chunk map by IsoChunkMap.update exactly as chunks are while walking; pzopt.NoLoadingScreen's reveal draws them
 * from the center outwards as they bake.
 *
 * Stock waited in IsoWorld.init (WorldStreamer.isBusy loop) and again in GameLoadingState.update for the whole map.
 * WorldStreamer's job order is by distance to the players, and there is no player during the load, so the initial
 * requests were served in no particular order; center() gives the comparator the load's center meanwhile.
 */
public final class CenterFirstLoad {
   /** chunks around the player's chunk that must be loaded before the world is entered (7 x 7) */
   static final int RADIUS = 3;
   private static volatile boolean haveCenter;
   private static volatile int cx;
   private static volatile int cy;
   private static volatile boolean enteredEarly;

   private CenterFirstLoad() {
   }

   public static boolean active() {
      return Config.CENTER_FIRST_LOAD && Overrides.enabled() && !GameClient.client && !GameServer.server;
   }

   /** From IsoWorld.init before the initial chunk requests: the load's center chunk. */
   public static void setCenter(int wx, int wy) {
      cx = wx;
      cy = wy;
      haveCenter = active();
      enteredEarly = false;
   }

   /** WorldStreamer's comparator while no player exists: the load's center (tile coordinates), or null. */
   public static float[] center() {
      return haveCenter ? new float[]{cx * 8 + 4.0F, cy * 8 + 4.0F} : null;
   }

   /** From IsoWorld.init's streamer wait: every valid chunk within RADIUS of the center has been loaded. */
   public static boolean nearLoaded() {
      if (!haveCenter) {
         return false;
      }
      ArrayList<IsoChunk> loaded = new ArrayList<>();
      IsoChunk.loadGridSquare.copyTo(loaded);
      HashSet<Long> have = new HashSet<>();
      for (IsoChunk c : loaded) {
         have.add(((long)c.wx << 32) ^ (c.wy & 0xFFFFFFFFL));
      }
      for (int x = cx - RADIUS; x <= cx + RADIUS; x++) {
         for (int y = cy - RADIUS; y <= cy + RADIUS; y++) {
            if (IsoWorld.instance.getMetaGrid().isValidChunk(x, y) && !have.contains(((long)x << 32) ^ (y & 0xFFFFFFFFL))) {
               return false;
            }
         }
      }
      enteredEarly = true;
      return true;
   }

   /**
    * IsoChunkMap.processAllLoadGridSquare at world entry: this chunk is within the entry radius (centerFirstEntryRadius,
    * at most RADIUS) of the load's center, so it is handed to the chunk map before the first world frame; the others
    * follow a few per frame through IsoChunkMap.update.
    */
   public static boolean nearCenter(int wx, int wy) {
      int r = Math.max(0, Math.min(RADIUS, Config.CENTER_FIRST_ENTRY_RADIUS));
      return Math.abs(wx - cx) <= r && Math.abs(wy - cy) <= r;
   }

   /** GameLoadingState.update: the loader entered early, so the streamer's remaining work does not hold the world back. */
   public static boolean enteredEarly() {
      return enteredEarly;
   }

   private static int[] order;
   private static int orderWidth = -1;
   private static boolean orderCenter;

   /**
    * LightingJNI.update's chunk walk order over the chunk map (index = cy * width + cx): nearest the centre first with
    * centerFirstLoad (ties in stock order), stock row order otherwise.
    */
   public static int[] gridOrder(int width) {
      boolean center = Config.CENTER_FIRST_LOAD && Overrides.enabled();
      if (order == null || orderWidth != width || orderCenter != center) {
         Integer[] idx = new Integer[width * width];
         for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
         }
         if (center) {
            int c = width / 2;
            java.util.Arrays.sort(idx, (a, b) -> {
               int ax = a % width - c, ay = a / width - c, bx = b % width - c, by = b / width - c;
               int d = Integer.compare(ax * ax + ay * ay, bx * bx + by * by);
               return d != 0 ? d : Integer.compare(a, b);
            });
         }
         int[] o = new int[idx.length];
         for (int i = 0; i < o.length; i++) {
            o[i] = idx[i];
         }
         order = o;
         orderWidth = width;
         orderCenter = center;
      }
      return order;
   }

   /** Once the world is up, the comparator goes back to the players. */
   public static void onWorldEntered() {
      haveCenter = false;
      enteredEarly = false; // IngameState.enter's processAllLoadGridSquare (the only user after the load) has run
   }
}
