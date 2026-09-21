package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.input.GameKeyboard;
import zombie.ui.TextManager;
import zombie.ui.UIFont;

/**
 * In-game performance overlay and frame log, so a measurement reads the same on every platform
 * without MangoHud or RivaTuner. The stock game only has the debug {@code FPSGraph} ("Display
 * FPS" key, in-game only): bars of frames per second, no frame-time tail, no utilization, no log.
 *
 * Three hooks, all in {@code RenderThread.lockStepRenderStep}: {@link #gpuBegin()} /
 * {@link #gpuEnd()} wrap {@code SpriteRenderer.postRender()} with a {@code GL_TIME_ELAPSED} query
 * (the frame's GPU work; the swap is outside it), and {@link #onSwap()} after {@code Display.update}
 * records the presented frame time, the instant MangoHud logs. {@link #draw()} runs on the game
 * thread from {@code Display.imguiEndFrame()}, which {@code Core.EndFrameUI} calls after the UI
 * composite and right before it hands the frame to the render thread, so the overlay sits on
 * top of the menus, the loading screen and the world alike. (After {@code states.render()} in
 * {@code GameWindow.renderInternal} is too late: the hand-off has happened.)
 *
 * What it shows, over a sliding window of {@link #WINDOW_NS}: fps (coloured against the cap, see
 * {@link #fpsColor}) and mean frame time, p99 / p99.9 / max, 1 %-low fps, frame-to-frame jitter, spikes (frames above twice the median), and the
 * utilization the objective asks for: GPU busy share (timer queries), the game thread's and the
 * render thread's CPU share of one core, the process's share of all cores and the whole machine's
 * (JMX), plus the heap. A verdict line names what is saturated when the frame rate is under the
 * cap; "below cap, nothing saturated" is itself the finding. A bar graph of the last frames sits
 * underneath with the cap's budget marked.
 *
 * The GPU number is the GPU-timeline span of the frame's draw commands. Those are submitted in
 * one burst by the render thread, so the span is the GPU's busy time unless the render thread
 * itself is the bottleneck, in which case it tracks the render thread's CPU share (shown next
 * to it) instead. Reading it against that column tells the two apart.
 *
 * Config: {@code overlaySampling=true} (Optimizations tab, off by default) turns the measurement on
 * at all; without it the toggle key shows a notice pointing at the tick box and the restart.
 * {@code overlay=true} shows it from boot (and implies sampling); the key bound to "Toggle performance overlay"
 * (Options > Key Bindings, default F9; {@code overlayKey=<lwjgl code>} is the fallback when the
 * binding is missing) toggles it any time. {@code overlayLog=true}, or any harness run, writes
 * {@code Zomboid/pzopt-overlay.out}: one CSV row per presented frame in MangoHud's column names
 * (fps, frametime in ms, cpu_load, gpu_load, plus game_load, render_load, gpu_ms, elapsed in ns,
 * epoch_ms), which harness/analyze.py reads like a MangoHud log. {@code overlayFont} picks the
 * UIFont (CodeMedium by default); {@code overlayCorner} one of tl, tr, bl, br.
 *
 * Cost: one nanoTime and a ring write per frame on the render thread, two GL query calls per
 * frame, a stats pass every {@link #REFRESH_NS} on the game thread (sorting at most a few
 * thousand floats), about 300 sprite quads per frame while visible, and a daemon thread that
 * samples the CPU / GPU utilization every {@link #UTIL_NS} (the JMX load calls are slow on
 * Windows and must never run on the game thread).
 */
public final class Overlay {
   private static final boolean ACTIVE = Overrides.enabled();
   /**
    * Whether the overlay measures anything: the presented-frame ring, the GL timer queries and the
    * utilization sampler thread. Off unless {@code overlaySampling=true} (the Optimizations tab),
    * something that needs the numbers ({@code overlay}, {@code overlayLog}) or a harness run; with
    * it off the toggle key only shows {@link #NOTICE}. Decided at boot, like every Config key.
    */
   private static final boolean SAMPLING = ACTIVE && (Config.OVERLAY_SAMPLING || Config.OVERLAY || Config.OVERLAY_LOG || Harness.REQUESTED);
   private static final boolean LOG = SAMPLING && (Config.OVERLAY_LOG || Harness.REQUESTED);
   private static final long NOTICE_NS = 8_000_000_000L;
   private static final String[] NOTICE = {
      "Performance overlay: sampling is off.",
      "Tick \"Sample frame times and utilization\" under Options > Optimizations > Performance overlay,",
      "then restart the game for it to take effect."
   };
   private static long noticeUntilNs;
   private static final long WINDOW_NS = 5_000_000_000L;
   private static final long REFRESH_NS = 250_000_000L;
   private static final long UTIL_NS = 500_000_000L;
   private static final int RING = 8192;
   /** Frames in the frame-time graph (2 px each), from {@code overlayGraph}; 0 = no graph. */
   private static final int GRAPH_BARS = graphBars();
   private static final int QUERIES = 8;
   private static final String BIND = "Toggle performance overlay";

   // --- ring of presented frames, written by the render thread, read by the game thread ---
   private static final float[] frameMs = new float[RING];
   private static final long[] frameEndNs = new long[RING];
   private static final float[] gpuMs = new float[RING];
   private static volatile int head; // frames recorded so far; slot = (head - 1) & (RING - 1) is the newest
   private static long lastSwapNs;
   private static volatile long renderThreadId = -1L;

   // --- GPU timer queries (render thread only) ---
   private static int gpuState; // 0 untried, 1 running, -1 unavailable
   private static final int[] queryIds = new int[QUERIES];
   private static final long[] queryFrame = new long[QUERIES]; // head value the query belongs to, -1 free
   private static int queryNext;
   private static int activeQuery = -1;
   private static float pendingGpuMs; // the most recent completed query, attributed to the next frame's slot

   // --- utilization, sampled by a daemon thread every UTIL_NS, never on the game thread ---
   // On Windows the JDK serves OperatingSystemMXBean.getProcessCpuLoad() / getCpuLoad() through PDH:
   // every call re-enumerates the "Process" performance object (every process on the machine) and
   // collects the counter query once per 500 ms. Cheap on Linux (/proc), 5-50 ms on an older
   // Windows PC, and it ran here on the game thread even with the overlay hidden: the reported
   // "micro stutter every half second". The sampler thread also reads the thread CPU times.
   private static volatile float gameLoad, renderLoad, processLoad, systemLoad;
   private static volatile float gpuLoad; // last-second GPU busy share, 0..100
   private static volatile long gameThreadId = -1L;
   private static Thread utilThread;
   private static final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
   private static final com.sun.management.OperatingSystemMXBean os = osBean();

   // --- display ---
   private static final float[] WHITE = {1f, 1f, 1f};
   private static final float[] GREEN = {0.45f, 1f, 0.45f};
   private static final float[] AMBER = {1f, 0.8f, 0.3f};
   private static final float[] RED = {1f, 0.45f, 0.45f};
   private static final float[] BLUE = {0.45f, 0.7f, 1f};
   /** The four fps tints from the options tab; names or RRGGBB hex, see {@link #color}. */
   private static final float[] FPS_BLUE = color(Config.OVERLAY_FPS_COLOR_BLUE, BLUE);
   private static final float[] FPS_GREEN = color(Config.OVERLAY_FPS_COLOR_GREEN, GREEN);
   private static final float[] FPS_YELLOW = color(Config.OVERLAY_FPS_COLOR_YELLOW, AMBER);
   private static final float[] FPS_RED = color(Config.OVERLAY_FPS_COLOR_RED, RED);
   private static volatile boolean visible = Config.OVERLAY;
   private static boolean fontFailed;
   private static UIFont font;
   private static long lastStatsNs;
   private static String[] lines = new String[0];
   /** The "NNN fps" token of the first line, drawn in {@link #fpsColor}; the rest of that line follows it in white. */
   private static String fpsText = "";
   private static float[] fpsColor = WHITE;
   private static float[] verdictColor = WHITE;
   private static String verdict = "";
   /** The game-thread tree (pzopt.GameThreadProfile), refreshed with the stats; drawn under the text lines. */
   private static String profileHeader = "";
   private static java.util.List<GameThreadProfile.Row> profileRows = java.util.List.of();
   private static final int PROFILE_SUBS = treeSubs(); // sub-phases shown per phase; -1 = no tree
   private static final int PROFILE_HOT = 2;  // hot methods hinted per sub-phase
   private static final int STATS_LINES = statsLines(); // of the four stats lines, how many show (fps / tails / full)

   private static int graphBars() {
      String v = Config.OVERLAY_GRAPH.trim().toLowerCase(java.util.Locale.ROOT);
      if (v.equals("off")) {
         return 0;
      }
      try {
         return Math.max(60, Math.min(RING - 64, Integer.parseInt(v)));
      } catch (NumberFormatException e) {
         return 240;
      }
   }

   private static int treeSubs() {
      String v = Config.OVERLAY_TREE.trim().toLowerCase(java.util.Locale.ROOT);
      if (v.equals("off")) {
         return -1;
      }
      try {
         return Math.max(0, Math.min(20, Integer.parseInt(v)));
      } catch (NumberFormatException e) {
         return 5;
      }
   }

   private static int statsLines() {
      switch (Config.OVERLAY_STATS.trim().toLowerCase(java.util.Locale.ROOT)) {
         case "off": return 0;
         case "fps": return 1;
         case "tails": return 3;
         default: return 4;
      }
   }
   /** The flame graph boxes of the window (pzopt.GameThreadProfile.flame), laid out in fractions of the panel width at refresh. */
   private static java.util.List<FlameBox> flameBoxes = java.util.List.of();
   private static int flameDepth; // rows of boxes (root row included)
   private static String flameTitle = "";
   private static final java.util.HashMap<String, Integer> labelWidths = new java.util.HashMap<>();

   static final class FlameBox {
      final float x0, x1; // fractions of the graph width
      final int depth;    // 0 = root row (drawn at the bottom)
      final String name;
      final float[] color;
      final float shade;  // per-name brightness, so neighbours of one phase stay apart

      FlameBox(float x0, float x1, int depth, String name, float[] color, float shade) {
         this.x0 = x0;
         this.x1 = x1;
         this.depth = depth;
         this.name = name;
         this.color = color;
         this.shade = shade;
      }
   }
   private static float budgetMs = 1000f / 240f;

   // --- log ---
   private static BufferedWriter log;
   private static boolean logFailed;
   private static long logStartNs, logStartEpochMs, lastLogFlushNs;
   private static final StringBuilder logLine = new StringBuilder(160);

   private Overlay() {
   }

   private static com.sun.management.OperatingSystemMXBean osBean() {
      try {
         java.lang.management.OperatingSystemMXBean b = ManagementFactory.getOperatingSystemMXBean();
         return b instanceof com.sun.management.OperatingSystemMXBean ? (com.sun.management.OperatingSystemMXBean)b : null;
      } catch (Throwable t) {
         return null;
      }
   }

   // ------------------------------------------------------------------ render thread hooks

   /** Before {@code SpriteRenderer.postRender()}: start the frame's GL_TIME_ELAPSED query. */
   public static void gpuBegin() {
      if (!SAMPLING || gpuState < 0) {
         return;
      }
      try {
         if (gpuState == 0) {
            GLCapabilities caps = GL.getCapabilities();
            if (!caps.OpenGL33 && !caps.GL_ARB_timer_query) {
               gpuState = -1;
               Log.info("overlay: no GL timer queries, GPU load unavailable");
               return;
            }
            GL15.glGenQueries(queryIds);
            Arrays.fill(queryFrame, -1L);
            gpuState = 1;
         }
         collectQueries();
         int q = queryNext;
         if (queryFrame[q] != -1L) {
            return; // every query object is still in flight; skip this frame
         }
         GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queryIds[q]);
         activeQuery = q;
      } catch (Throwable t) {
         gpuState = -1;
         Log.warn("overlay: GPU timer queries disabled: " + t);
      }
   }

   /** After {@code SpriteRenderer.postRender()}, before the swap. */
   public static void gpuEnd() {
      if (activeQuery < 0) {
         return;
      }
      try {
         GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
         queryFrame[activeQuery] = head;
         queryNext = (activeQuery + 1) % QUERIES;
      } catch (Throwable t) {
         gpuState = -1;
         Log.warn("overlay: GPU timer queries disabled: " + t);
      }
      activeQuery = -1;
   }

   /** Reads every finished query, attributing its GPU time to the frame slot it was issued for. */
   private static void collectQueries() {
      for (int i = 0; i < QUERIES; i++) {
         long f = queryFrame[i];
         if (f == -1L) {
            continue;
         }
         if (GL15.glGetQueryObjecti(queryIds[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            continue;
         }
         long ns = GL33.glGetQueryObjecti64(queryIds[i], GL15.GL_QUERY_RESULT);
         queryFrame[i] = -1L;
         if (f < head) { // slot already written by onSwap: fill it in (still within the ring)
            gpuMs[(int)(f & (RING - 1))] = ns / 1e6f;
         } else {
            pendingGpuMs = ns / 1e6f;
         }
      }
   }

   /** After {@code Display.update(true)}: one presented frame. */
   public static void onSwap() {
      if (!SAMPLING) {
         return;
      }
      long now = System.nanoTime();
      if (renderThreadId < 0) {
         renderThreadId = Thread.currentThread().threadId();
      }
      if (lastSwapNs != 0L) {
         float ms = (now - lastSwapNs) / 1e6f;
         int h = head;
         int slot = h & (RING - 1);
         frameMs[slot] = ms;
         frameEndNs[slot] = now;
         gpuMs[slot] = pendingGpuMs;
         pendingGpuMs = 0f;
         head = h + 1;
         if (LOG && h >= LOG_LAG) {
            logFrame(h - LOG_LAG); // the query for that frame has resolved by now (at most QUERIES in flight)
         }
      }
      lastSwapNs = now;
   }

   /** Rows are written {@link #LOG_LAG} frames late so gpu_ms is the frame's own timer-query result. */
   private static final int LOG_LAG = QUERIES;

   private static void logFrame(int frame) {
      if (logFailed) {
         return;
      }
      int slot = frame & (RING - 1);
      long end = frameEndNs[slot];
      float ms = frameMs[slot];
      try {
         if (log == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new BufferedWriter(new FileWriter(new File(dir, "pzopt-overlay.out"), false), 1 << 16);
            logStartNs = end;
            logStartEpochMs = System.currentTimeMillis() - (System.nanoTime() - end) / 1_000_000L;
            log.write("fps,frametime,gpu_ms,cpu_load,gpu_load,game_load,render_load,elapsed,epoch_ms\n");
            Log.info("overlay: logging presented frames to " + new File(dir, "pzopt-overlay.out"));
         }
         long elapsed = end - logStartNs;
         StringBuilder b = logLine;
         b.setLength(0);
         b.append(String.format(java.util.Locale.ROOT, "%.1f,%.4f,%.3f,%.1f,%.1f,%.1f,%.1f,", 1000f / ms, ms, gpuMs[slot], systemLoad, gpuLoad, gameLoad, renderLoad));
         b.append(elapsed).append(',').append(logStartEpochMs + elapsed / 1_000_000L).append('\n');
         log.write(b.toString());
         long now = end;
         if (now - lastLogFlushNs > 1_000_000_000L) {
            log.flush();
            lastLogFlushNs = now;
         }
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("overlay: log stopped: " + e);
      }
   }

   /** Flush the frame log (called at quit from the harness; safe to call any time). */
   public static void flushLog() {
      BufferedWriter w = log;
      if (w != null) {
         try {
            w.flush();
         } catch (IOException ignored) {
         }
      }
   }

   // ------------------------------------------------------------------ game thread

   public static boolean isVisible() {
      return visible;
   }

   public static void setVisible(boolean on) {
      visible = on;
   }

   /** Whether the frame log is being written (harness runs, {@code overlayLog}): the game-thread profile samples for it too. */
   static boolean logging() {
      return LOG;
   }

   /** From {@code Display.imguiEndFrame()} every game-thread frame: toggle key, stats refresh, draw. */
   public static void draw() {
      if (!ACTIVE) {
         return;
      }
      long now = System.nanoTime();
      if (toggled()) {
         if (SAMPLING) {
            visible = !visible;
            steadyLeftW = 0;
            Log.info("overlay: " + (visible ? "shown" : "hidden"));
         } else {
            noticeUntilNs = now + NOTICE_NS;
            Log.info("overlay: sampling is off (overlaySampling=false); " + NOTICE[1] + " " + NOTICE[2]);
         }
      }
      if (!SAMPLING) {
         if (noticeUntilNs > now && !fontFailed) {
            try {
               renderNotice();
            } catch (Throwable t) {
               fontFailed = true;
               Log.warn("overlay: draw failed, overlay off: " + t);
            }
         }
         return;
      }
      if (gameThreadId < 0) {
         gameThreadId = Thread.currentThread().threadId();
         startUtilSampler();
         GameThreadProfile.start(gameThreadId); // what the game thread does, for the verdict and the log
      }
      if (!visible || fontFailed) {
         return;
      }
      if (now - lastStatsNs >= REFRESH_NS) {
         lastStatsNs = now;
         refreshStats(now);
      }
      try {
         render();
      } catch (Throwable t) {
         fontFailed = true; // fonts not loaded yet or a UI class missing: try again next boot rather than every frame
         Log.warn("overlay: draw failed, overlay off: " + t);
      }
   }

   private static boolean toggled() {
      try {
         if (GameKeyboard.isKeyPressed(BIND)) {
            return true;
         }
         Core core = Core.getInstance();
         if (core != null && core.getKeyBinding(BIND).keyValue() == 0) {
            int k = Config.OVERLAY_KEY; // binding not registered (Lua not installed): raw fallback key
            return k > 0 && k < 256 && GameKeyboard.isKeyPressed(k);
         }
      } catch (Throwable ignored) {
      }
      return false;
   }

   /** Starts the utilization sampler once the game thread is known (its id is what it samples). */
   private static synchronized void startUtilSampler() {
      if (utilThread != null) {
         return;
      }
      Thread t = new Thread(Overlay::utilLoop, "pzopt-overlay-util");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
      utilThread = t;
   }

   /** The sampler thread: thread CPU shares, process / machine load, GPU busy share, every UTIL_NS. */
   private static void utilLoop() {
      long sampledNs = 0L, gameCpuNs = 0L, renderCpuNs = 0L;
      while (true) {
         try {
            Thread.sleep(UTIL_NS / 1_000_000L);
         } catch (InterruptedException e) {
            return;
         }
         long now = System.nanoTime();
         long dt = now - sampledNs;
         long g = 0L, r = 0L;
         try {
            if (!threads.isThreadCpuTimeEnabled()) {
               threads.setThreadCpuTimeEnabled(true);
            }
            long gid = gameThreadId;
            long rid = renderThreadId;
            g = gid >= 0 ? threads.getThreadCpuTime(gid) : 0L;
            r = rid >= 0 ? threads.getThreadCpuTime(rid) : 0L;
         } catch (Throwable ignored) {
         }
         if (sampledNs != 0L && dt > 0) {
            if (g > 0 && gameCpuNs > 0) {
               gameLoad = Math.min(100f, 100f * (g - gameCpuNs) / dt);
            }
            if (r > 0 && renderCpuNs > 0) {
               renderLoad = Math.min(100f, 100f * (r - renderCpuNs) / dt);
            }
         }
         gameCpuNs = g;
         renderCpuNs = r;
         sampledNs = now;
         // The PDH-backed calls: only while someone reads the numbers (the overlay or the frame log),
         // and off the game thread either way.
         if (os != null && (visible || LOG)) {
            try {
               double p = os.getProcessCpuLoad();
               double s = os.getCpuLoad();
               if (p >= 0) {
                  processLoad = (float)(p * 100);
               }
               if (s >= 0) {
                  systemLoad = (float)(s * 100);
               }
            } catch (Throwable ignored) {
            }
         }
         gpuLoad = gpuBusyShare(now);
      }
   }

   /** GPU busy share over the last second of presented frames (reads the ring the render thread writes). */
   private static float gpuBusyShare(long now) {
      int h = head;
      long gpuSum = 0L;
      long wall = 0L;
      for (int i = 1; i <= Math.min(h, RING - 64); i++) {
         int slot = (h - i) & (RING - 1);
         if (now - frameEndNs[slot] > 1_000_000_000L) {
            break;
         }
         gpuSum += (long)(gpuMs[slot] * 1000f);
         wall += (long)(frameMs[slot] * 1000f);
      }
      return wall > 0 ? Math.min(100f, 100f * gpuSum / wall) : 0f;
   }

   private static void refreshStats(long now) {
      int h = head;
      int n = Math.min(h, RING - 64);
      float[] win = new float[n];
      int count = 0;
      int lastSecond = 0;
      float sumMs = 0f;
      float jitter = 0f;
      float prev = -1f;
      for (int i = 1; i <= n; i++) {
         int slot = (h - i) & (RING - 1);
         long age = now - frameEndNs[slot];
         if (age > WINDOW_NS) {
            break;
         }
         float ms = frameMs[slot];
         win[count++] = ms;
         sumMs += ms;
         if (age <= 1_000_000_000L) {
            lastSecond++;
         }
         if (prev >= 0f) {
            jitter += Math.abs(ms - prev);
         }
         prev = ms;
      }
      if (count == 0) {
         lines = STATS_LINES == 0 ? new String[0] : new String[] {"performance overlay: waiting for frames"};
         fpsText = "";
         verdict = "";
         return;
      }
      float mean = sumMs / count;
      float[] sorted = Arrays.copyOf(win, count);
      Arrays.sort(sorted);
      float p50 = pct(sorted, 50), p99 = pct(sorted, 99), p999 = pct(sorted, 99.9f), max = sorted[count - 1];
      int spikes = 0;
      for (int i = 0; i < count; i++) {
         if (win[i] > 2f * p50) {
            spikes++;
         }
      }
      boolean uncapped = FrameCap.uncappedNow();
      int cap = uncapped ? 0 : FrameCap.lockNow();
      budgetMs = 1000f / (cap > 0 ? cap : 240);
      float fps = lastSecond;
      Runtime rt = Runtime.getRuntime();
      float heapUsed = (rt.totalMemory() - rt.freeMemory()) / 1073741824f;
      float heapMax = rt.maxMemory() / 1073741824f;
      int cores = rt.availableProcessors();
      String gpu = gpuState < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.0f %%", gpuLoad);
      fpsText = String.format(java.util.Locale.ROOT, "%3.0f fps", fps);
      fpsColor = fpsColor(fps, cap);
      String[] all = {
            String.format(java.util.Locale.ROOT, "   %5.2f ms   cap %s", mean, cap > 0 ? cap + " fps" : "none"),
            String.format(java.util.Locale.ROOT, "p50 %.2f   p99 %.2f   p99.9 %.2f   max %.1f ms   (%d frames / %d s)", p50, p99, p999, max, count, (int)(WINDOW_NS / 1_000_000_000L)),
            String.format(java.util.Locale.ROOT, "1%%-low %.0f fps   jitter %.2f ms   spikes >2x median %d", p99 > 0 ? 1000f / p99 : 0f, count > 1 ? jitter / (count - 1) : 0f, spikes),
            String.format(java.util.Locale.ROOT, "GPU %s   game thread %.0f %%   render thread %.0f %%   process %.0f %% of %d cores   machine %.0f %%   heap %.1f/%.1f GB",
                  gpu, gameLoad, renderLoad, processLoad, cores, systemLoad, heapUsed, heapMax),
      };
      lines = Arrays.copyOf(all, STATS_LINES);
      if (STATS_LINES == 0) {
         fpsText = "";
      }
      // what the game thread is doing (stack samples): a tree of phases and sub-phases, biggest first
      // the game-thread tree and the flame graph only once a save is loading or playing: in the menus
      // the stacks are just menu UI, and the flame column would sit over the menu / Workshop screens
      boolean world = inGame();
      profileHeader = PROFILE_SUBS < 0 || !world ? "" : GameThreadProfile.header();
      profileRows = PROFILE_SUBS < 0 || !world ? java.util.List.of() : GameThreadProfile.tree(PROFILE_SUBS, PROFILE_HOT);
      if (world) {
         layoutFlame();
      } else {
         flameBoxes = java.util.List.of();
         flameTitle = "";
      }
      // verdict against the objective: at the cap, or what is saturated, or nothing is
      String verdictMode = Config.OVERLAY_VERDICT.trim().toLowerCase(java.util.Locale.ROOT);
      if (verdictMode.equals("off")) {
         verdict = "";
      } else if (cap > 0 && fps >= cap * 0.98f) {
         verdict = "at the cap";
         verdictColor = GREEN;
      } else {
         float top = Math.max(gameLoad, Math.max(renderLoad, gpuState < 0 ? 0f : gpuLoad));
         if (top >= 90f) {
            String who = top == gameLoad ? "game thread" : top == renderLoad ? "render thread" : "GPU";
            verdict = (cap > 0 ? "below cap: " : "") + who + " bound";
            if (top == gameLoad && verdictMode.equals("detailed") && world) {
               String detail = GameThreadProfile.verdictDetail(); // the two biggest sub-phases, e.g. "chunk bakes 21 %, zombies 9 %"
               if (!detail.isEmpty()) {
                  verdict += ": " + detail;
               }
            }
            verdictColor = AMBER;
         } else {
            verdict = (cap > 0 ? "below cap, " : "") + "nothing saturated: waits or sync";
            verdictColor = RED;
         }
      }
   }

   /**
    * Colour of the fps number (Config {@code overlayFps*}). Defaults: with a cap and follow-cap on,
    * blue at the cap (98 %: the verdict's tolerance, the limiter never lands exactly on it), green
    * within 10 % of it, yellow within 50 %, red further below. Uncapped, or follow-cap off: blue above
    * 300 fps, green 150-300, yellow 100-150, red under 100. Off: white like the rest of the line.
    */
   static float[] fpsColor(float fps, int cap) {
      if (!Config.OVERLAY_FPS_COLOR) {
         return WHITE;
      }
      if (cap > 0 && Config.OVERLAY_FPS_FOLLOW_CAP) {
         float pct = fps * 100f / cap;
         return pct >= Config.OVERLAY_FPS_CAP_BLUE_PCT ? FPS_BLUE
               : pct >= Config.OVERLAY_FPS_CAP_GREEN_PCT ? FPS_GREEN
               : pct >= Config.OVERLAY_FPS_CAP_YELLOW_PCT ? FPS_YELLOW : FPS_RED;
      }
      return fps > Config.OVERLAY_FPS_BLUE_ABOVE ? FPS_BLUE
            : fps >= Config.OVERLAY_FPS_GREEN_ABOVE ? FPS_GREEN
            : fps >= Config.OVERLAY_FPS_YELLOW_ABOVE ? FPS_YELLOW : FPS_RED;
   }

   /** A colour name from the options tab or RRGGBB hex; {@code fallback} for anything else. */
   static float[] color(String spec, float[] fallback) {
      if (spec == null) {
         return fallback;
      }
      switch (spec.trim().toLowerCase(java.util.Locale.ROOT)) {
         case "blue": return BLUE;
         case "green": return GREEN;
         case "yellow": return AMBER;
         case "red": return RED;
         case "white": return WHITE;
         case "cyan": return new float[] {0.45f, 1f, 1f};
         case "lime": return new float[] {0.7f, 1f, 0.3f};
         case "orange": return new float[] {1f, 0.6f, 0.3f};
         case "magenta": return new float[] {1f, 0.5f, 1f};
         case "purple": return new float[] {0.7f, 0.5f, 1f};
         default:
            String hex = spec.trim().startsWith("#") ? spec.trim().substring(1) : spec.trim();
            if (hex.length() == 6) {
               try {
                  int rgb = Integer.parseInt(hex, 16);
                  return new float[] {((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f};
               } catch (NumberFormatException ignored) {
               }
            }
            Log.warn("overlay: unknown colour '" + spec + "', using the default");
            return fallback;
      }
   }

   private static float pct(float[] sorted, float p) {
      if (sorted.length == 0) {
         return 0f;
      }
      int i = (int)Math.ceil(p / 100f * sorted.length) - 1;
      return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
   }

   private static UIFont font() {
      if (font == null) {
         try {
            font = UIFont.valueOf(Config.OVERLAY_FONT);
         } catch (IllegalArgumentException e) {
            font = UIFont.CodeMedium;
         }
      }
      return font;
   }

   /** The toggle key with sampling off: {@link #NOTICE} in the overlay's corner for {@link #NOTICE_NS}. */
   private static void renderNotice() {
      TextManager tm = TextManager.instance;
      UIFont font = font();
      int lineH = tm.getFontHeight(font);
      int pad = 8;
      int textW = 0;
      for (String line : NOTICE) {
         textW = Math.max(textW, tm.MeasureStringX(font, line));
      }
      int w = textW + pad * 2;
      int h = NOTICE.length * lineH + pad * 2;
      String corner = Config.OVERLAY_CORNER;
      int x = corner.endsWith("r") ? Core.getInstance().getScreenWidth() - w - 10 : 10;
      int y = corner.startsWith("b") ? Core.getInstance().getScreenHeight() - h - 10 : 10;
      SpriteRenderer.instance.renderi(null, x, y, w, h, 0f, 0f, 0f, 0.65f, null);
      int ty = y + pad;
      for (int i = 0; i < NOTICE.length; i++) {
         float[] c = i == 0 ? AMBER : WHITE;
         tm.DrawString(font, x + pad, ty, NOTICE[i], c[0], c[1], c[2], 1.0);
         ty += lineH;
      }
   }

   private static boolean inGame() {
      try {
         return FrameCap.inGame();
      } catch (Throwable t) {
         return true; // state machine not up yet or a missing class: do not hide anything
      }
   }

   /**
    * Lays the window's flame graph out as boxes in fractions of the width: root at the bottom (row 0),
    * callees above their caller, biggest first from the left, coloured by the phase they belong to
    * (update green, render blue, lighting amber, pzopt frames magenta, the rest grey) with a per-name
    * shade. Nodes narrower than 1/1000 of the width are dropped; at most {@code overlayFlameDepth} rows.
    */
   private static void layoutFlame() {
      if ("off".equalsIgnoreCase(Config.OVERLAY_FLAME.trim())) {
         flameBoxes = java.util.List.of();
         flameTitle = "";
         return;
      }
      GameThreadProfile.Node root = GameThreadProfile.flame();
      if (root == null) {
         flameBoxes = java.util.List.of();
         flameTitle = "";
         return;
      }
      java.util.ArrayList<FlameBox> boxes = new java.util.ArrayList<>(1024);
      int maxDepth = Math.max(4, Config.OVERLAY_FLAME_DEPTH);
      int[] deepest = {0};
      placeFlame(root, 0f, 1f, 0, root.count, GameThreadProfile.C_OTHER_PUBLIC, boxes, maxDepth, deepest);
      flameBoxes = boxes;
      flameDepth = maxDepth; // the configured rows, whatever the deepest stack of this window: a steady panel
      flameTitle = "flame graph, last " + GameThreadProfile.WINDOW_SECONDS + " s (" + root.count + " stacks): root at the bottom, width = share, biggest first";
   }

   private static void placeFlame(GameThreadProfile.Node n, float x0, float x1, int depth, int total, float[] color, java.util.List<FlameBox> out, int maxDepth, int[] deepest) {
      if (x1 - x0 < 0.001f || depth >= maxDepth) {
         return;
      }
      float[] c = color;
      switch (n.name) {
         case "GameWindow.logic": c = GameThreadProfile.phaseColor("update"); break;
         case "GameWindow.renderInternal": c = GameThreadProfile.phaseColor("render"); break;
         case "LightingThread.update": c = GameThreadProfile.phaseColor("lighting"); break;
         default:
            if (n.name.startsWith("pzopt.")) {
               c = GameThreadProfile.C_PZOPT;
            }
      }
      int hh = n.name.hashCode();
      float shade = 0.72f + 0.28f * ((hh & 0xff) / 255f);
      out.add(new FlameBox(x0, x1, depth, n.name, c, shade));
      deepest[0] = Math.max(deepest[0], depth);
      float x = x0;
      float span = x1 - x0;
      for (GameThreadProfile.Node k : n.sortedKids()) {
         float w = span * k.count / Math.max(1, n.count);
         placeFlame(k, x, x + w, depth + 1, total, c, out, maxDepth, deepest);
         x += w;
      }
   }

   /** The widest left column drawn since the overlay was shown (see render). */
   private static int steadyLeftW;

   /** The stats lines with every number at its widest, so the width does not follow the live digits. */
   private static int statsTemplateWidth(TextManager tm, UIFont font, int fpsW) {
      if (STATS_LINES == 0) {
         return 0;
      }
      String[] t = {
            "   88.88 ms   cap 8888 fps",
            "p50 88.88   p99 88.88   p99.9 888.88   max 8888.8 ms   (88888 frames / 8 s)",
            "1%-low 8888 fps   jitter 88.88 ms   spikes >2x median 8888",
            "GPU 888 %   game thread 888 %   render thread 888 %   process 888 % of 88 cores   machine 888 %   heap 88.8/88.8 GB",
      };
      int w = 0;
      for (int i = 0; i < Math.min(STATS_LINES, t.length); i++) {
         w = Math.max(w, (i == 0 ? fpsW : 0) + labelWidth(tm, font, t[i]));
      }
      return w;
   }

   /** Rows the tree reserves: the three in-game phases, each with its sub-phases, whatever the window shows. */
   private static int treeRowsReserved() {
      return 3 * (1 + Math.max(0, PROFILE_SUBS));
   }

   /** The fixed width of a tree row: bar, a 26-character name, share, a wait share and a 44-character hint. */
   private static int treeRowWidth(TextManager tm, UIFont font, int indent, int barW, int pctW, int pad) {
      return indent * 2 + barW + pad + labelWidth(tm, font, "translucent floor objects x") + indent + pctW
            + labelWidth(tm, font, "  waiting 88 %") + indent + labelWidth(tm, font, "VisibilityPolygon2$Drawer.calculateVisibilityPolygonNew 88 %");
   }

   /** {@code text} cut with an ellipsis so it measures at most {@code maxW}; empty when even a few characters do not fit. */
   private static String fit(TextManager tm, UIFont font, String text, int maxW) {
      if (maxW <= 0) {
         return "";
      }
      if (tm.MeasureStringX(font, text) <= maxW) {
         return text;
      }
      int lo = 0, hi = text.length();
      while (lo < hi) {
         int mid = (lo + hi + 1) / 2;
         if (tm.MeasureStringX(font, text.substring(0, mid) + "\u2026") <= maxW) {
            lo = mid;
         } else {
            hi = mid - 1;
         }
      }
      return lo < 4 ? "" : text.substring(0, lo) + "\u2026";
   }

   /** A section divider across the panel: a gap, a faint 1 px line, a gap; returns the y below it. */
   private static int divider(SpriteRenderer sr, int x, int ty, int w, int pad) {
      sr.renderi(null, x + pad, ty + pad, w - pad * 2, 1, 1f, 1f, 1f, 0.3f, null);
      return ty + pad * 2 + 1;
   }

   private static int labelWidth(TextManager tm, UIFont font, String text) {
      Integer w = labelWidths.get(text);
      if (w == null) {
         if (labelWidths.size() > 4096) {
            labelWidths.clear();
         }
         w = tm.MeasureStringX(font, text);
         labelWidths.put(text, w);
      }
      return w;
   }

   private static void render() {
      TextManager tm = TextManager.instance;
      UIFont font = font();
      int lineH = tm.getFontHeight(font);
      int pad = 8;
      int graphH = lineH * 4;
      int graphW = GRAPH_BARS * 2;
      int textW = 0;
      int fpsW = fpsText.isEmpty() ? 0 : tm.MeasureStringX(font, fpsText);
      for (int i = 0; i < lines.length; i++) {
         textW = Math.max(textW, (i == 0 ? fpsW : 0) + tm.MeasureStringX(font, lines[i]));
      }
      textW = Math.max(textW, tm.MeasureStringX(font, verdict));
      // the game-thread tree: header, then one row per phase / sub-phase with a bar, the name, the share, the hint
      String header = profileHeader;
      java.util.List<GameThreadProfile.Row> tree = profileRows;
      int indent = tm.MeasureStringX(font, "    ");
      int pctW = tm.MeasureStringX(font, "100 %  ");
      int barW = Math.min(graphW, 140);
      String[] treeName = new String[tree.size()];
      String[] treePct = new String[tree.size()];
      String[] treeWait = new String[tree.size()];
      String[] treeHint = new String[tree.size()];
      int[] treeX = new int[tree.size()]; // x of the name relative to the panel's text start
      if (!header.isEmpty()) {
         textW = Math.max(textW, tm.MeasureStringX(font, header));
      }
      // a tree row never grows past this: the hint is cut to fit, so the panel width does not follow the names
      int treeRowMax = treeRowWidth(tm, font, indent, barW, pctW, pad);
      for (int i = 0; i < tree.size(); i++) {
         GameThreadProfile.Row r = tree.get(i);
         treeName[i] = r.name;
         treePct[i] = String.format(java.util.Locale.ROOT, "%.0f %%", r.pct);
         treeWait[i] = r.waitPct >= 0.5f ? String.format(java.util.Locale.ROOT, "  waiting %.0f %%", r.waitPct) : "";
         treeX[i] = indent * (r.depth + 1) + barW + pad;
         int used = treeX[i] + tm.MeasureStringX(font, r.name) + indent + pctW + tm.MeasureStringX(font, treeWait[i]);
         treeHint[i] = r.hint.isEmpty() ? "" : fit(tm, font, r.hint, treeRowMax - used - indent);
      }
      if (PROFILE_SUBS >= 0) {
         textW = Math.max(textW, treeRowMax);
      }
      // the frame graph gets a y-axis column (ms ticks) and an x-axis row; the flame graph the panel's width
      String[] yTicks = new String[4]; // 0, 1x, 2x, 3x the cap budget; the unit on the top one
      for (int i = 0; i < 4; i++) {
         yTicks[i] = i == 0 ? "0" : String.format(java.util.Locale.ROOT, i == 3 ? "%.1f ms" : "%.1f", budgetMs * i);
      }
      int axisW = tm.MeasureStringX(font, yTicks[3]) + pad;
      int hd = head;
      int bars = Math.min(GRAPH_BARS, Math.min(hd, RING - 64));
      float spanMs = 0f;
      for (int i = 0; i < bars; i++) {
         spanMs += frameMs[(hd - bars + i) & (RING - 1)];
      }
      // the x-axis label under the graph and the legend on a line of its own (it is long; it wraps to two lines when
      // the graph is narrow), both measured against fixed templates so the panel does not breathe with the numbers
      String xLabel = String.format(java.util.Locale.ROOT, "last %d frames (%.2f s), oldest to newest", bars, spanMs / 1000f);
      String legend1 = String.format(java.util.Locale.ROOT, "bars: frame ms, green under 1.1x the %.2f ms budget, amber under 2x, red above; blue: GPU ms; line: the budget", budgetMs);
      String legend2 = "";
      boolean graphOn = GRAPH_BARS > 0;
      int legendW = graphOn ? tm.MeasureStringX(font, legend1) : 0;
      int graphBlockW = axisW + graphW;
      if (graphOn && legendW > Math.max(graphBlockW, textW)) {
         int cut = legend1.indexOf("; blue");
         legend2 = legend1.substring(cut + 2);
         legend1 = legend1.substring(0, cut);
         legendW = Math.max(tm.MeasureStringX(font, legend1), tm.MeasureStringX(font, legend2));
      }
      if (graphOn) {
         textW = Math.max(textW, Math.max(graphBlockW, Math.max(axisW + tm.MeasureStringX(font, "last 9999 frames (99.99 s), oldest to newest"), legendW)));
      }
      java.util.List<FlameBox> flame = flameBoxes;
      String fTitle = flameTitle;
      int flameRows = flame.isEmpty() ? 0 : flameDepth;
      int flameRowH = lineH;
      // the flame graph sits in a column to the right of everything (overlayFlame=right / right-wide, 900 / 1400 px)
      // or under the frame graph across the panel (below)
      String flamePos = Config.OVERLAY_FLAME.trim().toLowerCase(java.util.Locale.ROOT);
      boolean flameRight = flameRows > 0 && !flamePos.equals("below");
      int flameH = flameRows > 0 ? lineH + flameRows * flameRowH : 0; // title + rows
      int flameColW = 0;
      if (flameRight) {
         flameColW = Math.max(flamePos.equals("right-wide") ? 1400 : 900, tm.MeasureStringX(font, fTitle));
      } else if (flameRows > 0) {
         textW = Math.max(textW, tm.MeasureStringX(font, fTitle));
      }
      // the stats lines vary by a digit or two between refreshes: measure them against widest-digit templates,
      // and keep the widest left column seen while the overlay is visible (reset when it is toggled) so
      // nothing shifts frame to frame
      textW = Math.max(textW, statsTemplateWidth(tm, font, fpsW));
      int leftW = Math.max(textW, graphOn ? graphW : 0) + pad * 2;
      if (leftW < steadyLeftW) {
         leftW = steadyLeftW;
      } else {
         steadyLeftW = leftW;
      }
      // sections separated by dividers: frame stats | game-thread tree | verdict | frame graph | flame graph (below)
      int div = pad * 2 + 1; // a divider: a gap, the 1 px line, a gap
      int treeRowsReserved = header.isEmpty() ? 0 : 1 + treeRowsReserved(); // fixed once a save is loaded
      int leftH = pad + lines.length * lineH
            + (header.isEmpty() ? 0 : div + treeRowsReserved * lineH)
            + (verdict.isEmpty() ? 0 : div + lineH)
            + (graphOn ? div + lineH / 2 + graphH + 2 + lineH * (legend2.isEmpty() ? 2 : 3) : 0)
            + (flameRows > 0 && !flameRight ? div + flameH : 0)
            + pad;
      if (leftH <= pad * 2 && flameRows == 0) {
         return; // every element off: nothing to draw
      }
      int w = leftW + (flameRight ? div + flameColW + pad : 0);
      int h = flameRight ? Math.max(leftH, pad + flameH + pad) : leftH;
      int screenW = Core.getInstance().getScreenWidth();
      int screenH = Core.getInstance().getScreenHeight();
      String corner = Config.OVERLAY_CORNER;
      int x = corner.endsWith("r") ? screenW - w - 10 : 10;
      int y = corner.startsWith("b") ? screenH - h - 10 : 10;
      SpriteRenderer sr = SpriteRenderer.instance;
      sr.renderi(null, x, y, w, h, 0f, 0f, 0f, 0.65f, null);
      int ty = y + pad;
      for (int i = 0; i < lines.length; i++) {
         int tx = x + pad;
         if (i == 0 && fpsW > 0) {
            tm.DrawString(font, tx, ty, fpsText, fpsColor[0], fpsColor[1], fpsColor[2], 1.0);
            tx += fpsW;
         }
         tm.DrawString(font, tx, ty, lines[i], 1.0, 1.0, 1.0, 1.0);
         ty += lineH;
      }
      if (!header.isEmpty()) {
         ty = divider(sr, x, ty, leftW, pad);
         tm.DrawString(font, x + pad, ty, header, 1.0, 1.0, 1.0, 1.0);
         ty += lineH;
      }
      for (int i = 0; i < tree.size(); i++) {
         GameThreadProfile.Row r = tree.get(i);
         float[] c = r.color;
         int bx = x + pad + indent * (r.depth + 1);
         // the bar: the row's share of the window on a 100 % = barW scale, its wait share in red at the left end
         int bw = Math.max(1, Math.round(barW * r.pct / 100f));
         sr.renderi(null, bx, ty + 2, bw, lineH - 4, c[0], c[1], c[2], r.depth == 0 ? 0.55f : 0.4f, null);
         if (r.waitPct >= 0.5f) {
            int ww = Math.max(1, Math.round(barW * r.waitPct / 100f));
            sr.renderi(null, bx, ty + 2, ww, lineH - 4, GameThreadProfile.C_WAIT[0], GameThreadProfile.C_WAIT[1], GameThreadProfile.C_WAIT[2], 0.6f, null);
         }
         int tx = x + pad + treeX[i];
         tm.DrawString(font, tx, ty, treeName[i], c[0], c[1], c[2], 1.0);
         tx += tm.MeasureStringX(font, treeName[i]) + indent;
         tm.DrawString(font, tx, ty, treePct[i], 1.0, 1.0, 1.0, 1.0);
         tx += pctW;
         if (!treeWait[i].isEmpty()) {
            tm.DrawString(font, tx, ty, treeWait[i], GameThreadProfile.C_WAIT[0], GameThreadProfile.C_WAIT[1], GameThreadProfile.C_WAIT[2], 1.0);
            tx += tm.MeasureStringX(font, treeWait[i]) + indent;
         }
         if (!treeHint[i].isEmpty()) {
            tm.DrawString(font, tx, ty, treeHint[i], 0.7, 0.7, 0.7, 1.0);
         }
         ty += lineH;
      }
      if (!verdict.isEmpty()) {
         ty = divider(sr, x, ty, leftW, pad);
         tm.DrawString(font, x + pad, ty, verdict, verdictColor[0], verdictColor[1], verdictColor[2], 1.0);
         ty += lineH;
      }
      // frame-time bars: newest on the right, budget line at one third, 3x budget at the top;
      // y axis = ms (ticks at 0, 1x, 2x, 3x the cap budget), x axis = the last frames in order
      if (graphOn) {
      ty = divider(sr, x, ty, leftW, pad);
      int gx = x + pad + axisW;
      int gy = ty + lineH / 2; // room for the top tick label, which sits half a line above the graph
      float scale = graphH / (3f * budgetMs);
      for (int i = 0; i < 4; i++) {
         int tickY = gy + graphH - (int)(budgetMs * i * scale);
         sr.renderi(null, gx - 4, tickY, 4, 1, 1f, 1f, 1f, 0.7f, null);
         if (i > 0) {
            sr.renderi(null, gx, tickY, graphW, 1, 1f, 1f, 1f, i == 1 ? 0.7f : 0.2f, null);
         }
         int labelY = Math.max(gy - lineH / 2, Math.min(gy + graphH - lineH / 2, tickY - lineH / 2));
         tm.DrawString(font, gx - 6 - tm.MeasureStringX(font, yTicks[i]), labelY, yTicks[i], 0.8, 0.8, 0.8, 1.0);
      }
      for (int i = 0; i < bars; i++) {
         int slot = (hd - bars + i) & (RING - 1);
         float ms = frameMs[slot];
         int bh = Math.max(1, Math.min(graphH, (int)(ms * scale)));
         float over = ms / budgetMs;
         float r = over > 2f ? 1f : over > 1.1f ? 1f : 0.4f;
         float g = over > 2f ? 0.3f : over > 1.1f ? 0.8f : 1f;
         sr.renderi(null, gx + i * 2, gy + graphH - bh, 2, bh, r, g, 0.4f, 0.9f, null);
         float gms = gpuMs[slot];
         if (gms > 0f) {
            int gh = Math.max(1, Math.min(graphH, (int)(gms * scale)));
            sr.renderi(null, gx + i * 2, gy + graphH - gh, 1, gh, 0.4f, 0.6f, 1f, 0.9f, null);
         }
      }
      sr.renderi(null, gx, gy + graphH, graphW, 1, 1f, 1f, 1f, 0.5f, null); // x axis
      tm.DrawString(font, gx, gy + graphH + 2, xLabel, 0.8, 0.8, 0.8, 1.0);
      ty = gy + graphH + 2 + lineH;
      tm.DrawString(font, x + pad, ty, legend1, 0.7, 0.7, 0.7, 1.0);
      ty += lineH;
      if (!legend2.isEmpty()) {
         tm.DrawString(font, x + pad, ty, legend2, 0.7, 0.7, 0.7, 1.0);
         ty += lineH;
      }
      }
      // flame graph: rows of boxes, root at the bottom, each box the share of its stack frame;
      // in its own column on the right (a vertical divider between), or under the frame graph
      if (flameRows > 0) {
         int fx, fw;
         if (flameRight) {
            int vx = x + leftW + pad; // the vertical divider
            sr.renderi(null, vx, y + pad, 1, h - pad * 2, 1f, 1f, 1f, 0.3f, null);
            fx = vx + 1 + pad;
            fw = flameColW;
            ty = y + pad;
         } else {
            ty = divider(sr, x, ty, leftW, pad);
            fx = x + pad;
            fw = leftW - pad * 2;
         }
         tm.DrawString(font, fx, ty, fTitle, 1.0, 1.0, 1.0, 1.0);
         ty += lineH;
         int bottom = ty + flameRows * flameRowH;
         sr.renderi(null, fx, ty, fw, flameRows * flameRowH, 0f, 0f, 0f, 0.6f, null); // darker backing: the boxes read against the world
         for (FlameBox b : flame) {
            int bx = fx + Math.round(b.x0 * fw);
            int bw = Math.round(b.x1 * fw) - Math.round(b.x0 * fw);
            if (bw < 3) {
               continue; // one quad per box every frame: the overlay's own cost shows up as "overlay" in the tree
            }
            int by = bottom - (b.depth + 1) * flameRowH;
            float[] c = b.color;
            sr.renderi(null, bx, by + 1, bw - 1, flameRowH - 2, c[0] * b.shade, c[1] * b.shade, c[2] * b.shade, 0.9f, null);
            if (bw > 12 && bw >= labelWidth(tm, font, b.name) + 6) {
               tm.DrawString(font, bx + 3, by, b.name, 0.05, 0.05, 0.05, 1.0);
            }
         }
      }
   }
}
