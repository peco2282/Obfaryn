#!/usr/bin/env python3
"""Exercise the installed CLI and embedded linkage runtime using only distribution JARs.
Run ./gradlew :obfaryn-cli:installDist first. Requires JDK 21 and Python 3.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / 'obfaryn-cli/build/install/obfaryn'


def run(args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'Command failed: {args}\n{result.stdout}{result.stderr}')
    return result.stdout.strip()


def main():
    runtimes = list((DIST / 'lib').glob('obfaryn-runtime-*.jar'))
    if len(runtimes) != 1:
        raise RuntimeError('Expected one installed runtime JAR; run :obfaryn-cli:installDist')
    with tempfile.TemporaryDirectory(prefix='obfaryn-distribution-') as temp:
        work = Path(temp)
        source = work / 'Main.java'
        source.write_text('''package smoke;
import com.peco2282.obfaryn.runtime.ProtectionRuntime;
public class Main {
  private static int calls;
  static int calculate(int value) { calls++; return (value + 17) ^ 9; }
  public static void main(String[] args) {
    int sites = ProtectionRuntime.prelink(Main.class, 1000);
    if (sites <= 0 || calls != 0) throw new AssertionError("prelink: " + sites);
    int result = calculate(25);
    if (result != 35 || calls != 1) throw new AssertionError("execution: " + result);
    System.out.println("distribution-ok");
  }
}
''', encoding='utf-8')
        classes = work / 'classes'
        classes.mkdir()
        run(['javac', '--release', '8', '-cp', runtimes[0], '-d', classes, source])
        original = work / 'input.jar'
        protected = work / 'protected.jar'
        run(['jar', '--create', '--file', original, '--main-class', 'smoke.Main', '-C', classes, '.'])
        launcher = DIST / 'bin' / ('obfaryn.bat' if os.name == 'nt' else 'obfaryn')
        run([launcher, original, '--output', protected, '--platform', 'generic',
             '--application-package', 'smoke', '--keep', 'smoke.**',
             '--call-indirection', '--prelink-calls', '--integer-arithmetic',
             '--control-flow-mode', 'light', '--verify-output', '--seed', '20261023',
             '--classpath', runtimes[0]])
        # No original classes, runtime JAR or engine on the application classpath.
        actual = run(['java', '-Xverify:all', '-jar', protected])
        if actual != 'distribution-ok':
            raise AssertionError(actual)
        print('Installed CLI: transformation, embedded prelink, target behavior and JVM verification passed')


if __name__ == '__main__':
    main()
