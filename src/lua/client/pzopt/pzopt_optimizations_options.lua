-- pzopt: "Optimizations" tab in the options screen, right after Display.
--  Every pzopt.Config key is a control here: booleans are tick boxes, integers are combos whose first
--  entry is the build's default on this machine. The values live in Java: the overridden
--  PerformanceSettings forwards to pzopt.Config (what is in force since boot) and pzopt.UserOptions
--  (Zomboid/pzopt/options.ini, what the next launch will read). Everything applies on the next
--  launch, so a change away from the boot value raises the stock "restart required" dialog.
--  A key set in the install dir's pzopt.properties or as -Dpzopt.<key> (harness runs) wins over the
--  file; its control shows that value, is disabled, and the tooltip says what pins it.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

local TAB = "Optimizations"
local RESTART_NOTE = "Takes effect on the next launch."

-- Keys, labels and tooltips. `choices` makes an integer combo; `note[value]` annotates an entry.
local SECTIONS = {
    {
        title = "Chunk textures: what bakes",
        entries = {
            { key = "treesInChunkTexture", label = "Bake trees into chunk textures",
              tip = "Static trees are drawn once into the chunk textures instead of every frame; only fading trees stay per-frame. Off = stock (every tree every frame)." },
            { key = "treeBakeDirect", label = "Bake trees one by one",
              tip = "Baked trees go through the plain sprite path. The batched path drops the largest (jumbo) trees near buildings." },
            { key = "windowsInChunkTexture", label = "Bake windows into chunk textures",
              tip = "Windows and glass doors bake like walls instead of being drawn every frame." },
            { key = "translucentTilesInChunkTexture", label = "Bake translucent tiles (experimental)",
              tip = "Fences, railings, wall decorations and overlays bake instead of drawing every frame. Known issue: some tiles bake as opaque black." },
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
            { key = "weatherMaskIdleSkip", label = "Skip the weather mask while nothing is drawn",
              tip = "Outdoors with no clouds, fog or rain the per-frame weather-mask view scan and mask draw are skipped; indoors only the player's building is scanned." },
        },
    },
    {
        title = "Sprite buffers (experimental)",
        entries = {
            { key = "persistentVbo", label = "Persistently mapped sprite buffers (experimental)",
              tip = "Sprite ring buffers use persistently mapped buffer storage. No gain at the 240 fps cap and suspected for a black building lot." },
        },
    },
    {
        title = "Performance overlay (F9, or the \"Toggle performance overlay\" key binding)",
        entries = {
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
    self.gameOptions:add(option)
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
    self.gameOptions:add(option)
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
    for _, section in ipairs(SECTIONS) do
        self:addHorizontalLine(y, section.title)
        for _, entry in ipairs(section.entries) do
            if p:isPzoptOptionKnown(entry.key) then
                if entry.choices then
                    addIntOption(self, entry, splitpoint, y, comboWidth)
                else
                    addBoolOption(self, entry, splitpoint, y, BUTTON_HGT)
                end
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
