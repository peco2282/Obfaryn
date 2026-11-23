#!/usr/bin/env python3
"""Fail when an obfuscated distribution JAR contains Obfaryn analysis sidecars.

This check deliberately examines only the artifact an attacker receives. Mapping and report files
may exist beside the build output for maintainers, but must not be packaged into the distribution.
Pass one or more final JARs, not an intermediate staging directory.
"""
import argparse
import json
import sys
import zipfile
from pathlib import Path, PurePosixPath

FORBIDDEN_NAMES = {
  'mapping.txt',
  'methods.json',
  'virtualization.json',
  'obfaryn-mapping.txt',
  'obfaryn-methods.json',
  'obfaryn-virtualization.json',
}


def suspicious(entry: str) -> bool:
  name = PurePosixPath(entry).name.lower()
  if name in FORBIDDEN_NAMES:
    return True
  return (
    name.endswith('.mapping.txt') or
    name.endswith('.methods.json') or
    name.endswith('.virtualization.json')
  )


def audit(jar: Path) -> dict:
  with zipfile.ZipFile(jar) as archive:
    entries = archive.namelist()
  leaks = sorted(entry for entry in entries if suspicious(entry))
  return {
    'jar': str(jar.resolve()),
    'sizeBytes': jar.stat().st_size,
    'entries': len(entries),
    'sidecarLeaks': leaks,
    'passed': not leaks,
  }


def main() -> int:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('jars', nargs='+', type=Path)
  parser.add_argument('--json', type=Path, help='write the complete machine-readable report')
  args = parser.parse_args()
  reports = [audit(jar) for jar in args.jars]
  if args.json:
    args.json.write_text(json.dumps(reports, indent=2) + '\n')
  for report in reports:
    status = 'PASS' if report['passed'] else 'FAIL'
    print(f"{status} {report['jar']}: {report['entries']} entries, "
          f"{report['sizeBytes']} bytes, leaks={report['sidecarLeaks']}")
  return 0 if all(report['passed'] for report in reports) else 1


if __name__ == '__main__':
  sys.exit(main())
