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
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Baseline call-mechanism costs: a statically-compiled direct call, a bound
 * {@link MethodHandle#invokeExact} (what {@link PassOverheadBenchmark} uses throughout), and
 * {@link Method#invoke}, all computing the same thing. {@code bench.Workload} is loaded in an
 * isolated classloader exactly as {@link PassOverheadBenchmark} loads it, so this is not special
 * cased to being unobfuscated; it is the same fixture's {@code plain} method, used here as the
 * reference point for interpreting the MethodHandle-based numbers elsewhere in this module.
 * Reflective calls into a renamed/obfuscated target work the same way once {@code
 * ReflectionScanner} has kept the name (see docs/protection.md); this benchmark measures only the
 * call mechanism's own cost, not target reachability (Phase 1's differential coverage).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ReflectionBenchmark {
  private MethodHandle handle;
  private Method reflective;

  @Setup(Level.Trial)
  public void setUp() throws Throwable {
    ObfuscatedFixtures.Fixture fixture = ObfuscatedFixtures.build();
    handle = ObfuscatedFixtures.handle(fixture.getBaseline(), "plain");
    reflective = ObfuscatedFixtures.method(fixture.getBaseline(), "plain");
  }

  @Benchmark public void directCall(Blackhole bh) { bh.consume(directPlain(41)); }
  @Benchmark public void methodHandleInvokeExact(Blackhole bh) throws Throwable { bh.consume((int) handle.invokeExact(41)); }
  @Benchmark public void reflectiveInvoke(Blackhole bh) throws Throwable { bh.consume((int) (Integer) reflective.invoke(null, 41)); }

  // A statically-compiled copy of bench.Workload.plain's logic: ordinary JVM invocation cost with
  // no classloading/reflection in the measured loop at all, as the floor the others are read against.
  private static int directPlain(int x) { return x * 2 + 1; }
}
