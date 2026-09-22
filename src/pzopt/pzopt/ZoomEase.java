package pzopt;

/**
 * The camera zoom's motion between two levels as a cubic Bézier ease (Config.ZOOM_EASE_MS, ZOOM_EASE; 2026-09-22).
 *
 * Stock (MultiTextureFBO2.update) moves the zoom by a fixed amount per frame, 0.03 per frame for a wheel notch, and
 * snaps onto the target: one notch takes 8 frames whatever the frame rate (16 ms at 500 fps, 130 ms at 60), the
 * speed is constant and the stop is abrupt. Here the motion is a function of wall-clock time: a level change of any
 * size takes ZOOM_EASE_MS, its progress shaped by a CSS-style cubic Bézier whose end points are (0,0) and (1,1) and
 * whose control points come from ZOOM_EASE ("x1,y1,x2,y2"; the default 0.25,0.1,0.25,1.0 is CSS "ease": a quick start
 * that settles gently, so a second notch during the motion does not stall it). A new target while moving starts a new
 * curve from the current zoom, so the zoom never jumps.
 *
 * {@link #value(float)} solves x(t) = progress for t by Newton steps with a bisection fallback, then returns y(t);
 * {@code tests/pzopt/ZoomEaseTest}.
 */
public final class ZoomEase {
   private final float x1, y1, x2, y2;

   public ZoomEase(float x1, float y1, float x2, float y2) {
      this.x1 = clamp01(x1);
      this.y1 = y1;
      this.x2 = clamp01(x2);
      this.y2 = y2;
   }

   /** "x1,y1,x2,y2"; anything unreadable gives CSS "ease". */
   public static ZoomEase parse(String spec) {
      try {
         String[] p = spec.trim().split("[,; ]+");
         if (p.length == 4) {
            return new ZoomEase(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
         }
      } catch (RuntimeException ignored) {
      }
      return new ZoomEase(0.25F, 0.1F, 0.25F, 1.0F);
   }

   private static float clamp01(float v) {
      return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
   }

   private static float bezier(float t, float a, float b) {
      // B(t) with P0 = 0, P1 = a, P2 = b, P3 = 1
      float u = 1.0F - t;
      return 3.0F * u * u * t * a + 3.0F * u * t * t * b + t * t * t;
   }

   private static float bezierSlope(float t, float a, float b) {
      float u = 1.0F - t;
      return 3.0F * u * u * a + 6.0F * u * t * (b - a) + 3.0F * t * t * (1.0F - b);
   }

   /** Progress 0..1 of the motion for the elapsed share 0..1 of its time (0 at 0, 1 at 1, monotonic in between). */
   public float value(float x) {
      if (x <= 0.0F) {
         return 0.0F;
      }
      if (x >= 1.0F) {
         return 1.0F;
      }
      // solve bezier(t, x1, x2) == x
      float t = x;
      for (int i = 0; i < 8; i++) {
         float err = bezier(t, this.x1, this.x2) - x;
         if (Math.abs(err) < 1e-5F) {
            return bezier(t, this.y1, this.y2);
         }
         float slope = bezierSlope(t, this.x1, this.x2);
         if (slope < 1e-6F) {
            break;
         }
         t -= err / slope;
         if (t <= 0.0F || t >= 1.0F) {
            break;
         }
      }
      float lo = 0.0F, hi = 1.0F;
      t = x;
      for (int i = 0; i < 30 && hi - lo > 1e-6F; i++) {
         t = (lo + hi) * 0.5F;
         if (bezier(t, this.x1, this.x2) < x) {
            lo = t;
         } else {
            hi = t;
         }
      }
      return bezier(t, this.y1, this.y2);
   }

   /** The zoom at `elapsedMs` of a motion from `from` to `to` that takes `durationMs`. */
   public float zoomAt(float from, float to, float elapsedMs, float durationMs) {
      if (durationMs <= 0.0F || elapsedMs >= durationMs) {
         return to;
      }
      return from + (to - from) * this.value(elapsedMs / durationMs);
   }
}
