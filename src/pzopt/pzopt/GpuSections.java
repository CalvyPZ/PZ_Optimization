package pzopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * GPU time per named section of the frame, measured with {@code GL_TIMESTAMP} queries that ride
 * the sprite command stream: {@link #begin(String)} / {@link #end(String)} are called on the game
 * thread while a frame is recorded, each queues a generic draw command whose render step (on the
 * render thread, in stream order) writes a timestamp query. Results are collected on the render
 * thread a few frames later and summed per section; {@link #summary()} (called from the periodic
 * FBORenderCell log line) prints the average GPU time per frame of each section since the last
 * summary. Timestamps nest freely, unlike GL_TIME_ELAPSED (the Overlay's whole-frame query).
 * Enabled by {@code Config.GPU_SECTIONS} (measurement only; a few dozen queries per frame).
 */
public final class GpuSections {
   private static final int RING = 4096;
   private static int[] ids;
   private static int next;
   private static final ArrayList<Pair> pending = new ArrayList<>();
   private static final HashMap<String, long[]> totals = new HashMap<>(); // name -> {ns, count}
   private static final ArrayList<Marker> pool = new ArrayList<>();
   private static long frames;
   private static int lastFrame = -1;

   private static final class Pair {
      final String name;
      final int beginId;
      final int endId;

      Pair(String name, int beginId, int endId) {
         this.name = name;
         this.beginId = beginId;
         this.endId = endId;
      }
   }

   /** One queued timestamp; render() runs on the render thread. */
   private static final class Marker extends TextureDraw.GenericDrawer {
      String name;
      boolean isEnd;
      int id;

      @Override
      public void render() {
         if (ids == null) {
            ids = new int[RING];
            GL15.glGenQueries(ids);
         }
         if (id < 0) {
            id = ids[next];
            next = (next + 1) % RING;
         }
         GL33.glQueryCounter(id, GL33.GL_TIMESTAMP);
         if (isEnd) {
            synchronized (pending) {
               pending.add(new Pair(name, openBegin(name), id));
            }
            collect();
         } else {
            synchronized (open) {
               open.put(name, id);
            }
         }
      }

      @Override
      public void postRender() {
         synchronized (pool) {
            pool.add(this);
         }
      }
   }

   private static final HashMap<String, Integer> open = new HashMap<>();

   private static int openBegin(String name) {
      synchronized (open) {
         Integer id = open.remove(name);
         return id == null ? -1 : id;
      }
   }

   public static boolean enabled() {
      return Config.GPU_SECTIONS && Overrides.enabled();
   }

   public static void begin(String name) {
      if (enabled()) {
         queue(name, false);
      }
   }

   public static void end(String name) {
      if (enabled()) {
         queue(name, true);
      }
   }

   /** Call once per frame on the game thread (any section site does it) so per-frame averages are right. */
   public static void frame(int frameNo) {
      if (frameNo != lastFrame) {
         lastFrame = frameNo;
         frames++;
      }
   }

   private static void queue(String name, boolean isEnd) {
      Marker m;
      synchronized (pool) {
         m = pool.isEmpty() ? new Marker() : pool.remove(pool.size() - 1);
      }
      m.name = name;
      m.isEnd = isEnd;
      m.id = -1;
      SpriteRenderer.instance.drawGeneric(m);
   }

   /** Render thread: fold every finished pair into the totals. */
   private static void collect() {
      synchronized (pending) {
         for (int i = pending.size() - 1; i >= 0; i--) {
            Pair p = pending.get(i);
            if (p.beginId < 0) {
               pending.remove(i);
               continue;
            }
            if (GL15.glGetQueryObjecti(p.endId, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            long t0 = GL33.glGetQueryObjecti64(p.beginId, GL15.GL_QUERY_RESULT);
            long t1 = GL33.glGetQueryObjecti64(p.endId, GL15.GL_QUERY_RESULT);
            pending.remove(i);
            synchronized (totals) {
               long[] t = totals.computeIfAbsent(p.name, k -> new long[2]);
               t[0] += Math.max(0L, t1 - t0);
               t[1]++;
            }
         }
      }
   }

   /** Average GPU microseconds per frame and pairs per frame for every section since the last call; resets. */
   public static String summary() {
      if (!enabled()) {
         return "";
      }
      StringBuilder sb = new StringBuilder(" | gpu us/frame:");
      synchronized (totals) {
         long f = Math.max(1L, frames);
         ArrayList<Map.Entry<String, long[]>> entries = new ArrayList<>(totals.entrySet());
         entries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
         for (Map.Entry<String, long[]> e : entries) {
            long[] t = e.getValue();
            sb.append(' ').append(e.getKey()).append('=').append(t[0] / 1000L / f).append('(').append(String.format("%.1f", (double) t[1] / f)).append("/f)");
         }
         totals.clear();
         frames = 0;
      }
      return sb.toString();
   }
}
