package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import zombie.ZomboidFileSystem;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;

/**
 * World-state capture for the parity check: right after a chunk's
 * loadInWorldStreamerThread() returns, every square property that pass writes
 * is serialised to pzopt-parity.out, on whichever thread ran the pass and
 * before the game thread sees the chunk. Since the pass only reads its own
 * chunk (the ChunkGetter clips to it) plus static map data, the capture is a
 * pure function of the chunk data on disk, so two runs from the same save
 * must produce identical captures regardless of pool width or scheduling.
 *
 * Only the first load of each chunk coordinate is captured: later reloads
 * follow a save with simulation changes in it.
 *
 * Line format, one per square:
 *   wx wy x y z | flags,... | key=value,... | collide path vision | roof surface | nav bits | room | zone
 * where nav bits are the 8 IsoDirections with a neighbour pointer set.
 */
public final class Parity {
   public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("pzopt.parity", readFlag()));
   private static final Set<Long> seen = new HashSet<>();
   private static final Object lock = new Object();
   private static final IsoDirections[] DIRS = IsoDirections.values();

   private Parity() {
   }

   private static String readFlag() {
      // parity is requested by the harness flag file (mode=parity), never by pzopt.properties
      return String.valueOf("parity".equals(HarnessFlags.get("mode")));
   }

   public static void capture(IsoChunk chunk) {
      if (!ENABLED) {
         return;
      }
      long key = ((long)chunk.wx << 32) | (chunk.wy & 0xffffffffL);
      synchronized (lock) {
         if (!seen.add(key)) {
            return;
         }
      }
      List<String> lines = new ArrayList<>();
      for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
         for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
               IsoGridSquare sq = chunk.getGridSquare(x, y, z);
               if (sq == null) {
                  continue;
               }
               lines.add(describe(chunk, sq));
            }
         }
      }
      synchronized (lock) {
         File f = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-parity.out");
         try (BufferedWriter w = new BufferedWriter(new FileWriter(f, true))) {
            w.write("# chunk " + chunk.wx + " " + chunk.wy + " levels " + chunk.getMinLevel() + ".." + chunk.getMaxLevel() + " squares " + lines.size() + "\n");
            for (String l : lines) {
               w.write(l);
               w.write('\n');
            }
         } catch (IOException e) {
            Log.warn("parity: could not write capture: " + e);
         }
      }
   }

   static String describe(IsoChunk chunk, IsoGridSquare sq) {
      StringBuilder sb = new StringBuilder(160);
      sb.append(chunk.wx).append(' ').append(chunk.wy).append(' ').append(sq.x).append(' ').append(sq.y).append(' ').append(sq.z).append(" | ");
      PropertyContainer pc = sq.getProperties();
      List<String> flags = new ArrayList<>();
      for (IsoFlagType t : pc.getFlagsList()) {
         flags.add(t.name());
      }
      flags.sort(null);
      sb.append(String.join(",", flags)).append(" | ");
      short[] keys = pc.keys();
      Arrays.sort(keys);
      for (int i = 0; i < keys.length; i++) {
         if (i > 0) {
            sb.append(',');
         }
         sb.append(keys[i]).append('=').append(pc.get(keys[i]));
      }
      sb.append(" | ").append(sq.collideMatrix).append(' ').append(sq.pathMatrix).append(' ').append(sq.visionMatrix);
      sb.append(" | ").append(sq.haveRoof ? 1 : 0).append(' ').append(pc.getSurface()).append(pc.isSurfaceOffset() ? "o" : "").append(' ').append(sq.solidFloorCached ? 1 : 0);
      int nav = 0;
      for (int i = 0; i < DIRS.length; i++) {
         IsoGridSquare n = sq.getAdjacentSquare(DIRS[i]);
         if (n != null) {
            nav |= 1 << i;
         }
      }
      sb.append(" | ").append(nav);
      sb.append(" | ").append(sq.getRoomID());
      sb.append(" | ").append(sq.zone == null ? "-" : sq.zone.name + "@" + sq.zone.x + "," + sq.zone.y);
      return sb.toString();
   }
}
