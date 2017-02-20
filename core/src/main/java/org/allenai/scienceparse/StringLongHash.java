package org.allenai.scienceparse;

/**
 * Taken from @sfussenegger
 * http://stackoverflow.com/questions/1660501/what-is-a-good-64bit-hash-function-in-java-for-textual-strings
 */

public class StringLongHash {
  
  //adapted from String.hashCode()
  public static long hash(String string) {
   long h = 1125899906842597L; // prime
   int len = string.length();
  
   for (int i = 0; i < len; i++) {
     h = 31*h + string.charAt(i);
   }
   return h;
  }

}
