import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** JDK 21 source launcher: java -Xverify:all VerifyClasses.java INPUT.jar CLASSPATH.txt PACKAGE */
class VerifyClasses {
  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("Expected INPUT.jar CLASSPATH.txt PACKAGE");
    List<URL> urls = new ArrayList<>();
    urls.add(Path.of(args[0]).toUri().toURL());
    for (String line : Files.readAllLines(Path.of(args[1]))) {
      if (!line.trim().isEmpty()) urls.add(Path.of(line.trim()).toUri().toURL());
    }
    String prefix = args[2].replace('.', '/') + "/";
    int checked = 0, failed = 0;
    try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
         JarFile jar = new JarFile(args[0])) {
      List<String> classes = jar.stream().map(entry -> entry.getName())
        .filter(name -> name.startsWith(prefix) && name.endsWith(".class")).sorted().toList();
      for (String entry : classes) {
        String name = entry.substring(0, entry.length() - 6).replace('/', '.');
        try {
          Class<?> type = Class.forName(name, false, loader);
          type.getDeclaredMethods();
          type.getDeclaredConstructors();
          type.getDeclaredFields();
          checked++;
        } catch (LinkageError | ReflectiveOperationException error) {
          failed++;
          System.out.println("FAIL " + name + ": " + error);
        }
      }
    }
    System.out.println("Verified " + checked + " classes without initialization; failures: " + failed);
    if (failed > 0) throw new IllegalStateException("JVM class verification failed");
  }
}
