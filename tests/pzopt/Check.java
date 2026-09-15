package pzopt;

final class Check {
   static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError("FAILED: " + what);
      }
   }
}
