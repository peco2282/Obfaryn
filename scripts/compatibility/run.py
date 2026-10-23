#!/usr/bin/env python3
"""Pinned public-source compatibility matrix. Requires JDK 21, git, Python 3 and network access.
Cloned code is built/executed; use a dedicated CI worker for untrusted upstream projects.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
INIT = Path(__file__).with_name('gradle.init.gradle')


def run(args, cwd, log):
    print('Running', Path(str(args[0])).name, '->', log.name, flush=True)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w') as stream:
        process = subprocess.run(list(map(str, args)), cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
    if process.returncode:
        print(log.read_text(errors='replace')[-12000:], file=sys.stderr)
        raise RuntimeError(f'Command failed ({process.returncode}); see {log}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', action='append', help='Corpus ID; repeatable')
    parser.add_argument('--skip-build', action='store_true', help='Reuse matching checkout and existing upstream build')
    parser.add_argument('--work-dir', type=Path, default=ROOT/'build/compatibility')
    args = parser.parse_args()
    projects = json.loads(Path(__file__).with_name('corpus.json').read_text())['projects']
    if args.project:
        unknown = set(args.project) - {p['id'] for p in projects}
        if unknown: parser.error('Unknown project: '+', '.join(sorted(unknown)))
        projects = [p for p in projects if p['id'] in args.project]
    work = args.work_dir.resolve(); work.mkdir(parents=True, exist_ok=True)
    run([ROOT/'gradlew', ':obfaryn-cli:installDist'], ROOT, work/'tool-build.log')
    jars = sorted((ROOT/'obfaryn-cli/build/install/obfaryn/lib').glob('*.jar'))
    cli = os.pathsep.join(map(str, jars))
    outcomes = []
    for project in projects:
        checkout = work/project['checkout']
        result = work/('results-'+project['id']); result.mkdir(exist_ok=True)
        if not (checkout/'.git').is_dir():
            run(['git', 'clone', '--no-checkout', project['url'], checkout], ROOT, result/'clone.log')
        current = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip()
        if current != project['commit']:
            run(['git', 'fetch', '--depth', '1', 'origin', project['commit']], checkout, result/'fetch.log')
            run(['git', 'checkout', '--detach', project['commit']], checkout, result/'checkout.log')
        properties = [f'-Dobfaryn.testProject={project["testProject"]}', f'-Dobfaryn.testTask={project["testTask"]}',
                      f'-Dobfaryn.resultDir={result}', f'-Dobfaryn.mainOutputs={os.pathsep.join(str(checkout/p) for p in project["mainOutputs"] + [project["jar"]])}']
        if not args.skip_build:
            run([checkout/'gradlew', *project['build'], '--console=plain'], checkout, result/'original-build.log')
        prefix = '' if project['testProject'] == ':' else project['testProject']
        # Keep the upstream build profile identical in both runs.
        build_flags = [p for p in project['build'] if p.startswith('-P') or p == '--configure-on-demand']
        run([checkout/'gradlew', *build_flags, '-I', INIT, *properties, prefix+':obfarynExportTestPaths', '--console=plain'], checkout, result/'export.log')
        input_jar = checkout/project['jar']
        if not input_jar.is_file(): raise RuntimeError(f'Missing upstream artifact: {input_jar}')
        roots = [Path(p) for p in (result/'test-roots.txt').read_text().splitlines() if Path(p).is_dir()]
        cmd = ['java', '-Xmx2g', '-cp', cli, 'com.peco2282.obfaryn.cli.MainKt', input_jar,
               '--output', result/'protected.jar', '--mapping', result/'protected.mapping.txt',
               '--platform', 'generic', '--application-package', project['package'], '--keep', project['package']+'.**',
               '--protection', 'strong', '--lightweight-control-flow', '--call-indirection', '--integer-arithmetic',
               '--arithmetic-operations', '4', '--prelink-calls', '--exclude-virtualization', '.*', '--seed', '20261022',
               '--max-input-instructions', '10000', '--max-input-branches', '256', '--max-added-instructions', '1024',
               '--max-growth-percent', '300', '--max-output-code-bytes', '60000',
               '--transformation-report', result/'methods.json', '--report', result/'summary.json']
        for p in (result/'test-paths.txt').read_text().splitlines():
            if Path(p).exists(): cmd += ['--classpath', p]
        run(cmd, ROOT, result/'obfuscate.log')
        for index, root in enumerate(roots):
            run(['java', '-cp', cli, ROOT/'scripts/RemapTestClasses.java', result/'protected.mapping.txt', root, result/'remapped-tests'], ROOT, result/f'remap-{index}.log')
        run([checkout/'gradlew', *build_flags, '-I', INIT, *properties, prefix+':obfarynCompatibilityTest', '--console=plain'], checkout, result/'transformed-tests.log')
        suites = [ET.parse(p).getroot() for p in (result/'test-results').glob('TEST-*.xml')]
        tests = sum(int(s.get('tests', 0)) for s in suites)
        failures = sum(int(s.get('failures', 0))+int(s.get('errors', 0)) for s in suites)
        skipped = sum(int(s.get('skipped', 0)) for s in suites)
        if not tests or failures or skipped: raise RuntimeError(f'Incomplete corpus run: {tests} tests, {failures} failures, {skipped} skipped')
        outcomes.append({'id': project['id'], 'url': project['url'], 'commit': project['commit'],
                         'tests': tests, 'failures': failures, 'skipped': skipped,
                         'inputSha256': hashlib.sha256(input_jar.read_bytes()).hexdigest(),
                         'outputSha256': hashlib.sha256((result/'protected.jar').read_bytes()).hexdigest(),
                         'profile': 'bounded-nonvm-prelink', 'publicApiNamesPreserved': True})
        (work/'results.json').write_text(json.dumps(outcomes, indent=2)+'\n')
        print(outcomes[-1], flush=True)


if __name__ == '__main__':
    main()
