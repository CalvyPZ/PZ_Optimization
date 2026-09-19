package pzopt;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

/**
 * Decides once, when the first overridden class loads, whether the overrides
 * may change behaviour in this JVM.
 *
 * The loose .class files shadow the jar's copies unconditionally, so a
 * recompiled class is always the one running. What this guard controls is
 * whether it takes its modified paths or the stock ones: on a game-build
 * mismatch every override falls back to stock behaviour and says so in the log.
 */
public final class Overrides {
   private static final boolean ENABLED = check();

   private Overrides() {
   }

   private static final List<String> deferredMarkers = new java.util.ArrayList<>();

   /**
    * For classes whose static initializer runs while the game's log cannot format a line yet: IsoMetaGrid is
    * constructed inside IsoWorld's own static initializer, and DebugLog reads IsoWorld.instance (still null) for
    * the frame number of every line. The marker is printed by the next onClassLoaded call.
    */
   public static void onClassLoadedQuiet(String className) {
      synchronized (deferredMarkers) {
         deferredMarkers.add(className);
      }
   }

   /** Called from the static initializer of each overridden class. */
   public static void onClassLoaded(String className) {
      synchronized (deferredMarkers) {
         for (String deferred : deferredMarkers) {
            Log.info("loaded override " + deferred + " (target revision " + BuildInfo.targetRevision() + ", "
                  + (ENABLED ? "active" : "DISABLED: build mismatch") + ", logged late)");
         }
         deferredMarkers.clear();
      }
      Log.info("loaded override " + className + " (target revision " + BuildInfo.targetRevision() + ", "
            + (ENABLED ? "active" : "DISABLED: build mismatch") + ")");
      // FileSystemImpl loads inside GameWindow's static initializer, before the game's log and file system exist;
      // the harness hooks wait for a later override (they are armed again on every class load until they take)
      if (!Log.gameLogReady()) {
         return;
      }
      try {
         AutoStart.start(); // no-op unless the harness flag file names a mode
      } catch (Throwable t) {
         Log.warn("harness: auto-start not armed: " + t);
      }
      try {
         LoadTrace.install(); // no-op unless instrument=true or a harness run
      } catch (Throwable t) {
         Log.warn("load trace not installed: " + t);
      }

   }

   /** True when the running game is the build the overrides were compiled against. */
   public static boolean enabled() {
      return ENABLED;
   }

   private static boolean check() {
      String target = BuildInfo.targetRevision();
      String actual = jarRevision();
      if (!target.equals(actual)) {
         Log.error("game revision is " + actual + " but overrides were built for " + target
               + "; overrides disabled, running stock behaviour. Rebuild and reinstall (scripts/build.sh, scripts/pzopt.sh install).");
         return false;
      }
      String overrides = BuildInfo.get("overrides");
      if (overrides != null) {
         for (String cls : overrides.split(",")) {
            String expected = BuildInfo.get("stock." + cls.replace('/', '.'));
            String found = jarClassSha256(cls + ".class");
            if (expected != null && found != null && !expected.equals(found)) {
               Log.error("jar copy of " + cls + " differs from the one the overrides were built against"
                     + " (same revision " + actual + "); overrides disabled, running stock behaviour.");
               return false;
            }
         }
      }
      return true;
   }

   /** zombie.GitVersion.REVISION read reflectively, so it comes from the jar's class, not an inlined constant. */
   private static String jarRevision() {
      try {
         Field f = Class.forName("zombie.GitVersion").getField("REVISION");
         return String.valueOf(f.get(null));
      } catch (Exception e) {
         Log.warn("could not read zombie.GitVersion.REVISION: " + e);
         return "unknown";
      }
   }

   /** sha256 of the jar's (not the loose file's) copy of a class resource, or null if not found. */
   private static String jarClassSha256(String resource) {
      try {
         List<URL> urls = Collections.list(Overrides.class.getClassLoader().getResources(resource));
         for (URL u : urls) {
            if (!u.getProtocol().equals("jar") || u.getPath().contains("pzopt.jar")) {
               continue; // the game's own jar, not the overrides jar of the jar-based install (docs/plan-instant-load.md B8)
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = u.openStream()) {
               byte[] buf = new byte[65536];
               int n;
               while ((n = in.read(buf)) > 0) {
                  md.update(buf, 0, n);
               }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
               sb.append(String.format("%02x", b));
            }
            return sb.toString();
         }
      } catch (Exception e) {
         Log.warn("could not hash jar resource " + resource + ": " + e);
      }
      return null;
   }
}
