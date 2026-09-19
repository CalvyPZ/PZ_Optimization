package pzopt;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.core.skinnedmodel.shader.Shader;

/**
 * Shaders that a Model has already created, keyed by shader name and the static flag, so the next Model with the
 * same shader takes it from here instead of blocking on a round trip to the render thread.
 *
 * Stock Model.CreateShader always posts to the render thread and waits, even when ShaderManager already has the
 * shader: the caller (usually the loader thread, e.g. AnimalDefinitions loading 73 animal models that all use
 * animalEffect) then waits for the render thread's next queue drain, which is one render step. That is ~1 ms on the
 * desktop but ~220 ms on a laptop whose loading-screen render step is slow, where the 73 round trips were 16.5 s
 * of the load (GitHub issue #1). Only the first model per shader still pays the round trip.
 *
 * ShaderManager never removes shaders and a shader that is reloaded (debug file watcher) recompiles in place, so a
 * cached reference stays valid for the life of the process.
 */
public final class ModelShaders {
   private static final ConcurrentHashMap<String, Shader> cache = new ConcurrentHashMap<>();
   private static final AtomicLong hits = new AtomicLong();
   private static final AtomicLong roundTrips = new AtomicLong();
   private static final AtomicLong waitedNs = new AtomicLong();

   private ModelShaders() {
   }

   public static boolean enabled() {
      return Config.SHADER_CACHE && Overrides.enabled();
   }

   private static String key(String name, boolean isStatic) {
      return isStatic ? name + "&static" : name;
   }

   /** The shader a previous model created for this name, or null if this is the first (or the cache is off). */
   public static Shader get(String name, boolean isStatic) {
      if (name == null) {
         return null;
      }
      Shader s = cache.get(key(name, isStatic));
      if (s != null) {
         hits.incrementAndGet();
      }
      return s;
   }

   /** Called after the render-thread round trip that created (or found) the shader. */
   public static void created(String name, boolean isStatic, Shader shader, long ns) {
      roundTrips.incrementAndGet();
      waitedNs.addAndGet(ns);
      if (name != null && shader != null) {
         cache.putIfAbsent(key(name, isStatic), shader);
      }
   }

   public static String summary() {
      return String.format("model shaders: %d cached, %d render-thread round trips (%.3f s waited), %d served from the cache",
            cache.size(), roundTrips.get(), waitedNs.get() / 1e9, hits.get());
   }
}
