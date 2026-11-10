#!/usr/bin/env python3
"""Resource cost of the obfuscation *process* itself: wall time, CPU time, peak RSS, GC activity.

The other scripts in this directory measure the *output* jar's runtime behavior (tick latency,
VM-dispatch cost). This one measures the engine's own footprint while it runs -- how long an
obfuscation invocation takes end to end, how much CPU it burns, how much memory it peaks at, and
how much garbage-collection work the JVM running it does. That is the figure a CI job's timeout or
a developer's build-machine sizing actually depends on, and it was previously unmeasured: the
per-pass `elapsedNanos` in the transformation report (see docs/performance.md, "Obfuscation-process
cost: per-pass elapsed time") only accounts for time spent inside `transform()` calls, not JVM
startup, classpath scanning, JAR I/O, or GC pauses.

Requires GNU time (`/usr/bin/time -v`, present on Linux; not on macOS's BSD time) for CPU/RSS, and
a JDK with unified logging (`-Xlog:gc`, JDK 9+) for GC counts and pause time. Synthetic and
host-sensitive, like run.py and virtualization.py -- run it apart from builds/tests.
"""
import argparse
import json
import os
import platform
import re
import statistics
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import environment  # noqa: E402

GNU_TIME = '/usr/bin/time'

# A moderately sized, varied synthetic application -- closer to a real plugin jar's class/method
# count than the single-class fixtures the other scripts use, since pass cost (rename mapping,
# call-graph analysis, control-flow rewriting) scales with class and method count, not loop
# iterations. Deterministic: same class bodies every run, so results are comparable run to run.
CLASS_TEMPLATE = """
package workload;
public final class C{index} {{
  private C{index}() {{}}
{methods}
}}
"""
METHOD_TEMPLATE = """
  public static int m{m}(int a, int b) {{
    int acc = a;
    for (int i = 0; i < {iterations}; i++) {{
      acc = acc * 31 + b - i;
      if ((acc & 1) == 0) {{ acc ^= C{callee}.m{callee_m}(acc, i); }} else {{ acc += i; }}
      try {{
        if (acc == 0) throw new IllegalStateException("zero");
      }} catch (IllegalStateException e) {{
        acc -= 1;
      }}
    }}
    return acc;
  }}
"""


def generate_sources(out_dir: Path, classes: int, methods_per_class: int) -> list[Path]:
  out_dir.mkdir(parents=True, exist_ok=True)
  paths = []
  for c in range(classes):
    methods = []
    for m in range(methods_per_class):
      callee = (c + 1) % classes
      callee_m = m % methods_per_class
      methods.append(METHOD_TEMPLATE.format(m=m, iterations=8, callee=callee, callee_m=callee_m))
    source = CLASS_TEMPLATE.format(index=c, methods=''.join(methods))
    path = out_dir / f'C{c}.java'
    path.write_text(source)
    paths.append(path)
  return paths


def execute(args, log=None, cwd=None):
  result = subprocess.run(list(map(str, args)), cwd=cwd or ROOT, text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
  if log:
    log.write_text(result.stdout)
  if result.returncode:
    raise RuntimeError(result.stdout[-8000:])
  return result.stdout


GC_PAUSE = re.compile(r'Pause\s+\S.*?\s(\d+(?:\.\d+)?)ms\s*$')
TIME_FIELDS = {
  'Elapsed (wall clock) time (h:mm:ss or m:ss)': 'wallClockRaw',
  'User time (seconds)': 'userSeconds',
  'System time (seconds)': 'systemSeconds',
  'Maximum resident set size (kbytes)': 'maxRssKb',
  'Major (requiring I/O) page faults': 'majorPageFaults',
  'Minor (reclaiming a frame) page faults': 'minorPageFaults',
  'Voluntary context switches': 'voluntaryContextSwitches',
  'Involuntary context switches': 'involuntaryContextSwitches',
}


def parse_wall_clock(raw: str) -> float:
  parts = raw.strip().split(':')
  parts = [float(p) for p in parts]
  seconds = 0.0
  for part in parts:
    seconds = seconds * 60 + part
  return seconds


def parse_time_v(text: str) -> dict:
  values = {}
  for line in text.splitlines():
    line = line.strip()
    for label, key in TIME_FIELDS.items():
      if line.startswith(label + ':'):
        raw = line[len(label) + 1:].strip()
        values[key] = parse_wall_clock(raw) if key == 'wallClockRaw' else float(raw)
  if 'wallClockRaw' in values:
    values['wallSeconds'] = values.pop('wallClockRaw')
  return values


def parse_gc_log(path: Path) -> dict:
  if not path.is_file():
    return {'gcCount': 0, 'gcPauseMillis': 0.0}
  count, total = 0, 0.0
  for line in path.read_text(errors='replace').splitlines():
    match = GC_PAUSE.search(line)
    if match:
      count += 1
      total += float(match.group(1))
  return {'gcCount': count, 'gcPauseMillis': round(total, 3)}


def main():
  parser = argparse.ArgumentParser(description=__doc__,
                                    formatter_class=argparse.RawDescriptionHelpFormatter)
  parser.add_argument('--forks', type=int, default=5)
  parser.add_argument('--classes', type=int, default=80)
  parser.add_argument('--methods-per-class', type=int, default=15)
  parser.add_argument('--heap', default='512m', help='-Xmx for the measured obfuscation JVM')
  parser.add_argument('--max-wall-seconds', type=float, help='Optional local gate; host-sensitive')
  parser.add_argument('--max-rss-mb', type=float, help='Optional local gate; host-sensitive')
  parser.add_argument('--output', type=Path, default=ROOT / 'build/performance-process')
  args = parser.parse_args()
  if args.forks < 1 or args.classes < 1 or args.methods_per_class < 1:
    parser.error('Invalid sample sizes')
  if not Path(GNU_TIME).is_file():
    parser.error(f'{GNU_TIME} not found; this script requires GNU time (Linux)')

  out = args.output.resolve()
  out.mkdir(parents=True, exist_ok=True)

  execute([ROOT / 'gradlew', ':obfaryn-cli:installDist'], out / 'tool-build.log')
  libs = sorted((ROOT / 'obfaryn-cli/build/install/obfaryn/lib').glob('*.jar'))
  cli = os.pathsep.join(map(str, libs))

  sources = out / 'sources'
  generate_sources(sources, args.classes, args.methods_per_class)
  classes_dir = out / 'classes'
  classes_dir.mkdir(exist_ok=True)
  execute(['javac', '--release', '8', '-d', classes_dir] + sorted(sources.glob('*.java')),
          out / 'compile.log')
  original = out / 'original.jar'
  with zipfile.ZipFile(original, 'w', zipfile.ZIP_DEFLATED) as jar:
    for file in sorted(classes_dir.rglob('*.class')):
      jar.write(file, file.relative_to(classes_dir))

  samples = []
  for fork in range(args.forks):
    protected = out / f'protected-{fork}.jar'
    gc_log = out / f'gc-{fork}.log'
    time_log = out / f'time-{fork}.log'
    cmd = [GNU_TIME, '-v', '-o', str(time_log), 'java', f'-Xmx{args.heap}',
           f'-Xlog:gc:file={gc_log}:time,uptime,level,tags',
           '-cp', cli, 'com.peco2282.obfaryn.cli.MainKt', original, '--output', protected,
           '--application-package', 'workload', '--keep', 'workload.**', '--protection', 'strong',
           '--lightweight-control-flow', '--call-indirection', '--integer-arithmetic',
           '--arithmetic-operations', '4', '--seed', str(20261022 + fork)]
    execute(cmd, out / f'obfuscate-{fork}.log')
    sample = parse_time_v(time_log.read_text())
    sample.update(parse_gc_log(gc_log))
    sample['outputBytes'] = protected.stat().st_size
    samples.append(sample)
    print(fork, sample, flush=True)

  fields = [k for k in samples[0] if k != 'outputBytes'] + ['outputBytes']
  medians = {field: statistics.median(s[field] for s in samples) for field in fields}
  report = {'schemaVersion': 1, 'java': execute(['java', '-version']).strip(), 'host': platform.platform(),
            **environment.collect(ROOT),
            'synthetic': True, 'classes': args.classes, 'methodsPerClass': args.methods_per_class,
            'heap': args.heap, 'measurements': args.forks, 'forks': samples, 'medians': medians}
  (out / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
  print(json.dumps({'medians': medians}, indent=2))

  if args.max_wall_seconds is not None and medians['wallSeconds'] > args.max_wall_seconds:
    raise SystemExit('wall-clock time gate failed')
  if args.max_rss_mb is not None and medians['maxRssKb'] / 1024.0 > args.max_rss_mb:
    raise SystemExit('peak RSS gate failed')


if __name__ == '__main__':
  main()
