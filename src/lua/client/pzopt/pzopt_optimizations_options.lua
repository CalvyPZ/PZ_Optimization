-- pzopt: "Optimizations" tab in the options screen, right after Display.
--  Every pzopt.Config key is a control here: booleans are tick boxes, integers are combos whose first
--  entry is the build's default on this machine. The values live in Java: the overridden
--  PerformanceSettings forwards to pzopt.Config (what is in force since boot) and pzopt.UserOptions
--  (Zomboid/pzopt/options.ini, what the next launch will read). Everything applies on the next
--  launch, so a change away from the boot value raises the stock "restart required" dialog.
--  A key set in the install dir's pzopt.properties or as -Dpzopt.<key> (harness runs) wins over the
--  file; its control shows that value, is disabled, and the tooltip says what pins it.
--  The top of the tab is the master switch (key `enabled`): off = every override takes its stock
--  path, the same as a build mismatch, whatever the other keys say. "Disable all (stock)" turns it
--  off; "Enable all" turns it on and puts every other control back to the build's defaults.
--  Both only change the controls; Apply / Accept saves them like any other option.
--  Right of the controls sits the preview panel (PzoptPreview, fixed while the list scrolls): for the
--  setting under the mouse it plays two clips side by side, the stock game and the optimized build on the
--  same route (animated GIFs under media/ui/pzopt/compare/, made by harness/menu-gifs.py, decoded by
--  pzopt.GifTextures), shows the setting's description and its value now / at the next launch, and draws
--  one bar per resource (game thread, render thread, other cores, GPU, VRAM, RAM, disk, load time, chunk
--  arrival) from the EFFECTS table below: left = less work / sooner, right = more. Which clip a setting
--  shows is its section's `clip`, overridden per key in KEY_CLIP.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

local TAB = "Optimizations"
local RESTART_NOTE = "Takes effect on the next launch."

-- The master switch, drawn before the sections with the two buttons.
local MASTER = { key = "enabled", label = "Optimizations enabled (master switch)",
  tip = "Off = the game runs stock: every override takes its original code path and the settings below are ignored. On = the settings below apply." }

-- Colour names pzopt.Overlay.color knows (a RRGGBB hex typed into options.ini also works).
local FPS_COLOURS = { "blue", "green", "yellow", "red", "white", "cyan", "lime", "orange", "magenta", "purple" }

-- Keys, labels and tooltips. `choices` makes a combo (integer or string); `note[value]` annotates an entry.
local SECTIONS = {
    {
        title = "Chunk textures: what bakes", clip = "drive",
        entries = {
            { key = "treesInChunkTexture", label = "Bake trees into chunk textures",
              tip = "Static trees are drawn once into the chunk textures instead of every frame; only fading trees stay per-frame. Off = stock (every tree every frame)." },
            { key = "treeBakeMaxChunksPerSec", label = "Bake trees only below this chunk rate (chunks/s)",
              choices = { "0", "12", "24", "48" }, note = { ["0"] = "always bake" },
              tip = "While chunks stream in faster than this (walking loads about 9 a second, driving at 60 km/h about 32, at 120 km/h about 72) new chunk textures are baked without their trees and the trees are drawn per frame instead: a texture that lives a second or two while driving costs more to bake its trees into than to draw them. Textures already baked keep their trees until they re-bake anyway." },
            { key = "treeBakeDirect", label = "Bake trees one by one",
              tip = "Baked trees go through the plain sprite path. The batched path drops the largest (jumbo) trees near buildings." },
            { key = "treeBakePass", label = "Tree pass: whole crowns, depth by height",
              tip = "Baked trees are drawn by their own pass: into every chunk texture the crown reaches (a jumbo tree is up to 7 tiles wide) and with a depth that rises with the crown like walls do. Off = trees are clipped at their chunk texture's border and cut by upper-floor walls behind them (issue #5)." },
            { key = "treeAppend", label = "Draw new trees into neighbour textures",
              tip = "A newly loaded chunk's trees that reach into an already baked neighbour texture are drawn on top of it instead of re-baking the whole texture; same picture, most of the re-bakes while driving." },
            { key = "windowsInChunkTexture", label = "Bake windows into chunk textures",
              tip = "Windows and glass doors bake like walls instead of being drawn every frame." },
            { key = "translucentTilesInChunkTexture", label = "Bake translucent tiles",
              tip = "Fences, railings, wall decorations and overlays bake into the chunk textures instead of being drawn every frame (about 3,000 draws a frame at max zoom)." },
            { key = "curtainDepthNudgePct", label = "Curtain depth nudge (hundredths of a tile)",
              choices = { "0", "3", "5", "10" }, note = { ["0"] = "off" },
              tip = "Closed curtains draw this much nearer the camera than their tile geometry says, so a baked window never shows through them (north windows sit 0.017 tile in front of their curtain in the game's tile geometry)." },
        },
    },
    {
        title = "Chunk textures: bake budgets", clip = "nightdrive",
        entries = {
            { key = "bakeBudget", label = "Chunk textures baked per frame",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunk-level textures (re)baked in one frame; the rest wait for the next frame." },
            { key = "rebakeBudget", label = "Chunk texture re-bakes per frame",
              choices = { "0", "2", "4", "8", "16" }, note = { ["0"] = "unlimited, stock" },
              tip = "Textures dirtied only by lighting drift, a redraw or a cutaway change keep their previous image for a few frames past this many re-bakes." },
            { key = "rebakeMaxFrames", label = "Longest re-bake hold (frames)",
              choices = { "1", "2", "3", "4", "6" },
              tip = "A held re-bake lands after at most this many frames." },
            { key = "lightingRebakeBudget", label = "Lighting-only re-bakes per frame",
              choices = { "2", "4", "8", "16", "32" },
              tip = "Textures dirtied only by a lighting change (daylight drift, a lightning flash) re-bake at most this many per frame." },
            { key = "lightingRebakeMaxFrames", label = "Longest lighting-only re-bake hold (frames)",
              choices = { "3", "10", "30", "60" },
              tip = "A lighting-only re-bake lands after at most this many frames; a lightning strike spreads over this window instead of one long frame." },
            { key = "lightingRebakeMs", label = "Minimum ms between lighting-only rebakes",
              choices = { "0", "50", "100", "250", "500" }, note = { ["0"] = "stock" },
              tip = "A chunk texture dirtied only by a lighting change is not re-baked more often than this." },
            { key = "lightingStrongDelta", label = "Light change that re-bakes at once (0-255)",
              choices = { "2", "4", "6", "12", "24", "255" }, note = { ["255"] = "hold everything" },
              tip = "A square whose light moved by this much since its texture was last baked (a torch or headlight beam sweeping in) re-bakes now, like stock; smaller drift keeps the holds above." },
            { key = "lightingGlobalDeltaPct", label = "Global light move that keeps the spread (%)",
              choices = { "1", "2", "5", "10", "100" }, note = { ["100"] = "never" },
              tip = "A lightning flash or a fast dusk moves the whole scene's light at once; past this per-frame move the lighting re-bakes stay spread over frames instead of landing at once." },
            { key = "lightingFlush", label = "Flush queued lighting refreshes before a lighting pass",
              tip = "Chunks the per-frame lighting refresh budget still holds are refreshed just before the next lighting pass rewrites their dirty bits; off, they keep stale light until it changes again." },
            { key = "lightingBudget", label = "Chunk lighting refreshes per frame",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunks whose square light info is refreshed in one frame; the rest continue next frame." },
        },
    },
    {
        title = "Cutaways, lighting and weather (game thread)", clip = "spin",
        entries = {
            { key = "cutawayFast", label = "Replay cutaway masks",
              tip = "Clean chunk levels replay their stored wall-cutaway occluder masks instead of re-testing every square." },
            { key = "cutawayRadius", label = "Cutaway radius (chunks)",
              choices = { "0", "1", "2", "3", "4", "6" }, note = { ["0"] = "all on screen, stock" },
              tip = "Cutaway wall visits only consider chunks within this many chunks of the camera." },
            { key = "gridStackInterval", label = "Frames between buildings-in-front scans",
              choices = { "0", "2", "4", "8" }, note = { ["0"] = "every frame, stock" },
              tip = "While the camera square and facing are unchanged, the buildings-in-front scan runs this often." },
            { key = "lightSwitchCheckFrames", label = "Frames between light-switch power checks",
              choices = { "0", "5", "15", "30", "60" }, note = { ["0"] = "every frame, stock" },
              tip = "Each light switch reuses its has-electricity answer for this many frames." },
            { key = "rainTiles", label = "Rain and snow as repeated tiles",
              tip = "The particle cell is packed once and drawn once per screen cell on the GPU instead of every copy being packed on both threads; same picture." },
            { key = "puddleCache", label = "Cache packed puddle vertices per chunk",
              tip = "Rain puddles keep their packed vertices per chunk level and only refresh lighting, camera offset and depth each frame; 4.5 ms of a thunderstorm frame at max zoom." },
            { key = "rainSplashesFast", label = "Rain splashes without the game RNG",
              tip = "Splash starts are drawn with a cheap local generator (one draw per splash instead of one game RNG call per idle square per frame); same chance, timing and sprites." },
            { key = "puddleEarlyZ", label = "Puddle shader with early depth test",
              tip = "The puddle shaders take their depth from the vertex instead of writing it per pixel, so wet ground hidden behind walls, roofs and objects is skipped before the expensive shader runs; same picture." },
            { key = "puddleVbo", label = "Keep puddle batches on the GPU",
              tip = "Each chunk level's cached puddle vertices stay in their own GPU buffer and are re-sent only when a light changed, the camera crossed a chunk edge or the batch was rebuilt; the camera offset is a matrix translation. Nothing is copied per frame." },
            { key = "puddleCacheFrames", label = "Puddle cache rebuild interval (frames)",
              choices = { "1", "30", "60", "120" }, note = { ["1"] = "rebuild every frame (cache off)" },
              tip = "A cached puddle batch is rebuilt with the stock code after this many frames at the latest; bakes and cutaway changes rebuild it at once." },
            { key = "weatherMaskIdleSkip", label = "Skip the weather mask while nothing is drawn",
              tip = "Outdoors with no clouds, fog or rain the per-frame weather-mask view scan and mask draw are skipped; indoors only the player's building is scanned." },
            { key = "weatherFxScalePct", label = "Weather effects buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "stock" },
              tip = "Clouds, fog, rain and the interior mask they are cut by are drawn into screen-sized buffers every frame; smaller buffers cost far less GPU and CPU and the soft content looks the same." },
            { key = "fogPass", label = "Fog in one pass (experimental)",
              tip = "EXPERIMENTAL. Heavy fog is drawn in one batch into a smaller buffer that is depth-tested against the scene and blended over it once, instead of shading every pixel up to twelve times with one draw call per row: fog at 120 km/h went from 220 to ~340 fps (clear: 447) on the 5120x2160 desktop. Known issue: power lines can flicker slightly in fog while the camera moves; disabling this removes it (stock fog, stock cost)." },
            { key = "fogScalePct", label = "Fog buffer size (% of screen)",
              choices = { "100", "75", "50", "33", "25" }, note = { ["100"] = "full resolution" },
              tip = "The fog buffer per axis as a percentage of the screen; 50 costs a quarter of the fog GPU work, 25 a sixteenth. Fog is soft, so the smaller buffers look the same, and edges where fog meets walls or wires are resolved against the real depth." },
            { key = "fogMaskFrames", label = "Fog square masks refresh (frames)",
              choices = { "0", "10", "20", "60" }, note = { ["0"] = "read every square every frame" },
              tip = "The fog rows are built from per-chunk masks of the squares that take fog instead of reading every square each frame; a mask is refreshed this many frames after its last refresh, so a newly built room reaches the fog within that many frames." },
            { key = "roofHideDebounceFrames", label = "Carport roof hide/show settle time (frames)",
              choices = { "0", "4", "8", "15", "30" }, note = { ["0"] = "stock" },
              tip = "A carport or pergola roof is hidden or shown only after the decision has held for this many frames, so a player on its edge (or pushed by zombies) does not make the roof flicker every frame." },
            { key = "cutawayVisitPrefilter", label = "Skip cutaway walls that cannot cut",
              tip = "A cutaway visit only walks the squares of walls that occlude a cutaway room, belong to a collapsing building, or may hide a window being peeked through; the rest are skipped before their squares are looked up." },
            { key = "cutawayInvalidateChanged", label = "Re-bake cutaway chunks only when a cutaway changed",
              tip = "A cutaway visit re-flags every cut-away wall square; stock re-bakes every chunk holding one on every visit. Only chunks where a square's cutaway flag actually changed are re-baked." },
            { key = "occlusionSkipLightingOnly", label = "Keep the occlusion grid when only lighting changed",
              tip = "The occluded-squares grid and the per-level rendered-square counts are rebuilt only when a visible chunk level changed for a reason other than lighting drift." },
            { key = "lightInfoChunkGate", label = "Ask the lighting engine per chunk level first",
              tip = "Before refreshing the 64 squares of a chunk level about to be re-baked, one chunk-level question to the lighting engine says whether any of them changed." },
            { key = "lightInfoOncePerFrame", label = "Ask the lighting engine once per square per frame",
              tip = "The per-square light-info JNI call is skipped when the same square was already refreshed this frame." },
            { key = "soundZoneCache", label = "Reuse ambient sound zone distances per square",
              tip = "The 80x80 zone scan behind each ambient zone parameter runs when the listener's square changes (at most every 30 frames), not every frame." },
        },
    },
    {
        title = "Sprite buffers", clip = "drive",
        entries = {
            { key = "persistentVbo", label = "Persistently mapped sprite buffers",
              tip = "Sprite ring buffers use persistently mapped buffer storage instead of an orphan and re-map per batch. About 2.7x the uncapped frame rate at max zoom." },
            { key = "vboBatchKb", label = "Line/particle batch buffer (KB)",
              choices = { "4", "256", "1024" }, note = { ["4"] = "stock: rain flushes every 28 particles" },
              tip = "Rain and snow particles, debug lines and other VBORenderer quads are uploaded and drawn in batches of this size instead of 4 KB." },
            { key = "vboFastQuads", label = "Single-advance particle quads",
              tip = "VBORenderer writes a textured quad's four vertices in one go instead of four bookkeeping round trips; same bytes." },
        },
    },
    {
        title = "Multiplayer", clip = "drive",
        entries = {
            { key = "luaChecksumExempt", label = "Leave the pzopt Lua files out of the server file check",
              tip = "When joining a server the game lists every Lua file under media/lua to the server; the three pzopt files (this tab, the frame cap combo, the key binding) only exist on clients and a server without them refused the join with \"File doesn't exist on the server\". They are skipped like the game skips SandboxVars.lua. Applies on the next launch." },
        },
    },
    {
        title = "Performance overlay (F9, or the \"Toggle performance overlay\" key binding)", clip = "spin",
        entries = {
            { key = "overlaySampling", label = "Sample frame times and utilization (needed for F9)",
              tip = "Records every presented frame, times the GPU with GL timer queries and samples the CPU load twice a second on a background thread. Off by default: without it F9 only shows a notice. \"Show the overlay from boot\" and \"Log every presented frame\" turn it on too. Applies on the next launch." },
            { key = "overlay", label = "Show the overlay from boot",
              tip = "Frame rate, frame-time tail (p99, p99.9, max, 1%-low, jitter, spikes), GPU busy share, game and render thread load, and a frame-time graph. The key toggles it any time." },
            { key = "overlayLog", label = "Log every presented frame",
              tip = "Writes Zomboid/pzopt-overlay.out, one CSV row per frame in MangoHud's column names, for harness/analyze.py. Harness runs log regardless." },
            { key = "overlayStats", label = "Frame statistics",
              choices = { "off", "fps", "tails", "full" },
              note = { fps = "the fps line", tails = "+ p99 / p99.9 / max, 1%-low, jitter, spikes", full = "+ GPU, thread, process and machine load, heap" },
              tip = "The lines at the top of the overlay." },
            { key = "overlayTree", label = "Game-thread tree",
              choices = { "off", "0", "3", "5", "8" },
              note = { ["0"] = "phases only", ["3"] = "3 sub-phases per phase", ["5"] = "5 sub-phases per phase", ["8"] = "8 sub-phases per phase" },
              tip = "What the game thread is doing, from its call stack sampled on a background thread: the phases (update / render / lighting) with their share of the time, under each the biggest sub-phases (chunk bakes, zombies, UI draw, frame hand-off...) with a bar, the wait share in red and the hottest methods. Also logged per second to Zomboid/pzopt-gamethread.out for harness/analyze.py. Applies on the next launch." },
            { key = "overlayVerdict", label = "Verdict line",
              choices = { "off", "short", "detailed" },
              note = { short = "\"at the cap\" / \"GPU bound\" / \"nothing saturated\"", detailed = "+ the two biggest game-thread sub-phases when it is the game thread" },
              tip = "What is holding the frame rate below the cap." },
            { key = "overlayGraph", label = "Frame-time graph",
              choices = { "off", "240", "480", "960" },
              note = { ["240"] = "last 240 frames (480 px)", ["480"] = "last 480 frames (960 px)", ["960"] = "last 960 frames (1920 px)" },
              tip = "A bar per presented frame (green under 1.1x the cap budget, amber under 2x, red above; GPU time in blue) with ms ticks and the budget line." },
            { key = "overlayFlame", label = "Game-thread flame graph",
              choices = { "off", "right", "right-wide", "below" },
              note = { right = "column beside the statistics, 900 px", ["right-wide"] = "column beside the statistics, 1400 px", below = "under the frame graph, panel width" },
              tip = "The last 5 s of stack samples as a flame graph: root (GameWindow.frameStep) at the bottom, callees above, width = share of the time, biggest first from the left; update green, render blue, lighting amber, pzopt frames magenta. Drawing it costs the game thread a few percent while shown (it appears as \"overlay\" in the tree). harness/flamegraph.py draws a whole run as an SVG." },
            { key = "overlayFlameDepth", label = "Flame graph rows",
              choices = { "12", "16", "24", "32", "48" },
              tip = "How many call levels above GameWindow.frameStep the flame graph shows." },
            { key = "gameThreadProfileHz", label = "Game-thread stack samples per second",
              choices = { "50", "100", "200", "500" },
              tip = "Higher resolves short phases sooner; each sample briefly stops the game thread (tens of microseconds). 100 is about 1 % resolution over the overlay's 5 s window. Sampling only runs while the overlay is shown or its log is on, and only when the tree, the flame graph, the detailed verdict or the log needs it." },
            { key = "overlayCorner", label = "Overlay corner",
              choices = { "tl", "tr", "bl", "br" },
              tip = "Where the overlay sits: top-left, top-right, bottom-left, bottom-right." },
            { key = "overlayFont", label = "Overlay font",
              choices = { "CodeMedium", "CodeSmall", "CodeLarge", "Small", "Medium", "Large" },
              tip = "The UI font the overlay text uses." },
        },
    },
    {
        title = "Performance overlay: fps colour", clip = "spin",
        entries = {
            { key = "overlayFpsColor", label = "Colour the fps number",
              tip = "The fps number takes one of four colours by how close it is to the target; off = white like the rest of the line." },
            { key = "overlayFpsFollowCap", label = "Follow the framerate cap",
              tip = "On: with a framerate cap the thresholds are percentages of it (the three \"% of the cap\" values). Off, or uncapped: the three fixed fps thresholds apply." },
            { key = "overlayFpsCapBluePct", label = "Blue: at the cap (% of the cap)",
              choices = { "100", "99", "98", "95", "90" },
              tip = "At or above this share of the cap counts as at the cap. The limiter rarely lands exactly on it, so 100 is stricter than it looks." },
            { key = "overlayFpsCapGreenPct", label = "Green: at or above (% of the cap)",
              choices = { "95", "90", "85", "80", "75" },
              tip = "Green from this share of the cap up to the blue threshold." },
            { key = "overlayFpsCapYellowPct", label = "Yellow: at or above (% of the cap)",
              choices = { "75", "66", "50", "33", "25" },
              tip = "Yellow from this share of the cap up to the green threshold; red below it." },
            { key = "overlayFpsBlueAbove", label = "Blue: above (fps, uncapped)",
              choices = { "500", "400", "300", "240", "200", "165", "144", "120", "60" },
              tip = "Uncapped, or with follow-cap off: blue above this many fps." },
            { key = "overlayFpsGreenAbove", label = "Green: at or above (fps, uncapped)",
              choices = { "300", "240", "200", "150", "120", "100", "60", "45" },
              tip = "Uncapped, or with follow-cap off: green from this many fps up to the blue threshold." },
            { key = "overlayFpsYellowAbove", label = "Yellow: at or above (fps, uncapped)",
              choices = { "200", "150", "120", "100", "75", "60", "45", "30" },
              tip = "Uncapped, or with follow-cap off: yellow from this many fps up to the green threshold; red below it." },
            { key = "overlayFpsColorBlue", label = "Colour for \"at the cap\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorGreen", label = "Colour for \"near the cap\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorYellow", label = "Colour for \"well below\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
            { key = "overlayFpsColorRed", label = "Colour for \"far below\"",
              choices = FPS_COLOURS,
              tip = "Named colour, or a RRGGBB hex value typed into Zomboid/pzopt/options.ini." },
        },
    },
    {
        title = "Chunk streaming", clip = "drive",
        entries = {
            { key = "parallel", label = "Parallel chunk loading",
              tip = "Chunk recalculation runs on a worker pool. Off = the stock single-threaded pass." },
            { key = "workers", label = "Chunk worker threads",
              choices = { "1", "2", "3", "4", "6", "8" },
              tip = "Width of the recalc pool; never more than cores - 1." },
            { key = "loadWorkers", label = "Chunk worker threads while a world loads",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "The initial 361-chunk recalc uses this many threads, then the pool shrinks back." },
            { key = "wake", label = "Wake the streamer on demand",
              tip = "The streamer thread wakes when a chunk is queued instead of polling every 140 ms." },
            { key = "chunkHandoffDivisor", label = "Chunk hand-off budget (queue divisor)",
              choices = { "0", "4", "8", "16" }, note = { ["0"] = "stock: up to 4 chunks a frame" },
              tip = "At most 1 + queued/divisor freshly loaded chunks are handed to the game thread per frame, so a chunk row arriving at once is spread over a few frames instead of one long one." },
            { key = "hotsaveStaged", label = "Staged hot save",
              tip = "The periodic hot save serialises the meta grid and other systems one part per streamer update instead of all in one frame." },
            { key = "hotsaveIntervalSec", label = "Seconds between hot saves",
              choices = { "0", "5", "15", "30", "60", "120" }, note = { ["0"] = "every drain, stock" },
              tip = "Minimum seconds between the game-thread saves of the meta grid, game time, world map and entities that follow every drained chunk-save queue." },
        },
    },
    {
        title = "Boot: threads and caches", clip = "load",
        entries = {
            { key = "fmodAsync", label = "Start audio on a boot thread",
              tip = "FMOD and its banks (~1.6 s) initialise on a thread during boot." },
            { key = "preloadAnimSets", label = "Parse animation sets on a boot thread",
              tip = "The player and zombie animation-set XML trees parse off the loader thread (1.1 s)." },
            { key = "bootPump", label = "Decode textures during boot",
              tip = "A thread pumps the async file system during boot so texture pages and animations decode before the main menu." },
            { key = "bootFileThreads", label = "File threads during boot",
              choices = { "2", "4", "6", "8", "10", "12" },
              tip = "File pool width while the boot pump runs; shrinks to the in-game width at the load." },
            { key = "earlyModels", label = "Register models early",
              tip = "Models and the animation queue register right after the scripts load, giving the boot pump ~2 s more." },
            { key = "luaPrecompile", label = "Precompile Lua on a pool",
              tip = "Every Lua file compiles on a thread pool during boot; the game takes the prototypes from that cache." },
            { key = "animClipCache", label = "Cache animation clips",
              tip = "Imported animation clips are written under Zomboid/pzopt/anims and read from there on later boots." },
            { key = "packIndex", label = "Index texture packs",
              tip = "Texture packs keep their page offsets under Zomboid/pzopt/packs so the reader seeks instead of scanning 526 MB at boot." },
        },
    },
    {
        title = "Boot: parsers", clip = "load",
        entries = {
            { key = "scriptParserFast", label = "Linear script parser",
              tip = "Script comments strip in one pass and tokens parse without re-substringing; identical output (the stock passes cost 1.8 s at boot)." },
            { key = "itemParamSwitch", label = "Item parameter switch",
              tip = "Item script fields dispatch through a switch instead of a chain of 361 string compares per parameter (0.9 s of boot)." },
        },
    },
    {
        title = "World load: file system and decoding", clip = "load",
        entries = {
            { key = "fileThreads", label = "File threads in game",
              choices = { "1", "2", "4", "6", "8", "12" },
              tip = "Worker threads of the game's async file system (texture decode, model and animation import, depth maps). Stock: 2 on up to 4 cores, else 4." },
            { key = "fileInflight", label = "File tasks in flight",
              choices = { "8", "16", "32", "64" }, note = { ["16"] = "stock" },
              tip = "File tasks handed to the file threads at once." },
            { key = "textureBufferMb", label = "Decoded texture buffer (MB)",
              choices = { "50", "100", "256" }, note = { ["50"] = "stock" },
              tip = "Decoded texture bytes that may wait for the render thread before the decoders pause. Large values pile uploads onto one frame." },
            { key = "parallelDepthMaps", label = "Decode depth maps in parallel",
              tip = "The 218 depth-map tilesets decode concurrently instead of one at a time under one lock." },
            { key = "loaderCpuFixes", label = "Loader thread algorithmic fixes",
              tip = "Lot headers, vehicle zones and room ids resolve once per cell instead of repeatedly; identical results." },
            { key = "shaderCache", label = "Reuse model shaders",
              tip = "A model takes a shader an earlier model already created instead of waiting one loading-screen frame for the render thread." },
            { key = "mipmapArrays", label = "Row-based texture mipmaps",
              tip = "Texture mipmaps and alpha premultiply build row by row on byte arrays; same pixels as stock." },
        },
    },
    {
        title = "World load: loading screen", clip = "load",
        entries = {
            { key = "noLoadFade", label = "Skip the loading-screen fade",
              tip = "The loading screen does not fade to black (350 ms) before the world's own fade-in." },
            { key = "noIntroWait", label = "Click-to-start as soon as a new game is loaded",
              tip = "A new game shows click-to-start when loading is done instead of after the 33 s intro text." },
        },
    },
}

local function perf()
    return getPerformance()
end

-- The value the next launch will read: pinned > saved > default.
local function nextValue(entry)
    local p = perf()
    if p:getPzoptOptionPinnedBy(entry.key) ~= "" then
        return p:getPzoptOption(entry.key)
    end
    local saved = p:getPzoptOptionSaved(entry.key)
    if saved ~= "" then
        return saved
    end
    return p:getPzoptOptionDefault(entry.key)
end

local function store(entry, value)
    if value == perf():getPzoptOptionDefault(entry.key) then
        perf():setPzoptOption(entry.key, "")
    else
        perf():setPzoptOption(entry.key, value)
    end
end

local function tooltipFor(entry, pinnedBy)
    local t = entry.tip .. " " .. RESTART_NOTE .. " Key: " .. entry.key .. "."
    if pinnedBy ~= "" then
        t = t .. " Pinned by " .. pinnedBy .. " for this install; the menu cannot change it."
    end
    return t
end

-- ---------------------------------------------------------------------------------------------------
-- Preview panel: the two clips, the description and the effect bars of the setting under the mouse.

-- Clips under media/ui/pzopt/compare/<clip>-stock.gif / -opt.gif (harness/menu-gifs.py, harness/menu-gifs.json
-- names the runs). A section's `clip` is the default for its keys; KEY_CLIP picks another for one key.
local CLIP_TITLES = {
    drive = "120 km/h highway drive, clear day, max zoom",
    spin = "Rosewood, camera spinning through town, max zoom",
    fog = "120 km/h drive in heavy fog, max zoom",
    storm = "120 km/h drive in a thunderstorm, max zoom",
    horde = "Downtown Louisville, zombie population maxed",
    torch = "Night, hand torch, turning in place",
    nightdrive = "Night drive with headlights",
    load = "Launch to the main menu, then Continue to the world (real time)",
}
local KEY_CLIP = {
    rainTiles = "storm", puddleCache = "storm", rainSplashesFast = "storm", puddleEarlyZ = "storm", puddleVbo = "storm",
    puddleCacheFrames = "storm", weatherMaskIdleSkip = "storm", weatherFxScalePct = "storm", vboBatchKb = "storm",
    vboFastQuads = "storm", lightingRebakeBudget = "storm", lightingRebakeMaxFrames = "storm",
    fogPass = "fog", fogScalePct = "fog", fogMaskFrames = "fog",
    lightingStrongDelta = "torch", lightingFlush = "torch", lightingBudget = "torch",
    lightSwitchCheckFrames = "horde", soundZoneCache = "horde", gridStackInterval = "horde",
    bakeBudget = "drive", rebakeBudget = "drive", rebakeMaxFrames = "drive", treeBakeMaxChunksPerSec = "drive",
    curtainDepthNudgePct = "spin", treeBakePass = "spin", treeBakeDirect = "spin", roofHideDebounceFrames = "spin",
}

-- One bar per resource. `less` / `more` are the words for the two directions; `moreIsWork` colours the "more"
-- side blue instead of amber: putting idle cores to work is the point, not a cost.
local AXES = {
    { id = "cpu", label = "CPU: game thread", less = "less", more = "more",
      tip = "The thread that simulates the world and prepares every frame; it is what limits the frame rate most of the time." },
    { id = "render", label = "CPU: render thread", less = "less", more = "more",
      tip = "The thread that feeds the GPU." },
    { id = "cores", label = "CPU: other cores", less = "less", more = "more", moreIsWork = true,
      tip = "Worker threads: chunk loading, boot and file decoding, sampling." },
    { id = "gpu", label = "GPU", less = "less", more = "more" },
    { id = "vram", label = "VRAM", less = "less", more = "more" },
    { id = "ram", label = "RAM", less = "less", more = "more" },
    { id = "disk", label = "Disk / caches", less = "less", more = "more" },
    { id = "load", label = "Boot and load time", less = "shorter", more = "longer" },
    { id = "chunks", label = "Chunk arrival", less = "sooner", more = "later" },
}
local MAGNITUDE = { "a little ", "", "much " }

-- How each key changes the load on the parts above against the stock game, -3 .. 3 (0 / absent = no measurable
-- change): -1 a few percent, -2 clearly measurable, -3 the big wins (docs/results.md, docs/findings-*.md). A combo's
-- bars describe moving it away from stock in the direction the tab offers.
local EFFECTS = {
    enabled = { cpu = -3, render = -3, gpu = -1, cores = 2, ram = 1, disk = 1, load = -3, chunks = -2 },
    -- chunk textures
    treesInChunkTexture = { cpu = -3, render = -2, gpu = -1 },
    treeBakeMaxChunksPerSec = { cpu = -1 },
    treeBakeDirect = {},
    treeBakePass = { cpu = 1 },
    treeAppend = { cpu = -2, gpu = -1 },
    windowsInChunkTexture = { cpu = -2, render = -1, gpu = -1 },
    translucentTilesInChunkTexture = { cpu = -3, render = -2, gpu = -1 },
    curtainDepthNudgePct = {},
    bakeBudget = { cpu = -2 },
    rebakeBudget = { cpu = -1 },
    rebakeMaxFrames = {},
    lightingRebakeBudget = { cpu = -2, gpu = -1 },
    lightingRebakeMaxFrames = { cpu = -1 },
    lightingRebakeMs = { cpu = -1 },
    lightingStrongDelta = { cpu = 1 },
    lightingGlobalDeltaPct = { cpu = -1 },
    lightingFlush = { cpu = 1 },
    lightingBudget = { cpu = -2 },
    -- cutaways, lighting, weather
    cutawayFast = { cpu = -2 },
    cutawayRadius = { cpu = -2 },
    gridStackInterval = { cpu = -1 },
    lightSwitchCheckFrames = { cpu = -1 },
    rainTiles = { cpu = -3, render = -3, gpu = -1 },
    puddleCache = { cpu = -3, ram = 1 },
    rainSplashesFast = { cpu = -1 },
    puddleEarlyZ = { gpu = -2 },
    puddleVbo = { render = -3, gpu = -1, vram = 1 },
    puddleCacheFrames = { cpu = -1 },
    weatherMaskIdleSkip = { cpu = -1, gpu = -1 },
    weatherFxScalePct = { gpu = -2, render = -1, vram = -1 },
    fogPass = { gpu = -3, render = -2, cpu = -1, vram = 1 },
    fogScalePct = { gpu = -2, vram = -1 },
    fogMaskFrames = { cpu = -1 },
    roofHideDebounceFrames = {},
    cutawayVisitPrefilter = { cpu = -1 },
    cutawayInvalidateChanged = { cpu = -2, gpu = -1 },
    occlusionSkipLightingOnly = { cpu = -1 },
    lightInfoChunkGate = { cpu = -1 },
    lightInfoOncePerFrame = { cpu = -1 },
    soundZoneCache = { cpu = -1 },
    -- sprite buffers
    persistentVbo = { render = -3, gpu = -1 },
    vboBatchKb = { render = -2, vram = 1 },
    vboFastQuads = { render = -1 },
    -- multiplayer
    luaChecksumExempt = {},
    -- overlay
    overlaySampling = { cpu = 1, cores = 1 },
    overlay = { cpu = 1 },
    overlayLog = { disk = 1 },
    overlayTree = { cpu = 1, cores = 1 },
    overlayVerdict = {},
    overlayGraph = { cpu = 1 },
    overlayFlame = { cpu = 1, cores = 1 },
    gameThreadProfileHz = { cores = 1, cpu = 1 },
    -- chunk streaming
    parallel = { cores = 3, ram = 1, chunks = -3 },
    workers = { cores = 2, chunks = -1 },
    loadWorkers = { cores = 2, load = -1 },
    wake = { chunks = -2 },
    chunkHandoffDivisor = { cpu = -1, chunks = 1 },
    hotsaveStaged = { cpu = -1 },
    hotsaveIntervalSec = { cpu = -2, disk = -2 },
    -- boot
    fmodAsync = { load = -2, cores = 1 },
    preloadAnimSets = { load = -2, cores = 1 },
    bootPump = { load = -2, cores = 2, ram = 1 },
    bootFileThreads = { load = -1, cores = 2 },
    earlyModels = { load = -1 },
    luaPrecompile = { load = -2, cores = 2 },
    animClipCache = { load = -2, disk = 2 },
    packIndex = { load = -2, disk = 1 },
    scriptParserFast = { load = -2, ram = -1 },
    itemParamSwitch = { load = -1 },
    -- world load
    fileThreads = { load = -1, cores = 2 },
    fileInflight = { load = -1, ram = 1 },
    textureBufferMb = { load = -1, ram = 2 },
    parallelDepthMaps = { load = -1, cores = 1 },
    loaderCpuFixes = { load = -1, cpu = -1 },
    shaderCache = { load = -3, render = -1 },
    mipmapArrays = { cores = -1 },
    noLoadFade = { load = -1 },
    noIntroWait = { load = -2 },
}

-- SDR values of the media style (docs/media-style.md): stock amber, optimized green, a third series blue
local C_STOCK = { r = 0.79, g = 0.35, b = 0.17 }
local C_OPT = { r = 0.27, g = 0.87, b = 0.49 }
local C_BLUE = { r = 0.23, g = 0.56, b = 0.88 }
local C_TEXT = { r = 0.94, g = 0.94, b = 0.96 }
local C_GREY = { r = 0.66, g = 0.66, b = 0.70 }
local C_DIM = { r = 0.40, g = 0.40, b = 0.45 }
local CLIP_W, CLIP_H = 512, 216

local function clipPath(clip, side)
    return "media/ui/pzopt/compare/" .. clip .. "-" .. side .. ".gif"
end

PzoptPreview = ISPanel:derive("PzoptPreview")

function PzoptPreview:new(x, y, w, h, panel, rows)
    local o = ISPanel.new(self, x, y, w, h)
    o.panel = panel   -- the scrolling options panel the rows live in
    o.rows = rows     -- { entry, option, clip, y, h } in the panel's content space
    o.row = nil
    o.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 1 } -- opaque: the list's long labels pass under it
    o.borderColor = { r = 0.31, g = 0.31, b = 0.35, a = 1 }
    o.pad = 12
    o.maxHeight = h
    o.fontS = UIFont.Small
    o.fontM = UIFont.Medium
    o.hS = getTextManager():getFontHeight(UIFont.Small)
    o.hM = getTextManager():getFontHeight(UIFont.Medium)
    return o
end

-- the wheel over the preview scrolls the options list, like anywhere else on the page
function PzoptPreview:onMouseWheel(del)
    return false
end

function PzoptPreview:select(row)
    self.row = row
end

-- The row under the mouse: the list's mouse position is in its content space (scroll included), like row.y.
function PzoptPreview:pick()
    local panel = self.panel
    if not panel:isMouseOver() or self:isMouseOver() then return end
    local mx, my = panel:getMouseX(), panel:getMouseY()
    if mx >= self.x then return end
    for _, row in ipairs(self.rows) do
        if my >= row.y and my < row.y + row.h then
            self:select(row)
            return
        end
    end
end

function PzoptPreview:text(str, x, y, col, font, alpha)
    self:drawText(str, x, y, col.r, col.g, col.b, alpha or 1, font or self.fontS)
end

-- One clip box: caption, the current frame (or why there is none), a hairline frame.
function PzoptPreview:drawClip(x, y, w, h, caption, path, now, col)
    self:text(caption, x, y, col, self.fontS)
    y = y + self.hS + 2
    self:drawRect(x, y, w, h, 1, 0.02, 0.02, 0.03)
    local ok, tex = pcall(function() return perf():getPzoptGifFrame(path, now) end)
    if ok and tex then
        self:drawTextureScaledAspect(tex, x, y, w, h, 1, 1, 1, 1)
    else
        local state = ok and perf():getPzoptGifState(path) or "error"
        local msg = "no clip for this setting yet"
        if state == "loading" then msg = "loading..." elseif state == "error" then msg = "clip could not be decoded" end
        self:drawTextCentre(msg, x + w / 2, y + h / 2 - self.hS / 2, C_DIM.r, C_DIM.g, C_DIM.b, 1, self.fontS)
    end
    self:drawRectBorder(x, y, w, h, 1, 0.31, 0.31, 0.35)
    return y + h
end

function PzoptPreview:drawWrapped(str, x, y, w, col, font)
    local wrapped = getTextManager():WrapText(font or self.fontS, str, w)
    local h = font == self.fontM and self.hM or self.hS
    for line in string.gmatch(wrapped, "[^\n]+") do
        self:text(line, x, y, col, font)
        y = y + h
    end
    return y
end

-- The effect bars: a track per axis with the zero line in the middle; the bar grows left for less work / sooner
-- (green) and right for more (amber, or blue where more means idle cores put to work), a word says the same.
function PzoptPreview:drawBars(x, y, w, fx)
    local labW = math.min(170, math.floor(w * 0.3))
    local wordW = 110
    local barX = x + labW + 10
    local barW = w - labW - 10 - wordW - 8
    local rowH = self.hS + 6
    local mid = barX + math.floor(barW / 2)
    local half = math.floor(barW / 2) - 2
    for _, axis in ipairs(AXES) do
        local v = fx[axis.id] or 0
        if v > 3 then v = 3 elseif v < -3 then v = -3 end
        self:drawTextRight(axis.label, x + labW, y + 3, C_GREY.r, C_GREY.g, C_GREY.b, 1, self.fontS)
        self:drawRect(barX, y + 3, barW, rowH - 6, 1, 0.11, 0.11, 0.13)
        local word, wcol = "no change", C_DIM
        if v ~= 0 then
            local len = math.floor(half * math.abs(v) / 3)
            local col = v < 0 and C_OPT or (axis.moreIsWork and C_BLUE or C_STOCK)
            local bx = v < 0 and (mid - len) or (mid + 1)
            self:drawRect(bx, y + 3, len, rowH - 6, 1, col.r, col.g, col.b)
            word = MAGNITUDE[math.abs(v)] .. (v < 0 and axis.less or axis.more)
            wcol = col
        end
        self:drawRect(mid, y + 1, 1, rowH - 2, 1, 0.55, 0.55, 0.60)
        self:text(word, barX + barW + 8, y + 3, wcol, self.fontS)
        y = y + rowH
    end
    return y
end

function PzoptPreview:prerender()
    ISPanel.prerender(self)
    self:pick()
    local row = self.row
    local pad = self.pad
    local x, y, w = pad, pad, self.width - 2 * pad
    if not row then
        self:text("Point at a setting to see what it does.", x, y, C_GREY, self.fontM)
        return
    end
    local entry = row.entry
    local p = perf()
    -- title, key and values
    y = self:drawWrapped(entry.label, x, y, w, C_TEXT, self.fontM) + 2
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local values = "Key " .. entry.key .. "   since this boot: " .. p:getPzoptOption(entry.key)
        .. "   next launch: " .. row.option:pzoptCurrent()
    if pinnedBy ~= "" then values = values .. "   (pinned by " .. pinnedBy .. ")" end
    y = self:drawWrapped(values, x, y, w, C_GREY) + 8
    -- the two clips
    local gap = pad
    local iw = math.floor((w - gap) / 2)
    local ih = math.floor(iw * CLIP_H / CLIP_W)
    local now = getTimestampMs()
    self:drawClip(x, y, iw, ih, "STOCK GAME", clipPath(row.clip, "stock"), now, C_STOCK)
    y = self:drawClip(x + iw + gap, y, iw, ih, "OPTIMIZED (every optimization on)", clipPath(row.clip, "opt"), now, C_OPT) + 4
    y = self:drawWrapped((CLIP_TITLES[row.clip] or row.clip) .. ". Same save, route and machine; the number is that run's live frame rate.",
        x, y, w, C_DIM) + 8
    -- what it does
    y = self:drawWrapped(entry.tip, x, y, w, C_TEXT) + 8
    -- the bars
    self:text("Effect on your hardware", x, y, C_TEXT, self.fontM)
    y = y + self.hM + 4
    y = self:drawBars(x, y, w, EFFECTS[entry.key] or {})
    y = self:drawWrapped("Against the stock game, from the measurements in docs/results.md: green = less work or sooner, "
        .. "amber = more, blue = idle cores put to work. " .. RESTART_NOTE, x, y + 4, w, C_DIM)
    -- the panel is as tall as its content, never taller than the page
    local h = math.min(y + pad, self.maxHeight or (y + pad))
    if h ~= self.height then self:setHeight(h) end
end

local function comboLabels(entry, default, saved)
    local labels = { "Default (" .. default .. ")" }
    local values = {}
    local seen = {}
    for _, v in ipairs(entry.choices) do
        local text = v
        if entry.note and entry.note[v] then
            text = v .. " (" .. entry.note[v] .. ")"
        end
        table.insert(labels, text)
        table.insert(values, v)
        seen[v] = true
    end
    -- a value typed into options.ini by hand that is not in the list stays selectable
    if saved ~= "" and not seen[saved] then
        table.insert(labels, saved)
        table.insert(values, saved)
    end
    return labels, values
end

local function addBoolOption(self, entry, splitpoint, y, BUTTON_HGT)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local box = self:addYesNo(splitpoint, y, BUTTON_HGT, BUTTON_HGT, entry.label)
    box.tooltip = tooltipFor(entry, pinnedBy)
    if pinnedBy ~= "" then
        box.enable = false
    end
    local option = GameOption:new("pzopt." .. entry.key, box)
    function option.toUI(self)
        self.control:setSelected(1, nextValue(entry) == "true")
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local value = tostring(self.control:isSelected(1))
        store(entry, value)
        self:restartRequired(perf():getPzoptOption(entry.key), value)
    end
    -- the "Enable all" button puts the control back to the build's default
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        self.control:setSelected(1, perf():getPzoptOptionDefault(entry.key) == "true")
    end
    -- a profile button sets an explicit value (nil = the build's default)
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        if value == nil then return self:pzoptReset() end
        self.control:setSelected(1, value == "true")
    end
    -- what the control says right now (the preview panel's "next launch" value)
    function option.pzoptCurrent(self)
        return tostring(self.control:isSelected(1))
    end
    option.pzoptKey = entry.key
    self.gameOptions:add(option)
    return option
end

local function addIntOption(self, entry, splitpoint, y, comboWidth)
    local p = perf()
    local pinnedBy = p:getPzoptOptionPinnedBy(entry.key)
    local labels, values = comboLabels(entry, p:getPzoptOptionDefault(entry.key), p:getPzoptOptionSaved(entry.key))
    local combo = self:addCombo(splitpoint, y, comboWidth, 20, entry.label, labels, 1)
    combo:setToolTipMap({ defaultTooltip = tooltipFor(entry, pinnedBy) })
    if pinnedBy ~= "" then
        combo.disabled = true
    end
    local function indexOf(value)
        for i, v in ipairs(values) do
            if v == value then return i + 1 end
        end
        return nil
    end
    local option = GameOption:new("pzopt." .. entry.key, combo)
    function option.toUI(self)
        local pp = perf()
        local box = self.control
        if pinnedBy ~= "" then
            box.selected = indexOf(pp:getPzoptOption(entry.key)) or 1
        else
            local saved = pp:getPzoptOptionSaved(entry.key)
            box.selected = (saved ~= "" and indexOf(saved)) or 1
        end
    end
    function option.apply(self)
        if pinnedBy ~= "" then return end
        local box = self.control
        local value = box.selected > 1 and values[box.selected - 1] or ""
        perf():setPzoptOption(entry.key, value)
        local effective = value ~= "" and value or perf():getPzoptOptionDefault(entry.key)
        self:restartRequired(perf():getPzoptOption(entry.key), effective)
    end
    function option.pzoptReset(self)
        if pinnedBy ~= "" then return end
        self.control.selected = 1 -- "Default (...)"
    end
    function option.pzoptSet(self, value)
        if pinnedBy ~= "" then return end
        local index = value ~= nil and indexOf(value) or nil
        if index == nil then
            -- a profile value outside the combo's list becomes selectable, like a hand-typed one
            if value ~= nil and value ~= perf():getPzoptOptionDefault(entry.key) then
                table.insert(labels, value)
                table.insert(values, value)
                self.control:addOption(value)
                index = #values + 1
            else
                index = 1
            end
        end
        self.control.selected = index
    end
    function option.pzoptCurrent(self)
        local box = self.control
        if box.selected > 1 and values[box.selected - 1] then
            return values[box.selected - 1]
        end
        return perf():getPzoptOptionDefault(entry.key) .. " (default)"
    end
    option.pzoptKey = entry.key
    self.gameOptions:add(option)
    return option
end

-- "Enable all": master on, every other control back to the build's default. "Disable all (stock)":
-- master off, the other controls untouched (they are ignored while the master is off). Neither writes
-- anything: the controls are marked changed and Apply / Accept saves them through the options above.
local function setAll(self, enable)
    local master = self.pzoptMaster
    if master and master.control.enable then
        master.control:setSelected(1, enable)
        master:invokeOnChangeEvent()
    end
    if enable then
        for _, option in ipairs(self.pzoptOptions) do
            option:pzoptReset()
            option:invokeOnChangeEvent()
        end
    end
end

-- Profiles: one button sets a named group of controls (the rest go back to the build's default),
-- master on; Apply / Accept saves them like the other buttons. Values are the option strings.
local PROFILES = {
    {
        button = "Low-end hardware (4 cores or less)",
        tip = "Turns the master switch on and picks the settings measured on a 4-core CPU with an old GPU "
           .. "(Core i5-6300HQ / GTX 960M, 2026-09-21): no chunk worker pool (its threads took the game thread's core), "
           .. "trees baked only while walking (while driving a chunk texture lives seconds, and baking its trees cost "
           .. "more than drawing them per frame), and on the Display page lighting updates 10/s and the UI redrawn 30 "
           .. "times a second (the lighting thread and the Lua UI were the next biggest users of the four cores). "
           .. "Everything else goes back to the build's default. 120 km/h drive 44 -> 68 fps, walking 49 -> 81 "
           .. "(p99 80 -> 40 ms / 69 -> 30 ms); the launcher's G1 collector JSON is needed on top. See docs/results.md.",
        values = {
            workers = "1",
            loadWorkers = "2",
            treeBakeMaxChunksPerSec = "24",
        },
        -- stock Display-page combos by GameOption name -> combo index (MainOptions.lua lists):
        -- lightingFPS {5, 10, 15, 20, 25, 30, 45, 60}, UIRenderFPS {120, 60, 30, 25, 20, 15, 10}
        stock = { lightingFPS = 2, UIRenderFPS = 3 },
    },
}

local function applyProfile(self, profile)
    local master = self.pzoptMaster
    if master and master.control.enable then
        master.control:setSelected(1, true)
        master:invokeOnChangeEvent()
    end
    for _, option in ipairs(self.pzoptOptions) do
        option:pzoptSet(profile.values[option.pzoptKey])
        option:invokeOnChangeEvent()
    end
    for name, index in pairs(profile.stock or {}) do
        local option = self.gameOptions:get(name)
        local box = option and option.control
        if box and box.options and box.options[index] then
            box.selected = index
            option:invokeOnChangeEvent()
        else
            print("[pzopt] options tab: profile could not set stock option " .. name)
        end
    end
end

local function addAllButtons(self, splitpoint, y)
    local on = self:addButton(splitpoint, y, "Enable all (recommended defaults)")
    on.tooltip = "Turns the master switch on and puts every setting below back to the build's default on this machine. " .. RESTART_NOTE
    on.target = self
    on.onclick = function(target) setAll(target, true) end
    local off = self:addButton(splitpoint, y, "Disable all (stock game)")
    off.tooltip = "Turns the master switch off: the game runs its original code everywhere, as if the overrides were not installed. The settings below are kept for when you enable them again. " .. RESTART_NOTE
    off.target = self
    off.onclick = function(target) setAll(target, false) end
    local profileButtons = {}
    for _, profile in ipairs(PROFILES) do
        local b = self:addButton(splitpoint, y, profile.button)
        b.tooltip = profile.tip .. " " .. RESTART_NOTE
        b.target = self
        b.onclick = function(target) applyProfile(target, profile) end
        table.insert(profileButtons, b)
    end
    if self.pzoptMaster and not self.pzoptMaster.control.enable then
        on:setEnable(false)
        off:setEnable(false)
        on.tooltip = "Pinned by " .. perf():getPzoptOptionPinnedBy(MASTER.key) .. " for this install."
        off.tooltip = on.tooltip
        for _, b in ipairs(profileButtons) do
            b:setEnable(false)
            b.tooltip = on.tooltip
        end
    end
end

-- A section heading: a rule that stops short of the preview panel and the title above the label column.
local function addSectionLine(self, y, text, x0, width)
    local spacing = MainOptions.style.borderSpacing
    local hM = MainOptions.style:getFontHeight("Medium")
    local line = ISPanel:new(x0, self.addY + y, width, 2)
    line.prerender = function() end
    line.render = function(o) o:drawRect(0, 0, o.width, 1, 1.0, 0.5, 0.5, 0.5) end
    line:initialise()
    self.mainPanel:addChild(line)
    local shown = getTextManager():WrapText(UIFont.Medium, text, width, 1, "...")
    local label = ISLabel:new(x0, self.addY + y + spacing, hM, shown, 1, 1, 1, 1, UIFont.Medium, true)
    label:initialise()
    self.mainPanel:addChild(label)
    self.addY = self.addY + spacing * 2 + hM
end

-- Layout: the label column (right-aligned labels), the controls, then the fixed preview panel, the whole block
-- centred in the page. The preview takes ~36 % of the width between 600 and 1100 px; on a narrow screen it
-- shrinks so the controls keep their room.
local function layout(self, comboWidth)
    local W = self:getWidth()
    local labelW, gap, margin, sbar = 360, 40, 16, 13
    local previewW = math.max(600, math.min(1100, math.floor(W * 0.36)))
    local controlW = comboWidth
    for _, title in ipairs({ "Enable all (recommended defaults)", "Disable all (stock game)" }) do
        controlW = math.max(controlW, getTextManager():MeasureStringX(UIFont.Small, title) + 24)
    end
    for _, profile in ipairs(PROFILES) do
        controlW = math.max(controlW, getTextManager():MeasureStringX(UIFont.Small, profile.button) + 24)
    end
    local controlsW = labelW + 20 + controlW
    local avail = W - 2 * margin - sbar
    if controlsW + gap + previewW > avail then
        previewW = math.max(360, avail - controlsW - gap)
    end
    local blockW = controlsW + gap + previewW
    local x0 = math.max(margin, math.floor((W - sbar - blockW) / 2))
    return { x0 = x0, splitpoint = x0 + labelW, previewX = x0 + controlsW + gap, previewW = previewW,
             lineW = controlsW + gap / 2, margin = margin }
end

function MainOptions:pzoptAddOptimizationsPanel()
    local style = MainOptions.style
    local BUTTON_HGT = style.buttonHeight
    local y = style.initialY
    self.addY = 0
    local comboWidth = 45 * (getCore():getOptionFontSizeReal() + 1) + 60
    local L = layout(self, comboWidth)
    local splitpoint = L.splitpoint

    self:addPage(TAB)
    local panel = self.mainPanel
    local p = perf()
    local added, pinned = 0, 0
    self.pzoptOptions = {}
    self.pzoptMaster = nil
    local rows = {}
    local function addRow(entry, option, clip)
        table.insert(rows, { entry = entry, option = option, clip = clip,
                             y = option.control:getY(), h = math.max(option.control:getHeight(), BUTTON_HGT) })
    end
    local state = p:isPzoptEnabled() and "on" or "OFF: the game is running stock"
    addSectionLine(self, y, "All optimizations (since this boot: " .. state .. ")", L.x0, L.lineW)
    if p:isPzoptOptionKnown(MASTER.key) then
        self.pzoptMaster = addBoolOption(self, MASTER, splitpoint, y, BUTTON_HGT)
        addRow(MASTER, self.pzoptMaster, "drive")
        if p:getPzoptOptionPinnedBy(MASTER.key) ~= "" then pinned = pinned + 1 end
    end
    addAllButtons(self, splitpoint, y)
    for _, section in ipairs(SECTIONS) do
        addSectionLine(self, y, section.title, L.x0, L.lineW)
        for _, entry in ipairs(section.entries) do
            if p:isPzoptOptionKnown(entry.key) then
                local option
                if entry.choices then
                    option = addIntOption(self, entry, splitpoint, y, comboWidth)
                else
                    option = addBoolOption(self, entry, splitpoint, y, BUTTON_HGT)
                end
                table.insert(self.pzoptOptions, option)
                addRow(entry, option, KEY_CLIP[entry.key] or section.clip or "drive")
                added = added + 1
                if p:getPzoptOptionPinnedBy(entry.key) ~= "" then pinned = pinned + 1 end
            else
                print("[pzopt] options tab: unknown key " .. entry.key .. ", skipped")
            end
        end
    end
    addSectionLine(self, y, "Changes take effect on the next launch. File: Zomboid/pzopt/options.ini", L.x0, L.lineW)
    -- Same as the stock pages: without a scroll height the panel never scrolls, so the
    -- controls below the window edge are unreachable.
    panel:setScrollHeight(y + self.addY + 20)
    -- The preview panel: a child of the page that does not scroll with it, full page height, the master
    -- switch shown until the mouse points at another row.
    local preview = PzoptPreview:new(L.previewX, L.margin, L.previewW, panel:getHeight() - 2 * L.margin, panel, rows)
    preview:initialise()
    preview:instantiate()
    preview:setScrollWithParent(false)
    preview:setAnchorTop(true)
    preview:setAnchorBottom(true)
    panel:addChild(preview)
    if rows[1] then preview:select(rows[1]) end
    self.pzoptPreview = preview
    print("[pzopt] options tab: " .. added .. " controls, " .. pinned .. " pinned by pzopt.properties or -D, "
        .. #rows .. " preview rows, preview " .. L.previewW .. " px at x=" .. L.previewX)
end

local function install()
    if not MainOptions or MainOptions.pzoptOptimizationsTab then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then
        print("[pzopt] options tab: PerformanceSettings override not loaded or overrides disabled, tab not added")
        return
    end
    MainOptions.pzoptOptimizationsTab = true
    -- The tab goes right after Display: create() adds the pages in order, so hook the Display page.
    local stockAddDisplayPanel = MainOptions.addDisplayPanel
    function MainOptions:addDisplayPanel()
        stockAddDisplayPanel(self)
        local okPanel, err = pcall(MainOptions.pzoptAddOptimizationsPanel, self)
        if not okPanel then
            print("[pzopt] options tab: failed: " .. tostring(err))
        end
    end
    -- Closing the options screen (Back / Accept hide it) frees the preview clips' textures; the next
    -- visit decodes them again.
    local stockSetVisible = MainOptions.setVisible
    function MainOptions:setVisible(bVisible, ...)
        stockSetVisible(self, bVisible, ...)
        if not bVisible then
            pcall(function() getPerformance():releasePzoptGifs() end)
        end
    end
end

install()
Events.OnGameBoot.Add(install)
