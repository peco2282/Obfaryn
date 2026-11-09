package com.peco2282.obfaryn.benchmarks;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.TimeUnit;

/**
 * VM-virtualization overhead on instruction shapes beyond the dispatch-heavy/tiny-plumbing pair
 * {@code scripts/performance/virtualization.py} already isolates with multi-fork percentiles:
 * an ordinary method call, array read/write, allocation (boxing plus array/{@code Object[]}
 * construction) and exception handling (throw across a multi-catch). Every pass other than
 * virtualization is off in both the baseline and virtualized jar, matching that script's
 * methodology, so the only difference per pair is whether that one method is virtualized.
 *
 * This is a steady-state, JIT-warmed single-call view (JMH's purpose here), not a replacement for
 * that script's percentile/variance reporting across JVM forks; see docs/performance.md.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class VmShapeBenchmark {
  private MethodHandle plainOriginalHandle;
  private MethodHandle plainVirtualizedHandle;
  private MethodHandle arrayOriginalHandle;
  private MethodHandle arrayVirtualizedHandle;
  private MethodHandle allocOriginalHandle;
  private MethodHandle allocVirtualizedHandle;
  private MethodHandle exceptionOriginalHandle;
  private MethodHandle exceptionVirtualizedHandle;

  @Setup(Level.Trial)
  public void setUp() throws Throwable {
    ObfuscatedFixtures.Fixture fixture = ObfuscatedFixtures.build();
    java.nio.file.Path virtualized = fixture.getBuilds().get("virtualized");
    plainOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "plain");
    plainVirtualizedHandle = ObfuscatedFixtures.handle(virtualized, "plain");
    arrayOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "arrayWork");
    arrayVirtualizedHandle = ObfuscatedFixtures.handle(virtualized, "arrayWork");
    allocOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "allocWork");
    allocVirtualizedHandle = ObfuscatedFixtures.handle(virtualized, "allocWork");
    exceptionOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "exceptionWork");
    exceptionVirtualizedHandle = ObfuscatedFixtures.handle(virtualized, "exceptionWork");
  }

  @Benchmark public void methodCallOriginal(Blackhole bh) throws Throwable { bh.consume((int) plainOriginalHandle.invokeExact(41)); }
  @Benchmark public void methodCallVirtualized(Blackhole bh) throws Throwable { bh.consume((int) plainVirtualizedHandle.invokeExact(41)); }

  @Benchmark public void arrayOperationsOriginal(Blackhole bh) throws Throwable { bh.consume((int) arrayOriginalHandle.invokeExact(41)); }
  @Benchmark public void arrayOperationsVirtualized(Blackhole bh) throws Throwable { bh.consume((int) arrayVirtualizedHandle.invokeExact(41)); }

  @Benchmark public void objectAllocationOriginal(Blackhole bh) throws Throwable { bh.consume((int) allocOriginalHandle.invokeExact(41)); }
  @Benchmark public void objectAllocationVirtualized(Blackhole bh) throws Throwable { bh.consume((int) allocVirtualizedHandle.invokeExact(41)); }

  @Benchmark public void exceptionHandlingOriginal(Blackhole bh) throws Throwable { bh.consume((int) exceptionOriginalHandle.invokeExact(41)); }
  @Benchmark public void exceptionHandlingVirtualized(Blackhole bh) throws Throwable { bh.consume((int) exceptionVirtualizedHandle.invokeExact(41)); }

  // Exercises the throwing branch too: exception handling cost differs sharply by path.
  @Benchmark public void exceptionHandlingOriginalThrowing(Blackhole bh) throws Throwable { bh.consume((int) exceptionOriginalHandle.invokeExact(0)); }
  @Benchmark public void exceptionHandlingVirtualizedThrowing(Blackhole bh) throws Throwable { bh.consume((int) exceptionVirtualizedHandle.invokeExact(0)); }
}
