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
 * from C2: the tiered policy then compiles new hot methods with C1 at tier 1, code C2 already made stays; HotSpot marks
 * each declined method not C2-compilable for good, so it costs ~7 % mean fps late in a session), "c2idle" (tiered, the C2
 * compiler threads at SCHED_IDLE from world start: they compile only on otherwise idle CPU; Linux; no measured gain),
 * "auto" (default: c1play with {@code jitC1Cores} cores or fewer, the stock JIT above). Uses the DiagnosticCommand MBean
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

   /** c2idle, or auto above jitC1Cores cores: tiered JIT with the C2 compiler threads at SCHED_IDLE. */
   public static boolean idleWanted() {
      String m = Config.JIT_MODE;
      if (m.equals("c2idle")) {
         return true;
      }
      return false; // not part of auto: see onWorldStart
   }

   /** Called once the world is up (after loading, so the load screen still gets C2 at full priority). */
   public static synchronized void onWorldStart() {
      if (applied || !Overrides.enabled()) {
         return;
      }
      if (idleWanted()) {
         // 2026-09-23: an explicit option only. A first 6-core run looked like a large win (215 fps, no frame over 100 ms)
         // but a repeat with the same settings gave 152 fps, like tiered; on 12 / 16 cores and on the 4-core Dell it
         // measured the same as tiered, so auto keeps the stock JIT above jitC1Cores.
         applied = true;
         boolean ok = ThreadNice.addRule("C2 CompilerThre", "idle");
         Log.info("jit: C2 compiler threads at SCHED_IDLE for play (" + Config.JIT_MODE + ", " + Runtime.getRuntime().availableProcessors()
               + " cores)" + (ok ? "" : ": not available here, tiered JIT unchanged"));
         return;
      }
      if (!wanted()) {
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
