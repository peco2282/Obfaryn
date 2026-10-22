import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Rewrite test references using the original build's mapping; never transform test bodies. */
class RemapTestClasses {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("Expected MAPPING.txt TEST_CLASSES OUTPUT_DIRECTORY");
    Map<String, String> classes = new HashMap<>(), methods = new HashMap<>(), fields = new HashMap<>();
    for (String line : Files.readAllLines(Path.of(args[0]))) {
      String[] pair = line.trim().split(" -> ", 2);
      if (pair.length != 2) continue;
      if (!line.startsWith("  ")) classes.put(pair[0], pair[1]);
      else if (pair[0].contains("(")) methods.put(pair[0], pair[1]);
      else fields.put(pair[0], pair[1]);
    }
    Remapper remapper = new Remapper() {
      @Override public String map(String name) { return classes.getOrDefault(name, name); }
      @Override public String mapMethodName(String owner, String name, String descriptor) {
        return methods.getOrDefault(owner + "." + name + descriptor, name);
      }
      @Override public String mapFieldName(String owner, String name, String descriptor) {
        return fields.getOrDefault(owner + "." + name + ":" + descriptor, name);
      }
      @Override public String mapInvokeDynamicMethodName(String name, String descriptor) {
        Type result = Type.getReturnType(descriptor);
        if (result.getSort() != Type.OBJECT) return name;
        String prefix = result.getInternalName() + "." + name + "(";
        var candidates = methods.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
          .map(Map.Entry::getValue).distinct().toList();
        return candidates.size() == 1 ? candidates.get(0) : name;
      }
    };
    Path input = Path.of(args[1]), output = Path.of(args[2]);
    int count = 0;
    try (var paths = Files.walk(input)) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
        ClassReader reader = new ClassReader(Files.readAllBytes(path));
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);
        Path target = output.resolve(remapper.mapType(reader.getClassName()) + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
        count++;
      }
    }
    System.out.println("Updated application references in " + count + " test classes");
  }
}
