package pzopt;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * The per-frame worker pool of the zombie passes (2026-09-22, docs/plan-zombie-multithread.md §4.1).
 *
 * <p>One batch at a time: the game thread hands over {@code count} indexed tasks and a {@link Runner}, the daemon
 * workers ({@code pzopt-frame-N}, {@code frameThreads} of them, clamped to cores - 1) and the game thread itself pull
 * indices from a shared cursor until none are left, and {@link #run} returns when every task has finished. The wake is
 * a generation counter under one monitor (~50 µs), the join spins briefly then parks in 20 µs slices; a batch of a
 * few milliseconds hides both. {@code AnimBatch} (the bone math) and {@code ActionEval} (the action-context transitions)
 * are the two users; the game thread never blocks inside a batch on anything but the join.
 *
 * <p>A task that throws marks the batch failed; the runner decides what that means (both users switch themselves off
 * for the rest of the session and take the stock path).
 */
public final class FrameBatch {
   private FrameBatch() {
   }

   /** One indexed task. */
   public interface Runner {
      void run(int index) throws Throwable;
   }

   public static final int THREADS = Math.max(1, Math.min(Config.FRAME_THREADS, Runtime.getRuntime().availableProcessors() - 1));

   /**
    * One batch: its runner, size and counters in one object, published to the workers through {@link #current}. A worker
    * that wakes late works on the batch object it read, whose cursor is exhausted, and never touches a newer batch's
    * counters with an older batch's runner (the first version kept the fields loose and a late worker once read a null
    * runner against the next batch's cursor: an NPE and a task counted as finished without running, 2026-09-22).
    */
   private static final class Batch {
      final Runner runner;
      final int count;
      final AtomicInteger cursor = new AtomicInteger();
      final AtomicInteger finished = new AtomicInteger();
      volatile Throwable failure;

      Batch(Runner runner, int count) {
         this.runner = runner;
         this.count = count;
      }

      void work() {
         int i;
         while ((i = this.cursor.getAndIncrement()) < this.count) {
            try {
               this.runner.run(i);
            } catch (Throwable t) {
               if (this.failure == null) {
                  this.failure = t;
               }
            } finally {
               this.finished.incrementAndGet();
            }
         }
      }
   }

   private static final Object gate = new Object();
   private static int generation; // guarded by gate
   private static volatile Batch current; // the batch the workers should join
   private static Thread[] workers;
   private static boolean running; // game thread only

   public static long batches, tasks, waitNanos, workNanos;

   /**
    * Game thread: run tasks 0..count-1 on the workers and this thread, wait for all of them, and return the first
    * exception a task threw (null when every task finished).
    */
   public static Throwable run(int n, Runner r) {
      if (n <= 0) {
         return null;
      }
      if (running) {
         throw new IllegalStateException("FrameBatch.run is not reentrant");
      }
      if (workers == null) {
         start();
      }
      running = true;
      long t0 = System.nanoTime();
      batches++;
      tasks += n;
      Batch batch = new Batch(r, n);
      current = batch;
      synchronized (gate) {
         generation++;
         gate.notifyAll();
      }
      batch.work();
      long t1 = System.nanoTime();
      int spins = 0;
      while (batch.finished.get() < n) {
         if (++spins < 200) {
            Thread.onSpinWait();
         } else {
            LockSupport.parkNanos(20_000L);
         }
      }
      long t2 = System.nanoTime();
      workNanos += t1 - t0;
      waitNanos += t2 - t1;
      running = false;
      return batch.failure;
   }

   private static void start() {
      workers = new Thread[THREADS];
      for (int k = 0; k < THREADS; k++) {
         Thread t = new Thread(FrameBatch::workerLoop, "pzopt-frame-" + k);
         t.setDaemon(true);
         t.setPriority(Thread.NORM_PRIORITY);
         workers[k] = t;
         t.start();
      }
      Log.info("frameThreads: " + THREADS + " worker threads for the per-frame zombie batches");
   }

   private static void workerLoop() {
      int seen = 0;
      while (true) {
         synchronized (gate) {
            while (generation == seen) {
               try {
                  gate.wait();
               } catch (InterruptedException e) {
                  return;
               }
            }
            seen = generation;
         }
         Batch batch = current;
         if (batch != null) {
            batch.work();
         }
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "frame batches=" + batches + " tasks=" + tasks + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L);
   }
}
