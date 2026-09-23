package pzopt;

import gnu.trove.list.array.TIntArrayList;

/**
 * zoneEdgePrefilter (2026-09-23): the edge loops of zombie.iso.zones.Zone's polygon / polyline-outline rectangle tests.
 * Every Continue registers each map geometry zone into every chunk its bounds touch, and each chunk test ran all four
 * chunk sides against every polygon edge: 20 % of the map-zones step on the flip (JFR, flip-zonesjfr). An edge whose
 * bounding box stays more than MARGIN tiles from the side's box cannot hit it (the float error of the stock math at map
 * coordinates is far below a tile), so it is skipped before the stock arithmetic; every other edge runs that arithmetic
 * unchanged, so the answers are the stock ones (ZoneGeomTest).
 */
public final class ZoneGeom {
   static final float MARGIN = 1.0F;

   private ZoneGeom() {
   }

   /** Zone.lineSegmentIntersects' edge loop over a closed polygon of int points: true if the segment hits an edge. */
   public static boolean polygonEdgesHit(TIntArrayList points, float sx, float sy, float ex, float ey, float dirX, float dirY, float length) {
      float minX = Math.min(sx, ex) - MARGIN;
      float maxX = Math.max(sx, ex) + MARGIN;
      float minY = Math.min(sy, ey) - MARGIN;
      float maxY = Math.max(sy, ey) + MARGIN;
      int n = points.size();
      for (int j = 0; j < n; j += 2) {
         float node1x = points.getQuick(j);
         float node1y = points.getQuick(j + 1);
         float node2x = points.getQuick((j + 2) % n);
         float node2y = points.getQuick((j + 3) % n);
         if (Math.max(node1x, node2x) < minX || Math.min(node1x, node2x) > maxX
               || Math.max(node1y, node2y) < minY || Math.min(node1y, node2y) > maxY) {
            continue;
         }
         if (hit(sx, sy, dirX, dirY, length, node1x, node1y, node2x, node2y)) {
            return true;
         }
      }
      return false;
   }

   /** Zone.polylineOutlineSegmentIntersects' edge loop over the outline's float points. */
   public static boolean outlineEdgesHit(float[] points, float sx, float sy, float ex, float ey, float dirX, float dirY, float length) {
      float minX = Math.min(sx, ex) - MARGIN;
      float maxX = Math.max(sx, ex) + MARGIN;
      float minY = Math.min(sy, ey) - MARGIN;
      float maxY = Math.max(sy, ey) + MARGIN;
      int n = points.length;
      for (int j = 0; j < n; j += 2) {
         float node1x = points[j];
         float node1y = points[j + 1];
         float node2x = points[(j + 2) % n];
         float node2y = points[(j + 3) % n];
         if (Math.max(node1x, node2x) < minX || Math.min(node1x, node2x) > maxX
               || Math.max(node1y, node2y) < minY || Math.min(node1y, node2y) > maxY) {
            continue;
         }
         if (hit(sx, sy, dirX, dirY, length, node1x, node1y, node2x, node2y)) {
            return true;
         }
      }
      return false;
   }

   /** The stock per-edge test, operation for operation. */
   static boolean hit(float sx, float sy, float dirX, float dirY, float length, float node1x, float node1y, float node2x, float node2y) {
      float doaX = sx - node1x;
      float doaY = sy - node1y;
      float dbaX = node2x - node1x;
      float dbaY = node2y - node1y;
      float invDbaDir = 1.0F / (dbaY * dirX - dbaX * dirY);
      float t = (dbaX * doaY - dbaY * doaX) * invDbaDir;
      if (t >= 0.0F && t <= length) {
         float t2 = (doaY * dirX - doaX * dirY) * invDbaDir;
         return t2 >= 0.0F && t2 <= 1.0F;
      }
      return false;
   }
}
