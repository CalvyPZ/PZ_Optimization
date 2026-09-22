package pzopt;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The C++ half of {@code IsoPlayer.updateLOS} (experiment key {@code playerLosNative}, 2026-09-22): the distance,
 * "close" count and branch decision of every object in one native call per player per frame, over arrays Java packed
 * ({@code src/native/pzopt_los.cpp}, {@code natives/libpzopt_los64.so} next to the game's own natives, built with
 * {@code PZOPT_NATIVE=1 scripts/build.sh}). Bound through the FFM linker with {@code Linker.Option.critical(true)} so
 * the heap arrays are read in place, no JNI copies. Falls back to the Java loop when the library is missing.
 */
public final class NativeLos {
   private static MethodHandle pass;
   private static boolean tried;

   /** Grown as needed: interleaved x,y per object, flag bits, then the outputs. */
   public float[] xy = new float[2 * 4096];
   public byte[] flags = new byte[4096];
   public float[] dist = new float[4096];
   public byte[] action = new byte[4096];
   public Object[] objects = new Object[4096];

   public void ensure(int n) {
      if (n <= this.flags.length) {
         return;
      }
      int cap = Math.max(n, this.flags.length * 2);
      this.xy = java.util.Arrays.copyOf(this.xy, 2 * cap);
      this.flags = java.util.Arrays.copyOf(this.flags, cap);
      this.dist = new float[cap];
      this.action = new byte[cap];
      this.objects = java.util.Arrays.copyOf(this.objects, cap);
   }

   /** True when the library is loaded (loads it on the first call; one warning if it cannot). */
   public static boolean available() {
      if (pass != null) {
         return true;
      }
      if (tried) {
         return false;
      }
      tried = true;
      try {
         File lib = new File("natives", "libpzopt_los64.so").getAbsoluteFile();
         if (!lib.isFile() && !Config.PLAYER_LOS_NATIVE_LIB.isEmpty()) {
            lib = new File(Config.PLAYER_LOS_NATIVE_LIB);
         }
         if (!lib.isFile()) {
            Log.warn("playerLosNative: " + lib + " not found (build with PZOPT_NATIVE=1, or playerLosNativeLib=<path>); Java loop used");
            return false;
         }
         SymbolLookup lookup = SymbolLookup.libraryLookup(lib.getPath(), Arena.global());
         MemorySegment fn = lookup.find("pzopt_los_pass").orElseThrow();
         pass = Linker.nativeLinker().downcallHandle(fn,
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
               ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
               ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
               ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            Linker.Option.critical(true));
         Log.info("playerLosNative: " + lib.getName() + " loaded, the LOS arithmetic runs in C++");
         return true;
      } catch (Throwable t) {
         Log.warn("playerLosNative: not loaded (" + t + "); Java loop used");
         pass = null;
         return false;
      }
   }

   /** Runs the pass over the first {@code n} packed objects; returns the "close" count. */
   public int run(int n, float locX, float locY, float detectionRange, boolean asleep) {
      try {
         return (int)pass.invokeExact(n, MemorySegment.ofArray(this.xy), MemorySegment.ofArray(this.flags),
            locX, locY, detectionRange, asleep ? 1 : 0, MemorySegment.ofArray(this.dist), MemorySegment.ofArray(this.action));
      } catch (Throwable t) {
         throw new RuntimeException(t);
      }
   }
}
