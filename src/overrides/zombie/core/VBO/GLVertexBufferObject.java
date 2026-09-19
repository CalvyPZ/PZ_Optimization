package zombie.core.VBO;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.opengl.ARBMapBufferRange;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjglx.opengl.OpenGLException;
import zombie.core.skinnedmodel.model.VertexBufferObject;

public class GLVertexBufferObject {
   public static IGLBufferObject funcs;
   private long size;
   private final int type;
   private final int usage;
   private transient int id;
   private transient boolean mapped;
   private transient boolean cleared;
   private transient ByteBuffer buffer;
   private int vertexAttribArray = -1;
   // pzopt: persistent mapping (GL_ARB_buffer_storage) for the fixed-size sprite ring buffers
   private boolean pzoptPersistent;
   private long pzoptFence;
   private static final java.util.ArrayList<GLVertexBufferObject> pzoptUnmappedSinceFence = new java.util.ArrayList<>();
   private static boolean pzoptLogged;
   private static long pzoptWaits, pzoptWaitNs, pzoptStalls;
   private static final int PZOPT_STORAGE_FLAGS = 0x0002 | 0x0040 | 0x0080; // MAP_WRITE | MAP_PERSISTENT | MAP_COHERENT

   static {
      pzopt.Overrides.onClassLoaded("zombie.core.VBO.GLVertexBufferObject");
   }

   private static boolean pzoptUsePersistent() {
      return pzopt.Config.PERSISTENT_VBO && pzopt.Overrides.enabled() && GL.getCapabilities().GL_ARB_buffer_storage && GL.getCapabilities().OpenGL32;
   }

   /**
    * Fence the draws issued from the buffers unmapped since the last fence (a vertex/index pair is unmapped together
    * and drawn together); called when the next buffer is mapped, i.e. after those draws were issued.
    */
   private static void pzoptFencePrevious() {
      java.util.ArrayList<GLVertexBufferObject> pending = pzoptUnmappedSinceFence;
      for (int i = 0; i < pending.size(); i++) {
         GLVertexBufferObject prev = pending.get(i);
         if (prev.pzoptFence != 0L) {
            org.lwjgl.opengl.GL32.glDeleteSync(prev.pzoptFence);
         }
         prev.pzoptFence = org.lwjgl.opengl.GL32.glFenceSync(0x9117, 0); // SYNC_GPU_COMMANDS_COMPLETE
      }
      pending.clear();
   }

   private ByteBuffer pzoptMapPersistent() {
      pzoptFencePrevious();
      if (this.buffer == null) {
         org.lwjgl.opengl.GL44.glBufferStorage(this.type, this.size, PZOPT_STORAGE_FLAGS);
         this.buffer = GL30.glMapBufferRange(this.type, 0L, this.size, PZOPT_STORAGE_FLAGS, null);
         if (this.buffer == null) {
            throw new OpenGLException("Failed to persistently map a buffer " + this.size + " bytes long");
         }
         this.pzoptPersistent = true;
         this.cleared = true; // immutable storage: never glBufferData again
         if (!pzoptLogged) {
            pzoptLogged = true;
            pzopt.Log.info("persistent VBO mapping active (GL_ARB_buffer_storage)");
         }
      } else if (this.pzoptFence != 0L) {
         // the GPU may still be reading the batch written into this buffer 128 batches ago
         long t0 = System.nanoTime();
         int r = org.lwjgl.opengl.GL32.glClientWaitSync(this.pzoptFence, 0x0001, 1_000_000_000L); // SYNC_FLUSH_COMMANDS_BIT, 1 s
         org.lwjgl.opengl.GL32.glDeleteSync(this.pzoptFence);
         this.pzoptFence = 0L;
         // 0x911A ALREADY_SIGNALED, 0x911C CONDITION_SATISFIED (had to wait: the GPU was 128 batches behind), 0x911B TIMEOUT_EXPIRED, 0x911D WAIT_FAILED
         if (r == 0x911B || r == 0x911D) {
            pzopt.Log.warn("persistent VBO: fence wait returned 0x" + Integer.toHexString(r));
         }
         if (r == 0x911C) {
            pzoptStalls++;
         }
         long dt = System.nanoTime() - t0;
         if (dt > 20_000L) {
            pzoptWaits++;
            pzoptWaitNs += dt;
         }
      }
      this.buffer.order(ByteOrder.nativeOrder()).clear().limit((int)this.size);
      this.mapped = true;
      return this.buffer;
   }

   /** Number of map() calls that waited more than 20 us on the GPU, and their total time (dev counters). */
   public static long pzoptFenceWaits() {
      return pzoptWaits;
   }

   public static long pzoptFenceWaitNs() {
      return pzoptWaitNs;
   }

   /** map() calls whose fence had not signalled yet (the GPU was still reading the batch from 128 batches ago). */
   public static long pzoptFenceStalls() {
      return pzoptStalls;
   }

   public static void init() {
      if (GL.getCapabilities().OpenGL15) {
         System.out.println("OpenGL 1.5 buffer objects supported");
         funcs = new GLBufferObject15();
      } else {
         if (!GL.getCapabilities().GL_ARB_vertex_buffer_object) {
            throw new RuntimeException("Neither OpenGL 1.5 nor GL_ARB_vertex_buffer_object supported");
         }

         System.out.println("GL_ARB_vertex_buffer_object supported");
         funcs = new GLBufferObjectARB();
      }

      VertexBufferObject.funcs = funcs;
   }

   public GLVertexBufferObject(long size, int type, int usage) {
      this.size = size;
      this.type = type;
      this.usage = usage;
   }

   public GLVertexBufferObject(int type, int usage) {
      this.size = 0L;
      this.type = type;
      this.usage = usage;
   }

   public void create() {
      this.id = funcs.glGenBuffers();
   }

   public void clear() {
      if (this.pzoptPersistent) {
         return;
      }
      if (!this.cleared) {
         funcs.glBufferData(this.type, this.size, this.usage);
         this.cleared = true;
      }
   }

   protected void doDestroy() {
      if (this.id != 0) {
         this.unmap();
         if (this.pzoptPersistent) {
            this.bind();
            funcs.glUnmapBuffer(this.type);
            this.pzoptPersistent = false;
            this.buffer = null;
            pzoptUnmappedSinceFence.remove(this);
            if (this.pzoptFence != 0L) {
               org.lwjgl.opengl.GL32.glDeleteSync(this.pzoptFence);
               this.pzoptFence = 0L;
            }
         }
         funcs.glDeleteBuffers(this.id);
         this.id = 0;
      }
   }

   public ByteBuffer map(int size) {
      if (!this.mapped) {
         if (this.size != size) {
            this.size = size;
            this.clear();
         }

         if (this.buffer != null && this.buffer.capacity() < size) {
            this.buffer = null;
         }

         ByteBuffer old = this.buffer;
         if (GL.getCapabilities().OpenGL30) {
            int flags = 38;
            this.buffer = GL30.glMapBufferRange(this.type, 0L, size, 38, this.buffer);
         } else if (GL.getCapabilities().GL_ARB_map_buffer_range) {
            int flags = 38;
            this.buffer = ARBMapBufferRange.glMapBufferRange(this.type, 0L, size, 38, this.buffer);
         } else {
            this.buffer = funcs.glMapBuffer(this.type, funcs.GL_WRITE_ONLY(), size, this.buffer);
         }

         if (this.buffer == null) {
            throw new OpenGLException("Failed to map buffer " + this);
         }

         if (this.buffer != old && old != null) {
         }

         this.buffer.order(ByteOrder.nativeOrder()).clear().limit(size);
         this.mapped = true;
         this.cleared = false;
      }

      return this.buffer;
   }

   public ByteBuffer map() {
      if (!this.mapped) {
         assert this.size > 0L;
         if (this.pzoptPersistent || (this.buffer == null && pzoptUsePersistent())) {
            return this.pzoptMapPersistent();
         }
         this.clear();
         ByteBuffer old = this.buffer;
         if (GL.getCapabilities().OpenGL30) {
            int flags = 38;
            this.buffer = GL30.glMapBufferRange(this.type, 0L, this.size, 38, this.buffer);
         } else if (GL.getCapabilities().GL_ARB_map_buffer_range) {
            int flags = 38;
            this.buffer = ARBMapBufferRange.glMapBufferRange(this.type, 0L, this.size, 38, this.buffer);
         } else {
            this.buffer = funcs.glMapBuffer(this.type, funcs.GL_WRITE_ONLY(), this.size, this.buffer);
         }

         if (this.buffer == null) {
            throw new OpenGLException("Failed to map a buffer " + this.size + " bytes long");
         }

         if (this.buffer != old && old != null) {
         }

         this.buffer.order(ByteOrder.nativeOrder()).clear().limit((int)this.size);
         this.mapped = true;
         this.cleared = false;
      }

      return this.buffer;
   }

   public void orphan() {
      funcs.glMapBuffer(this.type, this.usage, this.size, null);
   }

   public boolean unmap() {
      if (this.mapped) {
         this.mapped = false;
         if (this.pzoptPersistent) {
            pzoptUnmappedSinceFence.add(this); // the draws from this buffer follow; fenced at the next map()
            return true;
         }
         return funcs.glUnmapBuffer(this.type);
      } else {
         return true;
      }
   }

   public boolean isMapped() {
      return this.mapped;
   }

   public void bufferData(ByteBuffer data) {
      funcs.glBufferData(this.type, data, this.usage);
   }

   @Override
   public String toString() {
      return "GLVertexBufferObject[" + this.id + ", " + this.size + "]";
   }

   public void bind() {
      funcs.glBindBuffer(this.type, this.id);
   }

   public void bindNone() {
      funcs.glBindBuffer(this.type, 0);
   }

   public int getID() {
      return this.id;
   }

   public void enableVertexAttribArray(int index) {
      if (this.vertexAttribArray != index) {
         this.disableVertexAttribArray();
         if (index >= 0) {
            GL20.glEnableVertexAttribArray(index);
         }

         this.vertexAttribArray = index >= 0 ? index : -1;
      }
   }

   public void disableVertexAttribArray() {
      if (this.vertexAttribArray != -1) {
         GL20.glDisableVertexAttribArray(this.vertexAttribArray);
         this.vertexAttribArray = -1;
      }
   }
}
