#!/usr/bin/env python3
"""Interpreter-dispatch microbenchmark: a virtualized method vs. the original, isolated.

Both jars run through the obfuscation engine with every ordinary pass disabled; the only difference
is that the target method is virtualized in one and left as plain bytecode in the other. The timed
method runs a fixed, dispatch-heavy integer loop, so the measured delta reflects VM instruction
dispatch/decode cost, not invocation plumbing or unrelated transforms. Synthetic and host-sensitive,
like scripts/performance/run.py -- run it apart from builds/tests and treat small deltas as noise.
"""
import argparse
import hashlib
import json
import os
import platform
import statistics
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent

FIXTURE = """
package bench;
public final class Workload {
  private Workload() {}
  // Dispatch-heavy, branch-containing integer loop; cost dominated by VM dispatch/decode.
  public static int hot(int seed) {
    int acc = seed;
    for (int i = 1; i <= 96; i++) {
      acc = acc * 1103515245 + 12345;
      acc ^= acc >>> 7;
      acc += (acc & 15) - (acc % 7);
      if ((acc & 1) == 0) acc -= i; else acc += i;
    }
    return acc;
  }
  // A few operations only; cost dominated by per-invocation plumbing, not dispatch.
  public static int tiny(int a) { return (a * 3 + 1) ^ (a >>> 1); }
}
"""

METHODS = ['hot', 'tiny']


def execute(args, log=None):
  result = subprocess.run(list(map(str, args)), cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
  if log:
    log.write_text(result.stdout)
  if result.returncode:
    raise RuntimeError(result.stdout[-8000:])
  return result.stdout


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('--forks', type=int, default=3)
  parser.add_argument('--warmup', type=int, default=200000)
  parser.add_argument('--measured', type=int, default=200000)
  parser.add_argument('--warmup-ms', type=int, default=1500)
  parser.add_argument('--max-p99-ratio', type=float, help='Optional local gate (virtual/original p99); host-sensitive')
  parser.add_argument('--output', type=Path, default=ROOT / 'build/performance-vm')
  args = parser.parse_args()
  if args.forks < 1 or args.warmup < 1 or args.measured < 100 or args.warmup_ms < 1:
    parser.error('Invalid sample sizes')
  out = args.output.resolve()
  out.mkdir(parents=True, exist_ok=True)

  execute([ROOT / 'gradlew', ':obfaryn-cli:installDist'], out / 'tool-build.log')
  libs = sorted((ROOT / 'obfaryn-cli/build/install/obfaryn/lib').glob('*.jar'))
  cli = os.pathsep.join(map(str, libs))

  classes = out / 'classes'
  classes.mkdir(exist_ok=True)
  source = out / 'bench/Workload.java'
  source.parent.mkdir(exist_ok=True)
  source.write_text(FIXTURE)
  execute(['javac', '--release', '8', '-d', classes, source])
  compiled = out / 'compiled.jar'
  with zipfile.ZipFile(compiled, 'w', zipfile.ZIP_DEFLATED) as jar:
    for file in sorted(classes.rglob('*.class')):
      jar.write(file, file.relative_to(classes))

  # Every ordinary pass off in both runs; the sole difference is virtualization of hot.
  off = ['--protection', 'standard', '--control-flow-mode', 'none',
         '--no-string-encryption', '--no-number-obfuscation', '--no-string-constant-fields',
         '--no-number-constant-fields', '--no-rename', '--keep', 'bench.**',
         '--application-package', 'bench', '--seed', '20261109']

  def obfuscate(name, extra):
    output = out / f'{name}.jar'
    execute(['java', '-cp', cli, 'com.peco2282.obfaryn.cli.MainKt', compiled, '--output', output,
             '--verify-output'] + off + extra, out / f'{name}-obfuscate.log')
    return output

  jars = {
    'original': obfuscate('original', []),
    'virtual': obfuscate('virtual', ['--virtualize-method', r'bench/Workload\.(hot|tiny)\(I\)I']),
  }

  # results[method][jar] = [per-fork samples]
  results = {method: {key: [] for key in jars} for method in METHODS}
  for fork in range(args.forks):
    order = list(jars) if fork % 2 == 0 else list(reversed(jars))
    for method in METHODS:
      for key in order:
        raw = execute(['java', '-Xmx512m', HERE / 'VmBenchmark.java', jars[key],
                       args.warmup, args.measured, args.warmup_ms, method], out / f'{method}-{key}-{fork}.json')
        sample = json.loads(raw)
        results[method][key].append(sample)
        print(method, key, fork, sample, flush=True)

  medians, ratios = {}, {}
  for method in METHODS:
    if len({v['checksum'] for values in results[method].values() for v in values}) != 1:
      raise RuntimeError(f'Workload behavior changed between original and virtualized output for {method}')
    medians[method] = {key: {field: (values[0][field] if field in ('checksum', 'sink') else statistics.median(v[field] for v in values))
                             for field in values[0]} for key, values in results[method].items()}
    ratios[method] = {field: medians[method]['virtual'][field] / medians[method]['original'][field]
                      for field in ['p50Ns', 'p95Ns', 'p99Ns']}

  report = {'schemaVersion': 2, 'java': execute(['java', '-version']).strip(), 'host': platform.platform(),
            'fixtureSha256': hashlib.sha256(FIXTURE.encode()).hexdigest(), 'synthetic': True, 'methods': METHODS,
            'warmupCalls': args.warmup, 'measuredCalls': args.measured, 'minimumWarmupMs': args.warmup_ms,
            'forks': results, 'medians': medians, 'virtualOverOriginal': ratios}
  (out / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
  print(json.dumps({'medians': medians, 'virtualOverOriginal': ratios}, indent=2))

  if args.max_p99_ratio is not None and ratios['hot']['p99Ns'] > args.max_p99_ratio:
    raise SystemExit('virtualized p99 dispatch gate failed')


if __name__ == '__main__':
  main()
