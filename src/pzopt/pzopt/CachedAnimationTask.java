package pzopt;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.model.AnimationAsset;
import zombie.core.skinnedmodel.model.FileTask_AbstractLoadModel;
import zombie.core.skinnedmodel.model.FileTask_LoadAnimation;
import zombie.core.skinnedmodel.model.ModelFileExtensionType;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.IFileTaskCallback;

/**
 * FileTask_LoadAnimation that answers from pzopt.AnimClipCache when it can
 * (docs/plan-instant-load.md, B11). The stock task resolves the source file
 * (txt / X / fbx / glTF) in a private step of call(); this subclass runs that
 * step, and for the imported formats returns the cached clips as a
 * {@link CachedClips} result, which the AnimationAssetManager override
 * applies. On a miss the stock import runs and its clips are written to the
 * cache afterwards.
 */
public final class CachedAnimationTask extends FileTask_LoadAnimation {
   private static final Method CHECK_EXTENSION;

   static {
      Method m = null;
      try {
         m = FileTask_AbstractLoadModel.class.getDeclaredMethod("checkExtensionType");
         m.setAccessible(true);
      } catch (Exception e) {
         Log.warn("anim clip cache disabled: " + e);
      }
      CHECK_EXTENSION = m;
   }

   /** Result type carrying cached clips. */
   public static final class CachedClips {
      public final HashMap<String, AnimationClip> clips;

      CachedClips(HashMap<String, AnimationClip> clips) {
         this.clips = clips;
      }
   }

   private final String meshKey;
   /** Set by call(): where the clips of this source belong in the cache (null = not cacheable). */
   public volatile File cacheFile;

   public CachedAnimationTask(AnimationAsset anim, FileSystem fileSystem, IFileTaskCallback cb) {
      super(anim, fileSystem, cb);
      String mesh = "none";
      try {
         if (anim.assetParams != null && anim.assetParams.animationsMesh != null && anim.assetParams.animationsMesh.getPath() != null) {
            mesh = String.valueOf(anim.assetParams.animationsMesh.getPath().getPath());
         }
      } catch (Throwable ignored) {
      }
      this.meshKey = mesh;
   }

   @Override
   public Object call() throws Exception {
      if (CHECK_EXTENSION == null) {
         return super.call();
      }
      ModelFileExtensionType res = (ModelFileExtensionType) CHECK_EXTENSION.invoke(this);
      if (res == null) {
         return null;
      }
      switch (res) {
         case Txt:
            return this.loadTxt();
         case X:
         case Fbx:
         case glTF:
            File f = AnimClipCache.fileFor(this.fileName, this.meshKey);
            HashMap<String, AnimationClip> clips = AnimClipCache.read(f);
            if (clips != null) {
               return new CachedClips(clips);
            }
            this.cacheFile = f;
            switch (res) {
               case X:
                  return this.loadX();
               case Fbx:
                  return this.loadFBX();
               default:
                  return this.loadGLTF();
            }
         default:
            return null;
      }
   }
}
