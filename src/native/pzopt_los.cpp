// pzopt: the arithmetic of IsoPlayer.updateLOS in C++ (experiment key playerLosNative, 2026-09-22).
//
// Java packs, per moving object that passed the cheap Java-side filters, its position and two visibility bits
// (could see / can see, read from the JNILighting objects of its square), then makes one call per player per
// frame. This computes what the stock loop computes before its side effects: the distance to the player (same
// float semantics as IsoUtils.DistanceTo: float subtraction, squares summed in double, sqrt, cast), the "close"
// count and the branch each object takes. Java then applies the side effects (alpha targets, spot tests, stats).
//
// Built by scripts/build.sh with PZOPT_NATIVE=1 into build/classes/natives/libpzopt_los64.so and called through
// the Java FFM linker (Linker.Option.critical so the arrays are read in place).
#include <cmath>
#include <cstdint>

extern "C" {

// action: 1 = hidden (alpha 0, no spot test), 2 = hidden but could see (alpha 0 + spot test), 3 = visible path.
int pzopt_los_pass(int n, const float* xy, const uint8_t* flags, float locX, float locY, float detectionRange,
                   int asleep, float* dist, uint8_t* action) {
   int close = 0;
   for (int i = 0; i < n; i++) {
      float dxf = locX - xy[i * 2];
      float dyf = locY - xy[i * 2 + 1];
      double d2 = (double)dxf * (double)dxf + (double)dyf * (double)dyf;
      float d = (float)std::sqrt(d2);
      dist[i] = d;
      if (d < 20.0f) {
         close++;
      }
      uint8_t f = flags[i];
      bool couldSee = (f & 1) != 0;
      bool canSee = (f & 2) != 0;
      if (asleep || !(canSee || (d < detectionRange && couldSee))) {
         action[i] = couldSee ? 2 : 1;
      } else {
         action[i] = 3;
      }
   }
   return close;
}

}
