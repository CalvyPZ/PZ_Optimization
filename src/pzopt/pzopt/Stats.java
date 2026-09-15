package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import zombie.ZomboidFileSystem;
import zombie.iso.IsoChunk;

/**
 * Per-chunk load/recalc timings and per-frame durations, written to
 * pzopt-chunks.out and pzopt-frames.out in the Zomboid user directory when
 * Config.INSTRUMENT is on. Off, every hook is a single static boolean test.
 *
 * pzopt-chunks.out columns (tab-separated, times in microseconds):
 *   tEnqueue wx wy minLevel maxLevel jobType thread queueWait loadTime recalcWait recalcTime publishWait
 *     tEnqueue    = enqueue time since Stats init (game start), microseconds
 *     queueWait   = enqueue (addJob) -> disk load start, on the streamer thread
 *     loadTime    = LoadChunk + VehiclesDB2.loadChunk
 *     recalcWait  = load end -> recalc start (0 when inline; pool queueing otherwise)
 *     recalcTime  = loadInWorldStreamerThread
 *     publishWait = recalc end -> chunk handed to the game thread queue
 * pzopt-frames.out: one frame duration in microseconds per line, game thread.
 */
public final class Stats {
   public static final boolean ENABLED = Config.INSTRUMENT;
   private static final boolean HOOK_FRAMES = ENABLED || Harness.REQUESTED;
   private static final ConcurrentHashMap<IsoChunk, Long> enqueued = new ConcurrentHashMap<>();
   private static final Object lock = new Object();
   private static final ArrayList<String> chunkLines = new ArrayList<>();
   private static int[] frameUs = new int[4096];
   private static int frameCount = 0;
   private static long lastFrameNs = 0L;
   private static int lastFrameId = Integer.MIN_VALUE;
   private static long lastFlushNs = System.nanoTime();
   private static boolean headerWritten = false;
   private static final long T0 = System.nanoTime();
   private static int chunkCount = 0;

   private Stats() {
   }

   /** Timings for one chunk, filled in along the streaming path. */
   public static final class Timing {
      public long enqueuedNs;
      public long loadStartNs;
      public long loadEndNs;
      public long recalcStartNs;
      public long recalcEndNs;
      public long publishNs;
      public String thread = "";
   }

   public static void onEnqueued(IsoChunk chunk) {
      if (ENABLED) {
         enqueued.put(chunk, System.nanoTime());
      }
   }

   public static Timing begin(IsoChunk chunk) {
      Timing t = new Timing();
      Long e = enqueued.remove(chunk);
      t.loadStartNs = System.nanoTime();
      t.enqueuedNs = e != null ? e : t.loadStartNs;
      return t;
   }

   public static void done(IsoChunk chunk, Timing t) {
      if (!ENABLED) {
         return;
      }
      String line = us(t.enqueuedNs - T0) + "\t" + chunk.wx + "\t" + chunk.wy + "\t" + chunk.getMinLevel() + "\t" + chunk.getMaxLevel() + "\t"
            + chunk.jobType + "\t" + t.thread + "\t"
            + us(t.loadStartNs - t.enqueuedNs) + "\t" + us(t.loadEndNs - t.loadStartNs) + "\t"
            + us(t.recalcStartNs - t.loadEndNs) + "\t" + us(t.recalcEndNs - t.recalcStartNs) + "\t"
            + us(t.publishNs - t.recalcEndNs);
      synchronized (lock) {
         chunkLines.add(line);
         chunkCount++;
         maybeFlush(false);
      }
   }

   public static int chunkCount() {
      synchronized (lock) {
         return chunkCount;
      }
   }

   /** Writes a marker line into both output files so analysis can align to route start/end. */
   public static void mark(String label) {
      if (!ENABLED) {
         return;
      }
      synchronized (lock) {
         maybeFlush(true);
         chunkLines.add("# " + label + " " + us(System.nanoTime() - T0));
         markers.add("# " + label + " " + us(System.nanoTime() - T0));
         maybeFlush(true);
      }
   }

   private static final ArrayList<String> markers = new ArrayList<>();

   /** Called from IsoChunk.update() (every loaded chunk, every frame); only the first call per frame does anything. */
   public static void frameTick(int frameId) {
      if (!HOOK_FRAMES || frameId == lastFrameId) {
         return;
      }
      long now = System.nanoTime();
      if (Harness.REQUESTED) {
         Harness.onFrame(now);
      }
      if (!ENABLED) {
         lastFrameId = frameId;
         return;
      }
      synchronized (lock) {
         if (frameId == lastFrameId) {
            return;
         }
         if (lastFrameId != Integer.MIN_VALUE && lastFrameNs != 0L) {
            if (frameCount == frameUs.length) {
               frameUs = java.util.Arrays.copyOf(frameUs, frameUs.length * 2);
            }
            frameUs[frameCount++] = (int)Math.min(Integer.MAX_VALUE, (now - lastFrameNs) / 1000L);
         }
         lastFrameId = frameId;
         lastFrameNs = now;
         maybeFlush(false);
      }
   }

   /** Write everything pending; called from the streamer thread on stop and on quit. */
   public static void flush() {
      if (!ENABLED) {
         return;
      }
      synchronized (lock) {
         maybeFlush(true);
      }
   }

   private static long us(long ns) {
      return ns / 1000L;
   }

   private static void maybeFlush(boolean force) {
      long now = System.nanoTime();
      if (!force && now - lastFlushNs < 5_000_000_000L && chunkLines.size() < 512 && frameCount < 4096) {
         return;
      }
      lastFlushNs = now;
      File dir = new File(ZomboidFileSystem.instance.getCacheDir());
      try {
         if (!chunkLines.isEmpty()) {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(dir, "pzopt-chunks.out"), true))) {
               if (!headerWritten) {
                  w.write("tEnqueueUs\twx\twy\tminLevel\tmaxLevel\tjobType\tthread\tqueueWaitUs\tloadUs\trecalcWaitUs\trecalcUs\tpublishWaitUs\n");
                  headerWritten = true;
               }
               for (String l : chunkLines) {
                  w.write(l);
                  w.write('\n');
               }
            }
            chunkLines.clear();
         }
         if (frameCount > 0 || !markers.isEmpty()) {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(dir, "pzopt-frames.out"), true))) {
               for (String m : markers) {
                  w.write(m);
                  w.write('\n');
               }
               markers.clear();
               for (int i = 0; i < frameCount; i++) {
                  w.write(Integer.toString(frameUs[i]));
                  w.write('\n');
               }
            }
            frameCount = 0;
         }
      } catch (IOException e) {
         Log.warn("could not write stats: " + e);
      }
   }
}
