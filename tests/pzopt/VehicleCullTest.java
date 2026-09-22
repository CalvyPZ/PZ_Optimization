package pzopt;

/** pzopt.VehicleCull.distanceSquaredToSegment: the nearest-point arithmetic behind the vehicle prefilter. */
public class VehicleCullTest {
   public static void main(String[] args) {
      check("on the segment", 0.0F, VehicleCull.distanceSquaredToSegment(5, 5, 0, 0, 10, 10));
      check("beside the middle", 2.0F, VehicleCull.distanceSquaredToSegment(6, 4, 0, 0, 10, 10));
      check("past the end clamps to the end point", 8.0F, VehicleCull.distanceSquaredToSegment(12, 12, 0, 0, 10, 10));
      check("before the start clamps to the start point", 25.0F, VehicleCull.distanceSquaredToSegment(-3, -4, 0, 0, 10, 10));
      check("zero-length segment is a point distance", 25.0F, VehicleCull.distanceSquaredToSegment(3, 4, 0, 0, 0, 0));
      System.out.println("VehicleCullTest ok");
   }

   private static void check(String what, float expected, float got) {
      if (Math.abs(expected - got) > 1.0E-4F) {
         throw new AssertionError(what + ": expected " + expected + ", got " + got);
      }
   }
}
