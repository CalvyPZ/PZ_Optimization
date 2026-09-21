package pzopt;

import zombie.core.PerformanceSettings;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunkLevel;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.objects.RainManager;
import zombie.network.GameServer;

/**
 * Rain splash update without the game's RNG (Config key {@code rainSplashesFast}).
 *
 * <p>Stock {@code IsoChunkLevel.updateRainSplashes} asks {@code Rand.NextBool(n)} once per idle square of every
 * on-screen chunk level every frame (64 x ~90 levels = ~6k calls a frame through the CellularAutomatonRNG,
 * 2.6 % of a thunderstorm frame on the laptop) to start a splash with probability 1/n. Here the idle squares
 * are walked with geometric skipping: one draw of a local xorshift generator per splash start gives the number
 * of idle squares to skip, which is the same Bernoulli(1/n)-per-square process (the gaps between successes of
 * independent trials are geometric). The advance of running splashes, the flags and the render call are the
 * stock ones, so the splash sprites, their timing and their random positions look the same; only the random
 * stream differs, as it does between two stock runs.
 */
public final class RainSplashes {
   private static long seed = 0x9E3779B97F4A7C15L;
   private static int cachedN = -1;
   private static double cachedLog1mP;
   private static long starts;
   private static long levels;

   public static boolean enabled() {
      return Overrides.enabled() && Config.RAIN_SPLASHES_FAST;
   }

   private static long next() {
      long x = seed;
      x ^= x << 13;
      x ^= x >>> 7;
      x ^= x << 17;
      seed = x;
      return x;
   }

   /** Number of idle squares to skip before the next start, Geometric(p) with p = 1/n. */
   private static int skip(int n) {
      if (n <= 1) {
         return 0;
      }
      if (n != cachedN) {
         cachedN = n;
         cachedLog1mP = Math.log(1.0 - 1.0 / n);
      }
      double u = ((next() >>> 11) + 1.0) * 0x1.0p-53; // (0, 1]
      double k = Math.log(u) / cachedLog1mP;
      return k >= 1.0e9 ? Integer.MAX_VALUE : (int)k;
   }

   /** The stock chance divisor for one square and frame: AdjustForFramerate((5 / intensity) * 100). */
   private static int chanceDivisor(int intensity) {
      int chance = (int)(5.0F / (float)intensity) * 100;
      return GameServer.server ? (int)((float)chance * 0.33333334F) : (int)((float)chance * ((float)PerformanceSettings.getLockFPS() / 30.0F));
   }

   /** Stock IsoChunkLevel.updateRainSplashes with the per-square RNG replaced by geometric skipping. */
   public static void update(IsoChunkLevel lvl) {
      if (lvl.rainSplashFrameNum == IsoCamera.frameState.frameCount) {
         return;
      }
      lvl.rainSplashFrameNum = IsoCamera.frameState.frameCount;
      boolean raining = IsoWorld.instance.currentCell.getRainIntensity() > 0 || RainManager.isRaining() && RainManager.rainIntensity > 0.0F;
      float[] frames = lvl.rainSplashFrame;
      byte[] flags = lvl.rainFlags;
      if (raining) {
         lvl.raining = true;
         if (IsoCamera.frameState.paused) {
            return;
         }
         levels++;
         int intensity = IsoWorld.instance.currentCell.getRainIntensity();
         if (intensity == 0) {
            intensity = Math.min((int)Math.floor(RainManager.rainIntensity / 0.2F) + 1, 5);
         }
         int n = chanceDivisor(intensity);
         float advance = 0.08F * (30.0F / (float)PerformanceSettings.getLockFPS());
         int skip = skip(n);
         for (int i = 0; i < frames.length; i++) {
            float f = frames[i];
            if (f < 0.0F) {
               if (skip == 0) {
                  frames[i] = 0.0F;
                  flags[i] = (byte)(flags[i] | 2);
                  starts++;
                  skip = skip(n);
               } else {
                  skip--;
               }
            } else {
               f += advance;
               frames[i] = f >= 1.0F ? -1.0F : f;
            }
         }
      } else if (lvl.raining) {
         lvl.raining = false;
         java.util.Arrays.fill(frames, -1.0F);
      }
   }

   /** Stock IsoChunkLevel.renderRainSplashes (public fields only). */
   public static void render(IsoChunkLevel lvl, int playerIndex) {
      if (!lvl.raining) {
         return;
      }
      float[] frames = lvl.rainSplashFrame;
      byte[] flags = lvl.rainFlags;
      FBORenderCutaways.ChunkLevelData cutawayLevel = lvl.getChunk().getCutawayDataForLevel(lvl.getLevel());
      for (int i = 0; i < frames.length; i++) {
         if (frames[i] < 0.0F) {
            continue;
         }
         IsoGridSquare square = lvl.getChunk().getGridSquare(i % 8, i / 8, lvl.getLevel());
         if (!cutawayLevel.shouldRenderSquare(playerIndex, square)) {
            continue;
         }
         square.renderRainSplash(playerIndex, square.getLightInfo(playerIndex), frames[i], (flags[i] & 2) != 0);
         flags[i] = (byte)(flags[i] & ~2);
      }
   }

   public static String stats() {
      return "rain splashes: level updates=" + levels + " starts=" + starts;
   }
}
