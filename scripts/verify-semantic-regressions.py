#!/usr/bin/env python3
"""Run reusable transformation regressions with full JVM verification (JDK 21)."""
import argparse
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--full', action='store_true', help='Run all core and CLI regressions')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix='obfaryn-semantics-') as temporary:
        init = Path(temporary) / 'verify.gradle'
        init.write_text('''allprojects {
  tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
    jvmArgs '-Xverify:all'
  }
}
''', encoding='utf-8')
        command = ['bash', str(root / 'gradlew'), '-I', str(init), '--console=plain', ':obfaryn-core:test']
        if args.full:
            command += [':obfaryn-transformer:test', ':obfaryn-cli:test']
        else:
            command += ['--tests', '*StatefulDifferentialTest', '--tests', '*InitializationSemanticsTest']
        return subprocess.run(command, cwd=root).returncode


if __name__ == '__main__':
    raise SystemExit(main())
