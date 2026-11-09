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
 * Per-call overhead of one transformation pass against the same, untransformed method. Each pair
 * below targets the same source method in {@code bench.Workload}, so the delta between
 * {@code *Original} and its obfuscated counterpart is that one pass's isolated steady-state cost,
 * not a whole tick workload or VM-dispatch comparison (both already covered by
 * {@code scripts/performance/run.py} and {@code virtualization.py}; see docs/performance.md).
 *
 * {@code invokeExact} (not reflection) is used so the measured bulk of the cost is the target
 * method itself once JIT-compiled, not the call mechanism; see {@link ReflectionBenchmark} for the
 * reflection-vs-direct-call comparison instead.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PassOverheadBenchmark {
  private MethodHandle baselineCallHandle;
  private MethodHandle stringOriginalHandle;
  private MethodHandle stringEncryptionHandle;
  private MethodHandle numberOriginalHandle;
  private MethodHandle numberObfuscationHandle;
  private MethodHandle controlFlowOriginalHandle;
  private MethodHandle controlFlowHandle;
  private MethodHandle callOriginalHandle;
  private MethodHandle callIndirectionHandle;
  private MethodHandle prelinkHandle;

  @Setup(Level.Trial)
  public void setUp() throws Throwable {
    ObfuscatedFixtures.Fixture fixture = ObfuscatedFixtures.build();
    baselineCallHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "plain");
    stringOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "stringWork");
    stringEncryptionHandle = ObfuscatedFixtures.handle(fixture.getBuilds().get("stringEncryption"), "stringWork");
    numberOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "numberWork");
    numberObfuscationHandle = ObfuscatedFixtures.handle(fixture.getBuilds().get("numberObfuscation"), "numberWork");
    controlFlowOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "controlFlowWork");
    controlFlowHandle = ObfuscatedFixtures.handle(fixture.getBuilds().get("controlFlow"), "controlFlowWork");
    callOriginalHandle = ObfuscatedFixtures.handle(fixture.getBaseline(), "callWork");
    callIndirectionHandle = ObfuscatedFixtures.handle(fixture.getBuilds().get("callIndirection"), "callWork");
    prelinkHandle = ObfuscatedFixtures.handle(fixture.getBuilds().get("prelink"), "callWork");
  }

  @Benchmark public void baselineMethodCall(Blackhole bh) throws Throwable { bh.consume((int) baselineCallHandle.invokeExact(41)); }

  @Benchmark public void stringOriginal(Blackhole bh) throws Throwable { bh.consume((String) stringOriginalHandle.invokeExact(41)); }
  @Benchmark public void stringEncryption(Blackhole bh) throws Throwable { bh.consume((String) stringEncryptionHandle.invokeExact(41)); }

  @Benchmark public void numberOriginal(Blackhole bh) throws Throwable { bh.consume((int) numberOriginalHandle.invokeExact(41)); }
  @Benchmark public void numberObfuscation(Blackhole bh) throws Throwable { bh.consume((int) numberObfuscationHandle.invokeExact(41)); }

  @Benchmark public void controlFlowOriginal(Blackhole bh) throws Throwable { bh.consume((int) controlFlowOriginalHandle.invokeExact(41)); }
  @Benchmark public void controlFlow(Blackhole bh) throws Throwable { bh.consume((int) controlFlowHandle.invokeExact(41)); }

  @Benchmark public void callOriginal(Blackhole bh) throws Throwable { bh.consume((int) callOriginalHandle.invokeExact(41)); }
  @Benchmark public void callIndirection(Blackhole bh) throws Throwable { bh.consume((int) callIndirectionHandle.invokeExact(41)); }
  @Benchmark public void prelink(Blackhole bh) throws Throwable { bh.consume((int) prelinkHandle.invokeExact(41)); }
}
