#!/usr/bin/env python3
"""Verify the external Spring Boot platform through the installed CLI.

Prepare with:
  bash gradlew :obfaryn-cli:installDist :platforms:springboot:jar

The fixture uses binary-compatible Spring annotation stubs so this check stays small and
offline. It verifies provider loading, platform policy, transformation, and execution of the
output JAR; it does not claim to start the Spring Framework itself.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'obfaryn-cli/build/install/obfaryn/bin/obfaryn'


def release_version():
    for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines():
        key, separator, value = line.partition('=')
        if separator and key.strip() == 'releaseVersion':
            return value.strip()
    return '0.0.0-SNAPSHOT'


def run(args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'Command failed: {args}\n{result.stdout}{result.stderr}')
    return result.stdout.strip()


def write_source(root, relative, source):
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding='utf-8')
    return path


def main():
    extension = (ROOT / 'platforms/springboot/build/libs' /
                 f'obfaryn-platform-springboot-{release_version()}.jar')
    if not CLI.is_file() or not extension.is_file():
        raise RuntimeError('Run :obfaryn-cli:installDist :platforms:springboot:jar first')

    with tempfile.TemporaryDirectory(prefix='obfaryn-springboot-extension-') as temporary:
        work = Path(temporary)
        sources = work / 'src'
        annotation = '''package %s;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.RUNTIME)
@Target(%s)
public @interface %s {}
'''
        files = [
            write_source(
                sources,
                'org/springframework/boot/autoconfigure/SpringBootApplication.java',
                annotation % ('org.springframework.boot.autoconfigure', 'ElementType.TYPE',
                              'SpringBootApplication'),
            ),
            write_source(
                sources,
                'org/springframework/scheduling/annotation/Scheduled.java',
                annotation % ('org.springframework.scheduling.annotation', 'ElementType.METHOD', 'Scheduled'),
            ),
            write_source(
                sources,
                'fixture/Tasks.java',
                '''package fixture;
import org.springframework.scheduling.annotation.Scheduled;
public final class Tasks {
  private int result;
  @Scheduled public void run() { result = compute(12); }
  private int compute(int value) { return value * 3 + 6; }
  public int result() { return result; }
  public static int unrelated(int value) { return value ^ 17; }
}
''',
            ),
            write_source(
                sources,
                'fixture/Application.java',
                '''package fixture;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public final class Application {
  public static void main(String[] args) {
    Tasks tasks = new Tasks();
    tasks.run();
    if (tasks.result() != 42) throw new AssertionError(tasks.result());
    System.out.println("springboot-extension-ok");
  }
}
''',
            ),
        ]
        classes = work / 'classes'
        run(['javac', '--release', '17', '-d', classes, *files])
        original = work / 'input.jar'
        manifest = ('Manifest-Version: 1.0\r\nMain-Class: fixture.Application\r\n'
                    'Start-Class: fixture.Application\r\n\r\n')
        with zipfile.ZipFile(original, 'w', zipfile.ZIP_DEFLATED) as jar:
            jar.writestr('META-INF/MANIFEST.MF', manifest)
            for file in sorted(classes.rglob('*.class')):
                jar.write(file, file.relative_to(classes))

        output = work / 'output.jar'
        analysis = work / 'virtualization.json'
        run([
            CLI, original, '--output', output, '--extension', extension, '--platform', 'springboot',
            '--application-package', 'fixture', '--protection', 'standard', '--control-flow-mode', 'none',
            '--no-string-encryption', '--no-number-obfuscation', '--no-string-constant-fields',
            '--no-number-constant-fields', '--no-rename-methods', '--no-rename-fields', '--verify-output',
            '--virtualize-method', r'fixture/.*\.(main|run|compute|unrelated).*',
            '--virtualization-mode', 'strict', '--virtualization-report', analysis, '--seed', '20261116',
        ])

        methods = {entry['identity']: entry for entry in json.loads(analysis.read_text(encoding='utf-8'))['methods']}
        expected_exclusions = {
            'fixture/Application.main([Ljava/lang/String;)V': 'spring boot application entrypoint',
            'fixture/Tasks.run()V': 'spring scheduled task',
            'fixture/Tasks.compute(I)I': 'spring hot callback dependency',
        }
        for identity, reason in expected_exclusions.items():
            entry = methods[identity]
            assert not entry['selected'] and entry['exclusionReason'] == reason, entry
        unrelated = methods['fixture/Tasks.unrelated(I)I']
        assert unrelated['selected'] and unrelated['eligible'] and unrelated['exclusionReason'] is None, unrelated
        assert run(['java', '-Xverify:all', '-jar', output]) == 'springboot-extension-ok'

    print('Spring Boot extension: provider loading, hot-path policy, transformation and output execution passed')


if __name__ == '__main__':
    main()
