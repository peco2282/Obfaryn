#!/usr/bin/env python3
"""Verify module-info exports/opens/uses/provides/main-class rewriting end to end,
then run the output JAR as a named module on the module path.
Run ./gradlew :obfaryn-cli:installDist first. Requires JDK 21 and Python 3.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / 'obfaryn-cli/build/install/obfaryn'
LAUNCHER = DIST / 'bin' / 'obfaryn'


def run(args, **kwargs):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f'Command failed: {args}\n{result.stdout}{result.stderr}')
    return result.stdout.strip()


def main():
    if not LAUNCHER.is_file():
        raise RuntimeError('Run :obfaryn-cli:installDist first')
    with tempfile.TemporaryDirectory(prefix='obfaryn-jpms-') as temporary:
        work = Path(temporary)
        src = work / 'src'
        (src / 'app').mkdir(parents=True)
        (src / 'app' / 'impl').mkdir(parents=True)
        (src / 'module-info.java').write_text('''module test.app {
  exports app;
  opens app;
  uses app.Greeter;
  provides app.Greeter with app.impl.GreeterImpl;
}
''', encoding='utf-8')
        (src / 'app' / 'Greeter.java').write_text('''package app;
public interface Greeter { String greet(); }
''', encoding='utf-8')
        (src / 'app' / 'impl' / 'GreeterImpl.java').write_text('''package app.impl;
import app.Greeter;
public class GreeterImpl implements Greeter {
  public String greet() { return "hi"; }
}
''', encoding='utf-8')
        (src / 'app' / 'Main.java').write_text('''package app;
import app.impl.GreeterImpl;
public class Main {
  public static void main(String[] args) {
    Greeter greeter = new GreeterImpl();
    System.out.println(greeter.greet());
  }
}
''', encoding='utf-8')
        classes = work / 'classes'
        run(['javac', '-d', classes,
             str(src / 'module-info.java'), str(src / 'app' / 'Greeter.java'),
             str(src / 'app' / 'impl' / 'GreeterImpl.java'), str(src / 'app' / 'Main.java')])
        original = work / 'input.jar'
        run(['jar', '--create', '--file', original, '--main-class', 'app.Main', '-C', classes, '.'])
        output = work / 'output.jar'
        run([LAUNCHER, original, '-o', output, '--application-package', 'app',
             '--flatten-packages', '--target-package', 'flat',
             '--no-string-encryption', '--no-number-obfuscation', '--verify-output'])

        described = run(['jar', '--file', output, '--describe-module'])
        # app.impl fully relocated to flat -> its concealed-package entry and the provider follow.
        if 'contains flat' not in described:
            raise AssertionError(f'Expected the relocated concealed package in the descriptor:\n{described}')
        if 'provides app.' not in described or ' with flat.' not in described:
            raise AssertionError(f'Expected the provider to follow its class rename:\n{described}')
        if 'exports app' not in described or 'opens app' not in described:
            raise AssertionError(f'Expected the pinned exports/opens to stay in place:\n{described}')

        # Classpath execution (ordinary deployment).
        classpath_result = run(['java', '-Xverify:all', '-cp', output, 'app.Main'])
        if classpath_result != 'hi':
            raise AssertionError(classpath_result)

        # Module-path execution: the rewritten descriptor must resolve as a real named module.
        module_result = run(['java', '-p', output, '-m', 'test.app'])
        if module_result != 'hi':
            raise AssertionError(module_result)

        print('JPMS module descriptor: exports/opens/concealed-package rewriting, provider tracking, '
              'classpath and module-path execution all passed')


if __name__ == '__main__':
    main()
