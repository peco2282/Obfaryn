#!/usr/bin/env python3
"""Execute one obfuscated artifact across multiple installed JDKs.

Obfaryn itself is built and run here with one fixed "build JDK" (whatever JDK invokes this
script's `./gradlew`/`java` calls); that is a separate question from which JDKs can *run* the
JAR this engine produces. This script keeps that distinction explicit: it transforms a small,
Java-8-bytecode fixture exactly once, then launches the resulting JAR with every JDK found under
`~/.sdkman/candidates/java/<id>/bin/java` (or explicit `--jdk ID=PATH` entries), each in its own
process, and records which ones ran it correctly.

This validates runtime execution across JDKs, not Obfaryn's own build requirements (JDK 21, see
AGENTS.md) and not every supported JVM vendor/OS combination -- only whatever this host has
installed. A JDK missing from the host is reported as "not available", not as a failure.
"""
import argparse
import json
import os
import platform
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDKMAN_JAVA = Path.home() / '.sdkman/candidates/java'

# Each candidate's SDKMAN identifier tells us its JDK feature version; this script does not try
# to parse `java -version` output (format varies too much across vendors) when a name is enough.
DEFAULT_CANDIDATES = {
  '8': '8.0.452-tem',
  '11': '11.0.28-tem',
  '17': '17.0.21.0+9-tem',
  '21': '21.0.12+1.1-tem',
  '25': '25-tem',
}


def run(args, cwd=ROOT, check=True, env=None):
  result = subprocess.run(list(map(str, args)), cwd=cwd, text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
  if check and result.returncode:
    raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, args))}\n{result.stdout[-6000:]}")
  return result.returncode, result.stdout


def discover_jdks(explicit):
  jdks = {}
  for entry in explicit:
    label, _, path = entry.partition('=')
    jdks[label] = Path(path)
  if explicit:
    return jdks
  for label, candidate in DEFAULT_CANDIDATES.items():
    java = SDKMAN_JAVA / candidate / 'bin' / 'java'
    if java.is_file():
      jdks[label] = java
  return jdks


def build_cli():
  run([ROOT / 'gradlew', ':obfaryn-cli:installDist'])
  jars = sorted((ROOT / 'obfaryn-cli/build/install/obfaryn/lib').glob('*.jar'))
  return os.pathsep.join(map(str, jars))


def compile_and_obfuscate(cli, work: Path):
  source = work / 'src/matrix/Fixture.java'
  source.parent.mkdir(parents=True, exist_ok=True)
  source.write_text('''package matrix;
import java.util.ArrayList;
import java.util.List;
public final class Fixture {
  private Fixture() {}
  interface Op { int apply(int a, int b); }
  static int calc(int a, int b) {
    List<Integer> values = new ArrayList<Integer>();
    for (int i = 0; i < 5; i++) values.add(a + i);
    int sum = 0;
    for (int v : values) sum += v;
    Op add = new Op() { public int apply(int x, int y) { return x + y; } };
    try {
      if (b == 0) throw new ArithmeticException("zero");
      return sum / b + add.apply(a, b);
    } catch (ArithmeticException e) {
      return -1;
    }
  }
  public static void main(String[] args) {
    int a = Integer.parseInt(args[0]), b = Integer.parseInt(args[1]);
    System.out.println(calc(a, b));
  }
}
''', encoding='utf-8')
  classes = work / 'classes'
  classes.mkdir()
  run(['javac', '--release', '8', '-d', classes, source])
  original = work / 'input.jar'
  run(['jar', '--create', '--file', original, '--main-class', 'matrix.Fixture', '-C', classes, '.'])
  protected = work / 'protected.jar'
  run(['java', '-cp', cli, 'com.peco2282.obfaryn.cli.MainKt', original, '--output', protected,
       '--application-package', 'matrix', '--keep', 'matrix.**', '--platform', 'generic',
       '--protection', 'strong', '--call-indirection', '--prelink-calls', '--integer-arithmetic',
       '--control-flow-mode', 'light', '--verify-output', '--seed', '20261111'])
  return protected


def java_int_div(a, b):
  """Java's `/` on int truncates toward zero; Python's `//` floors -- these differ for mixed signs."""
  q = abs(a) // abs(b)
  return -q if (a < 0) != (b < 0) else q


def expected(a, b):
  s = sum(a + i for i in range(5))
  if b == 0:
    return -1
  return java_int_div(s, b) + (a + b)


def main():
  parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  parser.add_argument('--jdk', action='append', default=[], metavar='LABEL=PATH',
                       help='Explicit java launcher to test; repeatable. Default: auto-discover via SDKMAN.')
  parser.add_argument('--work-dir', type=Path, default=ROOT / 'build/jdk-matrix')
  parser.add_argument('--json', type=Path)
  args = parser.parse_args()
  work = args.work_dir.resolve()
  work.mkdir(parents=True, exist_ok=True)

  build_java_version = subprocess.run(['java', '-version'], text=True,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()
  cli = build_cli()
  protected = compile_and_obfuscate(cli, work)

  jdks = discover_jdks(args.jdk)
  if not jdks:
    raise SystemExit('No JDKs found under ~/.sdkman/candidates/java; pass --jdk LABEL=PATH explicitly')

  cases = [(1, 3), (10, 0), (-7, 4), (5, -2)]
  results = {}
  for label, java in sorted(jdks.items(), key=lambda kv: kv[0]):
    outcomes = []
    ok = True
    for a, b in cases:
      code, out = run([java, '-Xverify:all', '-jar', protected, a, b], check=False)
      actual = out.strip().splitlines()[-1] if out.strip() else ''
      want = str(expected(a, b))
      passed = code == 0 and actual == want
      outcomes.append({'args': [a, b], 'expected': want, 'actual': actual, 'exitCode': code, 'passed': passed})
      ok = ok and passed
    results[label] = {'javaLauncher': str(java), 'passed': ok, 'cases': outcomes}
    print(f"JDK {label}: {'PASS' if ok else 'FAIL'}", flush=True)

  report = {
    'schemaVersion': 1,
    'buildJavaVersion': build_java_version,
    'os': platform.platform(),
    'resultsByJdk': results,
    'allPassed': all(r['passed'] for r in results.values()),
  }
  if args.json:
    args.json.write_text(json.dumps(report, indent=2) + '\n')
  if not report['allPassed']:
    failed = [label for label, r in results.items() if not r['passed']]
    raise SystemExit(f"JDK(s) failed to run the obfuscated artifact correctly: {failed}")
  print(f"All {len(results)} discovered JDKs ({', '.join(sorted(results))}) ran the obfuscated artifact correctly.")


if __name__ == '__main__':
  main()
