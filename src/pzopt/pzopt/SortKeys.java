package pzopt;

/**
 * Sort keys for the per-frame primitive sorts (2026-09-22): a float score and an element index packed into one long so
 * that {@code Arrays.sort(long[])} yields the order of a stable comparator sort — descending score, ties in element
 * order — without a comparator call per comparison. {@code IsoWorld.sceneCullZombies} uses it (zombieCullSortFast).
 */
public final class SortKeys {
   private SortKeys() {
   }

   /** Key of element {@code index} with {@code score}: sorts ascending as (score descending, index ascending). No NaN. */
   public static long descending(float score, int index) {
      int bits = Float.floatToIntBits(score + 0.0F); // -0 becomes +0: a comparator holds them equal
      int sortable = bits >= 0 ? bits : bits ^ 0x7FFFFFFF; // ascending int order = ascending float order
      long descending = -(long)sortable; // a larger score gives a smaller (signed) high word; -0 never reaches here, so no overflow
      return descending << 32 | (long)index & 0xFFFFFFFFL;
   }

   /** The element index a key was made with. */
   public static int index(long key) {
      return (int)key;
   }
}
