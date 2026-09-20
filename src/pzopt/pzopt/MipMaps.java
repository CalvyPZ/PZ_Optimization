package pzopt;

import java.nio.ByteBuffer;

/**
 * Mipmap generation for ImageData on byte[] rows instead of per-byte absolute
 * reads and writes of the direct (malloc'd) ByteBuffers.
 *
 * Same results as the stock ImageData.scaleMipLevelMaxAlpha,
 * scaleMipLevelAverage and performPreMultipliedAlpha(MipMapLevel), pixel for
 * pixel (tests/pzopt/MipMapsTest checks that against the game jar's class).
 * Written for GitHub issue #2 (a SIGSEGV in the C2-compiled
 * scaleMipLevelMaxAlpha on a file-pool thread of the issue #2 laptop) before
 * the crash log could be read. The log shows the fault on a plain stack spill
 * reload (mov r14d,[rsp+0x88]) at a 32-bit-truncated stack address, with two
 * other truncated-address faults on that machine the same evening, so the
 * cause is the machine, not this code. This class stays as a harmless
 * simplification: each parent row pair is read once with a bulk get, the sub
 * row is built in a plain byte[] with bounds-checked array indexing, and
 * written back with one bulk put, so the stock per-byte Unsafe accesses on the
 * direct buffers are gone from the hot loop. Same speed once warmed
 * (2048x2048 to level 1: stock 14.3 ms, this 13.5 ms).
 *
 * Config key mipmapArrays (default true); ImageData falls back to the stock
 * loops when it is off or when the worldMipmapColors debug option is on.
 */
public final class MipMaps {
   private MipMaps() {
   }

   private static final class Scratch {
      byte[] row0 = new byte[0];
      byte[] row1 = new byte[0];
      byte[] out = new byte[0];

      byte[] row0(int n) {
         if (row0.length < n) {
            row0 = new byte[n];
         }
         return row0;
      }

      byte[] row1(int n) {
         if (row1.length < n) {
            row1 = new byte[n];
         }
         return row1;
      }

      byte[] out(int n) {
         if (out.length < n) {
            out = new byte[n];
         }
         return out;
      }
   }

   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

   public static boolean enabled() {
      return Overrides.enabled() && Config.MIPMAP_ARRAYS;
   }

   /**
    * Levels 0 and 1: a sub pixel is the mean of the 2x2 parent block's opaque
    * pixels (alpha > 0); the top-left one always counts when
    * preserveTransparentColor is set. Fully transparent blocks stay zero.
    */
   public static void scaleMaxAlpha(ByteBuffer parent, int pw, int ph, ByteBuffer sub, int sw, int sh, boolean preserveTransparentColor) {
      Scratch s = SCRATCH.get();
      int pStride = pw * 4;
      int sStride = sw * 4;
      byte[] r0 = s.row0(pStride);
      byte[] r1 = s.row1(pStride);
      byte[] out = s.out(sStride);
      for (int y = 0; y < sh; y++) {
         int py0 = clamp(y * 2, ph - 1);
         int py1 = y * 2 + 1;
         boolean hasRow1 = py1 < ph;
         parent.get(py0 * pStride, r0, 0, pStride);
         if (hasRow1) {
            parent.get(py1 * pStride, r1, 0, pStride);
         }
         for (int x = 0; x < sw; x++) {
            int px0 = clamp(x * 2, pw - 1) * 4;
            int px1 = x * 2 + 1;
            boolean hasCol1 = px1 < pw;
            px1 *= 4;
            int r, g, b, a, n;
            int a0 = r0[px0 + 3] & 0xFF;
            if (preserveTransparentColor || a0 > 0) {
               r = r0[px0] & 0xFF;
               g = r0[px0 + 1] & 0xFF;
               b = r0[px0 + 2] & 0xFF;
               a = a0;
               n = 1;
            } else {
               r = g = b = a = 0;
               n = 0;
            }
            if (hasCol1) {
               int aa = r0[px1 + 3] & 0xFF;
               if (aa > 0) {
                  r += r0[px1] & 0xFF;
                  g += r0[px1 + 1] & 0xFF;
                  b += r0[px1 + 2] & 0xFF;
                  a += aa;
                  n++;
               }
            }
            if (hasRow1) {
               int aa = r1[px0 + 3] & 0xFF;
               if (aa > 0) {
                  r += r1[px0] & 0xFF;
                  g += r1[px0 + 1] & 0xFF;
                  b += r1[px0 + 2] & 0xFF;
                  a += aa;
                  n++;
               }
               if (hasCol1) {
                  aa = r1[px1 + 3] & 0xFF;
                  if (aa > 0) {
                     r += r1[px1] & 0xFF;
                     g += r1[px1 + 1] & 0xFF;
                     b += r1[px1 + 2] & 0xFF;
                     a += aa;
                     n++;
                  }
               }
            }
            if (n > 0) {
               r /= n;
               g /= n;
               b /= n;
               a /= n;
            }
            int o = x * 4;
            out[o] = (byte) r;
            out[o + 1] = (byte) g;
            out[o + 2] = (byte) b;
            out[o + 3] = (byte) a;
         }
         sub.put(y * sStride, out, 0, sStride);
      }
   }

   /**
    * Levels 2 and up: a sub pixel is the mean of every parent pixel of its 2x2
    * block that lies inside the image, alpha included.
    */
   public static void scaleAverage(ByteBuffer parent, int pw, int ph, ByteBuffer sub, int sw, int sh) {
      Scratch s = SCRATCH.get();
      int pStride = pw * 4;
      int sStride = sw * 4;
      byte[] r0 = s.row0(pStride);
      byte[] r1 = s.row1(pStride);
      byte[] out = s.out(sStride);
      for (int y = 0; y < sh; y++) {
         int py0 = clamp(y * 2, ph - 1);
         int py1 = y * 2 + 1;
         boolean hasRow1 = py1 < ph;
         parent.get(py0 * pStride, r0, 0, pStride);
         if (hasRow1) {
            parent.get(py1 * pStride, r1, 0, pStride);
         }
         for (int x = 0; x < sw; x++) {
            int px0 = clamp(x * 2, pw - 1) * 4;
            int px1 = x * 2 + 1;
            boolean hasCol1 = px1 < pw;
            px1 *= 4;
            int r = r0[px0] & 0xFF;
            int g = r0[px0 + 1] & 0xFF;
            int b = r0[px0 + 2] & 0xFF;
            int a = r0[px0 + 3] & 0xFF;
            int n = 1;
            if (hasCol1) {
               r += r0[px1] & 0xFF;
               g += r0[px1 + 1] & 0xFF;
               b += r0[px1 + 2] & 0xFF;
               a += r0[px1 + 3] & 0xFF;
               n++;
            }
            if (hasRow1) {
               r += r1[px0] & 0xFF;
               g += r1[px0 + 1] & 0xFF;
               b += r1[px0 + 2] & 0xFF;
               a += r1[px0 + 3] & 0xFF;
               n++;
               if (hasCol1) {
                  r += r1[px1] & 0xFF;
                  g += r1[px1 + 1] & 0xFF;
                  b += r1[px1 + 2] & 0xFF;
                  a += r1[px1 + 3] & 0xFF;
                  n++;
               }
            }
            int o = x * 4;
            out[o] = (byte) (r / n);
            out[o + 1] = (byte) (g / n);
            out[o + 2] = (byte) (b / n);
            out[o + 3] = (byte) (a / n);
         }
         sub.put(y * sStride, out, 0, sStride);
      }
   }

   /** Multiplies every pixel's RGB by its alpha, row by row, with the stock float arithmetic. */
   public static void premultiplyAlpha(ByteBuffer buf, int w, int h) {
      Scratch s = SCRATCH.get();
      int stride = w * 4;
      byte[] row = s.row0(stride);
      for (int y = 0; y < h; y++) {
         buf.get(y * stride, row, 0, stride);
         for (int i = 0; i < stride; i += 4) {
            int a = row[i + 3] & 0xFF;
            row[i] = (byte) (int) ((float) ((row[i] & 0xFF) * a) / 255.0F);
            row[i + 1] = (byte) (int) ((float) ((row[i + 1] & 0xFF) * a) / 255.0F);
            row[i + 2] = (byte) (int) ((float) ((row[i + 2] & 0xFF) * a) / 255.0F);
         }
         buf.put(y * stride, row, 0, stride);
      }
   }

   private static int clamp(int v, int max) {
      return v < 0 ? 0 : (v > max ? max : v);
   }
}
