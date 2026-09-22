package pzopt;

import org.lwjgl.opengl.GL20;

/** GLSL program compilation for the pzopt render-thread passes (sources are Java strings; failures are logged, 0 returned). */
final class Shaders {
   private Shaders() {
   }

   static int program(String name, String vertexSource, String fragmentSource) {
      int vs = compile(name, GL20.GL_VERTEX_SHADER, vertexSource);
      if (vs == 0) {
         return 0;
      }
      int fs = compile(name, GL20.GL_FRAGMENT_SHADER, fragmentSource);
      if (fs == 0) {
         GL20.glDeleteShader(vs);
         return 0;
      }
      int program = GL20.glCreateProgram();
      GL20.glAttachShader(program, vs);
      GL20.glAttachShader(program, fs);
      GL20.glLinkProgram(program);
      GL20.glDeleteShader(vs);
      GL20.glDeleteShader(fs);
      if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
         Log.warn(name + ": link failed: " + GL20.glGetProgramInfoLog(program, 4096));
         GL20.glDeleteProgram(program);
         return 0;
      }
      return program;
   }

   private static int compile(String name, int type, String source) {
      int shader = GL20.glCreateShader(type);
      GL20.glShaderSource(shader, source);
      GL20.glCompileShader(shader);
      if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
         Log.warn(name + ": shader compile failed: " + GL20.glGetShaderInfoLog(shader, 4096));
         GL20.glDeleteShader(shader);
         return 0;
      }
      return shader;
   }
}
