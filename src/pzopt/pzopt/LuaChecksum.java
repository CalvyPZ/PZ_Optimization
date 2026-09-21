package pzopt;

import java.util.Locale;

/**
 * Which Lua files stay out of the multiplayer Lua checksum ({@code NetChecksum.Checksummer.addFile}).
 *
 * <p>When a client joins a server, {@code LuaManager.LoadDirBase} feeds every file under {@code media/lua/shared}
 * and {@code media/lua/client} (game and mods) into {@code NetChecksum}: a total MD5 the server compares with its
 * own, and groups of per-file checksums it walks when the totals differ. Stock leaves {@code SandboxVars.lua} out.
 * The three pzopt Lua files (the Optimizations tab, the frame-cap combo, the F9 key binding) are installed into
 * the client's {@code media/lua/.../pzopt/} and do not exist on a server, so a community server answered
 * "File doesn't exist on the server: media/lua/shared/pzopt/pzopt_keybinding.lua" and refused the join. They are
 * client-side UI only and never touch game state a server checks, so they are skipped like {@code SandboxVars.lua}.
 *
 * <p>The exemption does not depend on {@code enabled}: the files are on disk either way, and a server running
 * these overrides skips them too, so both sides agree whichever has them.
 */
public final class LuaChecksum {
   private LuaChecksum() {
   }

   /** True for {@code media/lua/<sub>/pzopt/...} in either slash style and any case. */
   public static boolean exempt(String relPath) {
      if (relPath == null) {
         return false;
      }
      String p = relPath.replace('\\', '/').toLowerCase(Locale.ENGLISH);
      return p.startsWith("media/lua/") && p.contains("/pzopt/");
   }
}
