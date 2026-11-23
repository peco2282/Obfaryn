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
@SuppressWarnings("JavadocDeclaration")
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

  public static CallSite linkA(MethodHandles.Lookup lookup, String siteName, MethodType site,
      String metadata, int keyShare, int maskShare, int encodedKind) throws Throwable {
    return resolve(lookup, siteName, site, metadata, keyShare, maskShare, encodedKind);
  }

  public static CallSite linkB(MethodHandles.Lookup lookup, String siteName, MethodType site,
      int keyShare, String metadata, int encodedKind, int maskShare) throws Throwable {
    return resolve(lookup, siteName, site, metadata, keyShare, maskShare, encodedKind);
  }

  private static CallSite resolve(MethodHandles.Lookup lookup, String siteName, MethodType site,
      String metadata, int keyShare, int maskShare, int encodedKind) throws Throwable {
    Class<?> caller = lookup.lookupClass();
    ConcurrentHashMap<String, CallSite> cache = sites.get(caller);
    CallSite cached = cache.get(siteName);
    if (cached != null) return cached;
    ClassLoader loader = caller.getClassLoader();
    int key = keyShare ^ Integer.rotateLeft(maskShare, siteName.hashCode() & 31);
    int kind = encodedKind ^ Integer.rotateLeft(keyShare ^ maskShare, 3);
    int effectiveKey = contextualKey(key, caller.getName(), siteName);
    int ownerEnd = 16 + Integer.parseUnsignedInt(metadata.substring(0, 8), 16);
    int nameEnd = ownerEnd + Integer.parseUnsignedInt(metadata.substring(8, 16), 16);
    Class<?> target = Class.forName(decode(metadata.substring(16, ownerEnd), effectiveKey).replace('/', '.'), false, loader);
    String member = decode(metadata.substring(ownerEnd, nameEnd), effectiveKey);
    MethodType type = MethodType.fromMethodDescriptorString(decode(metadata.substring(nameEnd), effectiveKey), loader);
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

  private static int contextualKey(int key, String caller, String siteName) {
    int mixed = key ^ caller.hashCode();
    mixed = Integer.rotateLeft(mixed, 11) * -1640531527;
    return mixed ^ Integer.rotateLeft(siteName.hashCode(), 7);
  }
}
