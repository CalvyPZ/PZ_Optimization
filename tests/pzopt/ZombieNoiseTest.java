package pzopt;

import java.util.Random;
import zombie.iso.worldgen.zombie.ClosestSelectionType;
import zombie.iso.worldgen.zombie.ZombieVoronoi;

/**
 * ZombieNoise.cellNoise (voronoiFast) must return exactly ZombieVoronoi.evaluateCellNoise for every selection type,
 * the vanilla layer parameters (points 1, scale 12 / 55) and denser layers, over cells at negative and positive
 * coordinates.
 */
public class ZombieNoiseTest {
   public static void main(String[] args) {
      Random r = new Random(42);
      long[] seeds = {0L, 1L, 12345678901L, -77L};
      double[] scales = {12.0, 55.0, 7.5, 1.0, 33.3};
      int[] pointCounts = {1, 2, 3};
      int cells = 0;
      for (ClosestSelectionType type : ClosestSelectionType.values()) {
         for (long seed : seeds) {
            for (double scale : scales) {
               for (int points : pointCounts) {
                  ZombieVoronoi v = new ZombieVoronoi(seed, points, type, scale, 0.1);
                  for (int k = 0; k < 6; k++) {
                     int wx = r.nextInt(120) - 20;
                     int wy = r.nextInt(120) - 20;
                     double[] stock = v.evaluateCellNoise(wx, wy);
                     v.releaseCell(wx, wy);
                     double[] fast = ZombieNoise.cellNoise(seed, points, type, scale, wx, wy);
                     Check.check(fast != null, "a known selection type " + type);
                     for (int i = 0; i < 1024; i++) {
                        Check.check(Double.doubleToRawLongBits(stock[i]) == Double.doubleToRawLongBits(fast[i]),
                              type + " seed=" + seed + " scale=" + scale + " points=" + points + " cell " + wx + "," + wy + " sample " + i
                                    + ": " + stock[i] + " vs " + fast[i]);
                     }
                     cells++;
                  }
               }
            }
         }
      }
      System.out.println("ZombieNoiseTest: " + cells + " cells identical");
   }
}
