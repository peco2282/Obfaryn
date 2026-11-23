#!/usr/bin/env python3
"""Extract one reviewed Changelog section for a GitHub Release; never publish anything."""
import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def extract(text, version):
    version = version.removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', version):
        raise ValueError(f'Invalid release version: {version}')
    headings = list(re.finditer(r'^## \[([^\]\n]+)\][^\n]*$', text, re.MULTILINE))
    matches = [i for i, heading in enumerate(headings) if heading.group(1) == version]
    if len(matches) != 1:
        raise ValueError(f'Expected exactly one Changelog section for {version}')
    index = matches[0]
    start = headings[index].end()
    end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
    body = text[start:end].strip()
    if not body:
        raise ValueError(f'Empty Changelog section for {version}')
    return body + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='Release version or tag, e.g. 1.2.0 or v1.2.0')
    parser.add_argument('--changelog', type=Path, default=ROOT / 'CHANGELOG.md')
    parser.add_argument('--output', type=Path, help='Write notes here instead of stdout')
    parser.add_argument('--check-project-version', action='store_true',
                        help='Require gradle.properties releaseVersion to match the tag')
    args = parser.parse_args()
    try:
        notes = extract(args.changelog.read_text(encoding='utf-8'), args.version)
        if args.check_project_version:
            values = [line.partition('=')[2].strip()
                      for line in (ROOT / 'gradle.properties').read_text(encoding='utf-8').splitlines()
                      if line.partition('=')[0].strip() == 'releaseVersion']
            if values != [args.version.removeprefix('v')]:
                raise ValueError('Release tag must match gradle.properties releaseVersion')
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(notes, encoding='utf-8')
        else:
            sys.stdout.write(notes)
    except (OSError, ValueError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
