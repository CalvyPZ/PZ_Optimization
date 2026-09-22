package pzopt;

import org.lwjgl.opengl.GL11;
import zombie.core.textures.Texture;

/**
 * A {@link Texture} over a GL texture the upscaler owns (exact screen size, no power-of-two padding), so the stock
 * screen-shader quad of {@code MultiTextureFBO2.render()} can draw the upscaled frame exactly as it draws the
 * offscreen buffer. Only what {@code rendershader2} and the sprite ring buffer touch is overridden: id, sizes,
 * readiness and {@code bind}.
 */
public final class UpscaleTexture extends Texture {
   private int id;
   private int w;
   private int h;

   public UpscaleTexture() {
      super();
      this.setNameOnly("pzopt.upscale");
   }

   /** Point at a GL texture of the given size (render thread creates it; the game thread only reads the fields). */
   public void set(int glId, int width, int height) {
      this.id = glId;
      this.w = width;
      this.h = height;
      this.width = width;
      this.height = height;
      this.widthOrig = width;
      this.xStart = 0.0F;
      this.yStart = 0.0F;
      this.xEnd = 1.0F;
      this.yEnd = 1.0F;
      this.offsetX = 0.0F;
      this.offsetY = 0.0F;
      this.flip = false;
   }

   public boolean hasTexture() {
      return this.id != 0;
   }

   @Override
   public int getID() {
      return this.id;
   }

   @Override
   public int getWidth() {
      return this.w;
   }

   @Override
   public int getHeight() {
      return this.h;
   }

   @Override
   public int getWidthHW() {
      return this.w;
   }

   @Override
   public int getHeightHW() {
      return this.h;
   }

   @Override
   public boolean isReady() {
      return this.id != 0;
   }

   @Override
   public boolean isValid() {
      return this.id != 0;
   }

   @Override
   public boolean isDestroyed() {
      return false;
   }

   @Override
   public boolean isEmpty() {
      return this.id == 0;
   }

   @Override
   public void bind() {
      this.bind(GL11.GL_TEXTURE_2D);
   }

   /** The stock argument is the GL target (always GL_TEXTURE_2D); like TextureID.bindInternal this binds and records. */
   @Override
   public void bind(int target) {
      if (this.id == 0) {
         Texture.getErrorTexture().bind(target);
         return;
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.id);
      Texture.lastlastTextureID = Texture.lastTextureID;
      Texture.lastTextureID = this.id;
      Texture.bindCount++;
   }

   @Override
   public void destroy() {
      this.id = 0;
   }
}
