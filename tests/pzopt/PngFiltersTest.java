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
      for (boolean withAlpha : new boolean[] {true, false}) {
         for (int colours : new int[] {256, 17, 2}) {
            byte[] pal = new byte[colours * 3];
            r.nextBytes(pal);
            byte[] palA = null;
            if (withAlpha) {
               palA = new byte[colours]; // the decoder sizes it to the palette (tRNS entries, the rest opaque)
               r.nextBytes(palA);
            }
            int[] table = PngFilters.rgbaTable(pal, palA);
            for (int len : new int[] {2, 5, 1025}) {
               byte[] line = new byte[len];
               for (int i = 1; i < len; i++) {
                  line[i] = (byte)r.nextInt(colours);
               }
               java.nio.ByteBuffer want = java.nio.ByteBuffer.allocateDirect(4 * len + 8);
               java.nio.ByteBuffer got = java.nio.ByteBuffer.allocateDirect(4 * len + 8);
               want.position(3);
               got.position(3);
               for (int i = 1; i < len; i++) { // the stock copy, one byte at a time
                  int idx = line[i] & 255;
                  byte alpha = palA == null ? (byte)-1 : palA[idx];
                  want.put(pal[idx * 3]).put(pal[idx * 3 + 1]).put(pal[idx * 3 + 2]).put(alpha);
               }
               PngFilters.paletteToRgba(got, line, table, new int[len]);
               Check.check(want.position() == got.position(), "position after the line: " + want.position() + " vs " + got.position());
               Check.check(got.order() == java.nio.ByteOrder.BIG_ENDIAN, "buffer byte order restored");
               for (int i = 0; i < want.capacity(); i++) {
                  Check.check(want.get(i) == got.get(i), "palette colours " + colours + " alpha " + withAlpha + " len " + len + " byte " + i);
               }
            }
         }
      }
      System.out.println("PngFiltersTest: paletteToRgba identical to the stock copy");
      int stride = 4 * 300;
      java.nio.ByteBuffer page = java.nio.ByteBuffer.allocateDirect(stride * 520);
      for (int i = 0; i < page.capacity(); i++) {
         page.put(i, (byte)(r.nextInt(3) == 0 ? 0 : r.nextInt(256)));
      }
      for (int t = 0; t < 4; t++) {
         int left = t * 40, top = t * 60, w = 128, h = 256;
         if (t == 3) { // an all-transparent tile
            for (int y = 0; y < h; y++) {
               for (int x = 0; x < w; x++) {
                  page.put((top + y) * stride + (left + x) * 4 + 3, (byte)0);
               }
            }
         }
         float[] want = new float[w * h];
         boolean wantEmpty = true;
         for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
               int ps = (top + y) * stride + (left + x) * 4;
               int a = page.get(ps + 3) & 255;
               int b = page.get(ps + 2) & 255;
               want[x + y * w] = a == 0 ? -1.0F : b / 255.0F;
               if (wantEmpty && a != 0) {
                  wantEmpty = false;
               }
            }
         }
         float[] got = new float[w * h];
         boolean gotEmpty = PngFilters.depthTile(got, page, stride, left, top, w, h);
         Check.check(gotEmpty == wantEmpty, "tile " + t + " empty " + gotEmpty + " vs " + wantEmpty);
         Check.check(java.util.Arrays.equals(want, got), "tile " + t + " depth values");
      }
      System.out.println("PngFiltersTest: depthTile identical to the stock loop");
   }
}
