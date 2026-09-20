package pzopt;

import java.util.ArrayList;
import java.util.Arrays;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.debug.DebugOptions;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoPuddlesGeometry;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;

/**
 * Per chunk-level cache of the packed puddle vertices the game thread builds every frame in
 * FBORenderCell.renderPuddles (Config key {@code puddleCache}).
 *
 * <p>Stock walks every on-screen chunk on every level each frame, filters the chunk's cached puddle squares
 * (cutaway flags, on-screen test, floor layer, geometry flags), re-reads the four vertex lights and packs
 * 32 floats per square into IsoPuddles' RenderData (with four IsoDepthHelper lookups). In a thunderstorm at
 * max zoom on a 5120x2160 desktop that is ~8k squares and 4.5 ms of a 13 ms game-thread frame.
 *
 * <p>Of those 32 floats only three inputs change between frames: the vertex lights (float slot 6), the
 * camera's sub-pixel jiggle added to x/y (slots 4, 5) and the depth (slot 7), which depends on the camera's
 * chunk only and shifts by the same constant for every square when the camera crosses a chunk edge. The
 * geometry itself, its shape flags and the square list are fixed until the chunk level is re-baked. So a
 * batch is packed once per chunk level with the stock code (same bytes), stored on the IsoChunk, and on later
 * frames copied into RenderData with those three slots patched. A batch is rebuilt when its square list is
 * cleared by a bake, its cutaway flags change, or every {@code puddleCacheFrames} frames as a backstop
 * (staggered per chunk so the rebuilds never land on one frame).
 *
 * <p>The per-square on-screen test is not applied to cached batches: every puddle square of an on-screen
 * chunk level is submitted and the ones outside the viewport are clipped by the GPU, which is the same
 * picture.
 */
public final class PuddleCache {
   /** Floats per square in IsoPuddles.RenderData: 4 vertices x (4 dir flags, x, y, colour, depth). */
   private static final int FLOATS = 32;
   private static final int LEVELS = 64;

   public static final class Batch {
      float[] data = new float[0];
      IsoGridSquare[] squares = new IsoGridSquare[0];
      int count;
      int listSize;
      long flagMask;
      float jx;
      float jy;
      int camChunkX;
      int camChunkY;
      int builtFrame;
      int expiryFrame;
      boolean invalid = true;
   }

   /** Per-chunk slot: [playerIndex][z + 32]. Lives in IsoChunk.pzoptPuddles. */
   public static final class Slot {
      final Batch[][] batches = new Batch[4][];

      public void invalidate(int level) {
         int idx = level + 32;
         if (idx < 0 || idx >= LEVELS) {
            return;
         }
         for (Batch[] perPlayer : this.batches) {
            if (perPlayer != null && perPlayer[idx] != null) {
               perPlayer[idx].invalid = true;
            }
         }
      }

      Batch get(int playerIndex, int level) {
         int idx = level + 32;
         if (idx < 0 || idx >= LEVELS) {
            return null;
         }
         Batch[] perPlayer = this.batches[playerIndex];
         if (perPlayer == null) {
            perPlayer = this.batches[playerIndex] = new Batch[LEVELS];
         }
         Batch b = perPlayer[idx];
         if (b == null) {
            b = perPlayer[idx] = new Batch();
         }
         return b;
      }
   }

   private static int frame;
   private static int built;
   private static int reused;
   private static int rebuiltInvalid;
   private static int rebuiltFlags;
   private static int rebuiltExpired;
   private static final ArrayList<IsoGridSquare> scratch = new ArrayList<>();

   public static boolean enabled() {
      return Overrides.enabled() && Config.PUDDLE_CACHE;
   }

   /** Called from an IsoChunk bake right after FBORenderLevels.clearCachedSquares(level). */
   public static void invalidate(IsoChunk chunk, int level) {
      Slot s = chunk.pzoptPuddles;
      if (s != null) {
         s.invalidate(level);
      }
   }

   /**
    * The cached replacement for the body of FBORenderCell.renderPuddles: same guards, same per-z draw.
    */
   public static void render(int playerIndex, ArrayList<IsoChunk> onScreenChunks, int maxZ) {
      IsoPuddles puddles = IsoPuddles.getInstance();
      if (playerIndex == 0) {
         frame++;
      }
      PlayerCamera camera = IsoCamera.cameras[playerIndex];
      float jx = camera.fixJigglyModelsX * camera.zoom;
      float jy = camera.fixJigglyModelsY * camera.zoom;
      int camChunkX = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterX) / 8.0F);
      int camChunkY = PZMath.fastfloor(PZMath.fastfloor(IsoCamera.frameState.camCharacterY) / 8.0F);
      boolean noLighting = DebugOptions.instance.fboRenderChunk.nolighting.getValue();
      int interval = Math.max(1, Config.PUDDLE_CACHE_FRAMES);

      for (int z = 0; z <= maxZ; z++) {
         if (!puddles.pzoptCanRender(z)) {
            continue;
         }
         int first = puddles.pzoptNumSquares();

         for (int i = 0; i < onScreenChunks.size(); i++) {
            IsoChunk chunk = onScreenChunks.get(i);
            if (z < chunk.minLevel || z > chunk.maxLevel) {
               continue;
            }
            FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
            if (!renderLevels.isOnScreen(z)) {
               continue;
            }
            java.util.List<IsoGridSquare> squares = renderLevels.getCachedSquares_Puddles(z);
            if (squares.isEmpty()) {
               continue;
            }
            ChunkLevelData levelData = chunk.getCutawayDataForLevel(z);
            long mask = flagMask(levelData, playerIndex);
            Slot slot = chunk.pzoptPuddles;
            if (slot == null) {
               slot = chunk.pzoptPuddles = new Slot();
            }
            Batch b = slot.get(playerIndex, z);
            if (b == null) {
               continue;
            }

            boolean rebuild;
            if (b.invalid || b.listSize != squares.size()) {
               rebuild = true;
               rebuiltInvalid++;
            } else if (b.flagMask != mask) {
               rebuild = true;
               rebuiltFlags++;
            } else if (frame >= b.expiryFrame) {
               rebuild = true;
               rebuiltExpired++;
            } else {
               rebuild = false;
            }

            if (rebuild) {
               build(puddles, b, chunk, z, playerIndex, squares, levelData, mask, jx, jy, camChunkX, camChunkY, interval);
            } else if (b.count > 0) {
               reuse(puddles, b, chunk, z, playerIndex, jx, jy, camChunkX, camChunkY, noLighting);
            }
         }

         int count = puddles.pzoptNumSquares() - first;
         if (count > 0) {
            puddles.pzoptDraw(z, first, count);
         }
      }
   }

   private static long flagMask(ChunkLevelData levelData, int playerIndex) {
      byte[] flags = levelData.squareFlags[playerIndex];
      long mask = 0L;
      for (int i = 0; i < 64; i++) {
         if ((flags[i] & 1) != 0) {
            mask |= 1L << i;
         }
      }
      return mask;
   }

   /** Stock filter + stock packing (IsoPuddles.pzoptPack) into RenderData, then a copy of the block into the batch. */
   private static void build(IsoPuddles puddles, Batch b, IsoChunk chunk, int z, int playerIndex, java.util.List<IsoGridSquare> squares,
                             ChunkLevelData levelData, long mask, float jx, float jy, int camChunkX, int camChunkY, int interval) {
      ArrayList<IsoGridSquare> list = scratch;
      list.clear();
      boolean anyFloor = PerformanceSettings.puddlesQuality >= 2;
      for (int j = 0; j < squares.size(); j++) {
         IsoGridSquare square = squares.get(j);
         if (square.getZ() != z || !levelData.shouldRenderSquare(playerIndex, square)) {
            continue;
         }
         IsoObject floor = square.getFloor();
         if (floor == null) {
            continue;
         }
         if (!anyFloor && floor.getRenderInfo(playerIndex).layer == ObjectRenderLayer.TranslucentFloor) {
            continue;
         }
         IsoPuddlesGeometry pg = square.getPuddles();
         if (pg != null && pg.shouldRender()) {
            list.add(square);
         }
      }

      int before = puddles.pzoptNumSquares();
      puddles.pzoptPack(list, z);
      int count = puddles.pzoptNumSquares() - before;
      if (b.data.length < count * FLOATS) {
         b.data = new float[count * FLOATS];
      }
      if (count > 0) {
         System.arraycopy(puddles.pzoptData(), before * FLOATS, b.data, 0, count * FLOATS);
      }
      if (b.squares.length < count) {
         b.squares = new IsoGridSquare[count];
      }
      // pzoptPack keeps only the squares whose geometry passed shouldRender(), in list order
      int k = 0;
      for (int j = 0; j < list.size() && k < count; j++) {
         IsoPuddlesGeometry pg = list.get(j).getPuddles();
         if (pg != null && pg.shouldRender()) {
            b.squares[k++] = list.get(j);
         }
      }
      Arrays.fill(b.squares, k, b.squares.length, null);
      b.count = k == count ? count : 0; // a mismatch means the geometry changed under us: rebuild next frame
      b.listSize = squares.size();
      b.flagMask = mask;
      b.jx = jx;
      b.jy = jy;
      b.camChunkX = camChunkX;
      b.camChunkY = camChunkY;
      b.builtFrame = frame;
      int stagger = Math.floorMod(chunk.wx * 31 + chunk.wy * 17 + z * 7, interval);
      b.expiryFrame = frame + interval + stagger;
      b.invalid = k != count;
      list.clear();
      built++;
   }

   /** Copy the batch into RenderData and patch lights, jiggle and depth for this frame. */
   private static void reuse(IsoPuddles puddles, Batch b, IsoChunk chunk, int z, int playerIndex,
                             float jx, float jy, int camChunkX, int camChunkY, boolean noLighting) {
      int n = b.count;
      int base = puddles.pzoptAppend(b.data, n, z);
      float[] data = puddles.pzoptData();
      float dx = jx - b.jx;
      float dy = jy - b.jy;
      float ddepth = 0.0F;
      if (camChunkX != b.camChunkX || camChunkY != b.camChunkY) {
         float now = IsoDepthHelper.getChunkDepthData(camChunkX, camChunkY, chunk.wx, chunk.wy, z).depthStart;
         float then = IsoDepthHelper.getChunkDepthData(b.camChunkX, b.camChunkY, chunk.wx, chunk.wy, z).depthStart;
         ddepth = now - then;
      }
      boolean move = dx != 0.0F || dy != 0.0F;
      int o = base * FLOATS;
      for (int s = 0; s < n; s++) {
         IsoGridSquare sq = b.squares[s];
         // vertex order of IsoPuddlesGeometry.updateLighting: light verts 0, 3, 2, 1
         if (noLighting) {
            data[o + 6] = data[o + 14] = data[o + 22] = data[o + 30] = Float.intBitsToFloat(-1);
         } else {
            data[o + 6] = Float.intBitsToFloat(sq.getVertLight(0, playerIndex));
            data[o + 14] = Float.intBitsToFloat(sq.getVertLight(3, playerIndex));
            data[o + 22] = Float.intBitsToFloat(sq.getVertLight(2, playerIndex));
            data[o + 30] = Float.intBitsToFloat(sq.getVertLight(1, playerIndex));
         }
         if (move) {
            for (int v = o; v < o + FLOATS; v += 8) {
               data[v + 4] += dx;
               data[v + 5] += dy;
            }
         }
         if (ddepth != 0.0F) {
            data[o + 7] += ddepth;
            data[o + 15] += ddepth;
            data[o + 23] += ddepth;
            data[o + 31] += ddepth;
         }
         o += FLOATS;
      }
      reused++;
   }

   public static String stats() {
      return "puddle cache: frames=" + frame + " batches built=" + built + " reused=" + reused
         + " rebuilt (bake=" + rebuiltInvalid + " cutaway=" + rebuiltFlags + " expired=" + rebuiltExpired + ")";
   }
}
