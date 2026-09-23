package pzopt;

import org.json.JSONObject;

/**
 * pzopt.GcChoice's launcher edit: ZGC -> G1 with the marker (top level and per-platform sections), the optional pause
 * target, the undo back to the exact stock arguments, re-applying is a no-op, and a JSON without ZGC or our marker is
 * left alone.
 */
public class GcChoiceTest {
   private static final String STOCK = "{\"mainClass\":\"m\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\",\"-XX:-OmitStackTraceInFastThrow\"],"
         + "\"windows\":{\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\"]}}";

   public static void main(String[] args) {
      JSONObject stock = new JSONObject(STOCK);

      JSONObject j = new JSONObject(STOCK);
      check(GcChoice.toG1(j, 0), "switches");
      check(j.getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC") && !j.getJSONArray("vmArgs").toList().contains("-XX:+UseZGC"), "top level G1");
      check(j.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER), "marker");
      check(j.getJSONObject("windows").getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC"), "windows section G1");
      check(!j.toString().contains("MaxGCPauseMillis"), "no pause target at 0");
      check(!GcChoice.toG1(j, 0), "second switch is a no-op");
      check(GcChoice.toStock(j), "undo");
      check(j.similar(stock), "undo gives the stock JSON back: " + j);

      JSONObject p = new JSONObject(STOCK);
      GcChoice.toG1(p, 50);
      check(p.getJSONArray("vmArgs").toList().contains("-XX:MaxGCPauseMillis=50"), "pause target added");
      check(p.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER_PAUSE), "pause marker");
      GcChoice.toStock(p);
      check(p.similar(stock), "undo removes the pause target too: " + p);

      JSONObject g1 = new JSONObject(STOCK.replace("-XX:+UseZGC", "-XX:+UseG1GC"));
      String before = g1.toString();
      check(!GcChoice.toG1(g1, 0) && !GcChoice.toStock(g1) && g1.toString().equals(before), "a JSON already on G1 without our marker is left alone");
      System.out.println("GcChoiceTest ok");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError("GcChoiceTest: " + what);
      }
   }
}
