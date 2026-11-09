import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * JDK 21 launcher measuring interpreter-dispatch cost of a virtualized method against the original.
 * The measured {@code bench.Workload} method is selected by args[4] (default {@code hot}): {@code hot}
 * runs a fixed, dispatch-heavy integer loop (cost dominated by VM instruction dispatch/decode), while
 * {@code tiny} does a few operations so its cost is dominated by per-invocation plumbing (frame
 * acquire/release, ThreadLocal, argument marshalling, return boxing). Reports warm latency
 * percentiles, cold first call, and per-call thread allocation. Dead-code elimination is blocked by
 * folding every result into a volatile sink and a checksum compared across jars.
 */
class VmBenchmark {
  static volatile int sink;
  public static void main(String[] args) throws Throwable {
    int warmup = Integer.parseInt(args[1]), measured = Integer.parseInt(args[2]);
    long warmupMillis = args.length > 3 ? Long.parseLong(args[3]) : 1000;
    String method = args.length > 4 ? args[4] : "hot";
    if (warmup < 1 || measured < 100 || warmupMillis < 1) throw new IllegalArgumentException("Invalid sample sizes");
    try (var loader = new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      Class<?> type = loader.loadClass("bench.Workload");
      MethodHandle hot = MethodHandles.publicLookup().unreflect(type.getMethod(method, int.class));

      long coldBegin = System.nanoTime();
      sink = (int) hot.invokeExact(1);
      long cold = System.nanoTime() - coldBegin;

      int actualWarmup = 0;
      long warmupUntil = System.nanoTime() + warmupMillis * 1000000L;
      int acc = 0;
      while (actualWarmup < warmup || System.nanoTime() < warmupUntil) {
        acc ^= (int) hot.invokeExact(actualWarmup++ | 1);
      }
      sink = acc;

      long[] samples = new long[measured];
      var rawBean = ManagementFactory.getThreadMXBean();
      var allocationBean = rawBean instanceof com.sun.management.ThreadMXBean ? (com.sun.management.ThreadMXBean) rawBean : null;
      boolean allocations = allocationBean != null && allocationBean.isThreadAllocatedMemorySupported();
      if (allocations && !allocationBean.isThreadAllocatedMemoryEnabled()) allocationBean.setThreadAllocatedMemoryEnabled(true);
      long thread = Thread.currentThread().threadId();

      // Fixed-argument checksum so every jar performs identical work and must agree bit-for-bit.
      int checksum = 0;
      for (int i = 1; i <= measured; i++) checksum ^= (int) hot.invokeExact(i);

      long before = allocations ? allocationBean.getThreadAllocatedBytes(thread) : 0;
      acc = 0;
      for (int i = 0; i < measured; i++) {
        int arg = (i | 1);
        long begin = System.nanoTime();
        acc ^= (int) hot.invokeExact(arg);
        samples[i] = System.nanoTime() - begin;
      }
      long bytes = allocations ? allocationBean.getThreadAllocatedBytes(thread) - before : -1;
      sink = acc;

      Arrays.sort(samples);
      System.out.printf(java.util.Locale.ROOT,
        "{\"warmupActualCalls\":%d,\"coldNs\":%d,\"p50Ns\":%d,\"p95Ns\":%d,\"p99Ns\":%d,\"maxNs\":%d,\"allocatedBytesPerCall\":%.3f,\"checksum\":%d,\"sink\":%d}%n",
        actualWarmup, cold, samples[measured/2], samples[(int)(measured*0.95)], samples[(int)(measured*0.99)], samples[measured-1],
        bytes < 0 ? -1.0 : bytes/(double)measured, checksum, sink);
    }
  }
}
