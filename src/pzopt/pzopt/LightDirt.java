package pzopt;

import zombie.iso.IsoChunk;

/**
 * Strong versus weak lighting-only dirt on a chunk-level texture.
 *
 * <p>A chunk texture dirtied only by lighting (FBORenderLevels flag 32) is held by FBORenderCell for up to
 * {@code lightingRebakeMs} and spread over {@code lightingRebakeMaxFrames} frames past {@code lightingRebakeBudget}
 * starts a frame. That was tuned for sky drift (1/255 a tick on every square at once) and lightning (every
 * on-screen texture at once, five times a strike). A moving light source is neither: the player's torch sweeping
 * a room dirties the few chunk levels in its beam every frame by tens of levels, and holding those shows the beam
 * as a patchwork of past angles, one per chunk (the "blocky lights" video, 2026-09-21). Stock re-bakes them every
 * frame.
 *
 * <p>Each square accumulates the size of its light changes since its level was last baked (LightingJNI); past
 * {@link Config#LIGHTING_STRONG_DELTA} the level is marked strong. A strong level skips the hold and the spread and
 * re-bakes at once, unless the player's global light itself moved by {@link Config#LIGHTING_GLOBAL_DELTA} in a frame
 * (LightingJNI.update): that is a flash or a fast dusk, every exterior square changes together, and the spread stays
 * on for {@code lightingRebakeMaxFrames} frames, as before. (A count of strong levels a frame was tried first: turning
 * also moves the vision cone across the screen, so a sweep marked as many levels as a flash.)
 */
public final class LightDirt {
   private LightDirt() {
   }

   private static int spreadUntilFrame = -1;
   private static final float[] lastGlobal = new float[6];
   private static boolean haveGlobal;
   public static long strongMarks; // counters for the FBORenderCell log line
   public static long globalEvents;

   /** Largest channel difference between two ABGR vertex lights, 0-255. */
   public static int abgrDelta(int a, int b) {
      int d = Math.abs((a & 0xFF) - (b & 0xFF));
      d = Math.max(d, Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)));
      return Math.max(d, Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)));
   }

   /**
    * The global light handed to the lighting engine this frame (player 0). A move past Config.lightingGlobalDelta
    * on any component is a flash or a fast dusk: the square changes it causes arrive over the next passes and are
    * spread like before. The window covers those passes plus the spread itself.
    */
   public static void globalLight(int frameNo, float rmod, float gmod, float bmod, float ambient, float night, float sky) {
      float d = 0.0F;
      if (haveGlobal) {
         d = Math.abs(rmod - lastGlobal[0]);
         d = Math.max(d, Math.abs(gmod - lastGlobal[1]));
         d = Math.max(d, Math.abs(bmod - lastGlobal[2]));
         d = Math.max(d, Math.abs(ambient - lastGlobal[3]));
         d = Math.max(d, Math.abs(night - lastGlobal[4]));
         d = Math.max(d, Math.abs(sky - lastGlobal[5]));
      }
      lastGlobal[0] = rmod;
      lastGlobal[1] = gmod;
      lastGlobal[2] = bmod;
      lastGlobal[3] = ambient;
      lastGlobal[4] = night;
      lastGlobal[5] = sky;
      haveGlobal = true;
      if (d >= Config.LIGHTING_GLOBAL_DELTA) {
         spreadUntilFrame = frameNo + Config.LIGHTING_REBAKE_MAX_FRAMES + 2;
         globalEvents++;
      }
   }

   /** True while a global light event is being spread over frames. */
   public static boolean spreading(int frameNo) {
      return frameNo <= spreadUntilFrame;
   }

   /**
    * A square of {@code chunk} at level index {@code li} (z + 32) has accumulated a strong change since the level
    * was last baked. Marks the level for this frame.
    */
   public static void markStrong(IsoChunk chunk, int li, int frameNo) {
      if (chunk.pzoptLightStrongFrame[li] != frameNo) {
         chunk.pzoptLightStrongFrame[li] = frameNo;
         strongMarks++;
      }
   }

   /** True when the level's lighting-only dirt should re-bake now: marked strong since its last bake, no global event. */
   public static boolean rebakeNow(IsoChunk chunk, int level, int frameNo) {
      int li = level + 32;
      if (li < 0 || li >= 64 || frameNo <= spreadUntilFrame) {
         return false;
      }
      return chunk.pzoptLightStrongFrame[li] > chunk.pzoptLightBakeFrame[li];
   }

   /** The level's texture was (re)baked this frame: the accumulated changes are on screen. */
   public static void baked(IsoChunk chunk, int level, int frameNo) {
      int li = level + 32;
      if (li >= 0 && li < 64) {
         chunk.pzoptLightBakeFrame[li] = frameNo;
      }
   }

   /** A chunk object is being reused for another position. */
   public static void chunkReused(IsoChunk chunk) {
      java.util.Arrays.fill(chunk.pzoptLightStrongFrame, -1);
      java.util.Arrays.fill(chunk.pzoptLightBakeFrame, -1);
   }
}
