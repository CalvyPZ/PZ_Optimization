package pzopt;

import zombie.debug.DebugLog;
import zombie.debug.DebugLogStream;
import zombie.debug.DebugType;

/**
 * Logs through the game's DebugLog so lines land in console.txt, prefixed
 * [pzopt]. Outside the game (tests, tools) DebugLog is never initialised and
 * would swallow everything, so fall back to plain stdout there.
 */
public final class Log {
   private static final String PREFIX = "[pzopt] ";

   private Log() {
   }

   static boolean gameLogReady() {
      return System.out instanceof DebugLogStream;
   }

   public static void info(String msg) {
      if (gameLogReady()) {
         DebugLog.log(DebugType.General, PREFIX + msg);
      } else {
         System.out.println("LOG  " + PREFIX + msg);
      }
   }

   public static void warn(String msg) {
      if (gameLogReady()) {
         DebugType.General.warn(PREFIX + msg.replace("%", "%%"));
      } else {
         System.out.println("WARN " + PREFIX + msg);
      }
   }

   public static void error(String msg) {
      if (gameLogReady()) {
         DebugType.General.error(PREFIX + msg.replace("%", "%%"));
      } else {
         System.err.println("ERROR " + PREFIX + msg);
      }
   }
}
