package pzopt;

import java.util.Stack;

/** pzopt.PlayerLos: the identity set mirrors the lastSpotted stack through adds, clears and outside changes. */
public class PlayerLosTest {
   public static void main(String[] args) {
      Object a = new Object();
      Object b = new Object();
      Object c = new Object();
      Stack<Object> stack = new Stack<>();
      PlayerLos<Object> los = new PlayerLos<>();

      los.sync(stack);
      Check.check(!los.contains(a), "empty: nothing remembered");
      Check.check(los.add(stack, a), "first add reports added");
      Check.check(!los.add(stack, a), "repeat add reports not added");
      Check.check(stack.size() == 1 && stack.get(0) == a, "stack holds a once");
      Check.check(los.contains(a) && !los.contains(b), "set mirrors the stack");

      // an outside add (a mod through getLastSpotted) is picked up by sync
      stack.add(b);
      Check.check(!los.contains(b), "outside add not seen before sync");
      los.sync(stack);
      Check.check(los.contains(b) && los.contains(a) && los.size() == 2, "sync rebuilt from the stack");

      // a replaced stack (setLastSpotted) is picked up even at the same size
      Stack<Object> other = new Stack<>();
      other.add(c);
      other.add(a);
      los.sync(other);
      Check.check(los.contains(c) && los.contains(a) && !los.contains(b), "sync follows a replaced stack");

      // clear empties both
      los.clear(other);
      Check.check(other.isEmpty() && los.size() == 0 && !los.contains(a), "clear empties both");
      los.sync(other);
      Check.check(los.size() == 0, "sync after clear stays empty");

      // an outside clear is picked up too
      los.add(other, a);
      other.clear();
      los.sync(other);
      Check.check(!los.contains(a), "outside clear picked up");

      // identity, not equals: two equal-but-distinct keys stay distinct
      Stack<Object> strings = new Stack<>();
      PlayerLos<Object> ident = new PlayerLos<>();
      ident.sync(strings);
      ident.add(strings, new String("x"));
      Check.check(!ident.contains(new String("x")), "membership is by identity");
      System.out.println("PlayerLosTest ok");
   }
}
