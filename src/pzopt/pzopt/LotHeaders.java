package pzopt;

import zombie.iso.IsoLot;
import zombie.iso.LotHeader;
import zombie.iso.MapFiles;

/**
 * LotHeader.getZombieIntensityForChunk with the MapFiles.getLotHeader lookup
 * (a String.format plus two hash lookups) memoized per cell instead of done
 * per chunk (docs/plan-instant-load.md L2; the same fix MapCollisionData.init
 * already carries). Same loop, same tests, same result.
 */
public final class LotHeaders {
   private LotHeaders() {
   }

   /** One cell's memo: the header of each map-files layer, resolved on first use. */
   public static final class Cache {
      public final LotHeader header;
      private final LotHeader[] byLayer;
      private final boolean[] known;

      public Cache(LotHeader header) {
         this.header = header;
         int n = IsoLot.MapFiles.size();
         this.byLayer = new LotHeader[n];
         this.known = new boolean[n];
      }
   }

   public static int zombieIntensity(LotHeader lotHeader, int chunkX, int chunkY, Cache cache) {
      if (chunkX < 0 || chunkY < 0 || chunkX >= 32 || chunkY >= 32) {
         return -1;
      }
      if (lotHeader == null) {
         return -1;
      }
      for (int j = lotHeader.mapFiles.priority; j < IsoLot.MapFiles.size(); j++) {
         MapFiles mapFiles = IsoLot.MapFiles.get(j);
         int cell300X = zombie.core.math.PZMath.fastfloor((float) (lotHeader.cellX * 256 + chunkX * 8) / 300.0F);
         int cell300Y = zombie.core.math.PZMath.fastfloor((float) (lotHeader.cellY * 256 + chunkY * 8) / 300.0F);
         if (!mapFiles.bgHasCell300.getValue(cell300X - mapFiles.minCell300X, cell300Y - mapFiles.minCell300Y)) {
            continue;
         }
         if (j >= cache.byLayer.length) { // a layer added after the memo was made: resolve directly
            LotHeader direct = mapFiles.getLotHeader(lotHeader.cellX, lotHeader.cellY);
            return direct.getZombieIntensity(chunkX + chunkY * 32) & 0xFF;
         }
         if (!cache.known[j]) {
            cache.byLayer[j] = mapFiles.getLotHeader(lotHeader.cellX, lotHeader.cellY);
            cache.known[j] = true;
         }
         LotHeader lotHeader2 = cache.byLayer[j];
         return lotHeader2.getZombieIntensity(chunkX + chunkY * 32) & 0xFF;
      }
      return -1;
   }
}
