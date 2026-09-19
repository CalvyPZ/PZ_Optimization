package pzopt;

import java.util.ArrayList;
import java.util.List;

import fmod.fmod.FMODManager;
import zombie.core.Core;

/**
 * Boot work that the stock game does serially on the main thread but that
 * nothing needs until later (docs/plan-instant-load.md, B2 and B9).
 *
 * FMOD: FMODManager.init (system create plus twelve bank files, ~1.6 s of
 * native work) is the first thing GameWindow.mainThreadInit does. Its first
 * consumer is not GameSounds.ScriptsLoaded at the end of ScriptManager.Load
 * (event descriptions for the sound scripts) but the constructors of
 * SoundManager and AmbientStreamManager right after it in mainThreadInit:
 * their FMODGlobalParameter fields (MusicState, MusicIntensity, TimeOfDay, ...)
 * resolve their parameter descriptions from the loaded banks in the
 * constructor, and a null description is silently kept (the parameter never
 * registers and its values never reach FMOD; the menu music then never stops
 * and the in-game music and ambience are dead, issue #3). The four volume
 * setters read VCAs from the banks as well. So the init runs on a thread from
 * the top of mainThreadInit, the construction of the sound singletons and the
 * volume calls are queued behind it, and GameWindow.initShared joins the
 * thread and runs the queue just before the scripts load. The FMOD Studio API
 * is thread-safe; the game already drives it from several threads.
 */
public final class BootAsync {
   private static Thread fmod;
   private static Throwable fmodFailure;
   private static final List<Runnable> afterFmod = new ArrayList<>();
   private static long fmodStartNs;
   private static Thread animSets;
   private static long animSetsStartNs;

   public static boolean preloadAnimSets() {
      return Config.PRELOAD_ANIM_SETS && Overrides.enabled();
   }

   /**
    * Parse the animation-set XML trees the first world load needs (AnimationSet.GetAnimationSet caches them in its
    * static map; IsoPlayer's constructor spent 1.1 s on "player" on the loader thread). Runs on a thread from
    * after ModelManager.create; GetAnimationSet is synchronized in the override so the game never races it.
    */
   public static synchronized void startAnimSets() {
      if (!preloadAnimSets() || animSets != null) {
         return;
      }
      animSetsStartNs = System.nanoTime();
      animSets = new Thread(() -> {
         for (String name : new String[]{"player", "zombie", "player-vehicle", "zombie-crawler"}) {
            try {
               zombie.core.skinnedmodel.advancedanimation.AnimationSet.GetAnimationSet(name, false);
            } catch (Throwable t) {
               Log.warn("anim set preload " + name + ": " + t);
            }
         }
         Log.info(String.format("anim sets preloaded in %.2f s", (System.nanoTime() - animSetsStartNs) / 1e9));
      }, "pzopt-animset-preload");
      animSets.setDaemon(true);
      animSets.start();
   }

   /** Wait for the preload (called when the loading screen starts; normally long done by then). */
   public static void joinAnimSets() {
      Thread t;
      synchronized (BootAsync.class) {
         t = animSets;
      }
      if (t == null) {
         return;
      }
      long w = System.nanoTime();
      try {
         t.join();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
      Log.info(String.format("anim set preload joined, waited %.2f s", (System.nanoTime() - w) / 1e9));
   }

   private BootAsync() {
   }

   public static boolean fmodAsync() {
      return Config.FMOD_ASYNC && Overrides.enabled();
   }

   /** Start the FMOD init on its own thread. */
   public static synchronized void startFmod(Runnable init) {
      fmodStartNs = System.nanoTime();
      fmod = new Thread(() -> {
         try {
            init.run();
         } catch (Throwable t) {
            fmodFailure = t;
         }
      }, "pzopt-fmod-init");
      fmod.setDaemon(true);
      fmod.start();
   }

   /** Run after the FMOD init has finished: immediately if it has, else at joinFmod. */
   public static synchronized void afterFmod(Runnable r) {
      if (fmod == null) {
         r.run();
      } else {
         afterFmod.add(r);
      }
   }

   /** Wait for the FMOD init, then run what was queued behind it. Safe to call more than once. */
   public static void joinFmod() {
      Thread t;
      synchronized (BootAsync.class) {
         t = fmod;
      }
      if (t == null) {
         return;
      }
      long waitStart = System.nanoTime();
      try {
         t.join();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
      List<Runnable> queued;
      synchronized (BootAsync.class) {
         fmod = null;
         queued = new ArrayList<>(afterFmod);
         afterFmod.clear();
      }
      Log.info(String.format("fmod init on its own thread: %.2f s total, main thread waited %.2f s for it",
            (System.nanoTime() - fmodStartNs) / 1e9, (System.nanoTime() - waitStart) / 1e9));
      if (fmodFailure != null) {
         Log.warn("fmod init failed on the pzopt thread: " + fmodFailure);
      }
      for (Runnable r : queued) {
         r.run();
      }
      if (!Core.soundDisabled) {
         // Regression check for issue #3: MusicState is one of SoundManager's global parameters; it is only in the
         // map when its description was resolved after the banks loaded.
         boolean registered = FMODManager.instance.getGlobalParameter("MusicState") != null;
         if (registered) {
            Log.info("fmod global parameters registered after the join (MusicState found)");
         } else {
            Log.warn("fmod global parameter MusicState not registered: the sound managers were built before the banks loaded");
         }
      }
   }
}
