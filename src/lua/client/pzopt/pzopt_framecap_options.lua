-- pzopt: "Menu framerate" combo in Display options, right under the stock "Framerate" one.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable). The setting lives in Java: the overridden
-- PerformanceSettings forwards to pzopt.FrameCap, which persists it in Zomboid/pzopt/framecap.ini.
-- Index: 1 = same as in-game, 2 = uncapped, 3.. = the stock fps table.

local function labels()
    local t = { "Same as in-game", getText("UI_optionscreen_Uncapped"),
                "244", "240", "165", "144", "120", "95", "90", "75", "60", "55", "45", "30", "24" }
    return t
end

local function install()
    if not MainOptions or MainOptions.pzoptMenuFramerate then return end
    local ok, has = pcall(function() return getPerformance():getMenuFramerateChoices() > 0 end)
    if not ok or not has then
        print("[pzopt] menu framerate: PerformanceSettings override not loaded, combo not added")
        return
    end
    MainOptions.pzoptMenuFramerate = true
    local stockAddCombo = MainOptions.addCombo
    local frameLabel = getText("UI_optionscreen_framerate")
    function MainOptions:addCombo(x, y, w, h, name, options, selected, target, onchange)
        local combo = stockAddCombo(self, x, y, w, h, name, options, selected, target, onchange)
        if name ~= frameLabel or self.pzoptMenuCombo then return combo end
        local menu = stockAddCombo(self, x, y, w, h, "Menu framerate", labels(), 1, target, onchange)
        self.pzoptMenuCombo = menu
        local gameOption = GameOption:new('pzoptMenuFramerate', menu)
        function gameOption.toUI(self)
            self.control.selected = getPerformance():getMenuFramerateIndex()
        end
        function gameOption.apply(self)
            local box = self.control
            if box.options[box.selected] then
                getPerformance():setMenuFramerateIndex(box.selected)
            end
        end
        self.gameOptions:add(gameOption)
        return combo
    end
end

install()
Events.OnGameBoot.Add(install)
