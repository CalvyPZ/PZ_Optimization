package zombie.iso;

import gnu.trove.iterator.TLongIntIterator;
import gnu.trove.iterator.TLongObjectIterator;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.map.hash.TObjectByteHashMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.SandboxOptions;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.GameProfiler.ProfileArea;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.random.Rand;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.zones.Zone;
import zombie.network.GameClient;
import zombie.network.GameServer;

@UsedFromLua
public final class FishSchoolManager {
   private static final TLongIntHashMap noiseFishPointDisabler = new TLongIntHashMap();
   private static final TLongObjectHashMap<FishSchoolManager.ZoneData> zoneCache = new TLongObjectHashMap();
   private static final TLongObjectHashMap<FishSchoolManager.ChumData> chumPoints = new TLongObjectHashMap();
   private static final ArrayList<Zone> tempArrayList = new ArrayList<>();
   private int seed = -1;
   private int trashSeed = -1;
   private static final FishSchoolManager INSTANCE = new FishSchoolManager();
   private final ArrayList<int[]> noFishZones = new ArrayList<>();
   private final ArrayList<int[]> nearbyNoFishZones = new ArrayList<>();
   private final TObjectByteHashMap<IsoChunk> doneChunks = new TObjectByteHashMap();

   public static FishSchoolManager getInstance() {
      return INSTANCE;
   }

   public void generateSeed() {
      if (this.seed == -1) {
         this.seed = Rand.Next(100000);
      }

      if (this.trashSeed == -1) {
         this.trashSeed = Rand.Next(100000);
      }
   }

   public void updateSeed() {
      if (!GameClient.client) {
         noiseFishPointDisabler.clear();
         pzoptForgetNoise(); // pzopt
         zoneCache.clear();
         this.seed = Rand.Next(100000);
      }

      if (GameServer.server) {
         GameServer.transmitFishingData(this.seed, this.trashSeed, noiseFishPointDisabler, chumPoints);
      }
   }

   public void init() {
      noiseFishPointDisabler.clear();
      zoneCache.clear();
      chumPoints.clear();
      pzoptForgetNoise(); // pzopt
      this.trashSeed = -1;
      this.noFishZones.clear();
      this.load();
      if (GameClient.client) {
         GameClient.sendFishingDataRequest();
      } else {
         this.generateSeed();
      }
   }

   public void update() {
      if (!GameClient.client) {
         ProfileArea var1 = GameProfiler.getInstance().profile("Data");

         try {
            this.updateFishingData();
         } catch (Throwable var7) {
            if (var1 != null) {
               try {
                  var1.close();
               } catch (Throwable var5) {
                  var7.addSuppressed(var5);
               }
            }

            throw var7;
         }

         if (var1 != null) {
            var1.close();
         }
      }

      if (!GameServer.server) {
         ProfileArea var8 = GameProfiler.getInstance().profile("Splashes");

         try {
            this.generateSplashes();
         } catch (Throwable var6) {
            if (var8 != null) {
               try {
                  var8.close();
               } catch (Throwable var4) {
                  var6.addSuppressed(var4);
               }
            }

            throw var6;
         }

         if (var8 != null) {
            var8.close();
         }
      }
   }

   public void updateFishingData() {
      int currentTime = this.getCurrentGameTimeInMinutes();
      boolean needSendDataToClients = false;
      TLongIntIterator it = noiseFishPointDisabler.iterator();

      while (it.hasNext()) {
         it.advance();
         if (currentTime > it.value()) {
            it.remove();
            pzoptForgetNoise(); // pzopt
            needSendDataToClients = true;
         }
      }

      TLongObjectIterator<FishSchoolManager.ChumData> itx = chumPoints.iterator();

      while (itx.hasNext()) {
         itx.advance();
         if (currentTime > ((FishSchoolManager.ChumData)itx.value()).endTime) {
            itx.remove();
            pzoptForgetNoise(); // pzopt
            needSendDataToClients = true;
         }
      }

      if (GameServer.server && needSendDataToClients) {
         GameServer.transmitFishingData(this.seed, this.trashSeed, noiseFishPointDisabler, chumPoints);
      }

      TLongObjectIterator<FishSchoolManager.ZoneData> itxx = zoneCache.iterator();

      while (itxx.hasNext()) {
         itxx.advance();
         Zone zone = ((FishSchoolManager.ZoneData)itxx.value()).zone;
         if (zone != null && currentTime > zone.getLastActionTimestamp() + 7200) {
            zone.setName("0");
            zone.setLastActionTimestamp(this.getCurrentGameTimeInMinutes());
            if (GameServer.server) {
               GameServer.sendZone(zone);
            }
         }
      }
   }

   private int getCurrentGameTimeInMinutes() {
      return (int)(GameTime.instance.getCalender().getTimeInMillis() / 60000L);
   }

   private void generateSplashes() {
      IsoCell cell = IsoCell.getInstance();
      this.doneChunks.clear();

      for (int playerIndex = 0; playerIndex < IsoPlayer.numPlayers; playerIndex++) {
         IsoChunkMap chunkMap = cell.getChunkMap(playerIndex);
         if (!chunkMap.ignore) {
            this.initNearbyNoFishZones(chunkMap, this.nearbyNoFishZones);

            for (int cy = 0; cy < IsoChunkMap.chunkGridWidth; cy++) {
               for (int cx = 0; cx < IsoChunkMap.chunkGridWidth; cx++) {
                  IsoChunk chunk = chunkMap.getChunk(cx, cy);
                  if (chunk != null && !this.doneChunks.containsKey(chunk)) {
                     this.doneChunks.put(chunk, (byte)1);
                     if (0 >= chunk.minLevel
                        && 0 <= chunk.maxLevel
                        && chunk.getNumberOfWaterTiles() != 0
                        && (!PerformanceSettings.fboRenderChunk || chunk.getRenderLevels(playerIndex).isOnScreen(0))
                        && (PerformanceSettings.fboRenderChunk || chunk.IsOnScreen(true))) {
                        IsoGridSquare[] squares = chunk.getSquaresForLevel(0);

                        for (IsoGridSquare square : squares) {
                           if (square != null && square.getProperties().has(IsoFlagType.water)) {
                              int x = square.x;
                              int y = square.y;
                              if (Core.debug && DebugOptions.instance.debugDrawFishingZones.getValue()) {
                                 this.drawDebugFishingZones(x, y);
                              }

                              long coordsHash = this.coordsToHash(x, y);
                              if (!noiseFishPointDisabler.containsKey(coordsHash)) {
                                 if (this.isFishPoint(x, y, this.nearbyNoFishZones)) {
                                    int fishNum = this.getNumberOfFishInPoint(x, y);
                                    if (fishNum > 0 && Rand.Next(Math.max(12 + (30 - fishNum) * 2, 1)) == 0) {
                                       this.generateSplashInRadius(x, y, this.getFishPointRadius(x, y));
                                    }
                                 }

                                 FishSchoolManager.ChumData chData = (FishSchoolManager.ChumData)chumPoints.get(coordsHash);
                                 if (chData != null && Rand.Next(Math.max(5, chData.maxForceTime - this.getCurrentGameTimeInMinutes()) * 2 + 60) == 0) {
                                    this.generateSplashInRadius(x, y, 0.3F);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void initNearbyNoFishZones(IsoChunkMap chunkMap, ArrayList<int[]> noFishZones1) {
      noFishZones1.clear();
      int minX = chunkMap.getWorldXMinTiles();
      int minY = chunkMap.getWorldYMinTiles();
      int maxX = chunkMap.getWorldXMaxTiles();
      int maxY = chunkMap.getWorldYMaxTiles();

      for (int i = 0; i < this.noFishZones.size(); i++) {
         int[] zone = this.noFishZones.get(i);
         if (maxX >= zone[0] && minX < zone[2] && maxY >= zone[1] && minY <= zone[3]) {
            noFishZones1.add(zone);
         }
      }
   }

   private boolean isNoFishZone(int x, int y, ArrayList<int[]> noFishZones1) {
      for (int i = 0; i < noFishZones1.size(); i++) {
         int[] zone = noFishZones1.get(i);
         if (x >= zone[0] && x <= zone[2] && y >= zone[1] && y <= zone[3]) {
            return true;
         }
      }

      return false;
   }

   private boolean isNoFishZone(int x, int y) {
      if (this.noFishZones.isEmpty()) {
         KahluaTable tbl = (KahluaTable)LuaManager.getTableObject("Fishing.NoFishZones");
         if (tbl == null) {
            return true;
         }

         for (int i = 0; i < tbl.size(); i++) {
            KahluaTable zoneTbl = (KahluaTable)tbl.rawget(i);
            if (zoneTbl != null) {
               Double x1 = (Double)zoneTbl.rawget("x1");
               Double y1 = (Double)zoneTbl.rawget("y1");
               Double x2 = (Double)zoneTbl.rawget("x2");
               Double y2 = (Double)zoneTbl.rawget("y2");
               int[] arr = new int[]{x1.intValue(), y1.intValue(), x2.intValue(), y2.intValue()};
               this.noFishZones.add(arr);
            }
         }
      }

      for (int i = 0; i < this.noFishZones.size(); i++) {
         int[] zone = this.noFishZones.get(i);
         if (x >= zone[0] && x <= zone[2] && y >= zone[1] && y <= zone[3]) {
            return true;
         }
      }

      return false;
   }

   private boolean isFishPoint(int x, int y) {
      return this.isNoFishZone(x, y) ? false : this.procedureRandomFloat(x, y, this.seed) > 0.995F;
   }

   private boolean isFishPoint(int x, int y, ArrayList<int[]> noFishZones1) {
      return this.isNoFishZone(x, y, noFishZones1) ? false : this.procedureRandomFloat(x, y, this.seed) > 0.995F;
   }

   private float getFishPointRadius(int x, int y) {
      return this.procedureRandomFloat(x, y, this.seed + 6599);
   }

   private boolean isTrashPoint(int x, int y) {
      return this.procedureRandomFloat(x, y, this.trashSeed + 9281) > 0.99F;
   }

   private float getTrashPointRadius(int x, int y) {
      return this.procedureRandomFloat(x, y, this.trashSeed + 8573);
   }

   private long coordsToHash(int x, int y) {
      long x1 = x >= 0 ? 2L * x : -2L * x - 1L;
      long y1 = y >= 0 ? 2L * y : -2L * y - 1L;
      long c = (x1 >= y1 ? x1 * x1 + x1 + y1 : x1 + y1 * y1) / 2L;
      return (x >= 0 || y >= 0) && (x < 0 || y < 0) ? -c - 1L : c;
   }

   private float procedureRandomFloat(long x, long y, long seed) {
      x = x << 13 ^ x;
      y = y << 13 ^ y;
      seed = seed << 13 ^ seed;
      long t = x * 790169L
         + x * x * x * 15731L
         + y * 789221L
         + y * y * y * 16057L
         + seed * 788317L
         + seed * seed * seed * 15401L
         + x * y * 209123L
         + y * seed * 209581L
         + x * seed * 208501L
         + x * y * seed * 15749L
         + 1376312588L;
      return (float)(((double)(t % 1073741824L) / 5.36870912E8 + 2.0) / 4.0); // pzopt: decompiler fix, the jar divides in double (Vineflower rendered a float divide)
   }

   private int getNumberOfFishInPoint(int x, int y) {
      int defaultNumber = (int)(this.procedureRandomFloat(x, y, this.seed + 7297) * 40.0F * (SandboxOptions.instance.fishAbundance.getValue() / 5.0));
      if (zoneCache.get(this.coordsToHash(x, y)) == null) {
         Zone zone = null;
         tempArrayList.clear();
         ArrayList<Zone> zones = IsoWorld.instance.metaGrid.getZonesAt(x, y, 0, tempArrayList);

         for (int i = 0; i < zones.size(); i++) {
            if (Objects.equals(zones.get(i).type, "Fishing")) {
               zone = zones.get(i);
               break;
            }
         }

         zoneCache.put(this.coordsToHash(x, y), new FishSchoolManager.ZoneData(zone));
      }

      return defaultNumber - ((FishSchoolManager.ZoneData)zoneCache.get(this.coordsToHash(x, y))).getCatchedFishNum();
   }

   private void generateSplashInRadius(int x, int y, float radiusCoeff) {
      float radius = 0.5F * radiusCoeff + 1.0F;
      float sqX = Rand.Next(x - radius, x + radius);
      float sqY = Rand.Next(y - radius, y + radius);
      if (!(this.dist(x, y, sqX, sqY) > radius)) {
         IsoGridSquare sq = IsoCell.getInstance().getGridSquare(sqX, sqY, 0.0);
         if (sq != null && sq.getProperties().has(IsoFlagType.water)) {
            sq.startWaterSplash(false);
         }
      }
   }

   private double dist(float x1, float y1, float x2, float y2) {
      float dx = x2 - x1;
      float dy = y2 - y1;
      return Math.sqrt(dx * dx + dy * dy);
   }

   // pzopt: Config.WORLD_SOUND_FAST. A repeat of a call with the same centre, radius and game minute writes the same
   // keys with the same expiry as the previous one, so it is a no-op: the last few calls are remembered and skipped
   // (the house alarm adds its 600-radius sound every frame for ~49 s, i.e. this (r/3)^2 square walk per frame). The
   // memo is dropped whenever either map is cleared, purged, received from the server or a chum point is added.
   private static final int NOISE_MEMO = 8; // pzopt
   private static final long[] noiseMemo = new long[NOISE_MEMO]; // pzopt: (x, y, radius, minute) packed
   private static int noiseMemoCount; // pzopt
   private static int noiseMemoNext; // pzopt

   private static void pzoptForgetNoise() { // pzopt
      noiseMemoCount = 0; // pzopt
      noiseMemoNext = 0; // pzopt
   }

   public void addSoundNoise(int x, int y, int radius) {
      long memoKey = 0L; // pzopt
      boolean memo = pzopt.Config.WORLD_SOUND_FAST && pzopt.Overrides.enabled() && radius >= 8 && radius < 65536 && x >= 0 && x < 65536 && y >= 0 && y < 65536; // pzopt: packable, and only walks worth it: the game adds ~10k tiny sounds a second which would flush the ring
      if (memo) { // pzopt
         memoKey = ((long)x << 48) | ((long)y << 32) | ((long)radius << 16) | (long)(this.getCurrentGameTimeInMinutes() & 0xFFFF); // pzopt
         for (int n = 0; n < noiseMemoCount; n++) { // pzopt
            if (noiseMemo[n] == memoKey) { // pzopt: identical call this game minute: every put would rewrite the same value
               return; // pzopt
            }
         }
      }

      if (pzopt.Config.WORLD_SOUND_FAST && pzopt.Overrides.enabled()) { // pzopt: the same walk, exact, without the per-square zone scan and sqrt
         this.pzoptWalkNoise(x, y, radius); // pzopt
      } else {
      for (int i = x - radius; i <= x + radius; i++) {
         for (int j = y - radius; j <= y + radius; j++) {
            if (this.dist(x, y, i, j) <= radius && (this.isFishPoint(i, j) || chumPoints.containsKey(this.coordsToHash(i, j)))) {
               noiseFishPointDisabler.put(this.coordsToHash(i, j), this.getCurrentGameTimeInMinutes() + 180);
            }
         }
      }
      } // pzopt

      if (memo) { // pzopt: remember this call
         noiseMemo[noiseMemoNext] = memoKey; // pzopt
         noiseMemoNext = (noiseMemoNext + 1) % NOISE_MEMO; // pzopt
         if (noiseMemoCount < NOISE_MEMO) { // pzopt
            noiseMemoCount++; // pzopt
         }
      }
   }

   private final ArrayList<int[]> pzoptZonesInBox = new ArrayList<>(); // pzopt

   // pzopt: Config.WORLD_SOUND_FAST. Stock tests every square of the (2r+1)² box against all 14 no-fish rectangles, takes a
   // square root for the disc test and probes the chum map; here the rectangles meeting the box are selected once (none,
   // almost always), the disc test is integer, the chum probe only runs when chum points exist, and a missing
   // Fishing.NoFishZones table (stock: every square is a no-fish zone) leaves only the chum points. Same keys, same values.
   private void pzoptWalkNoise(int x, int y, int radius) { // pzopt
      boolean allNoFish = false; // pzopt
      if (this.noFishZones.isEmpty()) { // pzopt
         this.isNoFishZone(x, y); // pzopt: stock's lazy load of the table
         if (this.noFishZones.isEmpty() && LuaManager.getTableObject("Fishing.NoFishZones") == null) { // pzopt
            allNoFish = true; // pzopt
         }
      }
      this.pzoptZonesInBox.clear(); // pzopt
      for (int n = 0; n < this.noFishZones.size(); n++) { // pzopt
         int[] zone = this.noFishZones.get(n); // pzopt
         if (x + radius >= zone[0] && x - radius <= zone[2] && y + radius >= zone[1] && y - radius <= zone[3]) { // pzopt
            this.pzoptZonesInBox.add(zone); // pzopt
         }
      }
      boolean chum = !chumPoints.isEmpty(); // pzopt
      if (allNoFish && !chum) { // pzopt: nothing in the box can be a fish or chum point
         return; // pzopt
      }
      int expiry = this.getCurrentGameTimeInMinutes() + 180; // pzopt: stock reads the clock per hit; it cannot change within the call
      double r2 = (double)radius * radius; // pzopt: exact
      for (int i = x - radius; i <= x + radius; i++) { // pzopt
         float fdx = (float)i - (float)x; // pzopt: the same float arithmetic as stock's dist()
         float dx2 = fdx * fdx; // pzopt
         for (int j = y - radius; j <= y + radius; j++) { // pzopt
            float fdy = (float)j - (float)y; // pzopt
            if ((double)(dx2 + fdy * fdy) > r2) { // pzopt: sqrt is monotonic and correctly rounded, so sqrt(s) <= r  <=>  s <= r²
               continue; // pzopt
            }
            boolean fish = false; // pzopt
            if (!allNoFish) { // pzopt
               boolean inZone = false; // pzopt
               for (int n = 0; n < this.pzoptZonesInBox.size(); n++) { // pzopt
                  int[] zone = this.pzoptZonesInBox.get(n); // pzopt
                  if (i >= zone[0] && i <= zone[2] && j >= zone[1] && j <= zone[3]) { // pzopt
                     inZone = true; // pzopt
                     break; // pzopt
                  }
               }
               fish = !inZone && this.procedureRandomFloat(i, j, this.seed) > 0.995F; // pzopt
            }
            if (fish || chum && chumPoints.containsKey(this.coordsToHash(i, j))) { // pzopt
               noiseFishPointDisabler.put(this.coordsToHash(i, j), expiry); // pzopt
            }
         }
      }
   }

   public void addChum(int x, int y, int force) {
      int currentTime = this.getCurrentGameTimeInMinutes();
      chumPoints.put(this.coordsToHash(x, y), new FishSchoolManager.ChumData(currentTime + 100, currentTime + 100 + force));
      pzoptForgetNoise(); // pzopt: a new chum point may lie inside a remembered call
   }

   public void catchFish(int x, int y) {
      Zone zone = this.getOrCreateFishingZone(x, y);
      int numberOfFish = Integer.parseInt(zone.getName());
      zone.setName(String.valueOf(numberOfFish + 1));
      zone.setOriginalName(String.valueOf(numberOfFish + 1));
      zone.setLastActionTimestamp(this.getCurrentGameTimeInMinutes());
      if (GameClient.client) {
         zone.sendToServer();
      }

      for (int i = x - 20; i <= x + 20; i++) {
         for (int j = y - 20; j <= y + 20; j++) {
            if (this.isFishPoint(i, j)) {
               zoneCache.put(this.coordsToHash(i, j), new FishSchoolManager.ZoneData(zone));
            }
         }
      }
   }

   private Zone getOrCreateFishingZone(int x, int y) {
      Zone zone = null;
      tempArrayList.clear();
      ArrayList<Zone> zones = IsoWorld.instance.metaGrid.getZonesAt(x, y, 0, tempArrayList);

      for (int i = 0; i < zones.size(); i++) {
         if (Objects.equals(zones.get(i).type, "Fishing")) {
            zone = zones.get(i);
            break;
         }
      }

      if (zone == null) {
         zone = IsoWorld.instance.registerZone("0", "Fishing", x - 20, y - 20, 0, 40, 40);
         zone.setLastActionTimestamp(this.getCurrentGameTimeInMinutes());
      }

      return zone;
   }

   public double getFishAbundance(int x, int y) {
      int result = 0;

      for (int i = -6; i <= 6; i++) {
         for (int j = -6; j <= 6; j++) {
            if (this.isFishPoint(x + i, y + j) && !noiseFishPointDisabler.containsKey(this.coordsToHash(x + i, y + j))) {
               double d = this.dist(x, y, x + i, y + j);
               double r = this.getFishPointRadius(x + i, y + j) + 2.0F;
               if (d <= r) {
                  result += this.getNumberOfFishInPoint(x + i, y + j);
               } else if (d <= r + 1.5) {
                  result = (int)(result + this.getNumberOfFishInPoint(x + i, y + j) * 0.5);
               }
            }

            FishSchoolManager.ChumData chum = (FishSchoolManager.ChumData)chumPoints.get(this.coordsToHash(x + i, y + j));
            if (chum != null && !noiseFishPointDisabler.containsKey(this.coordsToHash(x + i, y + j))) {
               double d = this.dist(x, y, x + i, y + j);
               if (this.getCurrentGameTimeInMinutes() > chum.maxForceTime) {
                  if (d <= 3.0) {
                     result += 15;
                  } else if (d <= 4.5) {
                     result += 7;
                  }
               } else if (d <= 3.0) {
                  result += 15 * (100 - (chum.maxForceTime - this.getCurrentGameTimeInMinutes())) / 100;
               } else if (d <= 4.5) {
                  result += 7 * (100 - (chum.maxForceTime - this.getCurrentGameTimeInMinutes())) / 100;
               }
            }
         }
      }

      if (result < 0) {
         result = 0;
      }

      return Math.floor(result);
   }

   public double getTrashAbundance(int x, int y) {
      for (int i = -6; i <= 6; i++) {
         for (int j = -6; j <= 6; j++) {
            if (this.isTrashPoint(x + i, y + j) && this.dist(x, y, x + i, y + j) < 4.0F * this.getTrashPointRadius(x + i, y + j) + 2.0F) {
               return this.procedureRandomFloat(x + i, y + j, this.trashSeed + 9601) / 2.0 + 0.05;
            }
         }
      }

      return 0.05;
   }

   public void setFishingData(ByteBufferWriter bb) {
      bb.putInt(this.seed);
      bb.putInt(this.trashSeed);
      bb.putInt(noiseFishPointDisabler.size());
      noiseFishPointDisabler.forEachKey(key -> {
         bb.putLong(key);
         return true;
      });
      bb.putInt(chumPoints.size());
      chumPoints.forEachEntry((key, chumData) -> {
         bb.putLong(key);
         bb.putInt(chumData.maxForceTime);
         return true;
      });
   }

   public void receiveFishingData(ByteBufferReader bb) {
      this.seed = bb.getInt();
      this.trashSeed = bb.getInt();
      noiseFishPointDisabler.clear();
      chumPoints.clear();
      pzoptForgetNoise(); // pzopt
      int size = bb.getInt();

      for (int i = 0; i < size; i++) {
         noiseFishPointDisabler.put(bb.getLong(), 0);
      }

      size = bb.getInt();

      for (int i = 0; i < size; i++) {
         chumPoints.put(bb.getLong(), new FishSchoolManager.ChumData(bb.getInt(), 0));
      }
   }

   public void load() {
      File file = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("fishingData.bin"));

      try (
         FileInputStream fis = new FileInputStream(file);
         BufferedInputStream bis = new BufferedInputStream(fis);
      ) {
         synchronized (SliceY.SliceBufferLock) {
            ByteBuffer bb = SliceY.SliceBuffer;
            bb.clear();
            int numBytes = bis.read(bb.array());
            bb.limit(numBytes);
            this.seed = bb.getInt();
            this.trashSeed = bb.getInt();
            int size = bb.getInt();

            for (int i = 0; i < size; i++) {
               chumPoints.put(bb.getLong(), new FishSchoolManager.ChumData(bb.getInt(), bb.getInt()));
            }
         }
      } catch (FileNotFoundException var15) {
      } catch (Throwable t) {
         ExceptionLogger.logException(t);
      }
   }

   public void save() {
      if (!Core.getInstance().isNoSave()) {
         File file = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("fishingData.bin"));

         try (
            FileOutputStream fos = new FileOutputStream(file);
            BufferedOutputStream bos = new BufferedOutputStream(fos);
         ) {
            synchronized (SliceY.SliceBufferLock) {
               ByteBuffer bb = SliceY.SliceBuffer;
               bb.clear();
               bb.putInt(this.seed);
               bb.putInt(this.trashSeed);
               bb.putInt(chumPoints.size());
               chumPoints.forEachEntry((key, chumData) -> {
                  bb.putLong(key);
                  bb.putInt(chumData.maxForceTime);
                  bb.putInt(chumData.endTime);
                  return true;
               });
               bos.write(bb.array(), 0, bb.position());
            }
         } catch (Throwable t) {
            ExceptionLogger.logException(t);
         }
      }
   }

   private void drawDebugFishingZones(int x, int y) {
      long coordsHash = this.coordsToHash(x, y);
      if (this.isFishPoint(x, y)) {
         if (this.getNumberOfFishInPoint(x, y) > 0 && !noiseFishPointDisabler.containsKey(coordsHash)) {
            LineDrawer.DrawIsoCircle(x, y, 0.0F, this.getFishPointRadius(x, y) + 1.0F, 16, 1.0F, 0.0F, 0.0F, 1.0F);
         } else {
            LineDrawer.DrawIsoCircle(x, y, 0.0F, this.getFishPointRadius(x, y) + 1.0F, 16, 1.0F, 0.6F, 0.0F, 0.6F);
         }
      }

      if (chumPoints.get(coordsHash) != null) {
         if (noiseFishPointDisabler.containsKey(coordsHash)) {
            LineDrawer.DrawIsoCircle(x, y, 0.0F, 1.6F, 16, 1.0F, 1.0F, 0.0F, 0.6F);
         } else {
            LineDrawer.DrawIsoCircle(x, y, 0.0F, 1.6F, 16, 0.4F, 0.25F, 0.15F, 1.0F);
         }
      }
   }

   public static class ChumData {
      public int maxForceTime;
      public int endTime;

      public ChumData(int maxForceTime, int endTime) {
         this.maxForceTime = maxForceTime;
         this.endTime = endTime;
      }
   }

   public static class ZoneData {
      public Zone zone;

      public ZoneData(Zone zone) {
         this.zone = zone;
      }

      public int getCatchedFishNum() {
         return this.zone == null ? 0 : PZMath.tryParseInt(this.zone.getName(), 0);
      }
   }
}
