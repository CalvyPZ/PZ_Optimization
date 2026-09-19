import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeEGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * Minimal GLFW window + swap loop using the game's own LWJGL build, to test overlay hooks
 * (MangoHud) against the exact library-loading path the game uses, without a 90 s game run.
 *
 *   javac -cp projectzomboid.jar -d /tmp/probe tools/GlfwSwapProbe.java
 *   MANGOHUD=1 LD_PRELOAD=/usr/lib/mangohud/libMangoHud_opengl.so \
 *     <non-java-named launcher> -cp projectzomboid.jar:/tmp/probe -Dorg.lwjgl.librarypath=<natives> \
 *     GlfwSwapProbe [wayland|x11] [seconds] [hud]
 *
 * With "hud", each frame is presented by calling the eglSwapBuffers exported by the preloaded
 * MangoHud library (looked up with the JDK foreign-function API) instead of glfwSwapBuffers:
 * GLFW resolves EGL entry points with dlsym on its private libEGL handle, which no LD_PRELOAD
 * interposition reaches, so MangoHud never sees the swap unless it is called directly.
 * (MangoHud's process-name blacklist includes "java": run the probe under another name.)
 */
public final class GlfwSwapProbe {
   public static void main(String[] args) throws Throwable {
      String platform = args.length > 0 ? args[0] : "x11";
      int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 4;
      boolean hud = args.length > 2 && "hud".equals(args[2]);
      GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, "wayland".equals(platform) ? GLFW.GLFW_PLATFORM_WAYLAND : GLFW.GLFW_PLATFORM_X11);
      if (!GLFW.glfwInit()) {
         throw new IllegalStateException("glfwInit failed");
      }
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);
      long win = GLFW.glfwCreateWindow(640, 360, "GlfwSwapProbe " + platform, 0L, 0L);
      if (win == 0L) {
         throw new IllegalStateException("glfwCreateWindow failed");
      }
      GLFW.glfwMakeContextCurrent(win);
      GL.createCapabilities();
      GLFW.glfwSwapInterval(0);
      System.out.println("platform=" + GLFW.glfwGetPlatform() + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
      MethodHandle hudSwap = null;
      long dpy = 0L, surf = 0L;
      if (hud) {
         dpy = GLFWNativeEGL.glfwGetEGLDisplay();
         surf = GLFWNativeEGL.glfwGetEGLSurface(win);
         SymbolLookup lookup = SymbolLookup.libraryLookup("libMangoHud_opengl.so", Arena.global());
         MemorySegment fn = lookup.find("eglSwapBuffers").orElseThrow();
         hudSwap = Linker.nativeLinker().downcallHandle(fn, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         System.out.println("hud swap via MangoHud eglSwapBuffers at 0x" + Long.toHexString(fn.address()) + " dpy=0x" + Long.toHexString(dpy) + " surf=0x" + Long.toHexString(surf));
      }
      long end = System.nanoTime() + seconds * 1_000_000_000L;
      int frames = 0;
      while (System.nanoTime() < end && !GLFW.glfwWindowShouldClose(win)) {
         GL11.glClearColor(0.1f, 0.2f, 0.3f, 1f);
         GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
         if (hudSwap != null) {
            int ok = (int)hudSwap.invokeExact(MemorySegment.ofAddress(dpy), MemorySegment.ofAddress(surf));
            if (ok == 0) {
               throw new IllegalStateException("eglSwapBuffers returned EGL_FALSE");
            }
         } else {
            GLFW.glfwSwapBuffers(win);
         }
         GLFW.glfwPollEvents();
         frames++;
      }
      System.out.println("frames=" + frames);
      GLFW.glfwDestroyWindow(win);
      GLFW.glfwTerminate();
   }
}
