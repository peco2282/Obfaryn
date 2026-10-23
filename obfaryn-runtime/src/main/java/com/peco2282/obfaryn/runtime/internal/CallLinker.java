package com.peco2282.obfaryn.runtime.internal;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Template copied into the output under a fresh name; requires only Java 8.
 * @hidden
 */
public final class CallLinker {
  private CallLinker() {}
  // ClassValue lets child-loader callers be collected while a parent-held linker stays alive.
  private static final ClassValue<ConcurrentHashMap<String, CallSite>> sites = new SiteCache();

  static final class SiteCache extends ClassValue<ConcurrentHashMap<String, CallSite>> {
    SiteCache() {}
    @Override protected ConcurrentHashMap<String, CallSite> computeValue(Class<?> caller) {
      return new ConcurrentHashMap<String, CallSite>();
    }
  }

  public static CallSite link(MethodHandles.Lookup lookup, String siteName, MethodType site,
      String owner, String name, String descriptor, int key, int kind) throws Throwable {
    Class<?> caller = lookup.lookupClass();
    ConcurrentHashMap<String, CallSite> cache = sites.get(caller);
    CallSite cached = cache.get(siteName);
    if (cached != null) return cached;
    ClassLoader loader = caller.getClassLoader();
    Class<?> target = Class.forName(decode(owner, key).replace('/', '.'), false, loader);
    MethodType type = MethodType.fromMethodDescriptorString(decode(descriptor, key), loader);
    String member = decode(name, key);
    MethodHandle handle = kind == 184
        ? lookup.findStatic(target, member, type)
        : lookup.findVirtual(target, member, type);
    CallSite resolved = new ConstantCallSite(handle.asType(site));
    CallSite raced = cache.putIfAbsent(siteName, resolved);
    return raced == null ? resolved : raced;
  }

  private static String decode(String text, int key) {
    char[] result = new char[text.length() / 4];
    for (int i = 0; i < result.length; i++) {
      key = key * 1664525 + 1013904223;
      int bits = Integer.parseInt(text.substring(i * 4, i * 4 + 4), 16);
      result[i] = (char) (bits ^ (key >>> 16));
    }
    return new String(result);
  }
}
