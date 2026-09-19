package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime settings for the overrides, read once from pzopt.properties in the
 * game install directory (next to projectzomboid.jar, so scripts/pzopt.sh
 * status can show it) with -Dpzopt.<key> system properties overriding.
 *
 * Keys:
 *   parallel    true/false   kill switch: false forces the stock single-threaded pass (default true)
 *   workers     int          recalc pool width; clamped to [1, availableProcessors - 1] (default: min(4, cores - 1))
 *   instrument  true/false   record per-chunk timings and frame times to pzopt-*.out (default false)
 *   wake        true/false   wake the streamer thread on enqueue instead of the stock 140 ms polls (default true)
 *   dev         true/false   development assertions, e.g. game-thread-only code reached from a worker (default false)
 *   translucentCache true/false  reuse prepared translucent render lists (default false)
 *   hotsaveIntervalSec int   (default 30) minimum seconds between the "hot saves" of the ancillary systems (meta grid, game time,
 *                            world map, entities) that ChunkSaveWorker runs on the game thread whenever its chunk
 *                            save queue drains; 0 = stock (every drain, i.e. every chunk row while moving)
 *   persistentVbo   true/false  sprite ring buffers use persistently mapped buffer storage instead of an orphaning
 *                            glBufferData + glMapBufferRange per 64 KB batch (default true)
 *   treesInChunkTexture true/false  static trees bake into the chunk textures; only translucent/fading trees are
 *                            drawn every frame (default true; false = stock: every tree every frame)
 *   windowsInChunkTexture true/false  windows and glass doors bake like walls instead of being drawn every frame
 *                            (default true)
 *   translucentTilesInChunkTexture true/false  tiles flagged Translucent in tileGeometry.txt (fences, railings, wall
 *                            decorations, overlays, crops: 16k definitions) bake instead of being drawn every frame
 *                            (default true)
 *   bakeBudget      int          chunk-level textures (re)baked per frame, the rest deferred to the next frame (default 8; 0 = stock, unlimited)
 *   lightingBudget  int          chunks whose square light info is refreshed per frame, the rest continue next frame (default 8; 0 = stock)
 *   lightingRebakeMs int         a chunk texture dirtied only by a lighting change is not re-baked more often than this (0 = stock)
 *   cutawayFast     true/false   replay stored occluder masks for clean chunk levels instead of re-testing every square (default true)
 *   cutawayRadius   int          cutaway wall visits only consider chunks within this many chunks of the camera (0 = all on screen)
 *   gridStackInterval int        frames between buildings-in-front scans while the camera square and facing are unchanged (0 = every frame)
 *
 * Game load (docs/plan-game-load.md):
 *   fileThreads     int          worker threads of the game's async file system (texture decode, model and animation
 *                            import, depth maps); stock is 2 on <= 4 cores, else 4 (default: max(4, cores / 2): with cores - 2
 *                            the game's own 8 meta-grid loader threads ran 2.5x slower and the load was 0.65 s longer, load-s3 vs load-s3f8)
 *   fileInflight    int          file tasks handed to those threads at once (stock 16; default 4 * fileThreads)
 *   textureBufferMb int          decoded-texture bytes that may wait for the render thread before the decoders pause
 *                            (stock 50; default 50: 256 MB let ~200 MB of uploads pile up on the render thread and
 *                            gave a 5 s frame a few seconds into the world, run load-s1-155507)
 *   parallelDepthMaps true/false  the 218 depth-map tilesets decode concurrently instead of one at a time under
 *                            one lock (default true)
 *   loaderCpuFixes  true/false   algorithmic fixes on the loader thread with identical results: MapCollisionData.init
 *                            resolves lot headers once per cell, IsoMetaGrid.checkVehiclesZones dedupes with a hash
 *                            set (default true)
 *   loadWorkers     int          recalc pool width while a world is loading (GameLoadingState.loader alive): the 361
 *                            chunks of the initial chunk map recalc on this many threads, then the pool shrinks back
 *                            to `workers` (default max(workers, cores / 2); clamped like workers; cores - 2 tripled the per-chunk
 *                            recalc time through contention and gained nothing, load-s3)
 */
public final class Config {
   private static final Properties props = load();
   public static final boolean PARALLEL = bool("parallel", true);
   public static final int WORKERS = clampWorkers(integer("workers", Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
   public static final boolean INSTRUMENT = bool("instrument", false);
   public static final boolean WAKE = bool("wake", true);
   public static final boolean DEV = bool("dev", false);
   public static final boolean TRANSLUCENT_CACHE = bool("translucentCache", false);
   public static final int HOTSAVE_INTERVAL_SEC = integer("hotsaveIntervalSec", 30);
   public static final boolean PERSISTENT_VBO = bool("persistentVbo", true);
   public static final boolean TREES_IN_CHUNK_TEXTURE = bool("treesInChunkTexture", true);
   public static final boolean WINDOWS_IN_CHUNK_TEXTURE = bool("windowsInChunkTexture", true);
   public static final boolean TRANSLUCENT_TILES_IN_CHUNK_TEXTURE = bool("translucentTilesInChunkTexture", true);
   public static final int BAKE_BUDGET = integer("bakeBudget", 8);
   public static final int LIGHTING_BUDGET = integer("lightingBudget", 8);
   public static final int LIGHTING_REBAKE_MS = integer("lightingRebakeMs", 0);
   public static final boolean CUTAWAY_FAST = bool("cutawayFast", true);
   public static final int CUTAWAY_RADIUS = integer("cutawayRadius", 0);
   public static final int GRID_STACK_INTERVAL = integer("gridStackInterval", 0);
   public static final int FILE_THREADS = Math.max(1, integer("fileThreads", Math.max(4, Runtime.getRuntime().availableProcessors() / 2)));
   public static final int FILE_INFLIGHT = Math.max(1, integer("fileInflight", 4 * FILE_THREADS));
   public static final int TEXTURE_BUFFER_MB = Math.max(1, integer("textureBufferMb", 50));
   public static final boolean PARALLEL_DEPTH_MAPS = bool("parallelDepthMaps", true);
   public static final boolean LOADER_CPU_FIXES = bool("loaderCpuFixes", true);
   public static final int LOAD_WORKERS = clampWorkers(integer("loadWorkers", Math.max(WORKERS, Runtime.getRuntime().availableProcessors() / 2)));

   private Config() {
   }

   private static Properties load() {
      Properties p = new Properties();
      File f = new File("pzopt.properties"); // cwd is the install dir when launched by ProjectZomboid64.exe
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (Exception e) {
            Log.warn("could not read " + f.getAbsolutePath() + ": " + e);
         }
      }
      return p;
   }

   private static String raw(String key) {
      String v = System.getProperty("pzopt." + key);
      return v != null ? v : props.getProperty(key);
   }

   private static boolean bool(String key, boolean def) {
      String v = raw(key);
      return v == null ? def : Boolean.parseBoolean(v.trim());
   }

   private static int integer(String key, int def) {
      String v = raw(key);
      if (v == null) {
         return def;
      }
      try {
         return Integer.parseInt(v.trim());
      } catch (NumberFormatException e) {
         Log.warn("bad integer for " + key + ": " + v + "; using " + def);
         return def;
      }
   }

   /** At least 1, and never the full processor count: the render thread keeps one core. */
   static int clampWorkers(int requested) {
      int max = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
      return Math.max(1, Math.min(requested, max));
   }

   /** Effective pool width: 1 when parallelism is switched off or the build guard tripped. */
   public static int effectiveWorkers() {
      return PARALLEL && Overrides.enabled() ? WORKERS : 1;
   }

   /** Wake-on-enqueue is also off when the build guard tripped, so a mismatched build is fully stock. */
   public static boolean effectiveWake() {
      return WAKE && Overrides.enabled();
   }

   public static String describe() {
      return "parallel=" + PARALLEL + " workers=" + WORKERS + " (effective " + effectiveWorkers() + ", cores "
            + Runtime.getRuntime().availableProcessors() + ") wake=" + WAKE + " (effective " + effectiveWake() + ") instrument=" + INSTRUMENT + " dev=" + DEV
            + " translucentCache=" + TRANSLUCENT_CACHE + " hotsaveIntervalSec=" + HOTSAVE_INTERVAL_SEC + " persistentVbo=" + PERSISTENT_VBO + " treesInChunkTexture=" + TREES_IN_CHUNK_TEXTURE + " windowsInChunkTexture=" + WINDOWS_IN_CHUNK_TEXTURE + " translucentTilesInChunkTexture=" + TRANSLUCENT_TILES_IN_CHUNK_TEXTURE + " bakeBudget=" + BAKE_BUDGET + " lightingBudget=" + LIGHTING_BUDGET + " lightingRebakeMs=" + LIGHTING_REBAKE_MS + " cutawayFast=" + CUTAWAY_FAST + " cutawayRadius=" + CUTAWAY_RADIUS + " gridStackInterval=" + GRID_STACK_INTERVAL
            + " fileThreads=" + FILE_THREADS + " fileInflight=" + FILE_INFLIGHT + " textureBufferMb=" + TEXTURE_BUFFER_MB + " parallelDepthMaps=" + PARALLEL_DEPTH_MAPS + " loaderCpuFixes=" + LOADER_CPU_FIXES + " loadWorkers=" + LOAD_WORKERS;
   }
}
