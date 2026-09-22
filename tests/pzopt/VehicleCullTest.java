package pzopt;

/** pzopt.VehicleCull.distanceSquaredToSegment: the nearest-point arithmetic behind the vehicle prefilter. */
public class VehicleCullTest {
   public static void main(String[] args) {
      check("on the segment", 0.0F, VehicleCull.distanceSquaredToSegment(5, 5, 0, 0, 10, 10));
      check("beside the middle", 2.0F, VehicleCull.distanceSquaredToSegment(6, 4, 0, 0, 10, 10));
      check("past the end clamps to the end point", 8.0F, VehicleCull.distanceSquaredToSegment(12, 12, 0, 0, 10, 10));
      check("before the start clamps to the start point", 25.0F, VehicleCull.distanceSquaredToSegment(-3, -4, 0, 0, 10, 10));
      check("zero-length segment is a point distance", 25.0F, VehicleCull.distanceSquaredToSegment(3, 4, 0, 0, 0, 0));
      nearList();
      System.out.println("VehicleCullTest ok");
   }

   /** near(): the per-frame candidate list is rebuilt on a new frame, target or larger reach, and reused otherwise. */
   private static void nearList() {
      java.util.List<zombie.vehicles.BaseVehicle> none = java.util.Collections.emptyList();
      int builds = VehicleCull.nearBuilds();
      VehicleCull.near(none, 10, 10, 20, 1);
      VehicleCull.near(none, 10, 10, 20, 1);
      VehicleCull.near(none, 10, 10, 15, 1);
      Check.check(VehicleCull.nearBuilds() == builds + 1, "same frame, target and a reach that fits: one build");
      VehicleCull.near(none, 10, 10, 25, 1);
      Check.check(VehicleCull.nearBuilds() == builds + 2, "a larger reach rebuilds");
      VehicleCull.near(none, 11, 10, 25, 1);
      Check.check(VehicleCull.nearBuilds() == builds + 3, "a moved target rebuilds");
      VehicleCull.near(none, 11, 10, 25, 2);
      Check.check(VehicleCull.nearBuilds() == builds + 4, "a new frame rebuilds");
   }

   private static void check(String what, float expected, float got) {
      if (Math.abs(expected - got) > 1.0E-4F) {
         throw new AssertionError(what + ": expected " + expected + ", got " + got);
      }
   }
}
