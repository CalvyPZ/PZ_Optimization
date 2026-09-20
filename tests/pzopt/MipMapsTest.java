package pzopt;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * pzopt.MipMaps must produce byte-identical mip levels and premultiplied
 * alpha to the game's own ImageData.scaleMipLevelMaxAlpha,
 * scaleMipLevelAverage and performPreMultipliedAlpha(MipMapLevel). The stock
 * class comes from the game jar alone (not the override in build/classes) and
 * is driven through reflection; its MipMapLevel objects wrap plain direct
 * buffers so no LWJGL native allocator is needed.
 */
public final class MipMapsTest {
   public static void main(String[] args) throws Exception {
      String pzDir = System.getenv("PZ_DIR");
      if (pzDir == null) {
         pzDir = "/games/steamapps/common/ProjectZomboid/projectzomboid";
      }
      File jar = new File(pzDir, "projectzomboid.jar");
      Check.check(jar.isFile(), "game jar at " + jar);
      ClassLoader stock = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
      Class<?> imageData = Class.forName("zombie.core.textures.ImageData", true, stock);
      Class<?> mipLevel = Class.forName("zombie.core.textures.MipMapLevel", true, stock);
      Class<?> wrapped = Class.forName("zombie.core.utils.WrappedBuffer", true, stock);

      Method maxAlpha = imageData.getDeclaredMethod("scaleMipLevelMaxAlpha", mipLevel, mipLevel, int.class);
      Method average = imageData.getDeclaredMethod("scaleMipLevelAverage", mipLevel, mipLevel, int.class);
      Method premul = imageData.getDeclaredMethod("performPreMultipliedAlpha", mipLevel);
      maxAlpha.setAccessible(true);
      average.setAccessible(true);
      premul.setAccessible(true);
      Field preserve = imageData.getField("preserveTransparentColor");
      Field wrappedBuf = wrapped.getDeclaredField("buf");
      wrappedBuf.setAccessible(true);
      Constructor<?> levelCtor = mipLevel.getConstructor(int.class, int.class, wrapped);

      // ImageData has no cheap constructor (files, TextureID); allocate it without running one.
      Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      Object image = unsafe.allocateInstance(imageData);
      Object wrapper = unsafe.allocateInstance(wrapped);

      Random rnd = new Random(2);
      int[][] sizes = {{1, 1}, {2, 2}, {1, 8}, {8, 1}, {3, 5}, {16, 16}, {17, 9}, {64, 32}, {128, 128}, {256, 96}};
      int cases = 0;
      for (int[] wh : sizes) {
         for (int variant = 0; variant < 4; variant++) {
            int pw = wh[0];
            int ph = wh[1];
            ByteBuffer parent = ByteBuffer.allocateDirect(pw * ph * 4);
            fill(parent, rnd, variant);
            boolean preserveTransparent = (variant & 1) != 0;
            preserve.setBoolean(image, preserveTransparent);
            int sw = pw > 1 ? pw >> 1 : pw;
            int sh = ph > 1 ? ph >> 1 : ph;

            // level 0/1 rule
            ByteBuffer expected = ByteBuffer.allocateDirect(sw * sh * 4);
            ByteBuffer got = ByteBuffer.allocateDirect(sw * sh * 4);
            wrappedBuf.set(wrapper, parent);
            Object parentLevel = levelCtor.newInstance(pw, ph, wrapper);
            Object subWrapper = unsafe.allocateInstance(wrapped);
            wrappedBuf.set(subWrapper, expected);
            Object subLevel = levelCtor.newInstance(sw, sh, subWrapper);
            maxAlpha.invoke(image, parentLevel, subLevel, 0);
            MipMaps.scaleMaxAlpha(parent, pw, ph, got, sw, sh, preserveTransparent);
            Check.check(same(expected, got), "maxAlpha " + pw + "x" + ph + " variant " + variant);

            // level 2+ rule
            expected.clear();
            got.clear();
            zero(expected);
            zero(got);
            average.invoke(image, parentLevel, subLevel, 2);
            MipMaps.scaleAverage(parent, pw, ph, got, sw, sh);
            Check.check(same(expected, got), "average " + pw + "x" + ph + " variant " + variant);

            // premultiplied alpha, in place
            ByteBuffer copy = ByteBuffer.allocateDirect(pw * ph * 4);
            copy.put(0, parent, 0, parent.capacity());
            premul.invoke(image, parentLevel);
            MipMaps.premultiplyAlpha(copy, pw, ph);
            Check.check(same(parent, copy), "premultiply " + pw + "x" + ph + " variant " + variant);
            cases++;
         }
      }

      // the stock code leaves the buffer positions where it found them apart from the rewind; ours writes absolutely
      ByteBuffer p = ByteBuffer.allocateDirect(8 * 8 * 4);
      fill(p, rnd, 2);
      p.position(5);
      ByteBuffer s = ByteBuffer.allocateDirect(4 * 4 * 4);
      MipMaps.scaleMaxAlpha(p, 8, 8, s, 4, 4, false);
      Check.check(p.position() == 5 && s.position() == 0, "positions untouched by the bulk path");

      System.out.println("MipMapsTest ok: " + cases + " cases identical to the stock ImageData");
   }

   private static void fill(ByteBuffer buf, Random rnd, int variant) {
      for (int i = 0; i < buf.capacity(); i++) {
         int v = rnd.nextInt(256);
         if (i % 4 == 3) {
            // alpha: variant 2/3 make transparency common so the alpha>0 branches of level 0/1 are exercised
            if (variant >= 2 && rnd.nextInt(3) == 0) {
               v = 0;
            } else if (variant >= 2 && rnd.nextInt(3) == 0) {
               v = 255;
            }
         }
         buf.put(i, (byte) v);
      }
   }

   private static void zero(ByteBuffer buf) {
      for (int i = 0; i < buf.capacity(); i++) {
         buf.put(i, (byte) 0);
      }
   }

   private static boolean same(ByteBuffer a, ByteBuffer b) {
      if (a.capacity() != b.capacity()) {
         return false;
      }
      for (int i = 0; i < a.capacity(); i++) {
         if (a.get(i) != b.get(i)) {
            System.err.println("byte " + i + ": stock " + (a.get(i) & 0xFF) + " ours " + (b.get(i) & 0xFF));
            return false;
         }
      }
      return true;
   }
}
