package pzopt;

import java.io.InputStream;
import java.util.Properties;

/**
 * What the overrides were compiled against, read from
 * pzopt/build-info.properties which scripts/build.sh writes next to the classes.
 */
public final class BuildInfo {
   private static final Properties props = load();

   private BuildInfo() {
   }

   private static Properties load() {
      Properties p = new Properties();
      try (InputStream in = BuildInfo.class.getResourceAsStream("/pzopt/build-info.properties")) {
         if (in != null) {
            p.load(in);
         }
      } catch (Exception e) {
         Log.warn("could not read build-info.properties: " + e);
      }
      return p;
   }

   public static String get(String key) {
      return props.getProperty(key);
   }

   /** Game revision (zombie.GitVersion.REVISION) the overrides were compiled against. */
   public static String targetRevision() {
      return props.getProperty("revision", "unknown");
   }
}
