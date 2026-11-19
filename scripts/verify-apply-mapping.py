#!/usr/bin/env python3
"""Exercise apply-mapping end to end with only the installed CLI and distribution JARs.

Obfuscates a Java 8 API on its own, then obfuscates a consumer built separately while
re-applying the API's mapping, and runs the two output JARs together with the original
classes off the classpath. Also checks that a stale mapping (a different build of the API)
is rejected. Run ./gradlew :obfaryn-cli:installDist first. Requires JDK 21 and Python 3.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DIST = ROOT / 'obfaryn-cli/build/install/obfaryn'
SEED = '20261119'


def run(args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'Command failed: {args}\n{result.stdout}{result.stderr}')
    return result.stdout.strip()


def run_expecting_failure(args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    if not result.returncode:
        raise AssertionError(f'Command unexpectedly succeeded: {args}\n{result.stdout}')
    return f'{result.stdout}{result.stderr}'


def main():
    launcher = DIST / 'bin' / ('obfaryn.bat' if os.name == 'nt' else 'obfaryn')
    if not launcher.exists():
        raise RuntimeError('Installed CLI not found; run ./gradlew :obfaryn-cli:installDist')

    with tempfile.TemporaryDirectory(prefix='obfaryn-apply-mapping-') as temp:
        work = Path(temp)

        # The API, obfuscated on its own and "shipped".
        api_src = work / 'Greeter.java'
        api_src.write_text('''package api;
public class Greeter {
  public String greet() { return "hi"; }
}
''', encoding='utf-8')
        api_classes = work / 'api-classes'
        api_classes.mkdir()
        run(['javac', '--release', '8', '-d', api_classes, api_src])
        api_in = work / 'api-in.jar'
        run(['jar', '--create', '--file', api_in, '-C', api_classes, '.'])
        api_out = work / 'api-obf.jar'
        api_map = work / 'api.mapping.txt'
        run([launcher, api_in, '--output', api_out, '--platform', 'generic',
             '--application-package', 'api', '--no-string-encryption', '--no-number-obfuscation',
             '--mapping', api_map, '--verify-output', '--seed', SEED])
        api_map_text = api_map.read_text(encoding='utf-8')
        greeter_line = next((l for l in api_map_text.splitlines() if l.startswith('api/Greeter -> ')), None)
        if greeter_line is None or greeter_line.split(' -> ')[1].strip() == 'api/Greeter':
            raise AssertionError(f'API class was not renamed:\n{api_map_text}')
        if not any(l.startswith('# source api-obf.jar ') for l in api_map_text.splitlines()):
            raise AssertionError(f'API mapping did not record its own provenance:\n{api_map_text}')

        # The consumer, compiled against the ORIGINAL API and obfuscated later with the API mapping applied.
        app_src = work / 'Main.java'
        app_src.write_text('''package app;
public class Main {
  public static void main(String[] args) {
    System.out.println(new api.Greeter().greet());
  }
}
''', encoding='utf-8')
        app_classes = work / 'app-classes'
        app_classes.mkdir()
        run(['javac', '--release', '8', '-cp', api_classes, '-d', app_classes, app_src])
        app_in = work / 'app-in.jar'
        run(['jar', '--create', '--file', app_in, '--main-class', 'app.Main', '-C', app_classes, '.'])
        app_out = work / 'app-obf.jar'
        app_map = work / 'app.mapping.txt'
        run([launcher, app_in, '--output', app_out, '--platform', 'generic',
             '--application-package', 'app', '--keep', 'app.Main',
             '--no-string-encryption', '--no-number-obfuscation',
             '--apply-mapping', api_map, '--classpath', api_out,
             '--mapping', app_map, '--verify-output', '--seed', SEED])
        app_map_text = app_map.read_text(encoding='utf-8')
        if greeter_line not in app_map_text.splitlines():
            raise AssertionError(f'Consumer mapping did not merge the applied API rename ({greeter_line}):\n{app_map_text}')
        if not any(l.startswith('# source api-obf.jar ') for l in app_map_text.splitlines()):
            raise AssertionError(f'Consumer mapping did not carry the API provenance:\n{app_map_text}')

        # Run both outputs together; the original API classes are not on the classpath.
        both = os.pathsep.join([str(api_out), str(app_out)])
        actual = run(['java', '-Xverify:all', '-cp', both, 'app.Main'])
        if actual != 'hi':
            raise AssertionError(f'Consumer did not resolve the separately-obfuscated API at runtime: {actual!r}')

        # A stale mapping (a different build of the API under the same name) must be rejected.
        stale_dir = work / 'stale'
        stale_dir.mkdir()
        stale_api = stale_dir / 'api-obf.jar'
        shutil.copyfile(api_out, stale_api)
        with open(stale_api, 'ab') as handle:
            handle.write(b'\x00')
        output = run_expecting_failure(
            [launcher, '--stacktrace', app_in, '--output', work / 'app-stale.jar', '--platform', 'generic',
             '--application-package', 'app', '--keep', 'app.Main',
             '--no-string-encryption', '--no-number-obfuscation',
             '--apply-mapping', api_map, '--classpath', stale_api,
             '--mapping', work / 'app-stale.mapping.txt', '--seed', SEED])
        if 'stale' not in output:
            raise AssertionError(f'Stale applied mapping was not reported as stale:\n{output}')

        print('Installed CLI: apply-mapping round-trip, merged mapping, provenance and stale rejection passed')


if __name__ == '__main__':
    main()
