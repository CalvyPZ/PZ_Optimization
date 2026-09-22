package pzopt;

import java.util.Random;

/** PngFilters.paeth4 against the stock PNGDecoder loop: byte-identical on random lines, runs of equal bytes and edges. */
public class PngFiltersTest {
   static void stock(byte[] cur, byte[] prev, int bpp) {
      int i;
      for (i = 1; i <= bpp; i++) {
         cur[i] += prev[i];
      }
      for (int n = cur.length; i < n; i++) {
         int a = cur[i - bpp] & 255;
         int b = prev[i] & 255;
         int c = prev[i - bpp] & 255;
         int p = a + b - c;
         int pa = Math.abs(p - a);
         int pb = Math.abs(p - b);
         int pc = Math.abs(p - c);
         if (pa <= pb && pa <= pc) {
            c = a;
         } else if (pb <= pc) {
            c = b;
         }
         cur[i] += (byte)c;
      }
   }

   public static void main(String[] args) {
      Random r = new Random(42);
      int lines = 0;
      for (int len : new int[] {5, 9, 13, 17, 4097, 8193, 6, 7, 8}) {
         byte[] prevA = new byte[len];
         byte[] prevB = new byte[len];
         for (int y = 0; y < 200; y++) {
            byte[] a = new byte[len];
            int mode = y % 4; // random, all equal, sparse, full-range extremes
            for (int i = 1; i < len; i++) {
               a[i] = (byte)(mode == 0 ? r.nextInt(256) : mode == 1 ? 7 : mode == 2 ? (r.nextInt(10) == 0 ? r.nextInt(256) : 0) : (r.nextBoolean() ? 0 : 255));
            }
            byte[] b = a.clone();
            stock(a, prevA, 4);
            PngFilters.paeth4(b, prevB);
            for (int i = 1; i < len; i++) {
               Check.check(a[i] == b[i], "len " + len + " line " + y + " byte " + i + ": stock " + a[i] + " fast " + b[i]);
            }
            prevA = a;
            prevB = b;
            lines++;
         }
      }
      System.out.println("PngFiltersTest: paeth4 identical to the stock loop on " + lines + " lines");
   }
}
