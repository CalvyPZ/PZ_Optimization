package pzopt;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * pzopt.ScriptText must produce the same output as the game's own ScriptParser
 * for every script text file of the install (media/scripts, tileGeometry,
 * seams, fonts). The stock class is loaded from the game jar alone, so the
 * override in build/classes is not what is being compared against.
 */
public final class ScriptTextTest {
   public static void main(String[] args) throws Exception {
      String pzDir = System.getenv("PZ_DIR");
      if (pzDir == null) {
         pzDir = "/games/steamapps/common/ProjectZomboid/projectzomboid";
      }
      File jar = new File(pzDir, "projectzomboid.jar");
      Check.check(jar.isFile(), "game jar at " + jar);
      ClassLoader stock = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
      Class<?> parser = Class.forName("zombie.scripting.ScriptParser", true, stock);
      Method strip = parser.getMethod("stripComments", String.class);
      Method tokens = parser.getMethod("parseTokens", String.class);

      List<Path> files = new ArrayList<>();
      try (Stream<Path> w = Files.walk(new File(pzDir, "media").toPath())) {
         w.filter(p -> p.toString().endsWith(".txt")).forEach(files::add);
      }
      Check.check(files.size() > 100, "found " + files.size() + " text files under media/");
      int fallbacks = 0;
      long stockNs = 0, fastNs = 0;
      for (Path p : files) {
         String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
         long t0 = System.nanoTime();
         String expected = (String) strip.invoke(null, text);
         long t1 = System.nanoTime();
         String got = ScriptText.stripComments(text);
         long t2 = System.nanoTime();
         stockNs += t1 - t0;
         fastNs += t2 - t1;
         if (got == null) {
            fallbacks++;
            continue;
         }
         Check.check(got.equals(expected), "stripComments differs for " + p);
         @SuppressWarnings("unchecked")
         List<String> exp = (List<String>) tokens.invoke(null, expected);
         List<String> gotTokens = ScriptText.parseTokens(expected);
         Check.check(gotTokens.equals(exp), "parseTokens differs for " + p + " (" + gotTokens.size() + " vs " + exp.size() + ")");
      }
      // synthetic edge cases: nested and adjacent comments, text before/after
      String[] cases = {
         "a /* b */ c", "/* a /* b */ c */ d", "x /* 1 */ y /* 2 */ z", "no comments {a=1,}", "/**/", "a/*b*/", "/*a*/b", "",
         "module Base { /* c */ item X { A = 1, /* nested /* deep */ */ B = 2, } }"
      };
      for (String c : cases) {
         String got = ScriptText.stripComments(c);
         if (got != null) {
            Check.check(got.equals(strip.invoke(null, c)), "stripComments differs for case '" + c + "'");
         }
      }
      System.out.println("ScriptTextTest: " + files.size() + " files identical, " + fallbacks + " fallbacks, stock "
            + stockNs / 1_000_000 + " ms, fast " + fastNs / 1_000_000 + " ms");
   }
}
