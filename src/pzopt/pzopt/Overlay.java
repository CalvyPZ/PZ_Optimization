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
 * Config: {@code overlay=true} shows it from boot; the key bound to "Toggle performance overlay"
 * (Options > Key Bindings, default F9; {@code overlayKey=<lwjgl code>} is the fallback when the
 * binding is missing) toggles it any time. {@code overlayLog=true}, or any harness run, writes
 * {@code Zomboid/pzopt-overlay.out}: one CSV row per presented frame in MangoHud's column names
 * (fps, frametime in ms, cpu_load, gpu_load, plus game_load, render_load, gpu_ms, elapsed in ns,
 * epoch_ms), which harness/analyze.py reads like a MangoHud log. {@code overlayFont} picks the
 * UIFont (CodeMedium by default); {@code overlayCorner} one of tl, tr, bl, br.
 *
 * Cost: one nanoTime and a ring write per frame on the render thread, two GL query calls per
 * frame, a stats pass every {@link #REFRESH_NS} on the game thread (sorting at most a few
 * thousand floats), and about 300 sprite quads per frame while visible.
 */
public final class Overlay {
   private static final boolean ACTIVE = Overrides.enabled();
   private static final boolean LOG = ACTIVE && (Config.OVERLAY_LOG || Harness.REQUESTED);
   private static final long WINDOW_NS = 5_000_000_000L;
   private static final long REFRESH_NS = 250_000_000L;
   private static final long UTIL_NS = 500_000_000L;
   private static final int RING = 8192;
   private static final int GRAPH_BARS = 240;
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

   // --- utilization, sampled on the game thread every UTIL_NS ---
   private static volatile float gameLoad, renderLoad, processLoad, systemLoad;
   private static volatile float gpuLoad; // last-second GPU busy share, 0..100
   private static long utilSampledNs, gameCpuNs, renderCpuNs;
   private static final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
   private static final com.sun.management.OperatingSystemMXBean os = osBean();

   // --- display ---
   private static final float[] WHITE = {1f, 1f, 1f};
   private static final float[] GREEN = {0.45f, 1f, 0.45f};
   private static final float[] AMBER = {1f, 0.8f, 0.3f};
   private static final float[] RED = {1f, 0.45f, 0.45f};
   private static final float[] BLUE = {0.45f, 0.7f, 1f};
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
      if (!ACTIVE || gpuState < 0) {
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
      if (!ACTIVE) {
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

   /** From {@code Display.imguiEndFrame()} every game-thread frame: toggle key, stats refresh, draw. */
   public static void draw() {
      if (!ACTIVE) {
         return;
      }
      if (toggled()) {
         visible = !visible;
         Log.info("overlay: " + (visible ? "shown" : "hidden"));
      }
      long now = System.nanoTime();
      if (now - utilSampledNs >= UTIL_NS) {
         sampleUtilization(now);
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

   private static void sampleUtilization(long now) {
      long dt = now - utilSampledNs;
      long g = 0L, r = 0L;
      try {
         if (!threads.isThreadCpuTimeEnabled()) {
            threads.setThreadCpuTimeEnabled(true);
         }
         g = threads.getThreadCpuTime(Thread.currentThread().threadId());
         long rid = renderThreadId;
         r = rid >= 0 ? threads.getThreadCpuTime(rid) : 0L;
      } catch (Throwable ignored) {
      }
      if (utilSampledNs != 0L && dt > 0) {
         if (g > 0 && gameCpuNs > 0) {
            gameLoad = Math.min(100f, 100f * (g - gameCpuNs) / dt);
         }
         if (r > 0 && renderCpuNs > 0) {
            renderLoad = Math.min(100f, 100f * (r - renderCpuNs) / dt);
         }
      }
      gameCpuNs = g;
      renderCpuNs = r;
      utilSampledNs = now;
      if (os != null) {
         double p = os.getProcessCpuLoad();
         double s = os.getCpuLoad();
         if (p >= 0) {
            processLoad = (float)(p * 100);
         }
         if (s >= 0) {
            systemLoad = (float)(s * 100);
         }
      }
      // GPU busy share over the last second of presented frames
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
      gpuLoad = wall > 0 ? Math.min(100f, 100f * gpuSum / wall) : 0f;
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
         lines = new String[] {"performance overlay: waiting for frames"};
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
      lines = new String[] {
            String.format(java.util.Locale.ROOT, "   %5.2f ms   cap %s", mean, cap > 0 ? cap + " fps" : "none"),
            String.format(java.util.Locale.ROOT, "p50 %.2f   p99 %.2f   p99.9 %.2f   max %.1f ms   (%d frames / %d s)", p50, p99, p999, max, count, (int)(WINDOW_NS / 1_000_000_000L)),
            String.format(java.util.Locale.ROOT, "1%%-low %.0f fps   jitter %.2f ms   spikes >2x median %d", p99 > 0 ? 1000f / p99 : 0f, count > 1 ? jitter / (count - 1) : 0f, spikes),
            String.format(java.util.Locale.ROOT, "GPU %s   game thread %.0f %%   render thread %.0f %%   process %.0f %% of %d cores   machine %.0f %%   heap %.1f/%.1f GB",
                  gpu, gameLoad, renderLoad, processLoad, cores, systemLoad, heapUsed, heapMax),
      };
      // verdict against the objective: at the cap, or what is saturated, or nothing is
      if (cap > 0 && fps >= cap * 0.98f) {
         verdict = "at the cap";
         verdictColor = GREEN;
      } else {
         float top = Math.max(gameLoad, Math.max(renderLoad, gpuState < 0 ? 0f : gpuLoad));
         if (top >= 90f) {
            String who = top == gameLoad ? "game thread" : top == renderLoad ? "render thread" : "GPU";
            verdict = (cap > 0 ? "below cap: " : "") + who + " bound";
            verdictColor = AMBER;
         } else {
            verdict = (cap > 0 ? "below cap, " : "") + "nothing saturated: waits or sync";
            verdictColor = RED;
         }
      }
   }

   /**
    * Colour of the fps number. Capped: blue at the cap (same 2 % tolerance as the verdict, the limiter
    * never lands exactly on it), green within 10 % of it, yellow within 50 %, red further below.
    * Uncapped: blue above 300 fps, green 150-300, yellow 100-150, red under 100.
    */
   static float[] fpsColor(float fps, int cap) {
      if (cap > 0) {
         return fps >= cap * 0.98f ? BLUE : fps >= cap * 0.9f ? GREEN : fps >= cap * 0.5f ? AMBER : RED;
      }
      return fps > 300f ? BLUE : fps >= 150f ? GREEN : fps >= 100f ? AMBER : RED;
   }

   private static float pct(float[] sorted, float p) {
      if (sorted.length == 0) {
         return 0f;
      }
      int i = (int)Math.ceil(p / 100f * sorted.length) - 1;
      return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
   }

   private static void render() {
      TextManager tm = TextManager.instance;
      if (font == null) {
         try {
            font = UIFont.valueOf(Config.OVERLAY_FONT);
         } catch (IllegalArgumentException e) {
            font = UIFont.CodeMedium;
         }
      }
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
      int w = Math.max(textW, graphW) + pad * 2;
      int rows = lines.length + (verdict.isEmpty() ? 0 : 1);
      int h = rows * lineH + graphH + pad * 3;
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
      if (!verdict.isEmpty()) {
         tm.DrawString(font, x + pad, ty, verdict, verdictColor[0], verdictColor[1], verdictColor[2], 1.0);
         ty += lineH;
      }
      // frame-time bars: newest on the right, budget line at one third, 3x budget at the top
      int gx = x + pad;
      int gy = ty + pad;
      float scale = graphH / (3f * budgetMs);
      int hd = head;
      int bars = Math.min(GRAPH_BARS, Math.min(hd, RING - 64));
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
      int budgetY = gy + graphH - (int)(budgetMs * scale);
      sr.renderi(null, gx, budgetY, graphW, 1, 1f, 1f, 1f, 0.7f, null);
   }
}
