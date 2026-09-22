package pzopt;

/** The cubic Bézier zoom ease: end points, monotonic progress, the CSS "ease" shape, and the zoom interpolation. */
public class ZoomEaseTest {
   public static void main(String[] args) {
      ZoomEase e = ZoomEase.parse("0.25,0.1,0.25,1.0");
      Check.check(e.value(0.0F) == 0.0F && e.value(1.0F) == 1.0F, "end points");
      Check.check(e.value(-1.0F) == 0.0F && e.value(2.0F) == 1.0F, "clamped outside 0..1");
      float prev = 0.0F;
      for (int i = 1; i <= 100; i++) {
         float v = e.value(i / 100.0F);
         Check.check(v >= prev - 1e-6F, "monotonic at " + i + ": " + v + " < " + prev);
         prev = v;
      }
      // CSS ease reference points (browsers): x=0.25 -> ~0.41, x=0.5 -> ~0.80, x=0.75 -> ~0.96
      Check.check(Math.abs(e.value(0.25F) - 0.408F) < 0.02F, "ease(0.25) = " + e.value(0.25F));
      Check.check(Math.abs(e.value(0.5F) - 0.802F) < 0.02F, "ease(0.5) = " + e.value(0.5F));
      Check.check(Math.abs(e.value(0.75F) - 0.960F) < 0.02F, "ease(0.75) = " + e.value(0.75F));
      ZoomEase lin = ZoomEase.parse("0.333,0.333,0.667,0.667");
      Check.check(Math.abs(lin.value(0.3F) - 0.3F) < 0.01F, "a straight Bézier is linear: " + lin.value(0.3F));
      ZoomEase io = ZoomEase.parse("0.42,0,0.58,1");
      Check.check(io.value(0.1F) < 0.05F && io.value(0.9F) > 0.95F, "ease-in-out starts and ends slowly");
      Check.check(Math.abs(io.value(0.5F) - 0.5F) < 0.01F, "ease-in-out is symmetric");
      Check.check(ZoomEase.parse("garbage").value(0.5F) == e.value(0.5F), "unreadable spec = CSS ease");
      Check.check(e.zoomAt(2.5F, 0.25F, 0.0F, 300.0F) == 2.5F, "start of the motion");
      Check.check(e.zoomAt(2.5F, 0.25F, 300.0F, 300.0F) == 0.25F, "end of the motion");
      Check.check(e.zoomAt(2.5F, 0.25F, 400.0F, 300.0F) == 0.25F, "past the end");
      float mid = e.zoomAt(2.5F, 0.25F, 150.0F, 300.0F);
      Check.check(mid < 1.0F && mid > 0.25F, "half way, mostly there: " + mid);
      Check.check(e.zoomAt(1.0F, 2.0F, 10.0F, 0.0F) == 2.0F, "zero duration = at once");
      System.out.println("ZoomEaseTest ok");
   }
}
