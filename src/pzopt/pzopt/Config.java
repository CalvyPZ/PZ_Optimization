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
 *   workers     int          recalc pool width; clamped to [1, availableProcessors - 1] (default: min(4, cores - 1), 1 on
 *                            4 cores or fewer: there the three workers took the game thread's core, Dell i5-6300HQ 2026-09-21)
 *   instrument  true/false   record per-chunk timings and frame times to pzopt-*.out (default false)
 *   wake        true/false   wake the streamer thread on enqueue instead of the stock 140 ms polls (default true)
 *   dev         true/false   development assertions, e.g. game-thread-only code reached from a worker (default false)
 *   luaChecksumExempt true/false  the pzopt Lua files (media/lua/{shared,client}/pzopt/) are left out of the multiplayer
 *                            Lua checksum a client sends to the server, like SandboxVars.lua is in stock: a server without
 *                            them otherwise refuses the join with "File doesn't exist on the server" (default true; not
 *                            tied to `enabled`, the files are on disk either way)
 *   updateCheck true/false   the main menu asks the GitHub releases once per boot whether a newer build for this game
 *                            revision exists and offers an "Update PZ Optimization" menu item that downloads and
 *                            installs it (pzopt.Updater; default true; never in harness runs)
 *   devUpdateOffer true/false  dev: offer the newest release for this revision whatever this build is, to see the
 *                            menu item, the dialog and the install (default false)
 *   translucentCache true/false  reuse prepared translucent render lists (default false)
 *   hotsaveIntervalSec int   (default 30) minimum seconds between the "hot saves" of the ancillary systems (meta grid, game time,
 *                            world map, entities) that ChunkSaveWorker runs on the game thread whenever its chunk
 *                            save queue drains; 0 = stock (every drain, i.e. every chunk row while moving)
 *   persistentVbo   true/false  sprite ring buffers use persistently mapped buffer storage instead of an orphaning
 *                            glBufferData + glMapBufferRange per 64 KB batch (default true)
 *   treesInChunkTexture true/false  static trees bake into the chunk textures; only translucent/fading trees are
 *   treeBakeMaxChunksPerSec int  above this many chunk hand-offs per second (pzopt.ChunkRate: walking ~9, 60 km/h ~32,
 *                            120 km/h ~72) trees are not baked into new chunk textures but drawn per frame, as with
 *                            treesInChunkTexture=false; textures already baked keep their trees until they re-bake.
 *                            A texture that lives a second or two while driving costs more to bake its trees into
 *                            (own texture plus neighbour copies) than drawing them per frame for that long: Dell
 *                            i5-6300HQ 2026-09-21, 120 km/h 28.6 -> 42.7 fps; walking is the other way round
 *                            (default 0 = always bake; the low-end profile sets 24)
 *   treeBakeDirect true/false  bake trees through IsoTree.render without a FBORenderTrees batch (the batch drops JUMBO trees)
 *   treeBakePass true/false   baked trees are drawn by the pzopt tree pass (pzopt.TreeBake): into every chunk-level texture
 *                            the sprite overlaps, last in the texture, with a depth that gets nearer with height like the
 *                            wall depth textures (issue #5: crowns clipped at the texture border, strips cut by upper
 *                            walls); false = the MinusFloor loop of the sprite path (default true)
 *   devRedrawFrame N          dev: force a full redraw of on-screen chunk levels N frames after the first render
 *   devCutawayLog true/false  dev: log every change of the buildings-to-collapse list and every orphan-structure
 *                            (carport / pergola roof) hide-show flip with the frame number (first 400 lines)
 *                            drawn every frame (default true; false = stock: every tree every frame)
 *   windowsInChunkTexture true/false  windows and glass doors bake like walls instead of being drawn every frame
 *                            (default true)
 *   translucentTilesInChunkTexture true/false  tiles flagged Translucent in tileGeometry.txt (fences, railings, wall
 *                            decorations, overlays, crops: 16k definitions) bake instead of being drawn every frame
 *                            (default true)
 *   curtainDepthNudgePct int     a curtain hanging in front of a window on its own square draws this many hundredths of
 *                            a tile nearer the camera than its tile geometry says (default 5; 0 = off). The north
 *                            window glass sits 0.017 tile in front of the north curtain in tileGeometry.txt; stock
 *                            draws both per frame in object order with depth writes off, so it never notices, but
 *                            once either bakes the depth test lets the glass through the closed curtain (issue #4)
 *   bakeBudget      int          chunk-level textures (re)baked per frame, the rest deferred to the next frame (default 8; 0 = stock, unlimited)
 *   lightingBudget  int          chunks whose square light info is refreshed per frame, the rest continue next frame (default 8; 0 = stock)
 *   lightingRebakeMs int         a chunk texture dirtied only by a lighting change is not re-baked more often than this (0 = stock)
 *   rebakeBudget    int          re-bakes per frame of on-screen chunk textures dirtied only by lighting, redraw or cutaways; past it the previous image stays for up to rebakeMaxFrames frames (default 4; 0 = every re-bake lands the same frame)
 *   rebakeMaxFrames int          longest hold for such a re-bake (default 3)
 *   lightingRebakeBudget int     lighting-only re-bakes (flag 32 alone: daylight drift, a lightning flash) started per frame
 *                            (default 8) ...
 *   lightingRebakeMaxFrames int  ... and how long one may stay stale (default 30). A lightning strike dirties every
 *                            on-screen texture; with the 3-frame cap of rebakeMaxFrames they all landed in one
 *                            50-90 ms frame, five times per strike (flash on, off, and every 250 ms of the ramp)
 *   lightingStrongDelta int      a square whose light moved by this much (0-255, largest channel, summed since its level was last
 *                            baked) marks the level strong: it skips lightingRebakeMs and the spread and re-bakes at once, like
 *                            stock (default 6; a moving torch changes squares by tens a frame, sky drift by 1 a tick)
 *   vehicleCull     true/false   a zombie's "is a vehicle between me and the player" test skips vehicles whose bounding circle
 *                            misses the segment before the exact box test (default true; 6 % of the game thread in downtown)
 *   animBonesParallel true/false the zombies' animation bone math (keyframe blend, twist bones, model and skin matrices,
 *                            9 % of the game thread on the Louisville horde) runs on worker threads after the object
 *                            postupdate loop instead of inline (default true; pzopt.AnimBatch)
 *   frameThreads    int          worker threads of the per-frame zombie batches (bone math, transition evaluation), clamped
 *                            to cores - 1 (default 8, or animBonesThreads when set; the game thread joins in)
 *   actionEvalParallel true/false the zombies' action-context transitions are evaluated on the frame workers after the
 *                            postupdate loop, applied on the game thread in order (default true; pzopt.ActionEval)
 *   devActionEvalCheck true/false dev: re-evaluate on the game thread at apply time and count disagreements
 *   zombieCullSortFast true/false the per-frame zombie relevance sort computes each score once (default true; same order)
 *   lightingReadParallel true/false the lighting queue's pre-pass drain reads its chunk levels on the frame workers
 *                            (default true; pzopt.LightingBatch)
 *   devLightingReadCheck true/false dev: re-read a sample of squares after a parallel batch and count mismatches
 *   skinTransformsPrecompute true/false the bone worker also computes the skin-transform sets of the models drawn last
 *                            frame, so the render phase finds them ready (default true; needs animBonesParallel)
 *   skinPalettePrecompute true/false the worker stores each skin-transform set as the shader palette too; the draw data
 *                            copies it in one bulk put instead of sixteen per matrix (default true)
 *   shadowPrep      true/false   a zombie's shadow ellipse computed on the bone worker instead of in renderShadow (default true)
 *   boneIndexCache  true/false   bone-name -> index answers cached per skinning data on the animation player (default true)
 *   ecsLookupFast   true/false   the entity-component lookups behind getStateMachine / getActionContext / getVariable are a
 *                            memoised class walk, a lean map probe and a cached field on IsoZombie (default true)
 *   actionConditionFast true/false action-context transition conditions read boolean / int animation variables through
 *                            their typed getters instead of print-and-parse (default true; same outcomes)
 *   charDrawPrep    true/false   the render phase's characters draw: the on-screen zombies' model draw data (model lights,
 *                            the render data of every sub-model, the depth / lights / matrix palette init) is built on the
 *                            game's slot-init executor right after the players are drawn, joined before the sequential
 *                            pass enqueues it in the stock order (pzopt.CharDraw; default true)
 *   zombieAtlasFast true/false   a culled zombie's atlas-sprite draw through a flat copy of its render chain instead of
 *                            the virtual IsoZombie.render -> IsoGameCharacter.render chain (same tests, same writes,
 *                            same sprite; default true)
 *   charDrawThreads int          threads of the pre-pass pool (default 14, clamped to cores - 2)
 *   devSimChecksum  true/false   dev: per-frame hash of every zombie's state after postupdate in Zomboid/pzopt-sim.out
 *   lightingStrongBudget int     how many strong levels re-bake at once in one frame (default 8); the rest are held like
 *                            drift (they still re-bake within lightingRebakeMs / the spread). A beam touches a few levels a
 *                            frame; turning moves the out-of-sight fade across every exterior square, ~every level. 0 = no cap
 *   lightingStrongFrameMs int    a game-thread frame step longer than this halves the strong re-bake budget for the next
 *                            frame (down to 1; grows back by one per frame under 3/4 of it): a slow frame moves the
 *                            out-of-sight fade further, marks more levels strong and bakes more, which is the loop that
 *                            held downtown Louisville at twice the GPU time per frame (default 0 = fixed budget: at 20 it held the strong
 *                            levels of a scene that is steadily slow, 30-45 fps downtown, and the held squares are
 *                            re-marked every pass — stale light instead of a broken loop; keep it for A/Bs)
 *   lightingGlobalDeltaPct int   a per-frame move of the player's global light (colour mods, ambient, night, sky level) past this
 *                            percentage is a global event (lightning flash, fast-forwarded dusk): strong levels keep the spread
 *                            for lightingRebakeMaxFrames frames (default 2; a torch or the vision cone never moves it)
 *   lightingFlush   true/false   chunks the lightingBudget queue still holds are refreshed just before the next lighting pass
 *                            rewrites their dirty bits (default true; false loses them, chunk-sized stale light at 120 km/h)
 *   lightSwitchCheckFrames int   frames a light switch reuses its "has electricity around" answer (default 15; 0 = every frame, stock)
 *   cutawayFast     true/false   replay stored occluder masks for clean chunk levels instead of re-testing every square (default true)
 *   cutawayRadius   int          cutaway wall visits only consider chunks within this many chunks of the camera (0 = all on screen)
 *   gridStackInterval int        frames between buildings-in-front scans while the camera square and facing are unchanged (0 = every frame)
 *   roofHideDebounceFrames int   a carport / pergola roof (orphan structure) is hidden or shown only after the decision has held
 *                            for this many consecutive frames (default 8; 0 = stock, the roof can flip every frame)
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
 *   puddleCache     true/false   FBORenderCell.renderPuddles keeps the packed puddle vertices of each chunk level on the IsoChunk
 *                            and only patches the vertex lights, the camera jiggle and the depth per frame instead of
 *                            re-filtering, re-lighting and re-packing every wet square (pzopt.PuddleCache); 4.5 ms of a
 *                            13 ms thunderstorm frame at max zoom on 5120x2160 (default true)
 *   puddleCacheFrames  N      a cached puddle batch is rebuilt with the stock code after N frames at the latest,
 *                            staggered per chunk; bakes and cutaway changes rebuild it at once (default 60)
 *   puddleEarlyZ    true/false   the puddle shaders (media/shaders/pzopt_puddles_hq|mq|lq) take their depth from the vertex
 *                            instead of writing gl_FragDepth, so the GPU's early depth test drops every wet-ground pixel
 *                            hidden behind a wall, roof or object before the ~200-op puddle shader runs; same colour
 *                            math, same depth values (default true)
 *   rainSplashesFast true/false  rain splash starts are drawn by geometric skipping over the idle squares with a local
 *                            xorshift generator (one draw per splash) instead of one Rand.NextBool through the game's
 *                            CellularAutomatonRNG per idle square of every on-screen chunk level per frame; same
 *                            per-square start probability, timing, sprites and positions (pzopt.RainSplashes)
 *                            (default true)
 *   treeAppend      true/false   tree pass: when a chunk exports trees into a neighbour's texture for the first time
 *                            (a newly loaded chunk next to baked ones while driving), the quads are drawn on top of
 *                            that finished texture (same draw, same depth test, mipmaps regenerated) instead of
 *                            re-baking it; a texture that is dirty, off screen or not composited this frame is
 *                            re-baked as before, and every later change still re-bakes (default true)
 *   puddleVbo       true/false   with puddleCache: every cached puddle batch lives in its own GL buffer on the render
 *                            thread, re-uploaded only when a square's light changed, the camera crossed a chunk edge
 *                            or the batch was rebuilt; the camera jiggle is a translation of the ModelViewProjection.
 *                            Nothing is copied or patched per frame on either thread (pzopt.PuddleVbo); 12 % of a
 *                            thunderstorm frame on the laptop, ~7 ring-buffer uploads per frame on the render thread
 *                            (default true)
 *   rainTiles       true/false   ParticleRectangle renders its particles once at the origin and lists the screen cells; the
 *                            render thread uploads that template once and draws it once per cell with a translated
 *                            ModelViewProjection (pzopt.RainTiles) instead of ~90k per-cell quads through VBORenderer
 *                            (13x7 cells of 1024 rain particles at 5120x2160; 1.7 ms game thread, 6 ms render thread)
 *                            (default true)
 *   fogPass         true/false   heavy fog (ImprovedFog) drawn as one batch into a fog buffer of fogScalePct % of the viewport,
 *                            depth-tested against the scene depth read in place (the offscreen buffer's depth becomes a
 *                            texture), composited once with a depth-aware blend; the rectangle depth comes from the
 *                            vertex (early depth rejection), the noise is sampled with mipmaps, and the game thread
 *                            skips the per-square walk that only fed the row iterator (pzopt.FogPass,
 *                            docs/findings-fog-2026-09-21.md). Stock shades every pixel up to twelve times with a
 *                            gl_FragDepth write and one draw call per row segment: 447 -> 220 fps on the 5120x2160
 *                            120 km/h uncapped route, ~340 with the pass at 25 %. EXPERIMENTAL (2026-09-21): the
 *                            maintainer still sees a slight flicker on power lines in fog while the camera moves that
 *                            the frame captures do not reproduce; false = stock fog, no flicker (default true)
 *   fogDepthCopy    true/false   keep the offscreen buffer's depth a renderbuffer and copy it for the fog pass instead of
 *                            reading it in place (measurement, or a driver that refuses the texture; slower) (default false)
 *   fogScalePct     25..100      the fog buffer size per axis as % of the viewport (100 = full resolution; 50 = a quarter
 *                            of the fog fragment and blend work; below 100 the depth is reduced per block to its nearest
 *                            value and the composite is depth-aware, so thin objects keep their fog; 25 reads the same as
 *                            stock at 1:1 on 5120x2160 and 1920x1080 captures) (default 25)
 *   fogMaskFrames   N            the fog row walk reads per-chunk masks of the squares that take fog (exterior, not in a
 *                            room) instead of touching every square object (~10k per level per frame at max zoom on
 *                            5120x2160); a chunk's masks are refreshed every N frames, staggered per chunk, and the
 *                            row segments found are replayed while the visible diamond is unchanged and fewer than N
 *                            frames old, so a new room or wall reaches the fog within N frames; 0 = the stock
 *                            per-square walk every frame (default 20)
 *   vboBatchKb      4..1536      VBORenderer element buffer in KB (4 = stock). The rain FX add ~100k particle quads a frame
 *                            at 5120x2160 through VBORenderer.addQuad, and the 4 KB stock buffer flushes (glBufferData + draw)
 *                            every 28 quads; the render thread spent 73 % of a thunderstorm frame there (default 1024)
 *   vboFastQuads    true/false   VBORenderer.addQuad writes the four vertices of a textured quad with one position advance
 *                            and no per-vertex isFull/currentRun/position() round trips; same bytes, same indices (default true)
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
   public static final int WORKERS = clampWorkers(integer("workers", defaultWorkers(Runtime.getRuntime().availableProcessors())));
   public static final boolean INSTRUMENT = bool("instrument", false);
   public static final boolean WAKE = bool("wake", true);
   public static final boolean DEV = bool("dev", false);
   public static final boolean LUA_CHECKSUM_EXEMPT = bool("luaChecksumExempt", true); // NetChecksum skips media/lua/*/pzopt/ files: they only exist on clients
   public static final boolean UPDATE_CHECK = bool("updateCheck", true); // main menu: check the GitHub releases for a newer build and offer the update item (pzopt.Updater)
   public static final boolean DEV_UPDATE_OFFER = bool("devUpdateOffer", false); // dev: offer the newest release for this revision whatever this build is (menu item / dialog / install checks)
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
   public static final float CURTAIN_DEPTH_NUDGE = Math.max(0, integer("curtainDepthNudgePct", 5)) / 100.0F; // tiles; issue #4
   public static final int BAKE_BUDGET = integer("bakeBudget", 8);
   public static final int LIGHTING_BUDGET = integer("lightingBudget", 8);
   public static final int LIGHTING_REBAKE_MS = integer("lightingRebakeMs", 250);
   public static final int REBAKE_BUDGET = integer("rebakeBudget", 4);
   public static final int REBAKE_MAX_FRAMES = Math.max(1, integer("rebakeMaxFrames", 3));
   public static final int LIGHTING_REBAKE_BUDGET = Math.max(1, integer("lightingRebakeBudget", 8)); // lighting-only (flag 32) re-bakes started per frame once rebakeBudget applies
   public static final int LIGHTING_REBAKE_MAX_FRAMES = Math.max(1, integer("lightingRebakeMaxFrames", 30)); // longest hold of a lighting-only re-bake
   // zoom smoothness (2026-09-22): chunk-level textures that leave the screen when the camera zooms in are kept while
   // the chunk stays inside the screen rectangle of the widest zoom (high-res ones: of the widest zoom below 0.75)
   // instead of being freed, so zooming back out re-uses them; a kept texture returning to the screen is re-baked under
   // its own per-frame budget and shown as it was meanwhile; a level whose texture at the new scale (the 0.75 crossing)
   // is still to be baked shows its other-scale texture instead of nothing
   public static final boolean ZOOM_RETAIN = bool("zoomRetain", true);
   public static final int ZOOM_REBAKE_BUDGET = Math.max(1, integer("zoomRebakeBudget", 12)); // most chunk-level bakes a zoom change may start per frame (returned textures and, during a flood, new ones); the plan adapts below it
   public static final float ZOOM_FRAME_MS = Math.max(1.0F, integer("zoomFrameMs", 10)); // game-thread frame length above which the per-frame count halves (grows back under 3/4 of it)
   public static final boolean ZOOM_PLACEHOLDER = bool("zoomPlaceholder", true); // other-scale texture while the new scale bakes
   // zoom motion (2026-09-22, pzopt.ZoomEase): a manual zoom change takes zoomEaseMs of wall-clock time along a cubic Bézier
   // (stock: 0.03 per frame and a snap, 8 frames whatever the frame rate); 0 = the stock step
   public static final int ZOOM_EASE_MS = Math.max(0, integer("zoomEaseMs", 300));
   public static final String ZOOM_EASE = string("zoomEase", "0.25,0.1,0.25,1.0"); // Bézier control points x1,y1,x2,y2 (CSS "ease")
   public static final int LIGHTING_STRONG_DELTA = Math.max(1, integer("lightingStrongDelta", 6)); // a square's light moved by this much (0-255, summed since the level's last bake): the level re-bakes now instead of being held (pzopt.LightDirt)
   public static final int LIGHTING_STRONG_BUDGET = Math.max(0, integer("lightingStrongBudget", 8)); // strong levels that re-bake at once per frame; the rest take the holds (0 = unlimited, the 2026-09-21 behaviour: turning marks ~every exterior level strong, 10.8 fps in downtown Louisville)
   public static final float LIGHTING_STRONG_FRAME_MS = Math.max(0, integer("lightingStrongFrameMs", 0)); // game-thread frame step above which the strong re-bake budget halves for the next frame (grows back under 3/4 of it); 0 = fixed budget. Breaks the slow-frame -> more strong marks -> more bakes -> slower frame loop of downtown Louisville (FBORenderCell.pzoptStrongBudget)
   public static final float LIGHTING_GLOBAL_DELTA = Math.max(0, integer("lightingGlobalDeltaPct", 2)) / 100.0F; // a per-frame move of the global light (colour, ambient, night, sky) past this is a flash or dusk: the spread applies for lightingRebakeMaxFrames
   public static final boolean LIGHTING_FLUSH = bool("lightingFlush", true); // drain the lightingBudget queue before a lighting pass rewrites the dirty bits (false = the 2026-09-21 morning behaviour, for A/Bs)
   public static final int LIGHT_SWITCH_CHECK_FRAMES = integer("lightSwitchCheckFrames", 15);
   public static final int DEV_REDRAW_FRAME = integer("devRedrawFrame", 0);
   public static final boolean DEV_WORLD_SOUND_TIMING = bool("devWorldSoundTiming", false); // dev: per-section nanoTime totals of WorldSoundManager.addSound (pzoptTiming())
   public static final boolean DEV_CUTAWAY_LOG = bool("devCutawayLog", false); // dev: roof hide/show decisions per frame in the log
   public static final boolean GPU_SECTIONS = bool("gpuSections", false); // measurement only: GPU time per frame section in the log
   public static final boolean DEV_WEATHER_FX_OFF = bool("devWeatherFxOff", false); // measurement only: skip the weather FX pass
   public static final boolean TREE_BAKE_DIRECT = bool("treeBakeDirect", true);
   public static final int TREE_BAKE_MAX_CHUNKS_PER_SEC = integer("treeBakeMaxChunksPerSec", 0); // 0 = always bake (pzopt.ChunkRate)
   public static final boolean TREE_BAKE_PASS = bool("treeBakePass", true); // issue #5: pzopt.TreeBake draws the baked trees
   public static final boolean CUTAWAY_FAST = bool("cutawayFast", true);
   public static final int CUTAWAY_RADIUS = integer("cutawayRadius", 6);
   public static final int GRID_STACK_INTERVAL = integer("gridStackInterval", 8);
   public static final boolean WEATHER_MASK_IDLE_SKIP = bool("weatherMaskIdleSkip", true);
   public static final int CHUNK_HANDOFF_DIVISOR = integer("chunkHandoffDivisor", 8);
   public static final int WEATHER_FX_SCALE_PCT = integer("weatherFxScalePct", 100);
   public static final boolean CUTAWAY_VISIT_PREFILTER = bool("cutawayVisitPrefilter", true);
   public static final int ROOF_HIDE_DEBOUNCE_FRAMES = integer("roofHideDebounceFrames", 8);
   public static final boolean CUTAWAY_INVALIDATE_CHANGED = bool("cutawayInvalidateChanged", true);
   public static final boolean SOUND_ZONE_CACHE = bool("soundZoneCache", true);
   public static final boolean VEHICLE_CULL = bool("vehicleCull", true); // IsoZombie.isVehicleBetween: bounding-circle test per vehicle before the exact box test, over the per-frame list of vehicles near the player (pzopt.VehicleCull)
   public static final boolean PLAYER_LOS_FAST = bool("playerLosFast", true); // IsoPlayer.updateLOS: the remembered-spotted membership is an identity set beside the stack (stock walked the stack per spotted object per frame), loop invariants hoisted, the sneak spot modifier memoised per frame (pzopt.PlayerLos)
   public static final boolean ZOMBIE_SPOT_FAST = bool("zombieSpotFast", true); // IsoZombie.spottedNew: a zombie whose spot chance is already zero (facing away, beyond its vision radius) skips the modifiers, the vehicle test and the roll, keeping the same outcome
   public static final boolean PLAYER_LOS_NATIVE = bool("playerLosNative", false); // experiment: the LOS arithmetic (distances, close count, branch per object) in C++ over arrays Java packs, one FFM call per player per frame (pzopt.NativeLos, natives/libpzopt_los64.so built with PZOPT_NATIVE=1); off = the Java loop
   public static final String PLAYER_LOS_NATIVE_LIB = string("playerLosNativeLib", ""); // experiment: absolute path of libpzopt_los64.so when it is not under the game dir's natives/ (build/native/ of the checkout survives other sessions' builds; build/classes does not)
   public static final boolean ANIM_BONES_PARALLEL = bool("animBonesParallel", true); // the zombies' animation bone math (blend, twist, model and skin matrices) runs on worker threads after the postupdate loop (pzopt.AnimBatch)
   public static final int ANIM_BONES_THREADS = Math.max(1, integer("animBonesThreads", 8)); // (2026-09-22: the old name of frameThreads, read as its default)
   public static final int FRAME_THREADS = Math.max(1, integer("frameThreads", ANIM_BONES_THREADS)); // worker threads of the per-frame zombie batches (bone math, action-context transitions; pzopt.FrameBatch; the game thread joins in); clamped to cores - 1
   public static final boolean ACTION_EVAL_PARALLEL = bool("actionEvalParallel", true); // the zombies' action-context transition evaluation runs on the frame workers between the postupdate loop and the animator updates (pzopt.ActionEval; IsoGameCharacter + ActionContext + MovingObjectUpdateScheduler overrides); a state with a Lua condition, a grappled / grappling / reanimated zombie and multiplayer take the stock path
   public static final boolean ZOMBIE_CULL_SORT_FAST = bool("zombieCullSortFast", true); // IsoWorld.sceneCullZombies: one relevance score per zombie and a primitive key sort instead of a comparator sort that recomputes both scores per comparison; same order
   public static final boolean LIGHTING_READ_PARALLEL = bool("lightingReadParallel", true); // the pre-pass drain of the lighting queue reads its chunk levels on the frame workers, one task per level (pzopt.LightingBatch; FBORenderCell.pzoptFlushPendingLighting, LightingJNI override); the room-seen / meta hooks are applied by the game thread after the join
   public static final boolean DEV_LIGHTING_READ_CHECK = bool("devLightingReadCheck", false); // dev: after a parallel lighting batch re-read one square in sixteen on the game thread and count squares whose stored fields differ from the native's answer
   public static final boolean DEV_ACTION_EVAL_CHECK = bool("devActionEvalCheck", false); // dev: evaluate every batched transition set again on the game thread at apply time and count / log disagreements with the worker's result
   public static final boolean SKIN_TRANSFORMS_PRECOMPUTE = bool("skinTransformsPrecompute", true); // the worker that updated a zombie's bones also multiplies them into the skin-transform sets its models used last frame, so the render phase finds them computed (AnimationPlayer.pzoptPrecomputeSkinTransforms; only with animBonesParallel)
   public static final boolean SKIN_PALETTE_PRECOMPUTE = bool("skinPalettePrecompute", true); // the worker also stores each precomputed skin-transform set as the shader palette buffer, so initMatrixPalette is one bulk copy (AnimatedModel override; needs skinTransformsPrecompute)
   public static final boolean SHADOW_PREP = bool("shadowPrep", true); // the worker that updated a zombie's bones also computes its shadow ellipse (pzopt.ShadowPrep); IsoZombie.calculateShadowParams serves it until the next update
   public static final boolean BONE_INDEX_CACHE = bool("boneIndexCache", true); // AnimationPlayer.getSkinningBoneIndex keeps the last few name -> index answers per skinning data (the shadow of every drawn character asked for three names per frame through two HashMap probes each)
   public static final boolean ECS_LOOKUP_FAST = bool("ecsLookupFast", true); // ECSComponent.getECSClass memoised per class, ECSEntity.tryGetECSComponent without the null checks and the reflective cast; IsoZombie keeps its StateMachineComponent in a field so getStateMachine / getActionContext / getCurrentState / isCurrentState / getVariable are field reads (stock: a class walk + a HashMap probe per call, ~5 % of the game thread on the Louisville horde)
   public static final boolean ACTION_CONDITION_FAST = bool("actionConditionFast", true); // CharacterVariableCondition: a boolean or int animation variable is compared from its typed getter instead of being printed to a string and parsed back per transition per frame (same result; floats and strings keep the stock path)
   public static final boolean CHAR_DRAW_PREP = bool("charDrawPrep", true); // the characters draw of the render phase: the on-screen zombies' draw data (ModelInstance.updateLights, ModelSlotRenderData.initModel + init, the camera record) built on the pass's own threads before the sequential enqueue pass (pzopt.CharDraw; FBORenderCell.renderMovingObjects, TextureDraw + ModelInstance overrides; the loop also skips the shadow call that returns without drawing for culled atlas zombies); players, animals, vehicles, fake-dead and hand-model zombies keep the stock path
   public static final boolean ZOMBIE_ATLAS_FAST = bool("zombieAtlasFast", true); // a culled zombie drawn as an atlas sprite (no active model, ~1,100 of the 1,600 on-screen objects of the Louisville horde) is drawn through a flat copy of its render chain (IsoZombie.pzoptRenderAtlas: the same tests, writes and sprite call as IsoZombie.render -> IsoGameCharacter.render, without the virtual chain); anything else falls back to the stock chain
   public static final int CHAR_DRAW_THREADS = Math.max(1, integer("charDrawThreads", 14)); // threads of the characters draw pre-pass pool (pzopt.CharDraw; clamped to cores - 2): the ~480 zombies' draw data must finish inside the chunk bakes, eight threads left the game thread waiting 0.2 ms a frame, twelve 0.13
   public static final boolean DEV_SIM_CHECKSUM = bool("devSimChecksum", false); // dev: one line per frame in Zomboid/pzopt-sim.out hashing every zombie's position, target, action state and animation state after postupdate (pzopt.SimChecksum, harness/simdiff.py)
   public static final boolean WORLD_SOUND_FAST = bool("worldSoundFast", true); // addSound walks only the loaded chunk grid; FishSchoolManager.addSoundNoise skips a repeat of an identical call in the same game minute (the house alarm adds its sound every frame)
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
   public static final boolean PUDDLE_CACHE = bool("puddleCache", true); // FBORenderCell.renderPuddles reuses packed puddle vertices per chunk level (pzopt.PuddleCache)
   public static final int PUDDLE_CACHE_FRAMES = integer("puddleCacheFrames", 60); // backstop rebuild interval of a cached puddle batch, staggered per chunk
   public static final boolean PUDDLE_EARLY_Z = bool("puddleEarlyZ", true); // puddle shaders take their depth from the vertex, no gl_FragDepth write: early depth test rejects occluded wet ground (media/shaders/pzopt_puddles_*)
   public static final boolean RAIN_SPLASHES_FAST = bool("rainSplashesFast", true); // splash starts by geometric skipping with a local generator instead of Rand.NextBool per idle square per frame (pzopt.RainSplashes)
   // --- render-resolution upscaling (docs/plan-upscalers.md, pzopt.RenderScale / pzopt.Upscaler) ---
   public static final String UPSCALER = string("upscaler", "off").trim().toLowerCase(java.util.Locale.ROOT); // off | bicubic | fsr1 | dlss | xess: the world pass renders at upscalerQuality's fraction of the screen and is resolved to the screen by this upscaler; the UI, text and the stock screen shader stay native
   public static final String UPSCALER_QUALITY = string("upscalerQuality", "quality").trim().toLowerCase(java.util.Locale.ROOT); // quality 67 % | balanced 59 % | performance 50 % | ultra 33 % | native 100 % (dlss: DLAA) of the screen size per axis
   public static final int UPSCALER_SCALE_PCT = integer("upscalerScalePct", 0); // explicit render scale in percent (10..100) instead of upscalerQuality's preset; 0 = use the preset
   public static final int FSR_SHARPNESS_PCT = Math.max(0, Math.min(100, integer("fsrSharpnessPct", 80))); // RCAS sharpening after EASU as a percentage: 100 = the sharpest (0 stops of attenuation), 0 = 2 stops (the mildest); AMD ships 80-100 in its sample
   public static final boolean DLSS_SHARPEN = bool("dlssSharpen", false); // dlss: the NGX sharpening flag (a mild extra sharpen; off = plain super resolution)
   public static final boolean UPSCALER_OBJECT_MV = bool("upscalerObjectMv", true); // dlss / xess: characters and vehicles write their own motion vectors (a rect masked by the depth band) on top of the camera motion; false = camera motion only
   public static final boolean DEV_UPSCALER_LOG = bool("devUpscalerLog", false); // dev: log every upscaler state change, the shared-image import and the first evaluations
   public static final String DLSS_PRESET = string("dlssPreset", "default").trim().toLowerCase(java.util.Locale.ROOT); // dlss: the render preset for every quality level: default (the driver's: transformer K / M / L), e or f (the older convolutional models, cheaper), j, k, l, m
   public static final boolean DLSS_DEPTH_INVERTED = bool("dlssDepthInverted", false); // dlss: the scene depth's larger values are nearer (the NGX DepthInverted flag)
   public static final boolean DLSS_JITTER = bool("dlssJitter", true); // dlss: draw the world with the Halton sub-pixel jitter DLSS accumulates from (false = no jitter, an A/B)
   public static final float DLSS_JITTER_SIGN = integer("dlssJitterSign", 1) < 0 ? -1.0F : 1.0F; // dlss: the sign the viewport offset is reported to NGX with (1 or -1, an A/B of the convention)
   public static final float DLSS_MV_SIGN = integer("dlssMvSign", 1) < 0 ? -1.0F : 1.0F; // dlss: the sign of the motion vectors (1 = current to previous position, NGX's convention; -1 the other way)
   public static final boolean TREE_APPEND = bool("treeAppend", true); // a new chunk's trees are drawn into the finished neighbour textures they reach instead of re-baking those textures (tree pass, issue #5)
   public static final boolean PUDDLE_VBO = bool("puddleVbo", true); // cached puddle batches kept in per-chunk-level GL buffers, jiggle as a matrix translation (pzopt.PuddleVbo)
   public static final boolean RAIN_TILES = bool("rainTiles", true); // weather particles rendered once as a template and drawn once per screen cell (pzopt.RainTiles)
   public static final int VBO_BATCH_KB = integer("vboBatchKb", 1024); // VBORenderer element buffer (4 = stock): rain particles flush every 28 quads at 4 KB
   public static final boolean VBO_FAST_QUADS = bool("vboFastQuads", true); // VBORenderer.addQuad writes the four vertices with one position advance
   public static final boolean FOG_PASS = bool("fogPass", true); // ImprovedFog as one batch into a scaled, depth-copied fog buffer (pzopt.FogPass)
   public static final int FOG_SCALE_PCT = integer("fogScalePct", 25); // fog buffer size per axis, % of the viewport
   public static final boolean DEV_FOG_NO_DRAW = bool("devFogNoDraw", false); // measurement: the fog pass does everything but the rectangle draw call
   public static final boolean DEV_FOG_FLAT = bool("devFogFlat", false);
   public static final int DEV_FOG_DEPTH_VIEW = integer("devFogDepthView", 0); // measurement: the composite shows 1 = the scene depth, 2 = the fog texel depth, 3 = the fog buffer alpha (R/G = depth * 255 integer / fraction)
   public static final boolean FOG_DEPTH_COPY = bool("fogDepthCopy", false); // keep the offscreen depth a renderbuffer and copy it for the fog pass (measurement / driver fallback) // measurement: the rectangles with a flat fragment shader (no noise fetches)
   public static final int FOG_MASK_FRAMES = integer("fogMaskFrames", 20); // a chunk's fog masks (which squares take fog) are refreshed this often; 0 = read every square every frame
   public static final boolean OVERLAY_SAMPLING = bool("overlaySampling", false); // measure at all (ring, GL timer queries, sampler thread); off by default since 2026-09-21
   public static final boolean OVERLAY = bool("overlay", false);
   public static final boolean OVERLAY_LOG = bool("overlayLog", false);
   // The overlay's elements, each a dropdown on the Optimizations tab: "off" or the element's own options.
   public static final String OVERLAY_STATS = string("overlayStats", "full"); // off | fps (the fps line) | tails (+ p99 / jitter lines) | full (+ utilization)
   public static final String OVERLAY_TREE = string("overlayTree", "5"); // the game-thread tree: off | 0 (phases only) | 3 | 5 | 8 sub-phases per phase
   public static final String OVERLAY_VERDICT = string("overlayVerdict", "detailed"); // off | short ("GPU bound") | detailed (+ the two biggest game-thread sub-phases)
   public static final String OVERLAY_GRAPH = string("overlayGraph", "240"); // the frame-time graph: off | 240 | 480 | 960 frames (2 px each)
   public static final String OVERLAY_FLAME = string("overlayFlame", "right"); // the game-thread flame graph: off | right (900 px column) | right-wide (1400) | below (under the frame graph)
   public static final int OVERLAY_FLAME_DEPTH = integer("overlayFlameDepth", 24); // rows of the flame graph (frames from GameWindow.frameStep up)
   public static final int GAME_THREAD_PROFILE_HZ = integer("gameThreadProfileHz", 100); // game-thread stack samples per second (10..1000); sampling runs when the tree, the flame graph, the detailed verdict or the frame log wants it
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

   /** min(4, cores - 1); 1 on 4 cores or fewer, where a pool only competes with the game, lighting and render threads. */
   static int defaultWorkers(int cores) {
      return cores <= 4 ? 1 : Math.min(4, cores - 1);
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
            + Runtime.getRuntime().availableProcessors() + ") wake=" + WAKE + " (effective " + effectiveWake() + ") instrument=" + INSTRUMENT + " dev=" + DEV + " luaChecksumExempt=" + LUA_CHECKSUM_EXEMPT
            + " translucentCache=" + TRANSLUCENT_CACHE + " hotsaveIntervalSec=" + HOTSAVE_INTERVAL_SEC + " persistentVbo=" + PERSISTENT_VBO + " treesInChunkTexture=" + TREES_IN_CHUNK_TEXTURE + " windowsInChunkTexture=" + WINDOWS_IN_CHUNK_TEXTURE + " translucentTilesInChunkTexture=" + TRANSLUCENT_TILES_IN_CHUNK_TEXTURE + " treeBakePass=" + TREE_BAKE_PASS + " curtainDepthNudgePct=" + Math.round(CURTAIN_DEPTH_NUDGE * 100.0F) + " bakeBudget=" + BAKE_BUDGET + " lightingBudget=" + LIGHTING_BUDGET + " lightingRebakeMs=" + LIGHTING_REBAKE_MS + " rebakeBudget=" + REBAKE_BUDGET + " rebakeMaxFrames=" + REBAKE_MAX_FRAMES + " lightingRebakeBudget=" + LIGHTING_REBAKE_BUDGET + " lightingRebakeMaxFrames=" + LIGHTING_REBAKE_MAX_FRAMES + " zoomRetain=" + ZOOM_RETAIN + " zoomRebakeBudget=" + ZOOM_REBAKE_BUDGET + " zoomFrameMs=" + Math.round(ZOOM_FRAME_MS) + " zoomPlaceholder=" + ZOOM_PLACEHOLDER + " zoomEaseMs=" + ZOOM_EASE_MS + " zoomEase=" + ZOOM_EASE + " lightingStrongDelta=" + LIGHTING_STRONG_DELTA + " lightingStrongBudget=" + LIGHTING_STRONG_BUDGET + " lightingStrongFrameMs=" + Math.round(LIGHTING_STRONG_FRAME_MS) + " lightingGlobalDeltaPct=" + Math.round(LIGHTING_GLOBAL_DELTA * 100.0F) + " lightingFlush=" + LIGHTING_FLUSH + " lightSwitchCheckFrames=" + LIGHT_SWITCH_CHECK_FRAMES + " cutawayFast=" + CUTAWAY_FAST + " cutawayRadius=" + CUTAWAY_RADIUS + " gridStackInterval=" + GRID_STACK_INTERVAL + " roofHideDebounceFrames=" + ROOF_HIDE_DEBOUNCE_FRAMES + " weatherMaskIdleSkip=" + WEATHER_MASK_IDLE_SKIP + " worldSoundFast=" + WORLD_SOUND_FAST + " vehicleCull=" + VEHICLE_CULL + " playerLosFast=" + PLAYER_LOS_FAST + " zombieSpotFast=" + ZOMBIE_SPOT_FAST + " playerLosNative=" + PLAYER_LOS_NATIVE + " animBonesParallel=" + ANIM_BONES_PARALLEL + " frameThreads=" + FRAME_THREADS + " actionEvalParallel=" + ACTION_EVAL_PARALLEL + " lightingReadParallel=" + LIGHTING_READ_PARALLEL + " zombieCullSortFast=" + ZOMBIE_CULL_SORT_FAST + " skinTransformsPrecompute=" + SKIN_TRANSFORMS_PRECOMPUTE + " skinPalettePrecompute=" + SKIN_PALETTE_PRECOMPUTE + " shadowPrep=" + SHADOW_PREP + " boneIndexCache=" + BONE_INDEX_CACHE + " ecsLookupFast=" + ECS_LOOKUP_FAST + " actionConditionFast=" + ACTION_CONDITION_FAST + " charDrawPrep=" + CHAR_DRAW_PREP + " zombieAtlasFast=" + ZOMBIE_ATLAS_FAST + " charDrawThreads=" + CHAR_DRAW_THREADS
            + " fileThreads=" + FILE_THREADS + " fileInflight=" + FILE_INFLIGHT + " textureBufferMb=" + TEXTURE_BUFFER_MB + " parallelDepthMaps=" + PARALLEL_DEPTH_MAPS + " loaderCpuFixes=" + LOADER_CPU_FIXES + " loadWorkers=" + LOAD_WORKERS + " scriptParserFast=" + SCRIPT_PARSER_FAST + " fmodAsync=" + FMOD_ASYNC + " noLoadFade=" + NO_LOAD_FADE + " noIntroWait=" + NO_INTRO_WAIT + " bootPump=" + BOOT_PUMP + " earlyModels=" + EARLY_MODELS + " luaPrecompile=" + LUA_PRECOMPILE + " preloadAnimSets=" + PRELOAD_ANIM_SETS + " animClipCache=" + ANIM_CLIP_CACHE + " packIndex=" + PACK_INDEX + " itemParamSwitch=" + ITEM_PARAM_SWITCH + " bootFileThreads=" + BOOT_FILE_THREADS + " shaderCache=" + SHADER_CACHE + " mipmapArrays=" + MIPMAP_ARRAYS + " puddleCache=" + PUDDLE_CACHE + " puddleCacheFrames=" + PUDDLE_CACHE_FRAMES + " puddleVbo=" + PUDDLE_VBO + " treeAppend=" + TREE_APPEND + " puddleEarlyZ=" + PUDDLE_EARLY_Z + " rainSplashesFast=" + RAIN_SPLASHES_FAST + " rainTiles=" + RAIN_TILES + " vboBatchKb=" + VBO_BATCH_KB + " vboFastQuads=" + VBO_FAST_QUADS + " fogPass=" + FOG_PASS + " fogScalePct=" + FOG_SCALE_PCT + " fogMaskFrames=" + FOG_MASK_FRAMES
            + " upscaler=" + UPSCALER + " upscalerQuality=" + UPSCALER_QUALITY + " upscalerScalePct=" + UPSCALER_SCALE_PCT + " fsrSharpnessPct=" + FSR_SHARPNESS_PCT + " dlssSharpen=" + DLSS_SHARPEN + " upscalerObjectMv=" + UPSCALER_OBJECT_MV;
   }
}
