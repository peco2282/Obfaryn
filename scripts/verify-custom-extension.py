#!/usr/bin/env python3
"""Verify API-only extension loading through the installed CLI, then run only output JARs.
Prepare with: bash gradlew :obfaryn-cli:installDist :examples:custom-extension:jar
Requires JDK 21 and Python 3. Does not publish or use an original application at runtime.
"""
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'obfaryn-cli/build/install/obfaryn/bin/obfaryn'


def run(args, expected=0):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    if result.returncode != expected:
        raise RuntimeError(f'Command failed: {args}\n{result.stdout}{result.stderr}')
    return result.stdout.strip()


def main():
    extensions = list((ROOT / 'examples/custom-extension/build/libs').glob('*.jar'))
    if not CLI.is_file() or len(extensions) != 1:
        raise RuntimeError('Run :obfaryn-cli:installDist :examples:custom-extension:jar first')
    extension = extensions[0]
    with tempfile.TemporaryDirectory(prefix='obfaryn-custom-extension-') as temporary:
        work = Path(temporary)
        source = work / 'Main.java'
        source.write_text('''package example;
public class Main {
  public static int answer() { return 7; }
  public static void main(String[] args) { System.out.println(answer()); }
}
''', encoding='utf-8')
        classes = work / 'classes'
        run(['javac', '--release', '8', '-d', classes, source])
        original = work / 'input.jar'
        run(['jar', '--create', '--file', original, '-C', classes, '.'])
        for prefix in ([], ['obfuscate']):
            for mode, selection in (
                ('empty', []),
                ('package', ['--package', 'example-padding']),
                ('platform', ['--also-platform', 'example']),
                ('disabled', ['--package', 'example-padding', '--disable-feature', 'example:padding=example/.*']),
            ):
                output = work / f'output-{len(prefix)}-{mode}.jar'
                report = output.with_suffix('.json')
                run([CLI, *prefix, original, '-o', output, '--no-default-package', '--extension', extension,
                     '--report', report, '--verify-output', *selection])
                data = json.loads(report.read_text(encoding='utf-8'))
                passes = data['transformerReports']
                if mode == 'empty':
                    assert passes == [], passes
                else:
                    assert [entry['id'] for entry in passes] == ['example-padding'], passes
                    assert passes[0]['metrics']['transformed'] == (0 if mode == 'disabled' else 1), passes
                assert run(['java', '-Xverify:all', '-cp', output, 'example.Main']) == '7'
        # Loader lifetime is per invocation; do not put providers on the CLI's application classpath.
        result = subprocess.run([str(CLI), str(original), '-o', str(work / 'missing.jar'),
                                 '--no-default-package', '--package', 'example-padding'],
                                capture_output=True, text=True)
        assert result.returncode != 0 and 'Unknown transformation package' in result.stderr, result.stderr
        assert not (work / 'missing.jar').exists()
    print('API-only extension: both CLI invocations, explicit packages/platforms, custom policy and JVM verification passed')


if __name__ == '__main__':
    main()
