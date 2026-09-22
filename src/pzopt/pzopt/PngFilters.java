package pzopt;

/**
 * pngPaethFast (2026-09-23): the PNG Paeth un-filter for 4-byte pixels, the texture-pack pages' format. The stock loop in
 * zombie.core.textures.PNGDecoder was 69 % of a page decode (JFR on a 1024 x 1014 Tiles2x page: 14 ms of 20); this one
 * keeps the left and upper-left neighbours of the four channels in locals and runs the channels interleaved, 40 % faster
 * with byte-identical output (PngFiltersTest). Pack pages are decoded on every boot and Continue, so the whole texture
 * load gets faster, most on slow CPUs.
 */
public final class PngFilters {
   private PngFilters() {
   }

   /** Paeth un-filter of one scan line in place; index 0 is the filter byte, pixels are 4 bytes. */
   public static void paeth4(byte[] cur, byte[] prev) {
      int n = cur.length;
      if (n < 5) {
         for (int i = 1; i < n; i++) {
            cur[i] += prev[i];
         }
         return;
      }
      int a0 = (cur[1] += prev[1]) & 0xFF;
      int a1 = (cur[2] += prev[2]) & 0xFF;
      int a2 = (cur[3] += prev[3]) & 0xFF;
      int a3 = (cur[4] += prev[4]) & 0xFF;
      int c0 = prev[1] & 0xFF;
      int c1 = prev[2] & 0xFF;
      int c2 = prev[3] & 0xFF;
      int c3 = prev[4] & 0xFF;
      int i = 5;
      for (; i + 3 < n; i += 4) {
         int b0 = prev[i] & 0xFF;
         int b1 = prev[i + 1] & 0xFF;
         int b2 = prev[i + 2] & 0xFF;
         int b3 = prev[i + 3] & 0xFF;
         a0 = (cur[i] + predict(a0, b0, c0)) & 0xFF;
         a1 = (cur[i + 1] + predict(a1, b1, c1)) & 0xFF;
         a2 = (cur[i + 2] + predict(a2, b2, c2)) & 0xFF;
         a3 = (cur[i + 3] + predict(a3, b3, c3)) & 0xFF;
         cur[i] = (byte)a0;
         cur[i + 1] = (byte)a1;
         cur[i + 2] = (byte)a2;
         cur[i + 3] = (byte)a3;
         c0 = b0;
         c1 = b1;
         c2 = b2;
         c3 = b3;
      }
      for (; i < n; i++) { // a line length that is not a whole number of pixels (never for RGBA; kept exact anyway)
         cur[i] += (byte)predict(cur[i - 4] & 0xFF, prev[i] & 0xFF, prev[i - 4] & 0xFF);
      }
   }

   /** The Paeth predictor (PNG spec): the neighbour nearest a + b - c, ties to a, then b. */
   static int predict(int a, int b, int c) {
      int pa = Math.abs(b - c);
      int pb = Math.abs(a - c);
      int pc = Math.abs(a + b - c - c);
      return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
   }
}
