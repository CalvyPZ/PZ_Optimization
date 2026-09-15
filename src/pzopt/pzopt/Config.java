package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime settings for the overrides, read once from pzopt.properties in the
 * game install directory (next to projectzomboid.jar, so scripts/pzopt.sh
 * status can show it) with -Dpzopt.<key> system properties overriding.
 *
 * Keys:
 *   parallel    true/false   kill switch: false forces the stock single-threaded pass (default true)
 *   workers     int          recalc pool width; clamped to [1, availableProcessors - 1] (default: min(4, cores - 1))
 *   instrument  true/false   record per-chunk timings and frame times to pzopt-*.out (default false)
 *   wake        true/false   wake the streamer thread on enqueue instead of the stock 140 ms polls (default true)
 *   dev         true/false   development assertions, e.g. game-thread-only code reached from a worker (default false)
 */
public final class Config {
   private static final Properties props = load();
   public static final boolean PARALLEL = bool("parallel", true);
   public static final int WORKERS = clampWorkers(integer("workers", Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
   public static final boolean INSTRUMENT = bool("instrument", false);
   public static final boolean WAKE = bool("wake", true);
   public static final boolean DEV = bool("dev", false);

   private Config() {
   }

   private static Properties load() {
      Properties p = new Properties();
      File f = new File("pzopt.properties"); // cwd is the install dir when launched by ProjectZomboid64.exe
      if (f.isFile()) {
         try (InputStream in = new FileInputStream(f)) {
            p.load(in);
         } catch (Exception e) {
            Log.warn("could not read " + f.getAbsolutePath() + ": " + e);
         }
      }
      return p;
   }

   private static String raw(String key) {
      String v = System.getProperty("pzopt." + key);
      return v != null ? v : props.getProperty(key);
   }

   private static boolean bool(String key, boolean def) {
      String v = raw(key);
      return v == null ? def : Boolean.parseBoolean(v.trim());
   }

   private static int integer(String key, int def) {
      String v = raw(key);
      if (v == null) {
         return def;
      }
      try {
         return Integer.parseInt(v.trim());
      } catch (NumberFormatException e) {
         Log.warn("bad integer for " + key + ": " + v + "; using " + def);
         return def;
      }
   }

   /** At least 1, and never the full processor count: the render thread keeps one core. */
   static int clampWorkers(int requested) {
      int max = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
      return Math.max(1, Math.min(requested, max));
   }

   /** Effective pool width: 1 when parallelism is switched off or the build guard tripped. */
   public static int effectiveWorkers() {
      return PARALLEL && Overrides.enabled() ? WORKERS : 1;
   }

   /** Wake-on-enqueue is also off when the build guard tripped, so a mismatched build is fully stock. */
   public static boolean effectiveWake() {
      return WAKE && Overrides.enabled();
   }

   public static String describe() {
      return "parallel=" + PARALLEL + " workers=" + WORKERS + " (effective " + effectiveWorkers() + ", cores "
            + Runtime.getRuntime().availableProcessors() + ") wake=" + WAKE + " (effective " + effectiveWake() + ") instrument=" + INSTRUMENT + " dev=" + DEV;
   }
}
