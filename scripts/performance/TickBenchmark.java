import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.lang.management.ManagementFactory;

/** JDK 21 launcher. Measures cold invocation separately, warm percentiles and thread allocations. */
class TickBenchmark {
  static volatile double sink;
  public static void main(String[] args) throws Throwable {
    int entities = Integer.parseInt(args[1]), warmup = Integer.parseInt(args[2]), ticks = Integer.parseInt(args[3]);
    if (entities < 1 || warmup < 1 || ticks < 100) throw new IllegalArgumentException("Invalid sample sizes");
    try (var loader = new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      Class<?> type = loader.loadClass("workload.TickWorkload");
      MethodHandle handle = MethodHandles.publicLookup().unreflect(type.getMethod("tick", double[].class, double[].class, int.class));
      double[] position = new double[entities], velocity = new double[entities];
      for (int i = 0; i < entities; i++) { position[i] = i % 199 - 99; velocity[i] = (i % 11 - 5) * 0.3; }
      long prepareBegin = System.nanoTime();
      int preparedSites = 0;
      if (args.length > 4 && Boolean.parseBoolean(args[4])) {
        Class<?> api = loader.loadClass("com.peco2282.obfaryn.runtime.ProtectionRuntime");
        preparedSites = (Integer) api.getMethod("prelink", Class.class, int.class).invoke(null, type, Integer.MAX_VALUE);
      }
      long prepareNs = preparedSites == 0 ? 0 : System.nanoTime() - prepareBegin;
      long coldBegin = System.nanoTime();
      sink = (double) handle.invokeExact(position, velocity, 0);
      long cold = System.nanoTime() - coldBegin;
      int actualWarmup = 0;
      long warmupMillis = args.length > 5 ? Long.parseLong(args[5]) : 1000;
      if (warmupMillis < 1) throw new IllegalArgumentException("warmup duration must be positive");
      long warmupUntil = System.nanoTime() + warmupMillis * 1000000;
      while (actualWarmup < warmup || System.nanoTime() < warmupUntil) {
        sink = (double) handle.invokeExact(position, velocity, actualWarmup++);
      }
      // Time-based warmup executes a different number of iterations per fork. Reset the inputs
      // so every measured phase performs identical work and produces a bit-identical checksum.
      for (int i = 0; i < entities; i++) position[i] = i % 199 - 99;
      long[] samples = new long[ticks];
      var rawBean = ManagementFactory.getThreadMXBean();
      var allocationBean = rawBean instanceof com.sun.management.ThreadMXBean ? (com.sun.management.ThreadMXBean) rawBean : null;
      boolean allocations = allocationBean != null && allocationBean.isThreadAllocatedMemorySupported();
      if (allocations && !allocationBean.isThreadAllocatedMemoryEnabled()) allocationBean.setThreadAllocatedMemoryEnabled(true);
      long thread = Thread.currentThread().threadId();
      long before = allocations ? allocationBean.getThreadAllocatedBytes(thread) : 0;
      for (int tick = 0; tick < ticks; tick++) {
        long begin = System.nanoTime();
        sink = (double) handle.invokeExact(position, velocity, tick);
        samples[tick] = System.nanoTime() - begin;
      }
      long bytes = allocations ? allocationBean.getThreadAllocatedBytes(thread) - before : -1;
      Arrays.sort(samples);
      System.out.printf(java.util.Locale.ROOT,
        "{\"warmupActualTicks\":%d,\"prelinkNs\":%d,\"prelinkedSites\":%d,\"coldNs\":%d,\"p50Ns\":%d,\"p95Ns\":%d,\"p99Ns\":%d,\"maxNs\":%d,\"allocatedBytesPerTick\":%.3f,\"checksum\":%.9f,\"checksumBits\":%d}%n",
        actualWarmup, prepareNs, preparedSites, cold, samples[ticks/2], samples[(int)(ticks*0.95)], samples[(int)(ticks*0.99)], samples[ticks-1],
        bytes < 0 ? -1.0 : bytes/(double)ticks, sink, Double.doubleToLongBits(sink));
    }
  }
}
