package com.peco2282.obfaryn.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional API embedded when prelinkCalls is enabled. No dependency on the obfuscation engine. */
public final class ProtectionRuntime {
  private ProtectionRuntime() {}

  /**
   * Resolves up to maxSites generated call sites without invoking their targets.
   * Invoking the generated static entry INITIALIZES caller. Call this only at an explicitly
   * chosen safe lifecycle point; this is not a side-effect-free class-loader scan.
   * Target classes are loaded without initialization by the generated linker.
   * Returns zero for a class with no generated prelink entry. Throws on resolution failure.
   * @param caller class whose generated sites should be prepared
   * @param maxSites maximum number of sites to process starting at zero
   * @return processed site count, including already cached sites
   * @throws IllegalArgumentException if maxSites is negative
   */
  public static int prelink(Class<?> caller, int maxSites) {
    return prelink(caller, 0, maxSites);
  }

  /**
   * Processes a stable site range; advance startSite by the returned count for bounded batches.
   * @param caller class whose generated sites should be prepared
   * @param startSite first site in the stable generated ordering
   * @param maxSites maximum number of sites in this batch
   * @return processed site count, or zero if no generated entry or no remaining site exists
   * @throws IllegalArgumentException if either range argument is negative
   */
  public static int prelink(Class<?> caller, int startSite, int maxSites) {
    if (startSite < 0) throw new IllegalArgumentException("startSite must be non-negative");
    if (maxSites < 0) throw new IllegalArgumentException("maxSites must be non-negative");
    if (maxSites == 0) return 0;
    try {
      Method method = caller.getDeclaredMethod("$obfaryn$prelink", int.class, int.class);
      method.setAccessible(true);
      return (Integer) method.invoke(null, startSite, maxSites);
    } catch (NoSuchMethodException absent) {
      return 0;
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof Error) throw (Error) cause;
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      throw new IllegalStateException("Call prelink failed", cause);
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("Cannot access generated prelink entry", failure);
    }
  }
}
