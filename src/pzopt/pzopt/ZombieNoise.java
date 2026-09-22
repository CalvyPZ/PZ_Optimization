package pzopt;

import java.lang.reflect.Field;
import java.util.Random;
import zombie.SandboxOptions;
import zombie.iso.worldgen.zombie.ClosestSelection;
import zombie.iso.worldgen.zombie.ClosestSelectionType;
import zombie.iso.worldgen.zombie.HashUtil;
import zombie.iso.worldgen.zombie.ZombieVoronoi;

/**
 * voronoiFast: ZombieVoronoi's per-cell noise (the zombie-density voronoi layers applied to every lot header) with the
 * same values, computed once per sector instead of once per sample.
 *
 * Stock evaluates each of a cell's 1024 samples by re-seeding a Random for the 3x3 sectors around it, allocating a
 * Coord per point and sorting the boxed squared distances through a stream to read element 0 and 1; that was 89 % of
 * the eight meta-grid loader threads (1.2 s of wall time of every world load, the loader waits for them). Here the
 * points of every sector the cell touches are generated once with the same seeding and draws, and the smallest and
 * second smallest of the same double expressions are kept while scanning: the sorted list's first two elements (squared
 * distances are never NaN or -0.0, so Double's order equals the numeric order). tests/pzopt/ZombieNoiseTest compares
 * the two bit for bit.
 */
public final class ZombieNoise {
   private static Field fSeed;
   private static Field fPoints;
   private static Field fClosest;
   private static Field fScale;
   private static Field fCutoff;
   private static volatile boolean failed;

   private ZombieNoise() {
   }

   public static boolean enabled() {
      return Config.VORONOI_FAST && Overrides.enabled() && !failed;
   }

   private static synchronized void resolve() throws ReflectiveOperationException {
      if (fSeed != null) {
         return;
      }
      Field seed = ZombieVoronoi.class.getDeclaredField("seed");
      Field points = ZombieVoronoi.class.getDeclaredField("numberPoints");
      Field closest = ZombieVoronoi.class.getDeclaredField("closestPoint");
      Field scale = ZombieVoronoi.class.getDeclaredField("scale");
      Field cutoff = ZombieVoronoi.class.getDeclaredField("cutoff");
      for (Field f : new Field[]{seed, points, closest, scale, cutoff}) {
         f.setAccessible(true);
      }
      fPoints = points;
      fClosest = closest;
      fScale = scale;
      fCutoff = cutoff;
      fSeed = seed;
   }

   /** ZombieVoronoi.evaluateCellCutoff(wx, wy): the sandbox's voronoi switch picks the layer's cutoff or 0. */
   public static double[] cellCutoff(ZombieVoronoi v, int wx, int wy) {
      if (!enabled()) {
         return v.evaluateCellCutoff(wx, wy);
      }
      try {
         resolve();
         double cutoff = SandboxOptions.instance.zombieVoronoiNoise.getValue() ? fCutoff.getDouble(v) : 0.0;
         double[] noise = cellNoise(fSeed.getLong(v), fPoints.getInt(v), (ClosestSelection)fClosest.get(v), fScale.getDouble(v), wx, wy);
         if (noise == null) {
            return v.evaluateCellCutoff(wx, wy);
         }
         for (int i = 0; i < noise.length; i++) {
            noise[i] = noise[i] < cutoff ? 0.0 : 1.0;
         }
         return noise;
      } catch (Throwable t) {
         failed = true;
         Log.warn("voronoiFast off for this session: " + t);
         return v.evaluateCellCutoff(wx, wy);
      }
   }

   /**
    * ZombieVoronoi.evaluateCellNoise(wx, wy) for the given layer parameters; null for a selection type this class does
    * not know (the caller then takes the stock path).
    */
   public static double[] cellNoise(long seed, int numberPoints, ClosestSelection closest, double scale, int wx, int wy) {
      int mode;
      if (closest == ClosestSelectionType.FIRST) {
         mode = 0;
      } else if (closest == ClosestSelectionType.SECOND) {
         mode = 1;
      } else if (closest == ClosestSelectionType.SECOND_MINUS_FIRST) {
         mode = 2;
      } else {
         return null;
      }
      if (mode != 0 && 9 * numberPoints < 2) {
         return null; // stock throws (list index 1 of a one-element list); let it
      }
      // the sectors of every sample's 3x3 neighbourhood: floor(sample) over the cell, one more on each side
      int sx0 = (int)Math.floor((double)(wx * 32) / scale) - 1;
      int sx1 = (int)Math.floor((double)(31 + wx * 32) / scale) + 1;
      int sy0 = (int)Math.floor((double)(wy * 32) / scale) - 1;
      int sy1 = (int)Math.floor((double)(31 + wy * 32) / scale) + 1;
      int nsx = sx1 - sx0 + 1;
      int nsy = sy1 - sy0 + 1;
      double[] px = new double[nsx * nsy * numberPoints];
      double[] py = new double[nsx * nsy * numberPoints];
      Random rng = new Random();
      for (int secX = sx0; secX <= sx1; secX++) {
         for (int secY = sy0; secY <= sy1; secY++) {
            rng.setSeed(HashUtil.hash2D(seed, secX, secY));
            int base = ((secX - sx0) * nsy + (secY - sy0)) * numberPoints;
            for (int i = 0; i < numberPoints; i++) {
               // Coord(rng.nextDouble() + secX, rng.nextDouble() + secY): x drawn first
               px[base + i] = rng.nextDouble() + (double)secX;
               py[base + i] = rng.nextDouble() + (double)secY;
            }
         }
      }
      double[] out = new double[1024];
      for (int x = 0; x < 32; x++) {
         for (int y = 0; y < 32; y++) {
            double fx = (double)(x + wx * 32) / scale;
            double fy = (double)(y + wy * 32) / scale;
            int iX = (int)Math.floor(fx);
            int iY = (int)Math.floor(fy);
            double m0 = Double.POSITIVE_INFINITY;
            double m1 = Double.POSITIVE_INFINITY;
            for (int xOffset = -1; xOffset <= 1; xOffset++) {
               int secX = iX + xOffset;
               for (int yOffset = -1; yOffset <= 1; yOffset++) {
                  int secY = iY + yOffset;
                  int base = ((secX - sx0) * nsy + (secY - sy0)) * numberPoints;
                  for (int i = 0; i < numberPoints; i++) {
                     double cx = px[base + i];
                     double cy = py[base + i];
                     double d = (fx - cx) * (fx - cx) + (fy - cy) * (fy - cy);
                     if (d < m0) {
                        m1 = m0;
                        m0 = d;
                     } else if (d < m1) {
                        m1 = d;
                     }
                  }
               }
            }
            out[x + y * 32] = mode == 0 ? m0 : mode == 1 ? m1 : m1 - m0;
         }
      }
      return out;
   }
}
