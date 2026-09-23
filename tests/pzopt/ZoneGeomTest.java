package pzopt;

import gnu.trove.list.array.TIntArrayList;
import java.util.Random;

/** ZoneGeom's pre-filtered edge loops give the stock loops' answers for chunk sides against map-like polygons. */
public class ZoneGeomTest {
   static boolean stock(TIntArrayList points, float sx, float sy, float dirX, float dirY, float length) {
      for (int j = 0; j < points.size(); j += 2) {
         float node1x = points.getQuick(j);
         float node1y = points.getQuick(j + 1);
         float node2x = points.getQuick((j + 2) % points.size());
         float node2y = points.getQuick((j + 3) % points.size());
         float doaX = sx - node1x;
         float doaY = sy - node1y;
         float dbaX = node2x - node1x;
         float dbaY = node2y - node1y;
         float invDbaDir = 1.0F / (dbaY * dirX - dbaX * dirY);
         float t = (dbaX * doaY - dbaY * doaX) * invDbaDir;
         if (t >= 0.0F && t <= length) {
            float t2 = (doaY * dirX - doaX * dirY) * invDbaDir;
            if (t2 >= 0.0F && t2 <= 1.0F) {
               return true;
            }
         }
      }
      return false;
   }

   public static void main(String[] args) {
      Random r = new Random(7);
      int checks = 0, hits = 0;
      for (int poly = 0; poly < 300; poly++) {
         int cx = 3000 + r.nextInt(12000), cy = 1000 + r.nextInt(14000);
         int extent = poly % 3 == 0 ? 2000 : 60 + r.nextInt(400);
         int n = 3 + r.nextInt(poly % 5 == 0 ? 200 : 20);
         TIntArrayList pts = new TIntArrayList();
         float[] fpts = new float[2 * n];
         for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            double rad = extent * (0.3 + 0.7 * r.nextDouble());
            int x = cx + (int)(rad * Math.cos(a)), y = cy + (int)(rad * Math.sin(a));
            if (r.nextInt(8) == 0 && i > 0) { // axis-aligned and repeated coordinates, like hand-drawn zones
               x = pts.getQuick(2 * i - 2);
            }
            pts.add(x);
            pts.add(y);
            fpts[2 * i] = x + (r.nextInt(4) == 0 ? 0.5F : 0.0F);
            fpts[2 * i + 1] = y;
         }
         for (int k = 0; k < 400; k++) {
            int x = (cx - extent - 16 + r.nextInt(2 * extent + 32)) & ~7, y = (cy - extent - 16 + r.nextInt(2 * extent + 32)) & ~7;
            float[][] sides = {{x, y, x + 8, y}, {x + 8, y, x + 8, y + 8}, {x + 8, y + 8, x, y + 8}, {x, y + 8, x, y}};
            for (float[] s : sides) {
               float dx = s[2] - s[0], dy = s[3] - s[1];
               float len = (float)Math.sqrt(dx * dx + dy * dy);
               float dirX = dx / len, dirY = dy / len;
               boolean want = stock(pts, s[0], s[1], dirX, dirY, len);
               boolean got = ZoneGeom.polygonEdgesHit(pts, s[0], s[1], s[2], s[3], dirX, dirY, len);
               Check.check(want == got, "polygon " + poly + " side " + java.util.Arrays.toString(s) + ": stock " + want + " fast " + got);
               TIntArrayList none = new TIntArrayList();
               boolean wantO = false;
               for (int j = 0; j < fpts.length; j += 2) {
                  if (ZoneGeom.hit(s[0], s[1], dirX, dirY, len, fpts[j], fpts[j + 1], fpts[(j + 2) % fpts.length], fpts[(j + 3) % fpts.length])) {
                     wantO = true;
                     break;
                  }
               }
               boolean gotO = ZoneGeom.outlineEdgesHit(fpts, s[0], s[1], s[2], s[3], dirX, dirY, len);
               Check.check(wantO == gotO, "outline " + poly + " side " + java.util.Arrays.toString(s));
               checks += 2;
               if (want) {
                  hits++;
               }
            }
         }
      }
      Check.check(hits > 1000, "the sides hit edges often enough to test: " + hits);
      System.out.println("ZoneGeomTest: " + checks + " side tests identical to the stock loops (" + hits + " polygon hits)");
   }
}
