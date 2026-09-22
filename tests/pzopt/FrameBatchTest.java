package pzopt;

import java.util.concurrent.atomic.AtomicIntegerArray;

/** pzopt.FrameBatch: every index runs exactly once, on more than one thread, the join is complete, an exception is reported. */
public class FrameBatchTest {
   public static void main(String[] args) throws Exception {
      int n = 20_000;
      AtomicIntegerArray hits = new AtomicIntegerArray(n);
      java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
      Throwable t = FrameBatch.run(n, i -> {
         hits.incrementAndGet(i);
         threads.add(Thread.currentThread().getName());
         if ((i & 1023) == 0) {
            Thread.sleep(1); // let the workers get a share
         }
      });
      Check.check(t == null, "no failure");
      for (int i = 0; i < n; i++) {
         Check.check(hits.get(i) == 1, "index " + i + " ran once, ran " + hits.get(i));
      }
      Check.check(threads.size() > 1, "more than one thread took part: " + threads);
      Check.check(FrameBatch.tasks >= n, "task counter");

      // a second batch after the first (the pool is reusable), and an exception surfaces without breaking the join
      Throwable failure = FrameBatch.run(100, i -> {
         if (i == 37) {
            throw new IllegalStateException("task 37");
         }
      });
      Check.check(failure instanceof IllegalStateException && "task 37".equals(failure.getMessage()), "the task's exception is returned: " + failure);
      Check.check(FrameBatch.run(0, i -> {
         throw new AssertionError("never");
      }) == null, "an empty batch does nothing");
      Check.check(FrameBatch.run(3, i -> {
      }) == null, "the pool works after a failed batch");
      System.out.println("FrameBatchTest ok");
   }
}
