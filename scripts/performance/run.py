#!/usr/bin/env python3
"""Repeatable synthetic tick regression benchmark; not a Paper server performance guarantee."""
import argparse
import json
import hashlib
import platform
import os
from pathlib import Path
import statistics
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def execute(args, log=None):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if log: log.write_text(result.stdout)
    if result.returncode: raise RuntimeError(result.stdout[-8000:])
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--forks', type=int, default=3)
    parser.add_argument('--entities', type=int, default=1024)
    parser.add_argument('--warmup', type=int, default=10000)
    parser.add_argument('--ticks', type=int, default=50000)
    parser.add_argument('--warmup-ms', type=int, default=1000)
    parser.add_argument('--max-p99-ratio', type=float, help='Optional local performance gate, sensitive to host noise')
    parser.add_argument('--max-extra-bytes-per-tick', type=float, help='Optional local allocation gate')
    parser.add_argument('--output', type=Path, default=ROOT/'build/performance')
    args = parser.parse_args()
    if args.forks < 1 or args.entities < 1 or args.warmup < 1 or args.ticks < 100 or args.warmup_ms < 1: parser.error('Invalid sample sizes')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    execute([ROOT/'gradlew', ':obfaryn-cli:installDist'], out/'tool-build.log')
    libs = sorted((ROOT/'obfaryn-cli/build/install/obfaryn/lib').glob('*.jar'))
    cli = os.pathsep.join(map(str, libs))
    classes = out/'classes'; classes.mkdir(exist_ok=True)
    execute(['javac', '--release', '8', '-d', classes, HERE/'TickWorkload.java'])
    original = out/'original.jar'
    with zipfile.ZipFile(original, 'w', zipfile.ZIP_DEFLATED) as jar:
        for file in sorted(classes.rglob('*.class')): jar.write(file, file.relative_to(classes))
    protected = out/'protected.jar'
    protection_command = ['java', '-Xmx1g', '-cp', cli, 'com.peco2282.obfaryn.cli.MainKt', original, '--output', protected,
             '--application-package', 'workload', '--keep', 'workload.**', '--platform', 'generic', '--protection', 'strong',
             '--lightweight-control-flow', '--call-indirection', '--integer-arithmetic', '--arithmetic-operations', '4',
             '--max-added-instructions', '256', '--max-growth-percent', '200', '--transformation-report', out/'methods.json',
             '--seed', '20261022', '--report', out/'summary.json']
    execute(protection_command, out/'obfuscate.log')
    prepared = out/'prepared.jar'
    prepared_command = list(protection_command)
    prepared_command[prepared_command.index('--output')+1] = prepared
    prepared_command[prepared_command.index('--transformation-report')+1] = out/'prepared-methods.json'
    prepared_command[prepared_command.index('--report')+1] = out/'prepared-summary.json'
    execute(prepared_command+['--prelink-calls'], out/'prepared-obfuscate.log')
    # A complete class-level HotPath is the explicit performance-preservation control group.
    hot_source = out/'hot-source/TickWorkload.java'; hot_source.parent.mkdir(exist_ok=True)
    hot_source.write_text((HERE/'TickWorkload.java').read_text().replace('public final class', '@com.peco2282.obfaryn.annotations.HotPath\npublic final class'))
    hot_classes = out/'hot-classes'; hot_classes.mkdir(exist_ok=True)
    execute(['javac', '--release', '8', '-cp', os.pathsep.join(map(str, libs)), '-d', hot_classes, hot_source], out/'hot-compile.log')
    hot_input = out/'hot-input.jar'
    with zipfile.ZipFile(hot_input, 'w', zipfile.ZIP_DEFLATED) as jar:
        for file in sorted(hot_classes.rglob('*.class')): jar.write(file, file.relative_to(hot_classes))
    hot = out/'hotpath.jar'
    execute(['java', '-cp', cli, 'com.peco2282.obfaryn.cli.MainKt', hot_input, '--output', hot,
             '--application-package', 'workload', '--keep', 'workload.**', '--protection', 'strong',
             '--call-indirection', '--integer-arithmetic', '--seed', '20261022'], out/'hot-obfuscate.log')
    cases = {'original': original, 'protected': protected, 'prepared': prepared, 'hotpath': hot}
    results = {key: [] for key in cases}
    for fork in range(args.forks):
        order = list(cases) if fork % 2 == 0 else list(reversed(cases))
        for key in order:
            raw = execute(['java', '-Xmx512m', HERE/'TickBenchmark.java', cases[key], args.entities, args.warmup, args.ticks, str(key == 'prepared').lower(), args.warmup_ms], out/f'{key}-{fork}.json')
            sample = json.loads(raw); results[key].append(sample)
            print(key, fork, sample, flush=True)
    medians = {key: {field: (values[0][field] if field in ('checksum', 'checksumBits', 'prelinkedSites') else statistics.median(v[field] for v in values)) for field in values[0]} for key, values in results.items()}
    if len({v['checksumBits'] for values in results.values() for v in values}) != 1: raise RuntimeError('Workload behavior changed')
    ratios = {key: {field: value[field]/medians['original'][field] for field in ['p50Ns', 'p95Ns', 'p99Ns']} for key, value in medians.items()}
    report = {'schemaVersion': 1, 'java': execute(['java', '-version']).strip(), 'host': platform.platform(),
              'workloadSha256': hashlib.sha256((HERE/'TickWorkload.java').read_bytes()).hexdigest(), 'synthetic': True, 'entities': args.entities, 'warmupTicks': args.warmup,
              'measuredTicks': args.ticks, 'minimumWarmupMs': args.warmup_ms, 'forks': results, 'medians': medians, 'ratios': ratios}
    (out/'results.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({'medians': medians, 'ratios': ratios}, indent=2))
    if args.max_p99_ratio is not None and ratios['protected']['p99Ns'] > args.max_p99_ratio: raise SystemExit('p99 regression gate failed')
    if args.max_extra_bytes_per_tick is not None:
        if medians['original']['allocatedBytesPerTick'] < 0: raise SystemExit('Allocation measurement unavailable')
        if medians['protected']['allocatedBytesPerTick']-medians['original']['allocatedBytesPerTick'] > args.max_extra_bytes_per_tick:
            raise SystemExit('Allocation regression gate failed')


if __name__ == '__main__':
    main()
