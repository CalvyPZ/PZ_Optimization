package pzopt;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

/** The stack classifier buckets synthetic game-thread stacks as documented; one JMX stack sample of a busy thread is cheap. */
public class GameThreadProfileTest {
   private static StackTraceElement f(String cls, String method) {
      return new StackTraceElement(cls, method, null, -1);
   }

   /** Leaf first, like ThreadInfo.getStackTrace(). */
   private static StackTraceElement[] stack(String... clsMethod) {
      StackTraceElement[] st = new StackTraceElement[clsMethod.length];
      for (int i = 0; i < clsMethod.length; i++) {
         int dot = clsMethod[i].lastIndexOf('.');
         st[i] = f(clsMethod[i].substring(0, dot), clsMethod[i].substring(dot + 1));
      }
      return st;
   }

   private static int count(Map<String, int[]> m, String key) {
      int[] c = m.get(key);
      return c == null ? 0 : c[0];
   }

   public static void main(String[] args) throws Exception {
      Map<String, int[]> m = new HashMap<>();
      // a zombie update inside the update phase: label wins over the bucket container's child
      GameThreadProfile.classify(stack(
            "java.util.HashMap.get", "zombie.characters.IsoZombie.updateInternal", "zombie.characters.IsoZombie.update",
            "zombie.MovingObjectUpdateSchedulerUpdateBucket.update", "zombie.MovingObjectUpdateScheduler.update",
            "zombie.iso.IsoCell.ProcessObjects", "zombie.iso.IsoCell.updateInternal", "zombie.iso.IsoCell.update",
            "zombie.iso.IsoWorld.updateWorld", "zombie.iso.IsoWorld.updateInternal", "zombie.iso.IsoWorld.update",
            "zombie.gameStates.IngameState.updateInternal", "zombie.gameStates.IngameState.update",
            "zombie.gameStates.GameStateMachine.update", "zombie.GameWindow.logic", "zombie.GameWindow.frameStep",
            "zombie.GameWindow.mainThreadStep", "zombie.MainThread.run", "java.lang.Thread.run"), Thread.State.RUNNABLE, m);
      Check.check(count(m, "p:update") == 1, "phase update: " + m.keySet());
      Check.check(count(m, "s:update/zombies") == 1, "sub-phase zombies: " + m.keySet());
      Check.check(count(m, "l:update/zombies/IsoZombie.updateInternal") == 1, "hot method is the innermost game frame (JDK leaf folded): " + m.keySet());
      Check.check(m.keySet().stream().noneMatch(k -> k.startsWith("w:")), "runnable: no wait bucket");

      // a chunk bake with a tree pass inside: the innermost label wins
      m.clear();
      GameThreadProfile.classify(stack(
            "pzopt.TreeBake.rect", "zombie.iso.fboRenderChunk.FBORenderCell.pzoptBakeTrees",
            "zombie.iso.fboRenderChunk.FBORenderCell.renderOneLevel", "zombie.iso.fboRenderChunk.FBORenderCell.renderOneChunk",
            "zombie.iso.fboRenderChunk.FBORenderCell.performRenderTiles", "zombie.iso.fboRenderChunk.FBORenderCell.renderTilesInternal",
            "zombie.iso.fboRenderChunk.FBORenderCell.RenderTiles", "zombie.iso.fboRenderChunk.FBORenderCell.renderInternal",
            "zombie.iso.IsoCell.render", "zombie.iso.IsoWorld.renderInternal", "zombie.iso.IsoWorld.render",
            "zombie.gameStates.IngameState.renderFrameInternal", "zombie.gameStates.IngameState.renderframe",
            "zombie.gameStates.IngameState.renderInternal", "zombie.gameStates.IngameState.render",
            "zombie.gameStates.GameStateMachine.render", "zombie.GameWindow.renderInternal", "zombie.GameWindow.frameStep"), Thread.State.RUNNABLE, m);
      Check.check(count(m, "p:render") == 1, "phase render: " + m.keySet());
      Check.check(count(m, "s:render/tree bake") == 1, "innermost label (tree bake inside the bake): " + m.keySet());
      Check.check(count(m, "l:render/tree bake/pzopt.TreeBake.rect") == 1, "hot: " + m.keySet());

      // unknown code under a container names itself by the container's child
      m.clear();
      GameThreadProfile.classify(stack(
            "zombie.iso.IsoGridSquare.getLightInfo", "zombie.iso.fboRenderChunk.FBORenderCell.newThing",
            "zombie.iso.fboRenderChunk.FBORenderCell.performRenderTiles", "zombie.iso.fboRenderChunk.FBORenderCell.renderTilesInternal",
            "zombie.iso.IsoCell.render", "zombie.iso.IsoWorld.renderInternal",
            "zombie.gameStates.IngameState.renderInternal", "zombie.GameWindow.renderInternal", "zombie.GameWindow.frameStep"), Thread.State.RUNNABLE, m);
      Check.check(count(m, "s:render/FBORenderCell.newThing") == 1, "container child names an unlabelled sub-phase: " + m.keySet());

      // waiting for the render thread in the hand-off (under the UI draw): the hand-off label is deeper and wins; wait bucket with the innermost game frame
      m.clear();
      GameThreadProfile.classify(stack(
            "java.lang.Object.wait0", "java.lang.Object.wait", "zombie.core.SpriteRenderer.waitForReadySlotToOpen",
            "zombie.core.SpriteRenderer.pushFrameDown", "zombie.core.opengl.RenderThread.Ready", "zombie.core.Core.EndFrameUI",
            "zombie.gameStates.IngameState.renderFrameUI", "zombie.gameStates.IngameState.renderframeui", "zombie.gameStates.IngameState.renderInternal",
            "zombie.GameWindow.renderInternal", "zombie.GameWindow.frameStep"), Thread.State.WAITING, m);
      Check.check(count(m, "s:render/frame hand-off") == 1, "hand-off label: " + m.keySet());
      Check.check(count(m, "w:render/frame hand-off/SpriteRenderer.waitForReadySlotToOpen") == 1, "wait bucket: " + m.keySet());

      // the limiter / menus: outside frameStep
      m.clear();
      GameThreadProfile.classify(stack("java.lang.Thread.sleep", "zombie.GameWindow.mainThreadStep", "zombie.MainThread.run"), Thread.State.TIMED_WAITING, m);
      Check.check(count(m, "p:outside frame") == 1 && count(m, "s:outside frame/outside frame") == 1, "outside frame: " + m.keySet());

      // folded stacks: game frames only, root first, from frameStep up; pzopt frames keep their package
      String folded = GameThreadProfile.fold(stack(
            "java.util.HashMap.get", "pzopt.TreeBake.rect", "zombie.iso.fboRenderChunk.FBORenderCell.pzoptBakeTrees",
            "zombie.GameWindow.renderInternal", "zombie.GameWindow.frameStep", "zombie.GameWindow.mainThreadStep", "zombie.MainThread.run"));
      Check.check(folded.equals("GameWindow.frameStep;GameWindow.renderInternal;FBORenderCell.pzoptBakeTrees;pzopt.TreeBake.rect"), "fold: " + folded);
      Check.check(GameThreadProfile.fold(stack("java.lang.Thread.sleep", "zombie.MainThread.run")).equals("MainThread.run"), "fold outside the frame keeps the thread root");

      // colours: stable per name, phases fixed, palette entries readable (not too dark)
      Check.check(java.util.Arrays.equals(GameThreadProfile.nameColor("chunk bakes"), GameThreadProfile.nameColor("chunk bakes")), "stable colour");
      Check.check(GameThreadProfile.phaseColor("render") != GameThreadProfile.phaseColor("update"), "phase colours differ");
      float[] c = GameThreadProfile.nameColor("zombies");
      Check.check(c.length == 3 && (c[0] + c[1] + c[2]) > 1.2f, "readable colour: " + java.util.Arrays.toString(c));

      // one JMX stack sample of a busy thread: the sampler's per-sample cost stays far below a frame
      Thread busy = new Thread(() -> {
         long x = 0;
         while (!Thread.currentThread().isInterrupted()) {
            x += deep(40);
         }
         System.out.print(x == 42 ? "" : ""); // keep the loop alive
      }, "busy");
      busy.setDaemon(true);
      busy.start();
      Thread.sleep(200);
      ThreadMXBean mx = ManagementFactory.getThreadMXBean();
      ThreadInfo warm = mx.getThreadInfo(busy.threadId(), 128);
      // C2 inlines most of deep(): the sample only has to reach the loop, not every recursion level
      Check.check(warm != null && warm.getStackTrace().length >= 2, "sample of the busy thread has a stack: " + (warm == null ? null : warm.getStackTrace().length));
      int n = 500;
      long t0 = System.nanoTime();
      for (int i = 0; i < n; i++) {
         mx.getThreadInfo(busy.threadId(), 128);
      }
      long perSample = (System.nanoTime() - t0) / n;
      System.out.println("GameThreadProfileTest: " + perSample / 1000 + " us per stack sample of a " + warm.getStackTrace().length + "-frame busy thread");
      Check.check(perSample < 2_000_000L, "a stack sample costs under 2 ms: " + perSample + " ns");
      busy.interrupt();
      System.out.println("GameThreadProfileTest: ok");
   }

   private static long deep(int n) {
      return n == 0 ? System.nanoTime() & 7 : deep(n - 1) + 1;
   }
}
