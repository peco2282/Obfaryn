package com.peco2282.obfaryn.platform.paper.cache;

import com.peco2282.obfaryn.platform.paper.annotation.PaperCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class PaperCachesTest {
  @Test
  void reusesResultAndOnlyConstructsOnMiss() {
    PaperCaches.invalidate(ValueFactory.class);
    ValueFactory.constructions.set(0);
    ValueFactory.calls.set(0);

    Object value = PaperCaches.get(ValueFactory.class);

    assertSame(value, PaperCaches.get(ValueFactory.class));
    assertEquals(1, ValueFactory.constructions.get());
    assertEquals(1, ValueFactory.calls.get());
  }

  @Test
  void cachesNull() {
    PaperCaches.invalidate(NullFactory.class);
    NullFactory.calls.set(0);

    assertNull(PaperCaches.get(NullFactory.class));
    assertNull(PaperCaches.get(NullFactory.class));
    assertEquals(1, NullFactory.calls.get());
  }

  @Test
  void retriesSupplierFailureWithoutWrappingIt() {
    PaperCaches.invalidate(RetryFactory.class);
    RetryFactory.constructions.set(0);

    assertSame(RetryFactory.failure,
        assertThrows(IllegalArgumentException.class, () -> PaperCaches.get(RetryFactory.class)));
    assertEquals("recovered", PaperCaches.get(RetryFactory.class));
    assertEquals("recovered", PaperCaches.get(RetryFactory.class));
    assertEquals(2, RetryFactory.constructions.get());
  }

  @Test
  void computesOnceUnderConcurrentAccess() throws Exception {
    PaperCaches.invalidate(ConcurrentFactory.class);
    ConcurrentFactory.constructions.set(0);
    ConcurrentFactory.calls.set(0);
    ConcurrentFactory.entered = new CountDownLatch(1);
    ConcurrentFactory.release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(8);
    CountDownLatch ready = new CountDownLatch(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++) {
        results.add(executor.submit(() -> {
          ready.countDown();
          await(start);
          return PaperCaches.get(ConcurrentFactory.class);
        }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(ConcurrentFactory.entered.await(5, TimeUnit.SECONDS));
      ConcurrentFactory.release.countDown();
      Object value = results.get(0).get(5, TimeUnit.SECONDS);
      for (Future<Object> result : results) {
        assertSame(value, result.get(5, TimeUnit.SECONDS));
      }
      assertEquals(1, ConcurrentFactory.constructions.get());
      assertEquals(1, ConcurrentFactory.calls.get());
    } finally {
      start.countDown();
      ConcurrentFactory.release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void invalidationRecomputesAndDoesNotAffectOtherFactories() {
    PaperCaches.invalidate(ValueFactory.class);
    PaperCaches.invalidate(NullFactory.class);
    ValueFactory.constructions.set(0);
    NullFactory.calls.set(0);
    Object oldValue = PaperCaches.get(ValueFactory.class);
    assertNull(PaperCaches.get(NullFactory.class));

    PaperCaches.invalidate(ValueFactory.class);
    Object newValue = PaperCaches.get(ValueFactory.class);

    assertNotSame(oldValue, newValue);
    assertSame(newValue, PaperCaches.get(ValueFactory.class));
    assertEquals(2, ValueFactory.constructions.get());
    assertNull(PaperCaches.get(NullFactory.class));
    assertEquals(1, NullFactory.calls.get());
    PaperCaches.invalidate(UnusedFactory.class);
  }

  @Test
  void invalidationAllowsRunningReaderToFinishWithOldResult() throws Exception {
    PaperCaches.invalidate(InvalidatedFactory.class);
    InvalidatedFactory.calls.set(0);
    InvalidatedFactory.entered = new CountDownLatch(1);
    InvalidatedFactory.release = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> oldResult = executor.submit(() -> PaperCaches.get(InvalidatedFactory.class));
      assertTrue(InvalidatedFactory.entered.await(5, TimeUnit.SECONDS));

      PaperCaches.invalidate(InvalidatedFactory.class);
      assertEquals(Integer.valueOf(2), PaperCaches.get(InvalidatedFactory.class));
      InvalidatedFactory.release.countDown();

      assertEquals(Integer.valueOf(1), oldResult.get(5, TimeUnit.SECONDS));
      assertEquals(Integer.valueOf(2), PaperCaches.get(InvalidatedFactory.class));
      assertEquals(2, InvalidatedFactory.calls.get());
    } finally {
      InvalidatedFactory.release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void recursionFailsFastAndLeavesHolderRetryable() {
    PaperCaches.invalidate(RecursiveFactory.class);
    RecursiveFactory.recurse = true;

    assertThrows(IllegalStateException.class, () -> PaperCaches.get(RecursiveFactory.class));
    RecursiveFactory.recurse = false;
    assertEquals("recovered", PaperCaches.get(RecursiveFactory.class));
  }

  @Test
  void constructorRecursionFailsFast() {
    PaperCaches.invalidate(RecursiveConstructorFactory.class);

    assertThrows(IllegalStateException.class, () -> PaperCaches.get(RecursiveConstructorFactory.class));
  }

  @Test
  void requiresPublicNoArgumentConstructor() {
    PaperCaches.invalidate(PrivateConstructorFactory.class);

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> PaperCaches.get(PrivateConstructorFactory.class));

    assertTrue(failure.getCause() instanceof NoSuchMethodException);
  }

  @Test
  void rejectsAbstractFactory() {
    PaperCaches.invalidate(AbstractFactory.class);

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> PaperCaches.get(AbstractFactory.class));

    assertTrue(failure.getCause() instanceof InstantiationException);
  }

  @Test
  void unwrapsConstructorRuntimeFailureAndRetries() {
    PaperCaches.invalidate(RuntimeConstructorFactory.class);
    RuntimeConstructorFactory.constructions.set(0);

    assertSame(RuntimeConstructorFactory.failure, assertThrows(IllegalArgumentException.class,
        () -> PaperCaches.get(RuntimeConstructorFactory.class)));
    assertEquals("recovered", PaperCaches.get(RuntimeConstructorFactory.class));
    assertEquals("recovered", PaperCaches.get(RuntimeConstructorFactory.class));
    assertEquals(2, RuntimeConstructorFactory.constructions.get());
  }

  @Test
  void unwrapsConstructorError() {
    PaperCaches.invalidate(ErrorConstructorFactory.class);

    assertSame(ErrorConstructorFactory.failure, assertThrows(AssertionError.class,
        () -> PaperCaches.get(ErrorConstructorFactory.class)));
  }

  @Test
  void wrapsCheckedConstructorFailureAndRetries() {
    PaperCaches.invalidate(CheckedConstructorFactory.class);
    CheckedConstructorFactory.constructions.set(0);

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> PaperCaches.get(CheckedConstructorFactory.class));

    assertSame(CheckedConstructorFactory.failure, failure.getCause());
    assertEquals("recovered", PaperCaches.get(CheckedConstructorFactory.class));
    assertEquals(2, CheckedConstructorFactory.constructions.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for cache test");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @PaperCache
  public static final class ValueFactory implements Supplier<Object> {
    static final AtomicInteger constructions = new AtomicInteger();
    static final AtomicInteger calls = new AtomicInteger();

    public ValueFactory() {
      constructions.incrementAndGet();
    }

    @Override
    public Object get() {
      calls.incrementAndGet();
      return new Object();
    }
  }

  @PaperCache
  public static final class NullFactory implements Supplier<Object> {
    static final AtomicInteger calls = new AtomicInteger();

    @Override
    public Object get() {
      calls.incrementAndGet();
      return null;
    }
  }

  @PaperCache
  public static final class RetryFactory implements Supplier<String> {
    static final AtomicInteger constructions = new AtomicInteger();
    static final IllegalArgumentException failure = new IllegalArgumentException("supplier failure");
    private final int attempt;

    public RetryFactory() {
      attempt = constructions.incrementAndGet();
    }

    @Override
    public String get() {
      if (attempt == 1) {
        throw failure;
      }
      return "recovered";
    }
  }

  @PaperCache
  public static final class ConcurrentFactory implements Supplier<Object> {
    static final AtomicInteger constructions = new AtomicInteger();
    static final AtomicInteger calls = new AtomicInteger();
    static CountDownLatch entered;
    static CountDownLatch release;

    public ConcurrentFactory() {
      constructions.incrementAndGet();
    }

    @Override
    public Object get() {
      calls.incrementAndGet();
      entered.countDown();
      await(release);
      return new Object();
    }
  }

  @PaperCache
  public static final class InvalidatedFactory implements Supplier<Integer> {
    static final AtomicInteger calls = new AtomicInteger();
    static CountDownLatch entered;
    static CountDownLatch release;

    @Override
    public Integer get() {
      int call = calls.incrementAndGet();
      if (call == 1) {
        entered.countDown();
        await(release);
      }
      return call;
    }
  }

  @PaperCache
  public static final class RecursiveFactory implements Supplier<String> {
    static boolean recurse;

    @Override
    public String get() {
      return recurse ? PaperCaches.get(RecursiveFactory.class) : "recovered";
    }
  }

  @PaperCache
  public static final class RecursiveConstructorFactory implements Supplier<String> {
    public RecursiveConstructorFactory() {
      PaperCaches.get(RecursiveConstructorFactory.class);
    }

    @Override
    public String get() {
      return "unreachable";
    }
  }

  public static final class PrivateConstructorFactory implements Supplier<String> {
    private PrivateConstructorFactory() {
    }

    @Override
    public String get() {
      return "unreachable";
    }
  }

  public abstract static class AbstractFactory implements Supplier<String> {
    public AbstractFactory() {
    }
  }

  @PaperCache
  public static final class RuntimeConstructorFactory implements Supplier<String> {
    static final AtomicInteger constructions = new AtomicInteger();
    static final IllegalArgumentException failure = new IllegalArgumentException("constructor failure");

    public RuntimeConstructorFactory() {
      if (constructions.incrementAndGet() == 1) {
        throw failure;
      }
    }

    @Override
    public String get() {
      return "recovered";
    }
  }

  @PaperCache
  public static final class ErrorConstructorFactory implements Supplier<String> {
    static final AssertionError failure = new AssertionError("constructor error");

    public ErrorConstructorFactory() {
      throw failure;
    }

    @Override
    public String get() {
      return "unreachable";
    }
  }

  @PaperCache
  public static final class CheckedConstructorFactory implements Supplier<String> {
    static final AtomicInteger constructions = new AtomicInteger();
    static final Exception failure = new Exception("checked constructor failure");

    public CheckedConstructorFactory() throws Exception {
      if (constructions.incrementAndGet() == 1) {
        throw failure;
      }
    }

    @Override
    public String get() {
      return "recovered";
    }
  }

  public static final class UnusedFactory implements Supplier<Object> {
    public UnusedFactory() {
      fail("Invalidating an unused factory must not instantiate it");
    }

    @Override
    public Object get() {
      throw new AssertionError("Invalidating an unused factory must not compute it");
    }
  }
}
