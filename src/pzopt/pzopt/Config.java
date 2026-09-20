package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime settings for the overrides, read once from pzopt.properties in the
 * game install directory (next to projectzomboid.jar, so scripts/pzopt.sh
 * status can show it) with -Dpzopt.<key> system properties overriding. Below both sits the
 * player's Zomboid/pzopt/options.ini, written by the Options > Optimizations tab
 * (pzopt.UserOptions); every key is exposed there (booleans as tick boxes, ints as combos) and
 * takes effect on the next launch. A key set in pzopt.properties or -D is shown pinned in the tab.
 *
 * Keys:
 *   enabled     true/false   master switch: false makes every override take its stock path, exactly as a build
 *                            mismatch does (Overrides.enabled() is false); the other keys are then ignored. The
 *                            "Disable all (stock)" / "Enable all" buttons of the Optimizations tab set it (default true)
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
 *   treeBakeDirect true/false  bake trees through IsoTree.render without a FBORenderTrees batch (the batch drops JUMBO trees)
 *   devRedrawFrame N          dev: force a full redraw of on-screen chunk levels N frames after the first render
 *                            drawn every frame (default true; false = stock: every tree every frame)
 *   windowsInChunkTexture true/false  windows and glass doors bake like walls instead of being drawn every frame
 *                            (default true)
 *   translucentTilesInChunkTexture true/false  tiles flagged Translucent in tileGeometry.txt (fences, railings, wall
 *                            decorations, overlays, crops: 16k definitions) bake instead of being drawn every frame
 *                            (default true)
 *   bakeBudget      int          chunk-level textures (re)baked per frame, the rest deferred to the next frame (default 8; 0 = stock, unlimited)
 *   lightingBudget  int          chunks whose square light info is refreshed per frame, the rest continue next frame (default 8; 0 = stock)
 *   lightingRebakeMs int         a chunk texture dirtied only by a lighting change is not re-baked more often than this (0 = stock)
 *   rebakeBudget    int          re-bakes per frame of on-screen chunk textures dirtied only by lighting, redraw or cutaways; past it the previous image stays for up to rebakeMaxFrames frames (default 4; 0 = every re-bake lands the same frame)
 *   rebakeMaxFrames int          longest hold for such a re-bake (default 3)
 *   lightSwitchCheckFrames int   frames a light switch reuses its "has electricity around" answer (default 15; 0 = every frame, stock)
 *   cutawayFast     true/false   replay stored occluder masks for clean chunk levels instead of re-testing every square (default true)
 *   cutawayRadius   int          cutaway wall visits only consider chunks within this many chunks of the camera (0 = all on screen)
 *   gridStackInterval int        frames between buildings-in-front scans while the camera square and facing are unchanged (0 = every frame)
 *   weatherMaskIdleSkip true/false skip the per-frame weather-mask tile scan and mask FBO draw while the player is outdoors and no cloud/fog/rain layer is active (default true)
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
 *                            set, IsoMetaCell.getChunk memoizes the lot header per cell, BuildingRoomsEditor
 *                            .checkBuildingAndRoomIDs indexes rooms once per cell (default true)
 *   scriptParserFast true/false  ScriptParser.stripComments in one linear pass and parseTokens without re-substringing
 *                            (identical output, tests/pzopt/ScriptTextTest; the stock passes cost 1.8 s at boot) (default true)
 *   earlyModels     true/false   ModelManager.create (models + the animation queue) runs right after the scripts load
 *                            instead of after the Lua load, giving the boot pump ~2 s more to import animations (default true)
 *   luaPrecompile   true/false   every Lua file (game, mods, map objects.lua) compiles on a pool during boot; the
 *                            game's LuaCompiler.loadis takes the prototype from that cache (default true)
 *   preloadAnimSets true/false   the player/zombie animation-set XML trees parse on a boot thread (1.1 s of the
 *                            loader thread otherwise) (default true)
 *   animClipCache   true/false   imported animation clips are written to <cache>/pzopt/anims/ after a stock import and
 *                            read from there on later boots instead of parsing the .X files with jassimp (default true)
 *   packIndex       true/false   version-0 texture packs keep their page end offsets in <cache>/pzopt/packs/*.idx so the
 *                            reader seeks instead of scanning 526 MB byte by byte at boot (default true)
 *   itemParamSwitch true/false   Item.DoParam dispatches through a switch on the lower-cased key instead of a chain of
 *                            361 equalsIgnoreCase tests per parameter (0.9 s of boot) (default true)
 *   dumpItems       true/false   after the scripts load, write every item script's fields to Zomboid/pzopt-items.out
 *                            (reflection) so two runs can be diffed (default false)
 *   bootPump        true/false   a thread pumps the async file system every 3 ms during GameWindow.init, so the queued
 *                            texture pages and animations decode during boot instead of after the main menu appears
 *                            (default true)
 *   bootFileThreads int          file pool width while the boot pump runs (default cores - 6: with cores - 2 the 16 cores
 *                            saturated and the main thread's Lua load ran 1.6x slower); shrinks to fileThreads at the load
 *   noLoadFade      true/false   GameLoadingState.exit does not fade the loading screen to black (350 ms of sleeps) before
 *                            the world's own 2 s fade-in (default true)
 *   fmodAsync       true/false   FMODManager.init (system + 12 banks, ~1.6 s) runs on a thread from the top of
 *                            GameWindow.mainThreadInit and is joined before the scripts load; the sound managers
 *                            (whose FMOD global parameters need the banks) are built at the join (default true)
 *   loadWorkers     int          recalc pool width while a world is loading (GameLoadingState.loader alive): the 361
 *                            chunks of the initial chunk map recalc on this many threads, then the pool shrinks back
 *                            to `workers` (default max(workers, cores / 2); clamped like workers; cores - 2 tripled the per-chunk
 *                            recalc time through contention and gained nothing, load-s3)
 *   shaderCache     true/false   Model.CreateShader takes a shader an earlier model already created from pzopt.ModelShaders
 *                            instead of posting to the render thread and waiting one render step per model; the 73
 *                            animal models of AnimalDefinitions were 16.5 s of the load on a laptop whose loading-screen
 *                            render step is ~220 ms (GitHub issue #1) (default true)
 *   mipmapArrays    true/false   ImageData builds texture mipmaps and premultiplies alpha row by row on byte[] copies
 *                            (pzopt.MipMaps) instead of the stock per-byte absolute reads/writes of the malloc'd
 *                            direct buffers; same pixels (tests/pzopt/MipMapsTest), same speed. Written for GitHub
 *                            issue #2, whose crash turned out to be the laptop (truncated stack address on a plain
 *                            spill reload), so this is a simplification, not the fix (default true)
 */
public final class Config {
   /** Every key read at init: key -> {effective value, default}, in declaration order (for the options tab). */
   private static final java.util.LinkedHashMap<String, String[]> REGISTRY = new java.util.LinkedHashMap<>();
   private static final Properties props = load();
   /** The player's Options > Optimizations choices (Zomboid/pzopt/options.ini), below props and -D. */
   private static final Properties userProps = UserOptions.load();
   /** Master switch, read by {@link Overrides#enabled()}; false = stock behaviour everywhere. */
   public static final boolean ENABLED = bool("enabled", true);
   public static final boolean PARALLEL = bool("parallel", true);
   public static final int WORKERS = clampWorkers(integer("workers", Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
   public static final boolean INSTRUMENT = bool("instrument", false);
   public static final boolean WAKE = bool("wake", true);
   public static final boolean DEV = bool("dev", false);
   public static final boolean TRANSLUCENT_CACHE = bool("translucentCache", false);
   public static final int HOTSAVE_INTERVAL_SEC = integer("hotsaveIntervalSec", 30);
   public static final boolean HOTSAVE_STAGED = bool("hotsaveStaged", false);
   public static final boolean PERSISTENT_VBO = bool("persistentVbo", true);
   public static final boolean PERSISTENT_VBO_FRAME_FENCE = bool("persistentVboFrameFence", false); // diagnostic: also wait for the fence of the frame the buffer was drawn in (did not affect the black squares, 2026-09-20)
   public static final int PERSISTENT_VBO_FRAME_LAG = integer("persistentVboFrameLag", 0); // diagnostic: wait for the fence of the unmap frame + N
   public static final int PERSISTENT_VBO_DELAY_US = integer("persistentVboDelayUs", 0); // diagnostic: CPU-only delay per persistent map (timing vs GPU race)
   public static final int PERSISTENT_VBO_SLOTS = integer("persistentVboSlots", 1); // storage slots per sprite buffer object (reuse distance x K)
   public static final boolean PERSISTENT_VBO_COHERENT = bool("persistentVboCoherent", true); // false: MAP_FLUSH_EXPLICIT + glFlushMappedBufferRange at unmap
   public static final boolean PERSISTENT_VBO_FINISH = bool("persistentVboFinish", false); // diagnostic: glFinish before every persistent map (GPU read race check)
   public static final boolean TREES_IN_CHUNK_TEXTURE = bool("treesInChunkTexture", true);
   public static final boolean WINDOWS_IN_CHUNK_TEXTURE = bool("windowsInChunkTexture", true);
   public static final boolean TRANSLUCENT_TILES_IN_CHUNK_TEXTURE = bool("translucentTilesInChunkTexture", true);
   public static final int BAKE_BUDGET = integer("bakeBudget", 8);
   public static final int LIGHTING_BUDGET = integer("lightingBudget", 8);
   public static final int LIGHTING_REBAKE_MS = integer("lightingRebakeMs", 250);
   public static final int REBAKE_BUDGET = integer("rebakeBudget", 4);
   public static final int REBAKE_MAX_FRAMES = Math.max(1, integer("rebakeMaxFrames", 3));
   public static final int LIGHT_SWITCH_CHECK_FRAMES = integer("lightSwitchCheckFrames", 15);
   public static final int DEV_REDRAW_FRAME = integer("devRedrawFrame", 0);
   public static final boolean GPU_SECTIONS = bool("gpuSections", false); // measurement only: GPU time per frame section in the log
   public static final boolean DEV_WEATHER_FX_OFF = bool("devWeatherFxOff", false); // measurement only: skip the weather FX pass
   public static final boolean TREE_BAKE_DIRECT = bool("treeBakeDirect", true);
   public static final boolean CUTAWAY_FAST = bool("cutawayFast", true);
   public static final int CUTAWAY_RADIUS = integer("cutawayRadius", 6);
   public static final int GRID_STACK_INTERVAL = integer("gridStackInterval", 8);
   public static final boolean WEATHER_MASK_IDLE_SKIP = bool("weatherMaskIdleSkip", true);
   public static final int CHUNK_HANDOFF_DIVISOR = integer("chunkHandoffDivisor", 8);
   public static final int WEATHER_FX_SCALE_PCT = integer("weatherFxScalePct", 100);
   public static final boolean CUTAWAY_VISIT_PREFILTER = bool("cutawayVisitPrefilter", true);
   public static final boolean CUTAWAY_INVALIDATE_CHANGED = bool("cutawayInvalidateChanged", true);
   public static final boolean SOUND_ZONE_CACHE = bool("soundZoneCache", true);
   public static final boolean OCCLUSION_SKIP_LIGHTING_ONLY = bool("occlusionSkipLightingOnly", true);
   public static final boolean LIGHT_INFO_CHUNK_GATE = bool("lightInfoChunkGate", true);
   public static final boolean LIGHT_INFO_ONCE_PER_FRAME = bool("lightInfoOncePerFrame", true);
   public static final int FILE_THREADS = Math.max(1, integer("fileThreads", Math.max(4, Runtime.getRuntime().availableProcessors() / 2)));
   public static final int FILE_INFLIGHT = Math.max(1, integer("fileInflight", 4 * FILE_THREADS));
   public static final int TEXTURE_BUFFER_MB = Math.max(1, integer("textureBufferMb", 50));
   public static final boolean PARALLEL_DEPTH_MAPS = bool("parallelDepthMaps", true);
   public static final boolean LOADER_CPU_FIXES = bool("loaderCpuFixes", true);
   public static final boolean SCRIPT_PARSER_FAST = bool("scriptParserFast", true);
   public static final boolean FMOD_ASYNC = bool("fmodAsync", true);
   public static final boolean NO_LOAD_FADE = bool("noLoadFade", true);
   /** new game: show click-to-start as soon as loading is done instead of after the 33 s intro text. */
   public static final boolean NO_INTRO_WAIT = bool("noIntroWait", true);
   public static final boolean BOOT_PUMP = bool("bootPump", true);
   public static final boolean EARLY_MODELS = bool("earlyModels", true);
   public static final boolean LUA_PRECOMPILE = bool("luaPrecompile", true);
   public static final boolean PRELOAD_ANIM_SETS = bool("preloadAnimSets", true);
   public static final boolean ANIM_CLIP_CACHE = bool("animClipCache", true);
   /** auto = honour options.ini (frameRate / uncappedFPS); true / false force the frame cap off / on for a run. */
   public static final String UNCAPPED_FPS = string("uncappedFps", "auto");
   public static final boolean PACK_INDEX = bool("packIndex", true);
   public static final boolean ITEM_PARAM_SWITCH = bool("itemParamSwitch", true);
   public static final boolean DUMP_ITEMS = bool("dumpItems", false);
   public static final int BOOT_FILE_THREADS = Math.max(1, integer("bootFileThreads", Math.max(4, Runtime.getRuntime().availableProcessors() - 6)));
   public static final int LOAD_WORKERS = clampWorkers(integer("loadWorkers", Math.max(WORKERS, Runtime.getRuntime().availableProcessors() / 2)));
   public static final boolean SHADER_CACHE = bool("shaderCache", true);
   public static final boolean MIPMAP_ARRAYS = bool("mipmapArrays", true);
   public static final boolean OVERLAY = bool("overlay", false);
   public static final boolean OVERLAY_LOG = bool("overlayLog", false);
   public static final int OVERLAY_KEY = integer("overlayKey", 67); // LWJGL 2 code, 67 = F9; used when the Lua binding is absent
   public static final String OVERLAY_FONT = string("overlayFont", "CodeMedium");
   public static final String OVERLAY_CORNER = string("overlayCorner", "tl");
   public static final boolean OVERLAY_FPS_COLOR = bool("overlayFpsColor", true); // colour the fps number (see Overlay.fpsColor)
   public static final boolean OVERLAY_FPS_FOLLOW_CAP = bool("overlayFpsFollowCap", true); // thresholds are % of the cap when one is set; else the fixed fps ones
   public static final int OVERLAY_FPS_CAP_BLUE_PCT = integer("overlayFpsCapBluePct", 98); // "at the cap": at or above this % of it
   public static final int OVERLAY_FPS_CAP_GREEN_PCT = integer("overlayFpsCapGreenPct", 90);
   public static final int OVERLAY_FPS_CAP_YELLOW_PCT = integer("overlayFpsCapYellowPct", 50); // below: red
   public static final int OVERLAY_FPS_BLUE_ABOVE = integer("overlayFpsBlueAbove", 300); // uncapped / follow-cap off: fixed fps thresholds
   public static final int OVERLAY_FPS_GREEN_ABOVE = integer("overlayFpsGreenAbove", 150);
   public static final int OVERLAY_FPS_YELLOW_ABOVE = integer("overlayFpsYellowAbove", 100); // below: red
   public static final String OVERLAY_FPS_COLOR_BLUE = string("overlayFpsColorBlue", "blue"); // a name Overlay.color knows or RRGGBB hex
   public static final String OVERLAY_FPS_COLOR_GREEN = string("overlayFpsColorGreen", "green");
   public static final String OVERLAY_FPS_COLOR_YELLOW = string("overlayFpsColorYellow", "yellow");
   public static final String OVERLAY_FPS_COLOR_RED = string("overlayFpsColorRed", "red");

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

   /** -Dpzopt.<key>, then the install dir's pzopt.properties, then the player's options.ini. */
   private static String raw(String key) {
      String v = System.getProperty("pzopt." + key);
      if (v == null) {
         v = props.getProperty(key);
      }
      return v != null ? v : userProps.getProperty(key);
   }

   private static <T> T register(String key, T effective, T def) {
      REGISTRY.put(key, new String[] {String.valueOf(effective), String.valueOf(def)});
      return effective;
   }

   private static boolean bool(String key, boolean def) {
      String v = raw(key);
      return register(key, v == null ? def : Boolean.parseBoolean(v.trim()), def);
   }

   private static String string(String key, String def) {
      String v = raw(key);
      return register(key, v == null ? def : v.trim(), def);
   }

   private static int integer(String key, int def) {
      String v = raw(key);
      if (v == null) {
         return register(key, def, def);
      }
      try {
         return register(key, Integer.parseInt(v.trim()), def);
      } catch (NumberFormatException e) {
         Log.warn("bad integer for " + key + ": " + v + "; using " + def);
         return register(key, def, def);
      }
   }

   // --- options tab (Options > Optimizations; see UserOptions) --------------------------------

   /** Is this one of the keys read at init? */
   public static boolean knows(String key) {
      return key != null && REGISTRY.containsKey(key);
   }

   /** The value in force since boot (before the clamps some keys apply), or null for an unknown key. */
   public static String value(String key) {
      String[] r = key == null ? null : REGISTRY.get(key);
      return r == null ? null : r[0];
   }

   /** The build's default on this machine, or null for an unknown key. */
   public static String defaultValue(String key) {
      String[] r = key == null ? null : REGISTRY.get(key);
      return r == null ? null : r[1];
   }

   /**
    * What pins the key above the player's options.ini: "-Dpzopt.<key>" or "pzopt.properties",
    * or null when the menu choice is what counts.
    */
   public static String pinnedBy(String key) {
      if (key == null) {
         return null;
      }
      if (System.getProperty("pzopt." + key) != null) {
         return "-Dpzopt." + key;
      }
      return props.getProperty(key) != null ? "pzopt.properties" : null;
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
            + " translucentCache=" + TRANSLUCENT_CACHE + " hotsaveIntervalSec=" + HOTSAVE_INTERVAL_SEC + " persistentVbo=" + PERSISTENT_VBO + " treesInChunkTexture=" + TREES_IN_CHUNK_TEXTURE + " windowsInChunkTexture=" + WINDOWS_IN_CHUNK_TEXTURE + " translucentTilesInChunkTexture=" + TRANSLUCENT_TILES_IN_CHUNK_TEXTURE + " bakeBudget=" + BAKE_BUDGET + " lightingBudget=" + LIGHTING_BUDGET + " lightingRebakeMs=" + LIGHTING_REBAKE_MS + " rebakeBudget=" + REBAKE_BUDGET + " rebakeMaxFrames=" + REBAKE_MAX_FRAMES + " lightSwitchCheckFrames=" + LIGHT_SWITCH_CHECK_FRAMES + " cutawayFast=" + CUTAWAY_FAST + " cutawayRadius=" + CUTAWAY_RADIUS + " gridStackInterval=" + GRID_STACK_INTERVAL + " weatherMaskIdleSkip=" + WEATHER_MASK_IDLE_SKIP
            + " fileThreads=" + FILE_THREADS + " fileInflight=" + FILE_INFLIGHT + " textureBufferMb=" + TEXTURE_BUFFER_MB + " parallelDepthMaps=" + PARALLEL_DEPTH_MAPS + " loaderCpuFixes=" + LOADER_CPU_FIXES + " loadWorkers=" + LOAD_WORKERS + " scriptParserFast=" + SCRIPT_PARSER_FAST + " fmodAsync=" + FMOD_ASYNC + " noLoadFade=" + NO_LOAD_FADE + " noIntroWait=" + NO_INTRO_WAIT + " bootPump=" + BOOT_PUMP + " earlyModels=" + EARLY_MODELS + " luaPrecompile=" + LUA_PRECOMPILE + " preloadAnimSets=" + PRELOAD_ANIM_SETS + " animClipCache=" + ANIM_CLIP_CACHE + " packIndex=" + PACK_INDEX + " itemParamSwitch=" + ITEM_PARAM_SWITCH + " bootFileThreads=" + BOOT_FILE_THREADS + " shaderCache=" + SHADER_CACHE + " mipmapArrays=" + MIPMAP_ARRAYS;
   }
}
