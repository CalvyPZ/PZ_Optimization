package pzopt;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Keeps the C2 compiler off the cores during play on machines with few of them (2026-09-22, the Dell: the two C2
 * compiler threads used about one of four cores through a 120 km/h drive, recompiling methods as the drive reached new
 * code paths, and the game thread waited for a CPU a fifth of the time; -XX:TieredStopAtLevel=1 cut the > 100 ms
 * frames by two thirds).
 *
 * {@code jitMode}: "tiered" (stock), "c1play" (from world start on, add a compiler directive that excludes every method
 * from C2: the tiered policy then compiles new hot methods with C1 at tier 1, code C2 already made stays), "auto"
 * (c1play when the machine has {@code jitC1Cores} cores or fewer). Uses the DiagnosticCommand MBean
 * (Compiler.directives_add), no launcher change; failures log once and leave the JIT alone.
 */
public final class JitGovernor {
   private static boolean applied;

   private JitGovernor() {}

   public static boolean wanted() {
      String m = Config.JIT_MODE;
      if (m.equals("c1play")) {
         return true;
      }
      return m.equals("auto") && Runtime.getRuntime().availableProcessors() <= Config.JIT_C1_CORES;
   }

   /** Called once the world is up (after loading, so the load screen still gets C2). */
   public static synchronized void onWorldStart() {
      if (applied || !Overrides.enabled() || !wanted()) {
         return;
      }
      applied = true;
      try {
         File f = File.createTempFile("pzopt-jit", ".json");
         f.deleteOnExit();
         Files.writeString(f.toPath(), "[{ match: \"*.*\", c2: { Exclude: true } }]");
         MBeanServer s = ManagementFactory.getPlatformMBeanServer();
         ObjectName dc = new ObjectName("com.sun.management:type=DiagnosticCommand");
         Object out = s.invoke(dc, "compilerDirectivesAdd", new Object[] {new String[] {f.getAbsolutePath()}},
               new String[] {String[].class.getName()});
         Log.info("jit: C2 excluded for play (" + Config.JIT_MODE + ", " + Runtime.getRuntime().availableProcessors() + " cores): "
               + String.valueOf(out).trim());
      } catch (Throwable t) {
         Log.warn("jit: could not add the C2 exclude directive: " + t);
      }
   }
}
