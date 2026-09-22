package pzopt;

/**
 * pngPaethFast (2026-09-23): the PNG Paeth un-filter for 4-byte pixels, the texture-pack pages' format. The stock loop in
 * zombie.core.textures.PNGDecoder was 69 % of a page decode (JFR on a 1024 x 1014 Tiles2x page: 14 ms of 20); this one
 * keeps the left and upper-left neighbours of the four channels in locals and runs the channels interleaved, 40 % faster
 * with byte-identical output (PngFiltersTest). Pack pages are decoded on every boot and Continue, so the whole texture
 * load gets faster, most on slow CPUs.
 * Also the palette to RGBA copy (every tile depth map): stock put four bytes per pixel one by one (52 % of a depth-map
 * decode); paletteToRgba looks the pixel up as one int in a 256-entry table and writes a scan line in one bulk put.
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

   /** The 256-entry little-endian RGBA table for a palette (3 bytes a colour) and its optional alpha entries. */
   public static int[] rgbaTable(byte[] palette, byte[] paletteA) {
      int[] t = new int[256];
      int colours = palette.length / 3;
      for (int i = 0; i < 256 && i < colours; i++) {
         int a = paletteA == null ? 0xFF : i < paletteA.length ? paletteA[i] & 0xFF : 0xFF;
         t[i] = (palette[i * 3] & 0xFF) | (palette[i * 3 + 1] & 0xFF) << 8 | (palette[i * 3 + 2] & 0xFF) << 16 | a << 24;
      }
      return t;
   }

   /**
    * One palette scan line (index 0 is the filter byte) as RGBA at the buffer's position, which then advances by
    * 4 * (line length - 1) bytes as the stock per-byte puts did. scratch holds at least that many ints.
    */
   public static void paletteToRgba(java.nio.ByteBuffer buffer, byte[] line, int[] table, int[] scratch) {
      int n = line.length - 1;
      for (int i = 0; i < n; i++) {
         scratch[i] = table[line[i + 1] & 0xFF];
      }
      int pos = buffer.position();
      java.nio.ByteOrder order = buffer.order();
      buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(scratch, 0, n);
      buffer.order(order);
      buffer.position(pos + 4 * n);
   }

   private static final ThreadLocal<byte[]> DEPTH_ROW = ThreadLocal.withInitial(() -> new byte[4 * 256]);

   /**
    * depthMapFast: one tile of a tile depth map (TileDepthTexture.load) from the decoded RGBA page: each pixel is -1 where
    * alpha is 0, else blue / 255, exactly as the stock loop; the tile's rows are read with one bulk get each instead of two
    * bounds-checked gets per pixel. Returns whether every pixel was transparent.
    */
   public static boolean depthTile(float[] pixels, java.nio.ByteBuffer bb, int stride, int left, int top, int width, int height) {
      byte[] row = DEPTH_ROW.get();
      if (row.length < 4 * width) {
         row = new byte[4 * width];
         DEPTH_ROW.set(row);
      }
      boolean empty = true;
      for (int y = 0; y < height; y++) {
         bb.get((top + y) * stride + left * 4, row, 0, 4 * width);
         int out = y * width;
         for (int x = 0; x < width; x++) {
            int a = row[4 * x + 3] & 255;
            int b = row[4 * x + 2] & 255;
            pixels[out + x] = a == 0 ? -1.0F : b / 255.0F;
            empty &= a == 0;
         }
      }
      return empty;
   }
}
