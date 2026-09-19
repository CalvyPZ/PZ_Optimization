package pzopt;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per task class: how many ran on the file pool and their summed run time (docs/plan-instant-load.md). */
public final class FileTaskStats {
   private static final ConcurrentHashMap<String, long[]> byClass = new ConcurrentHashMap<>();

   private FileTaskStats() {
   }

   public static void add(Class<?> task, long ns) {
      long[] v = byClass.computeIfAbsent(task.getSimpleName(), k -> new long[2]);
      synchronized (v) {
         v[0]++;
         v[1] += ns;
      }
   }

   public static String summary() {
      ArrayList<Map.Entry<String, long[]>> rows = new ArrayList<>(byClass.entrySet());
      rows.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
      StringBuilder sb = new StringBuilder("file tasks so far:");
      for (Map.Entry<String, long[]> e : rows) {
         synchronized (e.getValue()) {
            sb.append(String.format(" %s=%d/%.1fs", e.getKey(), e.getValue()[0], e.getValue()[1] / 1e9));
         }
      }
      return sb.toString();
   }
}
