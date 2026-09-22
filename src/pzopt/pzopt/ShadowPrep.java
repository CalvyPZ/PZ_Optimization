package pzopt;

import org.joml.Vector3f;
import org.lwjgl.util.vector.Matrix4f;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.iso.Vector2;
import zombie.iso.Vector3;

/**
 * A zombie's shadow ellipse on the worker that updated its bones ({@code shadowPrep}, 2026-09-22).
 *
 * <p>{@code IsoGameCharacter.calculateShadowParams} projects the head and both feet of the model to the ground plane
 * (three bone transforms, two rotations each), finds the extents along the facing, and returns the forward / back
 * multipliers of the shadow blob; stock runs it on the game thread in {@code renderShadow} for every drawn character
 * every frame (2 % of it on the Louisville horde, with the bone-name lookups). It only reads the animation player's
 * model transforms and angle, which the bone update on the worker just wrote, so {@code AnimationPlayer.pzoptRunDeferred}
 * runs the same math here right after the bones (thread-local scratch instead of stock's static {@code L_renderShadow}
 * holder) and keeps the two numbers on the player; the {@code IsoZombie.calculateShadowParams} override serves them
 * until the player's next update. The arithmetic is stock's, step for step, in the same float / double precision.
 */
public final class ShadowPrep {
   private ShadowPrep() {
   }

   private static final class Scratch {
      final Vector3 vector3 = new Vector3();
      final Vector3f forward = new Vector3f();
      final Vector3f closest3 = new Vector3f();
      final Vector2 vector2a = new Vector2();
      final Vector2 vector2b = new Vector2();
   }

   private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

   /** Counters for the log line: pairs computed on the workers, served to renderShadow, and renderShadow calls that computed. */
   public static long computed, served, fallback;

   /** Stock's forward multiplier when the player is not ready. */
   public static final float DEFAULT_FM = 1.4F;
   /** Stock's back multiplier when the player is not ready. */
   public static final float DEFAULT_BM = 1.125F;

   /**
    * The (fm, bm) pair of {@code IsoGameCharacter.calculateShadowParams(player, 1, false, sp)} for a zombie (animal
    * size 1, no ragdoll), packed as fm in the high and bm in the low 32 bits of the result. Requires
    * {@code player.isReady()} (checked by the caller: it lazily initialises skinning data, a game-thread matter).
    */
   public static long compute(AnimationPlayer player, int headBone, int leftFootBone, int rightFootBone) {
      Scratch s = SCRATCH.get();
      Vector3 v = s.vector3;
      boneToWorld(player, headBone, v);
      float p1x = v.x;
      float p1y = v.y;
      boneToWorld(player, leftFootBone, v);
      float p2x = v.x;
      float p2y = v.y;
      boneToWorld(player, rightFootBone, v);
      float p3x = v.x;
      float p3y = v.y;
      Vector3f vClosest = s.closest3;
      float fLen = 0.0F;
      float bLen = 0.0F;
      Vector3f forward = s.forward;
      Vector2 forward2 = s.vector2a.setLengthAndDirection(player.getAngle(), 1.0F);
      forward.set(forward2.x, forward2.y, 0.0F);
      Vector2 closest = closestPointOnLine(0.0, 0.0, 0.0F + forward.x, 0.0F + forward.y, p1x, p1y, s.vector2b);
      float cx = closest.x;
      float cy = closest.y;
      float cLen = closest.set(cx - 0.0F, cy - 0.0F).getLength();
      if (cLen > 0.001F) {
         vClosest.set(cx - 0.0F, cy - 0.0F, 0.0F).normalize();
         if (forward.dot(vClosest) > 0.0F) {
            fLen = Math.max(fLen, cLen);
         } else {
            bLen = Math.max(bLen, cLen);
         }
      }
      closest = closestPointOnLine(0.0, 0.0, 0.0F + forward.x, 0.0F + forward.y, p2x, p2y, s.vector2b);
      cx = closest.x;
      cy = closest.y;
      cLen = closest.set(cx - 0.0F, cy - 0.0F).getLength();
      if (cLen > 0.001F) {
         vClosest.set(cx - 0.0F, cy - 0.0F, 0.0F).normalize();
         if (forward.dot(vClosest) > 0.0F) {
            fLen = Math.max(fLen, cLen);
         } else {
            bLen = Math.max(bLen, cLen);
         }
      }
      closest = closestPointOnLine(0.0, 0.0, 0.0F + forward.x, 0.0F + forward.y, p3x, p3y, s.vector2b);
      cx = closest.x;
      cy = closest.y;
      cLen = closest.set(cx - 0.0F, cy - 0.0F).getLength();
      if (cLen > 0.001F) {
         vClosest.set(cx - 0.0F, cy - 0.0F, 0.0F).normalize();
         if (forward.dot(vClosest) > 0.0F) {
            fLen = Math.max(fLen, cLen);
         } else {
            bLen = Math.max(bLen, cLen);
         }
      }
      float fm = (fLen + 0.35F) * 1.35F;
      float bm = (bLen + 0.35F) * 1.35F;
      computed++;
      return ((long)Float.floatToRawIntBits(fm) << 32) | (Float.floatToRawIntBits(bm) & 0xffffffffL);
   }

   public static float fm(long packed) {
      return Float.intBitsToFloat((int)(packed >>> 32));
   }

   public static float bm(long packed) {
      return Float.intBitsToFloat((int)packed);
   }

   /** {@code Model.boneToWorldCoords(player, 0, 0, 0, 1, bone, vec)}: the bone's translation rotated by the rendered angle. */
   private static void boneToWorld(AnimationPlayer player, int boneIndex, Vector3 vec) {
      Matrix4f m = player.getModelTransformAt(boneIndex);
      vec.x = m.m03;
      vec.y = m.m13;
      vec.z = m.m23;
      vec.x *= 1.0F;
      vec.y *= 1.0F;
      vec.z *= 1.0F;
      float angle = player.getRenderedAngle();
      vec.x = -vec.x;
      vec.rotatey(angle);
      float y1 = vec.y;
      vec.y = vec.z;
      vec.z = y1;
      vec.x *= 1.5F;
      vec.y *= 1.5F;
      vec.z *= 0.61237234F;
      vec.x += 0.0F;
      vec.y += 0.0F;
      vec.z += 0.0F;
   }

   /** {@code IsoGameCharacter.closestpointonline}, verbatim. */
   private static Vector2 closestPointOnLine(double lx1, double ly1, double lx2, double ly2, double x0, double y0, Vector2 out) {
      double a1 = ly2 - ly1;
      double b1 = lx1 - lx2;
      double c1 = (ly2 - ly1) * lx1 + (lx1 - lx2) * ly1;
      double c2 = -b1 * x0 + a1 * y0;
      double det = a1 * a1 - -b1 * b1;
      double cx;
      double cy;
      if (det != 0.0) {
         cx = (a1 * c1 - b1 * c2) / det;
         cy = (a1 * c2 - -b1 * c1) / det;
      } else {
         cx = x0;
         cy = y0;
      }
      return out.set((float)cx, (float)cy);
   }
}
