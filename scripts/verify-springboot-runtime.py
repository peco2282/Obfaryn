#!/usr/bin/env python3
"""Build, transform, and execute a real Spring Boot application.

This networked compatibility check resolves a pinned Spring Boot release through Gradle. It runs
the original and transformed plain application JARs with the same external dependency classpath;
the original application JAR is never present while the transformed application runs.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BOOT_VERSION = '4.1.2'
JAVA = Path(os.environ.get('JAVA_HOME', '')) / 'bin/java' if os.environ.get('JAVA_HOME') else Path('java')
CLI = ROOT / 'obfaryn-cli/build/install/obfaryn/bin/obfaryn'


def release_version():
    for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines():
        key, separator, value = line.partition('=')
        if separator and key.strip() == 'releaseVersion':
            return value.strip()
    return '0.0.0-SNAPSHOT'


def run(args, cwd=ROOT, timeout=180):
    result = subprocess.run(list(map(str, args)), cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'Command failed: {args}\n{result.stdout}{result.stderr}')
    return result.stdout.strip()


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')


def main():
    extension = (ROOT / 'platforms/springboot/build/libs' /
                 f'obfaryn-platform-springboot-{release_version()}.jar')
    if not CLI.is_file() or not extension.is_file():
        raise RuntimeError('Run :obfaryn-cli:installDist :platforms:springboot:jar first')

    with tempfile.TemporaryDirectory(prefix='obfaryn-springboot-runtime-') as temporary:
        work = Path(temporary)
        write(work / 'settings.gradle.kts', 'rootProject.name = "obfaryn-spring-runtime-fixture"\n')
        write(work / 'build.gradle.kts', f'''plugins {{ java }}
repositories {{ mavenCentral() }}
dependencies {{
  implementation(platform("org.springframework.boot:spring-boot-dependencies:{BOOT_VERSION}"))
  implementation("org.springframework.boot:spring-boot-starter")
}}
java {{ sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }}
tasks.withType<JavaCompile>().configureEach {{ options.release.set(17) }}
tasks.jar {{
  manifest {{
    attributes["Main-Class"] = "fixture.Application"
    attributes["Start-Class"] = "fixture.Application"
  }}
}}
tasks.register<Copy>("copyRuntimeClasspath") {{
  from(configurations.runtimeClasspath)
  into(layout.buildDirectory.dir("runtime"))
}}
''')
        write(work / 'src/main/java/fixture/Signal.java', '''package fixture;
public record Signal(String value) {}
''')
        write(work / 'src/main/java/fixture/AnswerService.java', '''package fixture;
import org.springframework.stereotype.Service;
@Service
public class AnswerService { public int answer() { return 42; } }
''')
        write(work / 'src/main/java/fixture/Settings.java', '''package fixture;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "fixture")
public class Settings {
  private int answer;
  public int getAnswer() { return answer; }
  public void setAnswer(int answer) { this.answer = answer; }
}
''')
        write(work / 'src/main/java/fixture/Probe.java', '''package fixture;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class Probe {
  private final AnswerService service;
  private final CountDownLatch scheduled = new CountDownLatch(1);
  private final CountDownLatch event = new CountDownLatch(1);
  public Probe(AnswerService service) { this.service = service; }
  @Scheduled(fixedDelay = 20) public void scheduled() { scheduled.countDown(); }
  @EventListener public void onSignal(Signal signal) {
    if ("event-ok".equals(signal.value())) event.countDown();
  }
  @Async public CompletableFuture<Integer> asyncAnswer() {
    return CompletableFuture.completedFuture(service.answer());
  }
  public CountDownLatch scheduledLatch() { return scheduled; }
  public CountDownLatch eventLatch() { return event; }
}
''')
        write(work / 'src/main/java/fixture/Application.java', '''package fixture;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(Settings.class)
public class Application {
  public static void main(String[] args) throws Exception {
    ConfigurableApplicationContext context = new SpringApplicationBuilder(Application.class)
      .web(WebApplicationType.NONE)
      .properties(Map.of("spring.main.banner-mode", "off", "logging.level.root", "OFF", "fixture.answer", "42"))
      .run(args);
    try {
      Probe probe = context.getBean(Probe.class);
      if (context.getBean(Settings.class).getAnswer() != 42) throw new AssertionError("configuration");
      context.publishEvent(new Signal("event-ok"));
      if (probe.asyncAnswer().get(10, TimeUnit.SECONDS) != 42) throw new AssertionError("async");
      if (!probe.scheduledLatch().await(10, TimeUnit.SECONDS)) throw new AssertionError("scheduled");
      if (!probe.eventLatch().await(10, TimeUnit.SECONDS)) throw new AssertionError("event");
      System.out.println("springboot-runtime-ok");
    } finally {
      context.close();
    }
  }
}
''')
        run([ROOT / 'gradlew', '-p', work, '--no-daemon', 'jar', 'copyRuntimeClasspath'], timeout=300)
        original = work / 'build/libs/obfaryn-spring-runtime-fixture.jar'
        dependencies = sorted((work / 'build/runtime').glob('*.jar'))
        classpath = os.pathsep.join(map(str, dependencies))
        original_result = run([JAVA, '-Xverify:all', '-cp', f'{original}{os.pathsep}{classpath}',
                               'fixture.Application'], work, timeout=60)
        if 'springboot-runtime-ok' not in original_result:
            raise AssertionError(original_result)

        output = work / 'build/transformed.jar'
        report = work / 'build/report.json'
        command = [
            CLI, original, '--output', output, '--extension', extension, '--platform', 'springboot',
            '--application-package', 'fixture', '--protection', 'strong', '--control-flow-mode', 'light',
            '--integer-arithmetic', '--call-indirection', '--verify-output', '--report', report,
            '--seed', '20261116',
        ]
        for dependency in dependencies:
            command.extend(['--classpath', dependency])
        run(command, work, timeout=300)
        transformed_result = run([JAVA, '-Xverify:all', '-cp', f'{output}{os.pathsep}{classpath}',
                                  'fixture.Application'], work, timeout=60)
        if 'springboot-runtime-ok' not in transformed_result:
            raise AssertionError(transformed_result)
        summary = json.loads(report.read_text(encoding='utf-8'))
        if summary.get('platform') != 'springboot':
            raise AssertionError(summary)

    print(f'Spring Boot {BOOT_VERSION}: DI, configuration binding, component scan, scheduled/event/async callbacks and transformed execution passed')


if __name__ == '__main__':
    main()
