package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import zombie.characters.IsoZombie;
import zombie.characters.action.ActionContext;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;

/**
 * The determinism rig of the zombie passes ({@code devSimChecksum=true}, 2026-09-22, docs/plan-zombie-multithread.md §4.2).
 *
 * <p>Once per frame, after {@code MovingObjectUpdateScheduler.postupdate()} (every object updated, every deferred batch
 * joined), every zombie of the cell contributes its id, position, target, action-context state name, animator state
 * name and alpha to one 64-bit hash, and the frame's line goes to {@code Zomboid/pzopt-sim.out}: the frame number, the
 * zombie count and the hash, plus the hash of the positions alone and of the state names alone so a divergence says
 * which part moved. {@code harness/simdiff.py a b} compares two runs' files line by line and prints the first frame that
 * differs; a change that must not alter the simulation (a parallel evaluation, a typed compare) is verified by an
 * identical file over the same route with the key off and on.
 *
 * <p>The walk is game-thread only and costs a few hundred microseconds per frame with 2,000 zombies; it is a dev key,
 * never on in a normal run.
 */
public final class SimChecksum {
   private SimChecksum() {
   }

   public static final boolean ENABLED = Config.DEV_SIM_CHECKSUM;
   private static final String OUT_FILE = "pzopt-sim.out";
   private static BufferedWriter log;
   private static boolean failed;
   private static long frame;

   /** Game thread, right after the postupdate loop. */
   public static void frameDone() {
      if (!ENABLED || failed) {
         return;
      }
      IsoWorld world = IsoWorld.instance;
      if (world == null || world.getCell() == null) {
         return;
      }
      frame++;
      ArrayList<IsoZombie> zombies = world.getCell().getZombieList();
      long all = 0xcbf29ce484222325L;
      long positions = all;
      long states = all;
      int n = zombies.size();
      for (int i = 0; i < n; i++) {
         IsoZombie z = zombies.get(i);
         if (z == null) {
            continue;
         }
         long p = mix(z.getID());
         p = mix(p ^ Float.floatToRawIntBits(z.getX()));
         p = mix(p ^ Float.floatToRawIntBits(z.getY()));
         p = mix(p ^ Float.floatToRawIntBits(z.getZ()));
         positions = mix(positions ^ p);
         IsoMovingObject target = z.getTarget();
         long s = mix(target == null ? -1L : target.getID());
         ActionContext actionContext = z.getActionContext();
         String actionState = actionContext == null ? null : actionContext.getCurrentStateName();
         s = mix(s ^ (actionState == null ? 0 : actionState.hashCode()));
         AdvancedAnimator animator = z.getAdvancedAnimator();
         String animState = animator == null ? null : animator.getCurrentStateName();
         s = mix(s ^ (animState == null ? 0 : animState.hashCode()));
         s = mix(s ^ Float.floatToRawIntBits(z.getAlpha(0)));
         states = mix(states ^ s);
         all = mix(all ^ p ^ s);
      }
      write(frame, n, all, positions, states);
   }

   private static long mix(long h) {
      h ^= h >>> 33;
      h *= 0xff51afd7ed558ccdL;
      h ^= h >>> 33;
      h *= 0xc4ceb9fe1a85ec53L;
      h ^= h >>> 33;
      return h;
   }

   private static void write(long frame, int zombies, long all, long positions, long states) {
      try {
         if (log == null) {
            String dir = zombie.ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new BufferedWriter(new FileWriter(new File(dir, OUT_FILE), false), 1 << 16);
            log.write("# pzopt simulation checksum per frame after postupdate: frame, zombies, hash(all), hash(positions), hash(states)\n");
            Runtime.getRuntime().addShutdownHook(new Thread(SimChecksum::close, "pzopt-sim-close"));
         }
         log.write(frame + "\t" + zombies + "\t" + Long.toHexString(all) + "\t" + Long.toHexString(positions) + "\t" + Long.toHexString(states) + "\n");
         if ((frame & 63) == 0) {
            log.flush();
         }
      } catch (java.io.IOException e) {
         failed = true;
         Log.warn("devSimChecksum: cannot write " + OUT_FILE + ": " + e);
      }
   }

   private static synchronized void close() {
      try {
         if (log != null) {
            log.flush();
            log.close();
         }
      } catch (java.io.IOException ignored) {
      }
   }
}
