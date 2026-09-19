package zombie.core.skinnedmodel.model;

import zombie.asset.Asset;
import zombie.asset.AssetManager;
import zombie.asset.AssetPath;
import zombie.asset.AssetTask;
import zombie.asset.AssetTask_RunFileTask;
import zombie.asset.AssetManager.AssetParams;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.AnimationAsset.AnimationAssetParams;
import zombie.core.skinnedmodel.model.jassimp.ProcessedAiScene;
import zombie.debug.DebugType;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.FileTask;

public final class AnimationAssetManager extends AssetManager {
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.skinnedmodel.model.AnimationAssetManager");
   }

   public static final AnimationAssetManager instance = new AnimationAssetManager();

   protected void startLoading(Asset asset) {
      AnimationAsset anim = (AnimationAsset)asset;
      FileSystem fileSystem = this.getOwner().getFileSystem();
      // pzopt: the cache-aware task answers from the clip cache when it can (docs/plan-instant-load.md B11)
      FileTask fileTask = pzopt.AnimClipCache.enabled()
         ? new pzopt.CachedAnimationTask(anim, fileSystem, result -> this.loadCallback(anim, result))
         : new FileTask_LoadAnimation(anim, fileSystem, result -> this.loadCallback(anim, result));
      if (fileTask instanceof pzopt.CachedAnimationTask cachedTask) {
         this.pzoptTasks.put(anim, cachedTask);
      }

      fileTask.setPriority(4);
      String fileName = asset.getPath().getPath().toLowerCase();
      if (fileName.endsWith("bob_idle") || fileName.endsWith("bob_walk") || fileName.endsWith("bob_run")) {
         fileTask.setPriority(6);
      }

      AssetTask assetTask = new AssetTask_RunFileTask(fileTask, asset);
      this.setTask(asset, assetTask);
      assetTask.execute();
   }

   private void loadCallback(AnimationAsset anim, Object result) {
      if (result instanceof pzopt.CachedAnimationTask.CachedClips cached) { // pzopt: clips from the disk cache
         this.pzoptTasks.remove(anim);
         anim.animationClips = cached.clips;
         this.onLoadingSucceeded(anim);
         ModelManager.instance.animationAssetLoaded(anim);
      } else if (result instanceof ProcessedAiScene processedAiScene) {
         anim.onLoadedX(processedAiScene);
         this.onLoadingSucceeded(anim);
         ModelManager.instance.animationAssetLoaded(anim);
         this.pzoptWriteCache(anim);
      } else if (result instanceof ModelTxt modelTxt) {
         anim.onLoadedTxt(modelTxt);
         this.onLoadingSucceeded(anim);
         ModelManager.instance.animationAssetLoaded(anim);
      } else {
         DebugType.General.warn("Failed to load asset: " + anim.getPath());
         this.onLoadingFailed(anim);
      }
   }

   // pzopt: after a stock import, store the clips for the next boot (the task remembered where they belong)
   private final java.util.concurrent.ConcurrentHashMap<AnimationAsset, pzopt.CachedAnimationTask> pzoptTasks = new java.util.concurrent.ConcurrentHashMap<>();

   private void pzoptWriteCache(AnimationAsset anim) {
      pzopt.CachedAnimationTask task = this.pzoptTasks.remove(anim);
      if (task != null && task.cacheFile != null) {
         pzopt.AnimClipCache.writeAsync(task.cacheFile, anim.animationClips);
      }
   }

   protected Asset createAsset(AssetPath path, AssetParams params) {
      return new AnimationAsset(path, this, (AnimationAssetParams)params);
   }

   protected void destroyAsset(Asset asset) {
   }
}
