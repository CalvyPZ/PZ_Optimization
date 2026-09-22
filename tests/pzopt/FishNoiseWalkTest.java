package pzopt;

import gnu.trove.map.hash.TLongIntHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Random;
import zombie.iso.FishSchoolManager;

/**
 * FishSchoolManager.pzoptWalkNoise (worldSoundFast) must write exactly the keys and values of the stock
 * addSoundNoise loop: same disc test, same fish-point roll, same no-fish zones, same chum points. The stock
 * loop is re-stated here over the class's own private helpers (isFishPoint, coordsToHash) so the two walks
 * share every primitive and differ only in the loop structure under test.
 */
public class FishNoiseWalkTest {
   public static void main(String[] args) throws Exception {
      FishSchoolManager m = FishSchoolManager.getInstance();
      Field zonesF = FishSchoolManager.class.getDeclaredField("noFishZones");
      zonesF.setAccessible(true);
      @SuppressWarnings("unchecked")
      ArrayList<int[]> zones = (ArrayList<int[]>) zonesF.get(m);
      // the vanilla Fishing.NoFishZones table (media/lua/shared/Fishing/FishingZones.lua), so no Lua call is made
      int[][] vanilla = {{6351, 5243, 6363, 5258}, {12868, 1705, 12888, 1722}, {13701, 2761, 13717, 2781}, {13221, 3517, 13233, 3534},
            {12756, 1252, 12768, 1270}, {358, 9710, 374, 9719}, {761, 9601, 772, 9616}, {1930, 10626, 1966, 10707}, {12844, 1311, 12858, 1324},
            {993, 9645, 1006, 9653}, {685, 9784, 695, 9800}, {947, 9924, 960, 9933}, {12679, 1169, 12686, 1178}, {13414, 1854, 13424, 1864}};
      zones.clear();
      for (int[] z : vanilla) zones.add(z);
      Field seedF = FishSchoolManager.class.getDeclaredField("seed");
      seedF.setAccessible(true);
      seedF.setInt(m, 4242);
      Field mapF = FishSchoolManager.class.getDeclaredField("noiseFishPointDisabler");
      mapF.setAccessible(true);
      TLongIntHashMap map = (TLongIntHashMap) mapF.get(null);
      Method isFishPoint = FishSchoolManager.class.getDeclaredMethod("isFishPoint", int.class, int.class);
      isFishPoint.setAccessible(true);
      Method hash = FishSchoolManager.class.getDeclaredMethod("coordsToHash", int.class, int.class);
      hash.setAccessible(true);
      Method minutes = FishSchoolManager.class.getDeclaredMethod("getCurrentGameTimeInMinutes");
      minutes.setAccessible(true);
      Method fast = FishSchoolManager.class.getDeclaredMethod("pzoptWalkNoise", int.class, int.class, int.class);
      fast.setAccessible(true);
      Field chumF = FishSchoolManager.class.getDeclaredField("chumPoints");
      chumF.setAccessible(true);
      Object chumPoints = chumF.get(null);
      Method chumClear = chumPoints.getClass().getMethod("clear");
      Method chumHas = chumPoints.getClass().getMethod("containsKey", long.class);

      Random rnd = new Random(7);
      int[][] centres = new int[60][];
      int n = 0;
      for (int[] z : vanilla) { // centres on and around every zone, so the zone pre-selection is exercised
         centres[n++] = new int[] {z[0] + 3, z[1] + 3};
         centres[n++] = new int[] {z[2] + 40, z[3] - 20};
      }
      while (n < centres.length) centres[n++] = new int[] {rnd.nextInt(14000), rnd.nextInt(12000)};
      int cases = 0;
      long hits = 0;
      for (int[] c : centres) {
         for (int radius : new int[] {0, 1, 7, 16, 25, 83, 100}) {
            chumClear.invoke(chumPoints);
            if (rnd.nextBoolean()) { // chum points: one inside the disc, one just outside the box, one far away
               m.addChum(c[0] + radius / 2, c[1] - radius / 3, 5);
               m.addChum(c[0] + radius + 1, c[1], 5);
               m.addChum(c[0] + 5000, c[1] + 5000, 5);
            }
            map.clear();
            int expiry = (Integer) minutes.invoke(m) + 180;
            for (int i = c[0] - radius; i <= c[0] + radius; i++) { // the stock loop
               for (int j = c[1] - radius; j <= c[1] + radius; j++) {
                  float dx = (float) i - (float) c[0], dy = (float) j - (float) c[1];
                  long h = (Long) hash.invoke(m, i, j);
                  if (Math.sqrt(dx * dx + dy * dy) <= radius && ((Boolean) isFishPoint.invoke(m, i, j) || (Boolean) chumHas.invoke(chumPoints, h))) {
                     map.put(h, expiry);
                  }
               }
            }
            TLongIntHashMap expected = new TLongIntHashMap(map);
            map.clear();
            fast.invoke(m, c[0], c[1], radius);
            Check.check(map.size() == expected.size(), "size at " + c[0] + "," + c[1] + " r=" + radius + ": fast " + map.size() + " stock " + expected.size());
            for (long k : expected.keys()) {
               Check.check(map.containsKey(k) && map.get(k) == expected.get(k), "key " + k + " at " + c[0] + "," + c[1] + " r=" + radius);
            }
            hits += expected.size();
            cases++;
         }
      }
      Check.check(hits > 500, "the inputs produced fish/chum hits: " + hits);
      System.out.println("FishNoiseWalkTest: ok (" + cases + " walks, " + hits + " keys compared)");
   }
}
