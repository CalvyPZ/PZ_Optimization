package zombie.core.textures;

import zombie.asset.Asset;
import zombie.asset.AssetManager;
import zombie.asset.AssetPath;
import zombie.asset.AssetTask;
import zombie.asset.AssetTask_RunFileTask;
import zombie.asset.FileTask_LoadImageData;
import zombie.asset.FileTask_LoadPackImage;
import zombie.asset.AssetManager.AssetParams;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.TextureID.TextureIDAssetParams;
import zombie.core.utils.DirectBufferAllocator;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.FileTask;
import zombie.fileSystem.FileSystem.SubTexture;

public final class TextureIDAssetManager extends AssetManager {
   public static final TextureIDAssetManager instance = new TextureIDAssetManager();
   // pzopt: decoded bytes allowed to wait for the render thread before the decoders pause (stock 50 MB)
   private static final long WAIT_BYTES = pzopt.Overrides.enabled() ? pzopt.Config.TEXTURE_BUFFER_MB * 1048576L : 52428800L;

   static {
      pzopt.Overrides.onClassLoaded("zombie.core.textures.TextureIDAssetManager");
   }

   protected void startLoading(Asset asset) {
      TextureID textureID = (TextureID)asset;
      FileSystem fs = this.getOwner().getFileSystem();
      if (textureID.assetParams != null && textureID.assetParams.subTexture != null) {
         SubTexture subTex = textureID.assetParams.subTexture;
         FileTask fileTask = new FileTask_LoadPackImage(subTex.packName, subTex.pageName, fs, result -> this.onFileTaskFinished(asset, result));
         fileTask.setPriority(7);
         AssetTask assetTask = new AssetTask_RunFileTask(fileTask, asset);
         this.setTask(asset, assetTask);
         assetTask.execute();
      } else {
         FileTask fileTask = new FileTask_LoadImageData(asset.getPath().getPath(), fs, result -> this.onFileTaskFinished(asset, result));
         fileTask.setPriority(7);
         AssetTask assetTask = new AssetTask_RunFileTask(fileTask, asset);
         this.setTask(asset, assetTask);
         assetTask.execute();
      }
   }

   protected void unloadData(Asset asset) {
      TextureID textureID = (TextureID)asset;
      if (!textureID.isDestroyed()) {
         RenderThread.invokeOnRenderContext(textureID::destroy);
      }
   }

   protected Asset createAsset(AssetPath path, AssetParams params) {
      return new TextureID(path, this, (TextureIDAssetParams)params);
   }

   protected void destroyAsset(Asset asset) {
   }

   private void onFileTaskFinished(Asset asset, Object result) {
      TextureID textureID = (TextureID)asset;
      if (result instanceof ImageData imageData) {
         textureID.setImageData(imageData);
         this.onLoadingSucceeded(asset);
      } else {
         this.onLoadingFailed(asset);
      }
   }

   public void waitFileTask() {
      long pzoptT0 = 0L; // pzopt: time the decoders sleep here, in the file-task summary as "waitFileTask(sleep)"
      while (DirectBufferAllocator.getBytesAllocated() > WAIT_BYTES) { // pzopt: was 52428800L
         if (pzoptT0 == 0L) {
            pzoptT0 = System.nanoTime();
         }
         try {
            Thread.sleep(20L);
         } catch (InterruptedException var2) {
         }
      }
      if (pzoptT0 != 0L) {
         pzopt.FileTaskStats.add("waitFileTask(sleep)", System.nanoTime() - pzoptT0); // pzopt
      }
   }
}
