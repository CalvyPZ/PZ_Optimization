package pzopt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;

/**
 * pzopt.Updater without the network: the release pick (newest publish date with the zip for this
 * revision, drafts and other revisions skipped), the "is it newer than this build" rule, and the file
 * swap on a temporary game folder (new files over old, stale ones removed, manifest rewritten in the
 * installers' format, a zip for another revision refused before anything changes).
 */
public class UpdaterTest {
   static int failures;

   static void check(boolean ok, String what) {
      if (!ok) {
         failures++;
         System.err.println("FAIL: " + what);
      }
   }

   public static void main(String[] args) throws Exception {
      pick();
      newer();
      swap();
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("UpdaterTest ok");
   }

   static String release(String tag, String published, boolean draft, String... assets) {
      StringBuilder a = new StringBuilder();
      for (String name : assets) {
         if (a.length() > 0) {
            a.append(',');
         }
         a.append("{\"name\":\"").append(name).append("\",\"browser_download_url\":\"https://x/").append(name).append("\",\"size\":10}");
      }
      return "{\"tag_name\":\"" + tag + "\",\"published_at\":\"" + published + "\",\"draft\":" + draft
            + ",\"html_url\":\"https://x/" + tag + "\",\"body\":\"notes " + tag + "\",\"assets\":[" + a + "]}";
   }

   static void pick() {
      // the API lists by the tagged commit's date; the newest publish date with our zip must win
      JSONArray rels = new JSONArray("[" + String.join(",",
            release("win-aaaa-1111111", "2026-09-20T10:00:00Z", false, "pzopt-aaaa-classes.zip", "install.sh"),
            release("win-aaaa-2222222", "2026-09-22T10:00:00Z", false, "pzopt-aaaa-classes.zip"),
            release("win-aaaa-3333333", "2026-09-23T10:00:00Z", true, "pzopt-aaaa-classes.zip"),
            release("win-bbbb-4444444", "2026-09-24T10:00:00Z", false, "pzopt-bbbb-classes.zip"),
            release("win-aaaa-5555555", "2026-09-21T10:00:00Z", false, "pzopt-aaaa-classes.zip")) + "]");
      Updater.Release r = Updater.pickRelease(rels, "aaaa");
      check(r != null && "win-aaaa-2222222".equals(r.tag), "newest published release for the revision: " + (r == null ? null : r.tag));
      check(r != null && "2222222".equals(r.commit), "commit from the tag");
      check(r != null && r.zipUrl.endsWith("pzopt-aaaa-classes.zip"), "zip url");
      check(Updater.pickRelease(rels, "cccc") == null, "no release for an unknown revision");
      check("2222222".equals(Updater.tagCommit("win-aaaa-2222222")), "tagCommit");
   }

   static void newer() {
      Updater.Release r = new Updater.Release("win-aaaa-2222222", "2222222", "2026-09-22T10:00:00Z", "", "", "", 0);
      long before = 1789800000L;  // 2026-09-19
      long after = 1790200000L;   // 2026-09-24
      check(Updater.isNewer(r, "1111111", before), "older build, other commit: update");
      check(!Updater.isNewer(r, "2222222", before), "same commit: no update");
      check(!Updater.isNewer(r, "2222222-dirty", before), "same commit, dirty tree: no update");
      check(!Updater.isNewer(r, "1111111", after), "build newer than the release: no update");
      check(Updater.isNewer(r, "1111111", 0), "no build stamp: the commit decides");
      check(!Updater.isNewer(r, "unknown", before), "unknown commit: never");
      check(!Updater.isNewer(r, "", before), "empty commit: never");
      check(!Updater.isNewer(r, "22222", before), "shorter form of the same commit: no update");
   }

   static Path zip(Path dir, String name, String rev, Map<String, String> files) throws Exception {
      Path z = dir.resolve(name);
      try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(z))) {
         out.putNextEntry(new ZipEntry("pzopt/"));
         out.closeEntry();
         out.putNextEntry(new ZipEntry("pzopt/build-info.properties"));
         out.write(("revision=" + rev + "\ncommit=2222222\n").getBytes(StandardCharsets.UTF_8));
         out.closeEntry();
         for (Map.Entry<String, String> e : files.entrySet()) {
            out.putNextEntry(new ZipEntry(e.getKey()));
            out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
         }
      }
      return z;
   }

   static void swap() throws Exception {
      Path tmp = Files.createTempDirectory("pzopt-updater-test");
      Path game = tmp.resolve("game");
      Files.createDirectories(game.resolve("zombie/iso"));
      Files.createDirectories(game.resolve("media/lua/client/pzopt"));
      Files.createDirectories(game.resolve("media/ui/pzopt/old"));
      Files.createDirectories(game.resolve("pzopt"));
      Files.writeString(game.resolve("projectzomboid.jar"), "jar");
      Files.writeString(game.resolve("zombie/iso/IsoChunk.class"), "old chunk");
      Files.writeString(game.resolve("media/lua/client/pzopt/a.lua"), "old a");
      Files.writeString(game.resolve("media/ui/pzopt/old/gone.gif"), "old gif");
      Files.writeString(game.resolve("pzopt/build-info.properties"), "revision=aaaa\ncommit=1111111\n");
      Files.writeString(game.resolve(Updater.MANIFEST), "# files written by install.sh\n# revision=aaaa installed=x\n"
            + "media/lua/client/pzopt/a.lua 00\nmedia/ui/pzopt/old/gone.gif 00\npzopt/build-info.properties 00\nzombie/iso/IsoChunk.class 00\n");
      Files.writeString(game.resolve("keep.txt"), "not ours");

      // a zip for another revision is refused before any file moves
      Path wrong = zip(tmp, "wrong.zip", "bbbb", Map.of("zombie/iso/IsoChunk.class", "wrong"));
      boolean refused = false;
      try {
         Updater.swap(wrong, tmp.resolve("stage"), game, "aaaa");
      } catch (Exception e) {
         refused = e.getMessage().contains("built for game revision bbbb");
      }
      check(refused, "zip for another revision refused");
      check("old chunk".equals(Files.readString(game.resolve("zombie/iso/IsoChunk.class"))), "nothing changed by the refused zip");

      Path good = zip(tmp, "good.zip", "aaaa", Map.of(
            "zombie/iso/IsoChunk.class", "new chunk",
            "media/lua/client/pzopt/a.lua", "new a",
            "media/lua/client/pzopt/b.lua", "new b",
            "pzopt-files.txt", "list"));
      Map<String, String> installed = Updater.swap(good, tmp.resolve("stage"), game, "aaaa");
      check(installed.size() == 5, "five files installed: " + installed.keySet());
      check("new chunk".equals(Files.readString(game.resolve("zombie/iso/IsoChunk.class"))), "class replaced");
      check("new a".equals(Files.readString(game.resolve("media/lua/client/pzopt/a.lua"))), "lua replaced");
      check("new b".equals(Files.readString(game.resolve("media/lua/client/pzopt/b.lua"))), "new lua added");
      check(!Files.exists(game.resolve("media/ui/pzopt/old/gone.gif")), "stale file removed");
      check(!Files.exists(game.resolve("media/ui/pzopt/old")) && !Files.exists(game.resolve("media/ui")), "empty folders of stale files removed");
      check(Files.exists(game.resolve("media/lua")), "shared folders kept");
      check("not ours".equals(Files.readString(game.resolve("keep.txt"))), "foreign file untouched");
      check(!Files.exists(tmp.resolve("stage")), "stage folder removed");
      check("list".equals(Files.readString(game.resolve(Updater.FILE_LIST))), "the zip's pzopt-files.txt installed like install.sh does");
      List<String> manifest = Files.readAllLines(game.resolve(Updater.MANIFEST));
      check(manifest.get(1).startsWith("# revision=aaaa installed="), "manifest header: " + manifest.get(1));
      check(manifest.size() == 2 + 5, "manifest lines: " + manifest.size());
      String chunkLine = manifest.stream().filter(l -> l.startsWith("zombie/iso/IsoChunk.class ")).findFirst().orElse("");
      check(chunkLine.endsWith(" " + Updater.sha256(game.resolve("zombie/iso/IsoChunk.class"))), "manifest sha256 of the installed file");
      List<String> previous = Updater.previousFiles(game);
      check(previous.size() == 5 && previous.contains("media/lua/client/pzopt/b.lua"), "manifest reads back: " + previous);

      // a hostile zip entry is refused
      Path evil = zip(tmp, "evil.zip", "aaaa", Map.of("../escape.txt", "x"));
      boolean escaped = false;
      try {
         Updater.swap(evil, tmp.resolve("stage"), game, "aaaa");
      } catch (Exception e) {
         escaped = e.getMessage().contains("refusing zip entry");
      }
      check(escaped, "zip slip entry refused");
      check(!Files.exists(tmp.resolve("escape.txt")), "no file escaped the stage");
   }
}
