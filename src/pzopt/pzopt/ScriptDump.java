package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import zombie.ZomboidFileSystem;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;

/**
 * Writes every item script's fields (reflection over the class hierarchy,
 * sorted by full name) to Zomboid/pzopt-items.out, so a run with
 * itemParamSwitch=false and one with it on can be diffed byte for byte
 * (docs/plan-instant-load.md B3). Identity hashes are blanked.
 */
public final class ScriptDump {
   private ScriptDump() {
   }

   public static void dumpItems() {
      try {
         File out = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-items.out");
         ArrayList<Item> items = new ArrayList<>(ScriptManager.instance.getAllItems());
         items.sort(Comparator.comparing(i -> String.valueOf(i.getFullName())));
         ArrayList<Field> fields = new ArrayList<>();
         for (Class<?> c = Item.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
               if (Modifier.isStatic(f.getModifiers())) {
                  continue;
               }
               f.setAccessible(true);
               fields.add(f);
            }
         }
         fields.sort(Comparator.comparing(Field::getName));
         int n = 0;
         try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            for (Item item : items) {
               w.write("== " + item.getFullName());
               w.newLine();
               for (Field f : fields) {
                  Object v = f.get(item);
                  String s;
                  if (v == null) {
                     s = "null";
                  } else if (v.getClass().isArray()) {
                     s = v instanceof Object[] ? Arrays.deepToString((Object[]) v) : Arrays.toString((float[]) (v instanceof float[] ? v : new float[0]))
                           + (v instanceof float[] ? "" : String.valueOf(v));
                  } else {
                     s = String.valueOf(v);
                  }
                  w.write(f.getName() + "=" + s.replaceAll("@[0-9a-f]{1,8}", "@"));
                  w.newLine();
               }
               n++;
            }
         }
         Log.info("item dump: " + n + " items, " + fields.size() + " fields -> " + out);
      } catch (Throwable t) {
         Log.warn("item dump failed: " + t);
      }
   }
}
