-- pzopt harness: hands-off game runs for PZ_Optimization.
--
-- harness/run.sh writes ~/Zomboid/Lua/pzopt-harness.txt (getFileReader resolves
-- under Lua/) as key=value lines before launching the game. If the file names a
-- mode, this script continues latestSave.ini from the main menu's own tick as
-- soon as the menu accepts input (no click, no fixed wait) and, when quit_after is set, quits that many
-- seconds after the world is up. Everything else the harness does lives in
-- Java (pzopt.*), driven from the overridden WorldStreamer on the game thread.
--
-- Protocol on the flag file (it is the only state that survives: this script
-- is reloaded when the game resets Lua for the save's mod set, and the menu
-- fires OnMainMenuEnter more than once at start-up):
--   consumed=1   appended here once Continue has been triggered in this process
--   started=1    appended by pzopt.Harness once the world was up; back at the
--                menu with it set means the run is over -> quit to desktop
-- Without the file this script does nothing.

local FLAG_FILE = "pzopt-harness.txt"

local rawLines = {}

local function readFlags()
    local reader = getFileReader(FLAG_FILE, false)
    if not reader then return nil end
    local flags = {}
    local n = 0
    rawLines = {}
    while true do
        local line = reader:readLine()
        if line == nil then break end
        table.insert(rawLines, line)
        local k, v = string.match(line, "^%s*([%w_]+)%s*=%s*(.-)%s*$")
        if k then flags[k] = v; n = n + 1 end
    end
    reader:close()
    if n == 0 then return nil end
    return flags
end

local function appendFlag(line)
    -- true append: pzopt.Harness may already have added started=1 (the game auto-loads the
    -- save before the menu delay elapses), and rewriting from a stale copy would drop it
    local w = getFileWriter(FLAG_FILE, true, true)
    if not w then return end
    w:write(line .. "\n")
    w:close()
end

local pending = nil
local quitAtMs = nil

local function onMainMenuEnter()
    local flags = readFlags()
    if not flags or not flags.mode then return end
    if flags.started then
        print("[pzopt-harness] run finished, quitting to desktop")
        getCore():quit()
        return
    end
    if flags.consumed then return end -- Continue already triggered by this process (Lua was reset)
    print("[pzopt-harness] mode=" .. tostring(flags.mode) .. " quit_after=" .. tostring(flags.quit_after))
    pending = flags
end

-- OnFETick is the only per-frame event the main menu fires (OnTickEvenPaused is in-world only,
-- which is why earlier versions of this file never pressed Continue by themselves)
local function onFETick()
    if pending then
        local ms = MainScreen.instance
        -- MainScreen ignores menu actions while its own start-up delay runs; wait for that, nothing more
        if not ms or (ms.delay and ms.delay > 0) then return end
        local flags = pending
        pending = nil
        appendFlag("consumed=1")
        -- quit_after counts from here whether we continue or the game is already loading the save
        if flags.quit_after then
            local secs = tonumber(flags.quit_after)
            if secs then quitAtMs = getTimestampMs() + secs * 1000 end
        end
        if getPlayer() or ms.inGame then
            print("[pzopt-harness] a world is already loading; not continuing")
            return
        end
        if not MainScreen.latestSaveWorld then
            print("[pzopt-harness] no latest save to continue")
            return
        end
        print("[pzopt-harness] continuing latest save " .. tostring(MainScreen.latestSaveWorld) .. " (" .. tostring(MainScreen.latestSaveGameMode) .. ")")
        MainScreen.continueLatestSave(MainScreen.latestSaveGameMode, MainScreen.latestSaveWorld)
    end
end

-- lure=<animal type> (2026-09-22, CanSee repro): lure_at seconds after the player exists, spawn the
-- animal 8 tiles away, put a carrot in the primary hand and queue the stock ISLureAnimal action, i.e.
-- the context menu's "Lure" path (lureAnimal -> IsoAnimal.tryLure -> CanSee(IsoMovingObject)). A
-- status line every 2 s: distance, lured count, current action. Stock: the animal walks up to the player.
local lure = nil
local function lureTick()
    if lure == false then return end
    local player = getPlayer()
    if not player then return end
    if lure == nil then
        local flags = readFlags()
        if not flags or not flags.lure or flags.lure == "" then lure = false; return end
        lure = { kind = flags.lure, breed = flags.lure_breed or "holstein",
                 atMs = getTimestampMs() + (tonumber(flags.lure_at) or 10) * 1000 }
    end
    local now = getTimestampMs()
    if not lure.animal then
        if now < lure.atMs then return end
        local sq = player:getCurrentSquare()
        local target = nil
        for _, d in ipairs({ {8, 0}, {-8, 0}, {0, 8}, {0, -8}, {6, 6}, {-6, -6} }) do
            local s = getCell():getGridSquare(sq:getX() + d[1], sq:getY() + d[2], sq:getZ())
            if s and s:isFree(false) then target = s; break end
        end
        if not target then print("[pzopt-harness] lure: no free square 8 tiles from the player"); lure = false; return end
        local breed = AnimalDefinitions.getDef(lure.kind):getBreedByName(lure.breed)
        local animal = addAnimal(getCell(), target:getX(), target:getY(), target:getZ(), lure.kind, breed)
        animal:addToWorld()
        lure.animal = animal
        local item = player:getInventory():AddItem("Base.Carrots")
        player:setPrimaryHandItem(item)
        print("[pzopt-harness] lure: " .. lure.kind .. " at " .. target:getX() .. "," .. target:getY() .. ", player at " .. sq:getX() .. "," .. sq:getY() .. ", queueing ISLureAnimal with " .. item:getFullType())
        ISTimedActionQueue.add(ISLureAnimal:new(player, animal, item))
        lure.logMs = now
        return
    end
    if now - lure.logMs >= 2000 then
        lure.logMs = now
        print(string.format("[pzopt-harness] lure: dist=%.1f lured=%d action=%s", lure.animal:DistTo(player),
            player:getLuredAnimals():size(),
            tostring(ISTimedActionQueue.getTimedActionQueue(player).queue[1] and ISTimedActionQueue.getTimedActionQueue(player).queue[1].Type)))
    end
end

-- options_check=S (2026-09-23): S seconds into the world, activate the Optimizations tab of the in-game options
-- screen (built lazily on first activation since the lazy-tab change) through the stock tab path, without
-- showing the screen, and log whether it built, its control count and whether building left the screen
-- "changed" (it must not: Accept would then save untouched values).
local optionsCheck = nil
local function optionsCheckTick()
    if optionsCheck == false then return end
    if not getPlayer() then return end
    if optionsCheck == nil then
        local flags = readFlags()
        if not flags or not flags.options_check or flags.options_check == "" then optionsCheck = false; return end
        optionsCheck = { atMs = getTimestampMs() + (tonumber(flags.options_check) or 10) * 1000 }
    end
    if getTimestampMs() < optionsCheck.atMs then return end
    optionsCheck = false
    local mo = MainScreen.instance and MainScreen.instance.mainOptions
    if not mo or not mo.tabs then print("[pzopt-harness] options check: no in-game MainOptions"); return end
    local before = mo.pzoptBuilt
    local nBefore = #mo.gameOptions.options
    local t0 = getTimestampMs()
    mo.tabs:activateView("Optimizations")
    print(string.format("[pzopt-harness] options check: built before=%s after=%s in %d ms, controls=%d, game options %d -> %d, changed=%s, active=%s",
        tostring(before), tostring(mo.pzoptBuilt), getTimestampMs() - t0, mo.pzoptOptions and #mo.pzoptOptions or -1,
        nBefore, #mo.gameOptions.options, tostring(mo.gameOptions.changed), tostring(mo.tabs:getActiveView() == mo.pzoptPanel)))
    mo.tabs:activateView("Optimizations") -- a second activation must not build again
    print("[pzopt-harness] options check: second activation, game options " .. #mo.gameOptions.options)
end

local function onTickEvenPaused()
    local ok, err = pcall(lureTick)
    if not ok then print("[pzopt-harness] lure: rig error " .. tostring(err)); lure = false end
    local ok2, err2 = pcall(optionsCheckTick)
    if not ok2 then print("[pzopt-harness] options check: rig error " .. tostring(err2)); optionsCheck = false end
    if quitAtMs and getTimestampMs() >= quitAtMs then
        quitAtMs = nil
        print("[pzopt-harness] quit_after reached, quitting")
        getCore():quit()
    end
end

Events.OnMainMenuEnter.Add(onMainMenuEnter)
Events.OnFETick.Add(onFETick)
Events.OnTickEvenPaused.Add(onTickEvenPaused)
