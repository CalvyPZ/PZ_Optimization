package pzopt;

import java.util.ArrayList;

/**
 * Linear-time replacements for the two text passes of the game's script
 * parser (docs/plan-instant-load.md, B1).
 *
 * stripComments: the stock pass works backwards from the last comment close,
 * removing each block comment with StringBuilder.replace on the whole file, so
 * a file with thousands of comments costs O(comments * size); the 3 MB
 * tileGeometry.txt took 1.5 s. This pass walks forward once with a nesting
 * depth, which is the same pairing rule (a close inside an open pair needs one
 * more open further back), and returns null when the text is unbalanced so
 * the caller can fall back to the stock pass for the exact edge behaviour.
 *
 * parseTokens: the stock pass re-substrings the remaining file after every
 * top-level block; this one keeps an index. Same search order and the same
 * quirks (searches start one character in), so the tokens are identical.
 */
public final class ScriptText {
   private ScriptText() {
   }

   /** Block comments removed, or null if an open or close is unmatched (caller falls back). */
   public static String stripComments(String s) {
      int n = s.length();
      int first = s.indexOf("/*");
      if (first == -1) {
         return s.indexOf("*/") == -1 ? s : null;
      }
      StringBuilder out = new StringBuilder(n);
      out.append(s, 0, first);
      int depth = 0;
      int i = first;
      while (i < n) {
         char c = s.charAt(i);
         if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
            depth++;
            i += 2;
         } else if (c == '*' && i + 1 < n && s.charAt(i + 1) == '/') {
            if (depth == 0) {
               return null; // stray close: the stock pass leaves such files alone in ways not worth mirroring
            }
            depth--;
            i += 2;
         } else {
            if (depth == 0) {
               out.append(c);
            }
            i++;
         }
      }
      return depth == 0 ? out.toString() : null;
   }

   /** Top-level "{...}" blocks of the file, trimmed, exactly as the stock pass splits them. */
   public static ArrayList<String> parseTokens(String s) {
      ArrayList<String> tokens = new ArrayList<>();
      int base = 0;
      while (true) {
         int depth = 0;
         int open = base;   // stock: relative 0
         int closed = base;
         if (s.indexOf("}", open + 1) == -1) {
            String rest = s.substring(base).trim(); // stock: the brace-less remainder is a token of its own
            if (!rest.isEmpty()) {
               tokens.add(rest);
            }
            break;
         }
         do {
            open = s.indexOf("{", open + 1);
            closed = s.indexOf("}", closed + 1);
            if ((closed < open && closed != -1) || open == -1) {
               open = closed;
               depth--;
            } else {
               closed = open;
               depth++;
            }
         } while (depth > 0);
         if (open == -1) {
            break; // stock would loop forever on a file with a close but no open
         }
         tokens.add(s.substring(base, open + 1).trim());
         base = open + 1;
      }
      return tokens;
   }
}
