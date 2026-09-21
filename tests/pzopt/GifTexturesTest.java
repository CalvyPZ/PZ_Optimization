package pzopt;

import java.io.ByteArrayInputStream;

/** GIF decoding for the options-tab previews: compositing, delays, thinning, scaling and the frame clock. */
public class GifTexturesTest {
   public static void main(String[] args) throws Exception {
      int[] colours = { 0xFF0000, 0x00FF00, 0x0000FF };
      byte[] gif = GifTextures.encode(64, 32, colours, 20);
      GifTextures.Decoded d = GifTextures.decode(new ByteArrayInputStream(gif), 512, 48);
      Check.check(d.width == 64 && d.height == 32, "size kept below the width cap: " + d.width + "x" + d.height);
      Check.check(d.rgba.length == 3 && d.delaysMs.length == 3, "three frames: " + d.rgba.length);
      Check.check(d.delaysMs[0] == 200 && d.totalMs() == 600, "delays in ms: " + d.delaysMs[0] + " total " + d.totalMs());
      for (int i = 0; i < 3; i++) {
         byte[] f = d.rgba[i];
         int p = 4 * (10 * 64 + 20); // pixel (20, 10)
         int r = f[p] & 0xFF, g = f[p + 1] & 0xFF, b = f[p + 2] & 0xFF, a = f[p + 3] & 0xFF;
         int rgb = (r << 16) | (g << 8) | b;
         Check.check(rgb == colours[i] && a == 255, "frame " + i + " colour " + Integer.toHexString(rgb) + " alpha " + a);
      }
      Check.check(d.rgba[0].length == 64 * 32 * 4, "rgba bytes per frame");

      // frame clock: loops over the total delay
      Check.check(GifTextures.frameAt(d, 0) == 0 && GifTextures.frameAt(d, 199) == 0, "first frame for its whole delay");
      Check.check(GifTextures.frameAt(d, 200) == 1 && GifTextures.frameAt(d, 599) == 2, "later frames by delay");
      Check.check(GifTextures.frameAt(d, 600) == 0 && GifTextures.frameAt(d, 1234) == 0, "wraps: 1234 % 600 = 34 -> frame 0");
      Check.check(GifTextures.frameAt(d, -1) == 2, "negative clock wraps too");

      // thinning: 12 frames into at most 4, delays folded so the clip keeps its length
      int[] many = new int[12];
      for (int i = 0; i < 12; i++) {
         many[i] = (i * 20) << 16;
      }
      GifTextures.Decoded t = GifTextures.decode(new ByteArrayInputStream(GifTextures.encode(16, 16, many, 5)), 512, 4);
      Check.check(t.rgba.length == 4, "thinned to 4 frames: " + t.rgba.length);
      Check.check(t.totalMs() == 12 * 50, "total delay kept: " + t.totalMs());
      Check.check((t.rgba[1][0] & 0xFF) == 60, "second kept frame is source frame 3: red " + (t.rgba[1][0] & 0xFF));

      // scaling: a 1024-wide GIF comes out 512 wide with its aspect
      GifTextures.Decoded s = GifTextures.decode(new ByteArrayInputStream(GifTextures.encode(1024, 432, new int[] { 0x808080 }, 10)), 512, 48);
      Check.check(s.width == 512 && s.height == 216, "scaled to 512x216: " + s.width + "x" + s.height);
      Check.check(s.rgba[0].length == 512 * 216 * 4 && (s.rgba[0][0] & 0xFF) == 0x80, "scaled pixels");

      // a path that is not there
      Check.check(!GifTextures.resolve("media/ui/pzopt/compare/no-such.gif").isFile(), "missing file resolves to a non-file");
      System.out.println("GifTexturesTest OK");
   }
}
