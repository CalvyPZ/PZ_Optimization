package pzopt;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Publishes items in the order they were submitted, whatever order their
 * work completes in. A worker that completes an item calls {@link #complete};
 * the item and any completed items behind it in the queue are then published,
 * by that worker, in submission order. Failed items are handed to a separate
 * consumer instead of being published and block later items until
 * {@link #resolve} is called for them (the streamer thread retries them).
 *
 * Kept free of game classes so it can be unit-tested (tools/tests).
 */
public final class OrderedPublisher<T> {
   public static final class Entry<T> {
      public final T item;
      public final long seq;
      volatile boolean done;
      volatile Throwable failure;

      Entry(T item, long seq) {
         this.item = item;
         this.seq = seq;
      }
   }

   private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();
   private final Consumer<Entry<T>> publish;
   private final Consumer<Entry<T>> failed;
   private long nextSeq = 0;
   private Entry<T> blockedOn; // failed head awaiting resolve()

   public OrderedPublisher(Consumer<Entry<T>> publish, Consumer<Entry<T>> failed) {
      this.publish = publish;
      this.failed = failed;
   }

   public synchronized Entry<T> submit(T item) {
      Entry<T> e = new Entry<>(item, nextSeq++);
      queue.addLast(e);
      return e;
   }

   /** Number of submitted items not yet published. */
   public synchronized int inFlight() {
      return queue.size();
   }

   /** Called by the worker that finished (or failed) the entry's work. */
   public void complete(Entry<T> e, Throwable failure) {
      synchronized (this) {
         e.failure = failure;
         e.done = true;
      }
      drain();
   }

   /** Called by whoever handled a failed entry (retry or discard); unblocks the queue. */
   public void resolve(Entry<T> e) {
      synchronized (this) {
         if (blockedOn == e) {
            blockedOn = null;
            queue.pollFirst();
         }
      }
      drain();
   }

   private final Object drainLock = new Object();

   /**
    * Only one thread drains at a time and it publishes while holding drainLock,
    * otherwise two workers could each pop a head and publish them out of order.
    * The queue monitor is held only briefly so submit/complete never wait on a
    * publish.
    */
   private void drain() {
      synchronized (drainLock) {
         while (true) {
            Entry<T> head;
            boolean fail;
            synchronized (this) {
               if (blockedOn != null) {
                  return;
               }
               head = queue.peekFirst();
               if (head == null || !head.done) {
                  return;
               }
               fail = head.failure != null;
               if (fail) {
                  blockedOn = head; // stays at the head until resolve()
               } else {
                  queue.pollFirst();
               }
            }
            if (fail) {
               failed.accept(head);
               return;
            }
            publish.accept(head);
         }
      }
   }
}
