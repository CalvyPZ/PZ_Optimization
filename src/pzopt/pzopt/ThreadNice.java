package pzopt;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lowers the CPU priority of chosen threads of this process, so the game and render threads win the cores on a
 * machine with few of them (2026-09-22, the Dell: JIT compiler threads were ~40 % of the process's CPU samples
 * during a drive and the game thread waited for a CPU a quarter of its time).
 *
 * Rules come from {@code threadNice}: comma-separated {@code <comm prefix>=<action>[+<action>...]}, matched in order
 * against /proc/self/task/<tid>/comm (15 characters, e.g. "C2 CompilerThre"; '_' stands for a space; "*" matches
 * every thread; the first matching rule wins). Actions: a nice value 1..19 (an unprivileged process may lower its
 * own threads' priority), "idle" (SCHED_IDLE: runs only when a core would otherwise idle), "batch" (SCHED_BATCH),
 * "cpu<a>-<b>" / "cpu<a>.<b>.<c>" (affinity: the thread may only run on those cores; a thread inherits its
 * creator's mask, so the scan re-applies the rules to every new thread). A daemon re-scans every 500 ms.
 * Linux only; any failure disables it with one log line.
 */
public final class ThreadNice {
   private static final int PRIO_PROCESS = 0;
   private static final int SCHED_BATCH = 3;
   private static final int SCHED_IDLE = 5;

   private record Rule(String prefix, String value) {}

   private static final List<Rule> RULES = new ArrayList<>();
   private static final Set<Integer> DONE = new HashSet<>();
   private static MethodHandle setpriority;
   private static MethodHandle schedSetscheduler;
   private static MethodHandle schedSetaffinity;
   private static boolean started;

   private ThreadNice() {}

   public static synchronized void start() {
      if (started) {
         return;
      }
      started = true;
      String spec = Config.THREAD_NICE;
      if (spec == null || spec.isBlank() || !new File("/proc/self/task").isDirectory() || !Overrides.enabled()) {
         return;
      }
      for (String part : spec.split(",")) {
         int eq = part.lastIndexOf('=');
         if (eq > 0) {
            RULES.add(new Rule(part.substring(0, eq).trim().replace('_', ' '), part.substring(eq + 1).trim()));
         }
      }
      if (RULES.isEmpty()) {
         return;
      }
      try {
         Linker l = Linker.nativeLinker();
         setpriority = l.downcallHandle(l.defaultLookup().find("setpriority").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
         schedSetscheduler = l.downcallHandle(l.defaultLookup().find("sched_setscheduler").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
         schedSetaffinity = l.downcallHandle(l.defaultLookup().find("sched_setaffinity").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
      } catch (Throwable t) {
         Log.warn("threadNice: no native setpriority (" + t + "); off");
         return;
      }
      Thread th = new Thread(ThreadNice::loop, "pzopt-thread-nice");
      th.setDaemon(true);
      th.start();
   }

   private static void loop() {
      int rounds = 0;
      while (true) {
         try {
            int n = scan();
            if (n > 0 || rounds == 0) {
               Log.info("threadNice: " + n + " thread(s) adjusted this pass, " + DONE.size() + " in total (" + Config.THREAD_NICE + ")");
            }
            rounds++;
            Thread.sleep(500);
         } catch (InterruptedException e) {
            return;
         } catch (Throwable t) {
            Log.warn("threadNice: " + t + "; off");
            return;
         }
      }
   }

   private static int scan() throws Throwable {
      File[] tasks = new File("/proc/self/task").listFiles();
      if (tasks == null) {
         return 0;
      }
      int n = 0;
      for (File t : tasks) {
         int tid;
         try {
            tid = Integer.parseInt(t.getName());
         } catch (NumberFormatException e) {
            continue;
         }
         if (DONE.contains(tid)) {
            continue;
         }
         String comm;
         try {
            comm = Files.readString(new File(t, "comm").toPath()).trim();
         } catch (Exception e) {
            continue;
         }
         for (Rule r : RULES) {
            if (!r.prefix.equals("*") && !comm.startsWith(r.prefix)) {
               continue;
            }
            DONE.add(tid);
            boolean ok = true;
            for (String action : r.value.split("\\+")) {
               ok &= apply(tid, action.trim());
            }
            if (!ok) {
               Log.warn("threadNice: " + comm + " (" + tid + ") -> " + r.value + " failed");
            } else {
               n++;
            }
            break;
         }
      }
      return n;
   }

   private static boolean apply(int tid, String action) throws Throwable {
      if (action.isEmpty() || action.equals("keep")) {
         return true;
      }
      if (action.equals("idle") || action.equals("batch")) {
         try (Arena a = Arena.ofConfined()) {
            MemorySegment param = a.allocate(ValueLayout.JAVA_INT);
            param.set(ValueLayout.JAVA_INT, 0, 0);
            return (int) schedSetscheduler.invokeExact(tid, action.equals("idle") ? SCHED_IDLE : SCHED_BATCH, param) == 0;
         }
      }
      if (action.startsWith("cpu")) {
         long mask = 0;
         String list = action.substring(3);
         for (String part : list.split("\\.")) {
            int dash = part.indexOf('-');
            int lo = Integer.parseInt(dash < 0 ? part : part.substring(0, dash));
            int hi = dash < 0 ? lo : Integer.parseInt(part.substring(dash + 1));
            for (int c = lo; c <= hi && c < 64; c++) {
               mask |= 1L << c;
            }
         }
         try (Arena a = Arena.ofConfined()) {
            MemorySegment set = a.allocate(128);
            set.set(ValueLayout.JAVA_LONG, 0, mask);
            return (int) schedSetaffinity.invokeExact(tid, 128L, set) == 0;
         }
      }
      return (int) setpriority.invokeExact(PRIO_PROCESS, tid, Integer.parseInt(action)) == 0;
   }
}
