-- pzopt harness: hands-off game runs for PZ_Optimization.
--
-- harness/run.sh writes ~/Zomboid/Lua/pzopt-harness.txt (getFileReader resolves under Lua/) (key=value lines) before
-- launching the game. If the file names a mode, this script presses
-- "Continue" on the main menu so latestSave.ini loads without any clicking,
-- and, when quit_after is set, quits that many seconds after the world is up.
-- Once acted on, "consumed=1" is appended so a return to the main menu does
-- not loop; the Java side (pzopt.Harness) still reads the other keys. Everything else the harness does lives in Java (pzopt.*), driven
-- from the overridden WorldStreamer on the game thread.

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

local function consumeFlags()
    local w = getFileWriter(FLAG_FILE, true, false)
    if not w then return end
    for _, line in ipairs(rawLines) do w:write(line .. "\n") end
    w:write("consumed=1\n")
    w:close()
end

local pending = nil
local delayTicks = 0

local function onMainMenuEnter()
    local flags = readFlags()
    if not flags or not flags.mode then return end
    if flags.consumed then
        -- back at the menu after a harness run: end the process so run.sh returns
        print("[pzopt-harness] run finished, quitting to desktop")
        getCore():quit()
        return
    end
    print("[pzopt-harness] mode=" .. tostring(flags.mode) .. " quit_after=" .. tostring(flags.quit_after))
    pending = flags
    delayTicks = 90 -- let the menu finish building before pressing Continue
end

local quitAtMs = nil

local function onTickEvenPaused()
    if pending then
        delayTicks = delayTicks - 1
        if delayTicks > 0 then return end
        local flags = pending
        pending = nil
        consumeFlags()
        if getPlayer() or (MainScreen.instance and MainScreen.instance.inGame) then
            print("[pzopt-harness] a world is already loading; not pressing Continue")
            return
        end
        if not MainScreen.latestSaveWorld then
            print("[pzopt-harness] no latest save to continue")
            return
        end
        print("[pzopt-harness] continuing latest save " .. tostring(MainScreen.latestSaveWorld))
        if flags.quit_after then
            local secs = tonumber(flags.quit_after)
            if secs then quitAtMs = getTimestampMs() + secs * 1000 end
        end
        MainScreen.continueLatestSave(MainScreen.latestSaveGameMode, MainScreen.latestSaveWorld)
        return
    end
    if quitAtMs and getTimestampMs() >= quitAtMs then
        quitAtMs = nil
        print("[pzopt-harness] quit_after reached, quitting")
        getCore():quit()
    end
end

Events.OnMainMenuEnter.Add(onMainMenuEnter)
Events.OnTickEvenPaused.Add(onTickEvenPaused)
