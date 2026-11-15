package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

/** Structural checks only; constructors never attest world safety. */
public final class Checks {
 private Checks() {}
 public static void require(boolean ok, String why) { if (!ok) throw new IllegalArgumentException(why); }
 public static void nonnegative(long v) { require(v >= 0, "negative quantity"); }
 public static void finite(double v) { require(Double.isFinite(v), "nonfinite quantity"); }
 public static void text(String s) {
  Objects.requireNonNull(s); require(s.length() <= 65536, "text bound");
  for (int i=0;i<s.length();i++) { char c=s.charAt(i); if(Character.isHighSurrogate(c)) { require(++i<s.length() && Character.isLowSurrogate(s.charAt(i)), "unicode"); } else require(!Character.isLowSurrogate(c), "unicode"); }
 }
 public static void key(String s) { text(s); require(s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "resource id"); for(String p:s.substring(s.indexOf(':')+1).split("/",-1)) require(!p.isEmpty()&&!p.equals(".")&&!p.equals(".."), "path segment"); }
 public static <T> List<T> list(List<T> v) { require(v.size()<=65536,"collection bound"); return List.copyOf(v); }
 public static <T> Set<T> set(Set<T> v) { require(v.size()<=65536,"collection bound"); return Set.copyOf(v); }
 public static <K,V> Map<K,V> map(Map<K,V> v) { require(v.size()<=65536,"collection bound"); return Map.copyOf(v); }
}
