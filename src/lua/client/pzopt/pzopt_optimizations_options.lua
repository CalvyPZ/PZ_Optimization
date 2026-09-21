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
        title = "Chunk textures: what bakes",
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
        title = "Chunk textures: bake budgets",
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
            { key = "lightingBudget", label = "Chunk lighting refreshes per frame",
              choices = { "0", "2", "4", "8", "16", "32" }, note = { ["0"] = "unlimited, stock" },
              tip = "Chunks whose square light info is refreshed in one frame; the rest continue next frame." },
        },
    },
    {
        title = "Cutaways, lighting and weather (game thread)",
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
        title = "Sprite buffers",
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
        title = "Performance overlay (F9, or the \"Toggle performance overlay\" key binding)",
        entries = {
            { key = "overlaySampling", label = "Sample frame times and utilization (needed for F9)",
              tip = "Records every presented frame, times the GPU with GL timer queries and samples the CPU load twice a second on a background thread. Off by default: without it F9 only shows a notice. \"Show the overlay from boot\" and \"Log every presented frame\" turn it on too. Applies on the next launch." },
            { key = "overlay", label = "Show the overlay from boot",
              tip = "Frame rate, frame-time tail (p99, p99.9, max, 1%-low, jitter, spikes), GPU busy share, game and render thread load, and a frame-time graph. The key toggles it any time." },
            { key = "overlayLog", label = "Log every presented frame",
              tip = "Writes Zomboid/pzopt-overlay.out, one CSV row per frame in MangoHud's column names, for harness/analyze.py. Harness runs log regardless." },
            { key = "overlayCorner", label = "Overlay corner",
              choices = { "tl", "tr", "bl", "br" },
              tip = "Where the overlay sits: top-left, top-right, bottom-left, bottom-right." },
            { key = "overlayFont", label = "Overlay font",
              choices = { "CodeMedium", "CodeSmall", "CodeLarge", "Small", "Medium", "Large" },
              tip = "The UI font the overlay text uses." },
        },
    },
    {
        title = "Performance overlay: fps colour",
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
        title = "Chunk streaming",
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
        title = "Boot: threads and caches",
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
        title = "Boot: parsers",
        entries = {
            { key = "scriptParserFast", label = "Linear script parser",
              tip = "Script comments strip in one pass and tokens parse without re-substringing; identical output (the stock passes cost 1.8 s at boot)." },
            { key = "itemParamSwitch", label = "Item parameter switch",
              tip = "Item script fields dispatch through a switch instead of a chain of 361 string compares per parameter (0.9 s of boot)." },
        },
    },
    {
        title = "World load: file system and decoding",
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
        title = "World load: loading screen",
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

function MainOptions:pzoptAddOptimizationsPanel()
    local style = MainOptions.style
    local BUTTON_HGT = style.buttonHeight
    local y = style.initialY
    self.addY = 0
    local splitpoint = self:getWidth() / 2
    local comboWidth = 45 * (getCore():getOptionFontSizeReal() + 1) + 60

    self:addPage(TAB)
    local p = perf()
    local added, pinned = 0, 0
    self.pzoptOptions = {}
    self.pzoptMaster = nil
    local state = p:isPzoptEnabled() and "on" or "OFF: the game is running stock"
    self:addHorizontalLine(y, "All optimizations (since this boot: " .. state .. ")")
    if p:isPzoptOptionKnown(MASTER.key) then
        self.pzoptMaster = addBoolOption(self, MASTER, splitpoint, y, BUTTON_HGT)
        if p:getPzoptOptionPinnedBy(MASTER.key) ~= "" then pinned = pinned + 1 end
    end
    addAllButtons(self, splitpoint, y)
    for _, section in ipairs(SECTIONS) do
        self:addHorizontalLine(y, section.title)
        for _, entry in ipairs(section.entries) do
            if p:isPzoptOptionKnown(entry.key) then
                local option
                if entry.choices then
                    option = addIntOption(self, entry, splitpoint, y, comboWidth)
                else
                    option = addBoolOption(self, entry, splitpoint, y, BUTTON_HGT)
                end
                table.insert(self.pzoptOptions, option)
                added = added + 1
                if p:getPzoptOptionPinnedBy(entry.key) ~= "" then pinned = pinned + 1 end
            else
                print("[pzopt] options tab: unknown key " .. entry.key .. ", skipped")
            end
        end
    end
    self:addHorizontalLine(y, "Changes take effect on the next launch. File: Zomboid/pzopt/options.ini")
    -- Same as the stock pages: without a scroll height the panel never scrolls, so the
    -- controls below the window edge are unreachable.
    self.mainPanel:setScrollHeight(y + self.addY + 20)
    self:centerTabChildrenX(TAB)
    print("[pzopt] options tab: " .. added .. " controls, " .. pinned .. " pinned by pzopt.properties or -D")
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
end

install()
Events.OnGameBoot.Add(install)
