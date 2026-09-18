import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Dumps the sampling events of a JFR recording as one TSV line per sample,
 * which harness/attribute.py joins with the per-frame timings.
 *
 *   java tools/JfrSamples.java <recording.jfr> [thread-name] [max-depth]
 *
 * Columns: kind (java|native) \t thread \t epochNs \t frames
 * where frames is the stack top-first, ';'-separated, as pkg.Class.method.
 * Also emits, on the same timeline:
 *   gc    \t <name>   \t epochNs \t <sumOfPauses-us>   one per jdk.GarbageCollection
 *   pause \t <name>   \t epochNs \t <duration-us>      one per jdk.GCPhasePause (stop-the-world interval)
 *   stall \t <thread> \t epochNs \t <duration-us>      one per jdk.ZAllocationStall (thread blocked on ZGC)
 *
 * Why not `jfr print --json`: it defaults to a 5-frame stack depth, prints
 * timestamps as local-time strings, and for a 3-minute profile-settings
 * recording the JSON is hundreds of megabytes; the consumer API gives
 * nanosecond Instants and full stacks in a fraction of the space.
 */
public final class JfrSamples {
   public static void main(String[] args) throws IOException {
      if (args.length < 1) {
         System.err.println("usage: JfrSamples <recording.jfr> [thread-name] [max-depth]");
         System.exit(2);
      }
      Path file = Path.of(args[0]);
      String wantThread = args.length > 1 ? args[1] : null;
      int maxDepth = args.length > 2 ? Integer.parseInt(args[2]) : 48;
      long java = 0, nat = 0, gc = 0;
      try (RecordingFile rf = new RecordingFile(file);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out), 1 << 20)) {
         out.write("kind\tthread\tepochNs\tframes\n");
         while (rf.hasMoreEvents()) {
            RecordedEvent e = rf.readEvent();
            String type = e.getEventType().getName();
            if (type.equals("jdk.GarbageCollection")) {
               out.write("gc\t" + e.getString("name") + "\t" + epochNs(e.getStartTime()) + "\t" + e.getDuration("sumOfPauses").toNanos() / 1000 + "\n");
               gc++;
               continue;
            }
            if (type.equals("jdk.GCPhasePause")) {
               out.write("pause\t" + e.getString("name") + "\t" + epochNs(e.getStartTime()) + "\t" + e.getDuration().toNanos() / 1000 + "\n");
               continue;
            }
            if (type.equals("jdk.ZAllocationStall")) {
               RecordedThread st = e.getThread("eventThread");
               out.write("stall\t" + (st != null ? st.getJavaName() : "?") + "\t" + epochNs(e.getStartTime()) + "\t" + e.getDuration().toNanos() / 1000 + "\n");
               continue;
            }
            boolean isJava = type.equals("jdk.ExecutionSample");
            if (!isJava && !type.equals("jdk.NativeMethodSample")) {
               continue;
            }
            RecordedThread th = e.getThread("sampledThread");
            String name = th != null ? th.getJavaName() : "?";
            if (wantThread != null && !wantThread.equals(name)) {
               continue;
            }
            RecordedStackTrace st = e.getStackTrace();
            if (st == null) {
               continue;
            }
            StringBuilder sb = new StringBuilder(512);
            sb.append(isJava ? "java\t" : "native\t").append(name).append('\t').append(epochNs(e.getStartTime())).append('\t');
            List<RecordedFrame> frames = st.getFrames();
            int n = Math.min(frames.size(), maxDepth);
            for (int i = 0; i < n; i++) {
               RecordedMethod m = frames.get(i).getMethod();
               if (i > 0) {
                  sb.append(';');
               }
               sb.append(m.getType().getName()).append('.').append(m.getName());
            }
            sb.append('\n');
            out.write(sb.toString());
            if (isJava) {
               java++;
            } else {
               nat++;
            }
         }
      }
      System.err.println("samples: " + java + " java, " + nat + " native; " + gc + " gc events" + (wantThread != null ? " (thread " + wantThread + ")" : ""));
   }

   private static long epochNs(Instant t) {
      return t.getEpochSecond() * 1_000_000_000L + t.getNano();
   }
}
