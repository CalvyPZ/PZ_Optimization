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
            + " translucentCache=" + TRANSLUCENT_CACHE + " hotsaveIntervalSec=" + HOTSAVE_INTERVAL_SEC + " persistentVbo=" + PERSISTENT_VBO + " treesInChunkTexture=" + TREES_IN_CHUNK_TEXTURE + " windowsInChunkTexture=" + WINDOWS_IN_CHUNK_TEXTURE + " translucentTilesInChunkTexture=" + TRANSLUCENT_TILES_IN_CHUNK_TEXTURE + " bakeBudget=" + BAKE_BUDGET + " lightingBudget=" + LIGHTING_BUDGET + " lightingRebakeMs=" + LIGHTING_REBAKE_MS + " cutawayFast=" + CUTAWAY_FAST + " cutawayRadius=" + CUTAWAY_RADIUS + " gridStackInterval=" + GRID_STACK_INTERVAL;
   }
}
