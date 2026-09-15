package pzopt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/** Out-of-order completion must still publish in submission order; failures block until resolved. */
public class OrderedPublisherTest {
   public static void main(String[] args) throws Exception {
      // 1. shuffled completion, single thread
      List<Integer> published = new ArrayList<>();
      OrderedPublisher<Integer> p = new OrderedPublisher<>(e -> published.add(e.item), e -> { throw new AssertionError("no failures expected"); });
      List<OrderedPublisher.Entry<Integer>> entries = new ArrayList<>();
      for (int i = 0; i < 200; i++) entries.add(p.submit(i));
      List<OrderedPublisher.Entry<Integer>> order = new ArrayList<>(entries);
      Collections.shuffle(order, new Random(42));
      for (OrderedPublisher.Entry<Integer> e : order) {
         p.complete(e, null);
         // nothing may be published ahead of an unfinished earlier entry
         for (int k = 0; k < published.size(); k++) Check.check(published.get(k) == k, "published[" + k + "]=" + published.get(k));
      }
      Check.check(published.size() == 200 && p.inFlight() == 0, "all published in order");

      // 2. concurrent workers completing in arbitrary order
      List<Integer> out = Collections.synchronizedList(new ArrayList<>());
      OrderedPublisher<Integer> q = new OrderedPublisher<>(e -> out.add(e.item), e -> { throw new AssertionError(); });
      int n = 5000;
      List<OrderedPublisher.Entry<Integer>> es = new ArrayList<>();
      for (int i = 0; i < n; i++) es.add(q.submit(i));
      CountDownLatch done = new CountDownLatch(8);
      for (int t = 0; t < 8; t++) {
         final int tt = t;
         new Thread(() -> {
            Random r = new Random(tt);
            for (int i = tt; i < n; i += 8) {
               try { Thread.sleep(0, r.nextInt(1000)); } catch (InterruptedException ex) { }
               q.complete(es.get(i), null);
            }
            done.countDown();
         }).start();
      }
      done.await();
      Check.check(out.size() == n, "concurrent: all published (" + out.size() + ")");
      for (int i = 0; i < n; i++) Check.check(out.get(i) == i, "concurrent: order at " + i);

      // 3. a failed entry blocks later ones until resolved, then order resumes
      List<Integer> pub = new ArrayList<>();
      List<Integer> failed = new ArrayList<>();
      OrderedPublisher<Integer> f = new OrderedPublisher<>(e -> pub.add(e.item), e -> failed.add(e.item));
      OrderedPublisher.Entry<Integer> a = f.submit(0), b = f.submit(1), c = f.submit(2);
      f.complete(c, null);
      f.complete(b, new RuntimeException("boom"));
      f.complete(a, null);
      Check.check(pub.equals(List.of(0)) && failed.equals(List.of(1)), "failed entry reported once, later entry held back: " + pub + " " + failed);
      Check.check(f.inFlight() == 2, "failed + held entries still in flight");
      f.resolve(b);
      Check.check(pub.equals(List.of(0, 2)) && f.inFlight() == 0, "after resolve the held entry publishes: " + pub);
      System.out.println("OrderedPublisherTest: ok");
   }
}
