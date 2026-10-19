package com.peco2282.obfaryn.platform.paper.cache;

import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;

/** Runtime storage for cache factories, without globally retaining their classes. */
public final class PaperCaches {
  private static final ClassValue<Holder> HOLDERS = new CacheHolders();

  private PaperCaches() {
  }

  /**
   * Returns the factory's cached result, including {@code null}. Construction and
   * computation failures are not cached. Recursive initialization of the same factory
   * on the initializing thread fails with {@link IllegalStateException}.
   */
  @SuppressWarnings("unchecked")
  public static <T> T get(Class<? extends Supplier<T>> factoryType) {
    return (T) HOLDERS.get(factoryType).get(factoryType);
  }

  /**
   * Removes the installed holder. Readers already using it may still return its old
   * result; subsequent readers initialize a new holder.
   */
  public static void invalidate(Class<?> factoryType) {
    HOLDERS.remove(factoryType);
  }

  private static final class CacheHolders extends ClassValue<Holder> {
    CacheHolders() {
    }

    @Override
    protected Holder computeValue(Class<?> type) {
      return new Holder();
    }
  }

  private static final class Holder {
    private volatile boolean initialized;
    private boolean initializing;
    private Object value;

    Holder() {
    }

    Object get(Class<? extends Supplier<?>> factoryType) {
      if (!initialized) {
        synchronized (this) {
          if (!initialized) {
            if (initializing) {
              throw new IllegalStateException("Recursive cache initialization: " + factoryType.getName());
            }
            initializing = true;
            try {
              value = newFactory(factoryType).get();
              initialized = true;
            } finally {
              initializing = false;
            }
          }
        }
      }
      return value;
    }

    private static Supplier<?> newFactory(Class<? extends Supplier<?>> factoryType) {
      try {
        return factoryType.getConstructor().newInstance();
      } catch (InvocationTargetException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException) cause;
        }
        if (cause instanceof Error) {
          throw (Error) cause;
        }
        throw new IllegalStateException("Cache factory constructor failed: " + factoryType.getName(), cause);
      } catch (ReflectiveOperationException | SecurityException exception) {
        throw new IllegalStateException("Cannot construct cache factory: " + factoryType.getName(), exception);
      }
    }
  }
}