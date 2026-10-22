import com.peco2282.obfaryn.core.context.ObfuscationContext;
import com.peco2282.obfaryn.transformer.StringEncryptionTransformer;
import com.peco2282.obfaryn.transformer.XorStringEncryptionCodec;
import com.peco2282.obfaryn.transformer.RollingStringEncryptionCodec;
import com.peco2282.obfaryn.api.transformer.StringEncryptionCodec;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.*;

/** JDK 21 source launcher; run with the built CLI lib/* on the classpath. Not an application benchmark. */
class BenchmarkStringLiterals {
  static volatile String sink;
  static final String TEXT = "A representative Unicode literal: 日本語, permissions.example.read";
  static MethodHandle fixture(boolean cached, boolean rolling) throws Exception {
    var node = new ClassNode();
    node.version = Opcodes.V1_8;
    node.access = Opcodes.ACC_PUBLIC;
    node.name = cached ? "sample/CachedStrings" : "sample/UncachedStrings";
    node.superName = "java/lang/Object";
    var read = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()Ljava/lang/String;", null, null);
    read.instructions.add(new LdcInsnNode(TEXT));
    read.instructions.add(new InsnNode(Opcodes.ARETURN));
    read.maxStack = 1;
    node.methods.add(read);
    StringEncryptionCodec codec = rolling ? new RollingStringEncryptionCodec() : new XorStringEncryptionCodec();
    if (cached) {
      new StringEncryptionTransformer(new Random(1), codec).transform(
        new ObfuscationContext(new LinkedHashMap<>(Map.of(node.name, node)), new LinkedHashMap<>()));
    } else {
      var value = codec.encrypt(TEXT, 12345);
      read.instructions.clear();
      read.instructions.add(new LdcInsnNode(value.getPayload()));
      read.instructions.add(new LdcInsnNode(value.getKey()));
      read.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "decode", "(Ljava/lang/String;I)Ljava/lang/String;", false));
      read.instructions.add(new InsnNode(Opcodes.ARETURN));
      var decoder = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "decode", "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
      codec.generateDecryptor(node.name, decoder);
      node.methods.add(decoder);
    }
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    node.accept(writer);
    byte[] bytes = writer.toByteArray();
    Class<?> type = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
      Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
    }.define();
    return MethodHandles.publicLookup().findStatic(type, "read", MethodType.methodType(String.class));
  }
  static void loop(MethodHandle method, int count) throws Throwable {
    for (int i = 0; i < count; i++) sink = (String) method.invokeExact();
  }
  public static void main(String[] args) throws Throwable {
    int count = args.length == 0 ? 500_000 : Integer.parseInt(args[0]);
    if (count <= 0) throw new IllegalArgumentException("Iteration count must be positive");
    MethodHandle[] methods = { fixture(false, false), fixture(false, true), fixture(true, true) };
    for (var method : methods) {
      if (!TEXT.equals((String) method.invokeExact())) throw new AssertionError("Incorrect decoding");
      loop(method, 200_000);
    }
    var bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
    boolean allocations = bean != null && bean.isThreadAllocatedMemorySupported();
    if (allocations && !bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
    double[][] times = new double[3][5], bytes = new double[3][5];
    for (int round = 0; round < 5; round++) for (int offset = 0; offset < 3; offset++) {
      int variant = (round + offset) % 3;
      long beforeBytes = allocations ? bean.getCurrentThreadAllocatedBytes() : 0;
      long before = System.nanoTime();
      loop(methods[variant], count);
      times[variant][round] = (System.nanoTime() - before) / (double) count;
      bytes[variant][round] = allocations ? (bean.getCurrentThreadAllocatedBytes() - beforeBytes) / (double) count : Double.NaN;
    }
    System.out.println("JDK: " + System.getProperty("java.runtime.version"));
    System.out.println("Warm reads only; " + count + " iterations per round, 5 rounds, alternating order.");
    for (int i = 0; i < 3; i++) {
      Arrays.sort(times[i]); Arrays.sort(bytes[i]);
      System.out.printf(Locale.ROOT, "%s: median %.2f ns/read, %.2f allocated bytes/read%n",
        i == 0 ? "legacy XOR uncached" : i == 1 ? "rolling uncached" : "rolling cached", times[i][2], bytes[i][2]);
    }
    System.out.println("Excludes startup, cold misses, full transformation pipelines, and application workloads.");
  }
}
