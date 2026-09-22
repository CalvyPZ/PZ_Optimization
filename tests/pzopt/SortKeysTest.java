package pzopt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** pzopt.SortKeys: the packed primitive sort gives the order of a stable descending comparator sort, ties in element order. */
public class SortKeysTest {
   public static void main(String[] args) {
      Random rng = new Random(42);
      for (int round = 0; round < 200; round++) {
         int n = 1 + rng.nextInt(300);
         float[] scores = new float[n];
         for (int i = 0; i < n; i++) {
            switch (rng.nextInt(6)) {
               case 0 -> scores[i] = -10000.0F;
               case 1 -> scores[i] = rng.nextInt(5) * 10.0F - 20.0F; // many ties
               case 2 -> scores[i] = -0.0F;
               case 3 -> scores[i] = 0.0F;
               default -> scores[i] = (rng.nextFloat() - 0.5F) * 2000.0F;
            }
         }
         ArrayList<Integer> expected = new ArrayList<>();
         for (int i = 0; i < n; i++) {
            expected.add(i);
         }
         expected.sort((a, b) -> scores[a] < scores[b] ? 1 : scores[a] > scores[b] ? -1 : 0); // stock's comparator, List.sort is stable
         long[] keys = new long[n];
         for (int i = 0; i < n; i++) {
            keys[i] = SortKeys.descending(scores[i], i);
         }
         Arrays.sort(keys);
         for (int i = 0; i < n; i++) {
            int got = SortKeys.index(keys[i]);
            int want = expected.get(i);
            Check.check(got == want || scores[got] == scores[want], "round " + round + " position " + i + ": expected element " + want + " got " + got);
            Check.check(got == want, "round " + round + " position " + i + ": stable tie order, expected " + want + " got " + got);
         }
      }
      System.out.println("SortKeysTest ok");
   }
}
