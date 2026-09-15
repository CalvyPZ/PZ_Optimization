package pzopt;

/** In a dev build, the game-thread-only gate must fail loudly from a recalc worker and stay silent elsewhere. */
public class GuardTest {
   public static void main(String[] args) throws Exception {
      Check.check(Guard.DEV, "run with -Dpzopt.dev=true");
      Guard.assertGameThread("from main"); // not a worker: silent
      Throwable[] caught = new Throwable[1];
      Thread w = new Thread(() -> {
         try { Guard.assertGameThread("pathfinding registration"); } catch (Throwable t) { caught[0] = t; }
      }, Guard.WORKER_PREFIX + "7");
      w.start(); w.join();
      Check.check(caught[0] instanceof IllegalStateException && caught[0].getMessage().contains("pzopt-recalc-7"), "worker call throws: " + caught[0]);
      Throwable[] quiet = new Throwable[1];
      Thread o = new Thread(() -> {
         try { Guard.assertGameThread("x"); } catch (Throwable t) { quiet[0] = t; }
      }, "World Streamer");
      o.start(); o.join();
      Check.check(quiet[0] == null, "non-worker thread is silent");
      System.out.println("GuardTest: ok");
   }
}
