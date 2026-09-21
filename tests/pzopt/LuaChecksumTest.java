package pzopt;

/** The multiplayer Lua checksum leaves exactly the pzopt Lua files out. */
public final class LuaChecksumTest {
   public static void main(String[] args) {
      Check.check(LuaChecksum.exempt("media/lua/shared/pzopt/pzopt_keybinding.lua"), "shared pzopt file");
      Check.check(LuaChecksum.exempt("media/lua/client/pzopt/pzopt_optimizations_options.lua"), "client pzopt file");
      Check.check(LuaChecksum.exempt("media\\lua\\client\\pzopt\\pzopt_framecap_options.lua"), "Windows separators");
      Check.check(LuaChecksum.exempt("Media/Lua/Shared/PZOpt/x.lua"), "case insensitive");
      Check.check(!LuaChecksum.exempt("media/lua/shared/defines.lua"), "game file");
      Check.check(!LuaChecksum.exempt("media/lua/client/OptionScreens/MainOptions.lua"), "game file in a folder");
      Check.check(!LuaChecksum.exempt("media/lua/shared/pzopt_keybinding.lua"), "pzopt prefix without the folder");
      Check.check(!LuaChecksum.exempt("media/scripts/pzopt/x.txt"), "outside media/lua");
      Check.check(!LuaChecksum.exempt(null), "null");
      System.out.println("LuaChecksumTest ok");
   }
}
