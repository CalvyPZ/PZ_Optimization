package pzopt;

import org.joml.Vector3f;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * Cheap rejection for {@code IsoZombie.isVehicleBetween} ({@code vehicleCull}, 2026-09-22).
 *
 * <p>Stock asks every loaded vehicle for the exact intersection of the zombie-to-player segment with its box: the segment
 * is transformed into the vehicle's local space (two matrix multiplies, three pooled vectors) per vehicle, per zombie
 * that could see the player, per frame. Downtown Louisville has hundreds of parked cars and hundreds of zombies with a
 * line of sight, and the walk was 6 % of the game thread. A vehicle whose bounding circle (half the horizontal diagonal
 * of its script extents plus the centre-of-mass offset, with a margin) does not reach the segment cannot intersect it,
 * and that is two subtractions and a few multiplies on the vehicle's world position.
 */
public final class VehicleCull {
   private VehicleCull() {
   }

   /**
    * Extra radius: the exact test uses the physics transform's origin while getX/getY follow it a tick behind (0.46
    * tiles a tick at 100 km/h), so the circle is generous enough that a moving car can never be rejected wrongly.
    */
   private static final float MARGIN = 1.0F;

   /** False when the vehicle's bounding circle misses the segment (x1,y1)-(x2,y2) in world tiles. */
   public static boolean mayIntersect(BaseVehicle vehicle, float x1, float y1, float x2, float y2) {
      VehicleScript script = vehicle.getScript();
      if (script == null) {
         return true;
      }
      Vector3f extents = script.getExtents();
      Vector3f com = script.getCenterOfMassOffset();
      float half = 0.5F * (float)Math.sqrt(extents.x * extents.x + extents.z * extents.z);
      float radius = half + (float)Math.sqrt(com.x * com.x + com.z * com.z) + MARGIN;
      return distanceSquaredToSegment(vehicle.getX(), vehicle.getY(), x1, y1, x2, y2) <= radius * radius;
   }

   /** Squared distance from (px,py) to the segment (x1,y1)-(x2,y2). */
   static float distanceSquaredToSegment(float px, float py, float x1, float y1, float x2, float y2) {
      float dx = x2 - x1;
      float dy = y2 - y1;
      float len2 = dx * dx + dy * dy;
      float t = 0.0F;
      if (len2 > 1.0E-6F) {
         t = ((px - x1) * dx + (py - y1) * dy) / len2;
         t = t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
      }
      float cx = x1 + t * dx - px;
      float cy = y1 + t * dy - py;
      return cx * cx + cy * cy;
   }
}
