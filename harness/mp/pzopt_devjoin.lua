-- pzopt dev rig, NOT shipped: drives a non-Steam client started with "+connect ip:port" through the
-- connect popup, spawn region and character creation so the Lua checksum runs hands-off.
-- Lua is reset when the client connects (the server's mod list is loaded), so every screen is handled
-- on its own: whichever one is visible gets its Next pressed once.
local done, wait = {}, 0
local function say(s) print("[pzopt-devjoin] " .. s) end
say("loaded")
local function vis(ui) return ui and ui.isVisible and ui:isVisible() end
local function tick()
    if wait > 0 then wait = wait - 1 return end
    if not done.connect and vis(ServerConnectPopup.instance) then
        local p = ServerConnectPopup.instance
        p.usernameEntry:setText("admin"); p.passwordEntry:setText("pzoptadmin"); p.serverPasswordEntry:setText("")
        say("connect popup: pressing Connect as admin"); done.connect = true; wait = 30
        p:onOptionMouseDown({ internal = "CONNECT" }, 0, 0)
    elseif not done.spawn and vis(MapSpawnSelect.instance) then
        local m = MapSpawnSelect.instance
        if m.listbox.selected < 1 then m.listbox.selected = 1 end
        say("spawn select: " .. tostring(m.listbox.items[m.listbox.selected].item.name)); done.spawn = true; wait = 30
        m:clickNext()
    elseif not done.prof and MainScreen.instance and vis(MainScreen.instance.charCreationProfession) then
        say("profession screen: Next"); done.prof = true; wait = 30
        MainScreen.instance.charCreationProfession:onOptionMouseDown({ internal = "NEXT" }, 0, 0)
    elseif not done.char and MainScreen.instance and vis(MainScreen.instance.charCreationMain) then
        say("character screen: Next (loading + checksum follow)"); done.char = true
        MainScreen.instance.charCreationMain:onOptionMouseDown({ internal = "NEXT" }, 0, 0)
    end
end
Events.OnFETick.Add(tick)
Events.OnMainMenuEnter.Add(function() say("event OnMainMenuEnter") end)
Events.OnConnected.Add(function() say("event OnConnected") end)
Events.OnConnectFailed.Add(function(m, d) say("event OnConnectFailed " .. tostring(m) .. " " .. tostring(d)) end)
Events.OnConnectionStateChanged.Add(function(s, m) say("event state " .. tostring(s) .. " " .. tostring(m)) end)
Events.OnDisconnect.Add(function() say("event OnDisconnect") end)
Events.OnGameStart.Add(function() say("event OnGameStart: in the world, checksum passed") end)
