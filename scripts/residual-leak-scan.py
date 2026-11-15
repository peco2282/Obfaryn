#!/usr/bin/env python3
"""Residual-leak scan: which *original* identifiers and literals survive verbatim in an output JAR.

Unlike `analyze-recoverable-structure.py` (which builds one fully-known fixture and inspects it),
this is a reusable tool that runs over an *arbitrary* original/obfuscated JAR pair and reports what
of the original program is still present, unchanged, in the obfuscated output's own class files:

  * Renamed-but-leaked names -- for every entry the obfuscator's own `--mapping` says it renamed
    (original simple name != new name), whether that original simple name still appears verbatim as
    a token anywhere in the obfuscated class files. A renamed name that reappears is a genuine leak
    (a reference the renamer did not rewrite, a name embedded in Kotlin `@Metadata`, a kept signature
    still spelling it out, and so on).
  * Surviving string literals -- every `CONSTANT_String` value that is present in the original JAR
    and still present verbatim in the obfuscated JAR. With string encryption enabled these should be
    gone; survivors are strings the encoder did not cover.
  * Surviving integer literals -- every `CONSTANT_Integer` constant-pool value present in both. This
    section is advisory: many integers are legitimately kept (array sizes, opcode-level small ints
    never enter the constant pool at all), so a non-zero count is not by itself a defect.

What this is NOT: it is not a decompiler, and "present verbatim in the output" is a deliberately
narrow, checkable claim read straight from the JDK's own `javap` -- not a judgement about whether a
decompiler could reconstruct anything. A name the mapping did not rename, and a string the caller
chose to keep, are correctly *not* reported as leaks (that is what `--mapping` is for); without a
mapping the name section is skipped rather than guessed, because substring-matching arbitrary names
against the whole JAR produces noise, not evidence.

Needs the JDK (`javap`) on PATH. `--self-test` builds its own tiny fixtures with `javac` only (no
Gradle, no obfuscator) and checks that the scanner reports a clean pair as clean and a leaky pair as
leaky; it is wired into CI the same way as `report-summary.py --self-test`.
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def run(args, check=True):
  result = subprocess.run(list(map(str, args)), text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
  if check and result.returncode:
    raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, args))}\n{result.stdout[-6000:]}")
  return result.stdout


def extract(jar: Path, into: Path):
  with zipfile.ZipFile(jar) as zf:
    zf.extractall(into)
  return sorted(into.rglob('*.class'))


def javap_verbose(classfile: Path) -> str:
  # -p private members, -c disassembly, -v constant pool. check=False: javap can print and still
  # return non-zero on unusual class files, and a best-effort read is what this tool wants.
  return run(['javap', '-p', '-c', '-v', str(classfile)], check=False)


# A real string literal is a CONSTANT_String entry ("#n = String  #m // the text"), not just any
# Utf8 (which also holds every class name, descriptor and attribute name). Matching String entries
# keeps the string section to actual literals.
_STRING_ENTRY = re.compile(r'^\s*#\d+\s*=\s*String\s+#\d+\s*//\s*(.*)$')
_INT_ENTRY = re.compile(r'^\s*#\d+\s*=\s*Integer\s+(-?\d+)\b')


def constant_literals(class_files):
  """(string-literal values, integer-literal values) across every class file's constant pool."""
  strings, ints = set(), set()
  for classfile in class_files:
    for line in javap_verbose(classfile).splitlines():
      m = _STRING_ENTRY.match(line)
      if m:
        strings.add(m.group(1))
      m = _INT_ENTRY.match(line)
      if m:
        ints.add(int(m.group(1)))
  return strings, ints


def jar_text(class_files) -> str:
  """All of a JAR's class files disassembled, concatenated -- the haystack for name-token search."""
  return '\n'.join(javap_verbose(cf) for cf in class_files)


def read_mapping(path: Path):
  """Original-identity -> new-name entries from the CLI `--mapping` file (` -> ` separated)."""
  entries = {}
  if not path or not path.is_file():
    return entries
  for line in path.read_text().splitlines():
    if ' -> ' not in line:
      continue
    old, new = line.split(' -> ', 1)
    entries[old.strip()] = new.strip()
  return entries


def original_simple_name(key: str) -> str:
  """The simple (unqualified) original name from a mapping key.

  Class keys are internal names with no `.` (`pkg/Outer$Inner`) -> the last `/` segment. Member keys
  are `owner.name(descriptor)` for a method or `owner.name:descriptor` for a field (the owner itself
  uses `/`, never `.`) -> the name is the member part up to the first `(` (method) or `:` (field), so
  a type descriptor like `Ljava/util/.../AtomicReferenceArray;` is never mistaken for the name.
  """
  if '.' not in key:
    return key.split('/')[-1]
  member = key.split('.', 1)[1]
  return re.split(r'[(:]', member, maxsplit=1)[0]


def renamed_pairs(mapping, min_length: int):
  """(original_simple_name, new_name) for entries the obfuscator actually renamed.

  Skips entries whose simple name is unchanged (kept) and names shorter than `min_length` (short or
  common tokens like `a`/`run` match everywhere and would be noise, not evidence).
  """
  pairs = []
  for key, new in mapping.items():
    original = original_simple_name(key)
    new_simple = original_simple_name(new) if ('/' in new or '.' in new) else new
    if original == new_simple or len(original) < min_length:
      continue
    pairs.append((original, new_simple))
  # De-duplicate while preserving order; the same simple name can be renamed in several entries.
  seen = set()
  unique = []
  for original, new in pairs:
    if original in seen:
      continue
    seen.add(original)
    unique.append((original, new))
  return unique


def scan(original_jar: Path, obfuscated_jar: Path, mapping_path: Path, min_name_length: int, top: int):
  with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    original_classes = extract(original_jar, tmp / 'original')
    obfuscated_classes = extract(obfuscated_jar, tmp / 'obfuscated')

    original_strings, original_ints = constant_literals(original_classes)
    obfuscated_strings, obfuscated_ints = constant_literals(obfuscated_classes)
    obfuscated_text = jar_text(obfuscated_classes)

    surviving_strings = sorted(original_strings & obfuscated_strings)
    surviving_ints = sorted(original_ints & obfuscated_ints)

    mapping = read_mapping(mapping_path)
    name_section = None
    if mapping:
      pairs = renamed_pairs(mapping, min_name_length)
      leaked = sorted(
        original for original, _ in pairs
        if re.search(rf'(?<![\w$]){re.escape(original)}(?![\w$])', obfuscated_text)
      )
      name_section = {
        'mappingProvided': True,
        'renamedChecked': len(pairs),
        'leakedVerbatim': leaked[:top],
        'leakedCount': len(leaked),
      }
    else:
      name_section = {
        'mappingProvided': False,
        'note': 'No --mapping supplied; renamed-name leak detection skipped (see tool help).',
      }

    return {
      'originalJar': str(original_jar),
      'obfuscatedJar': str(obfuscated_jar),
      'names': name_section,
      'strings': {
        'originalCount': len(original_strings),
        'survivingVerbatim': surviving_strings[:top],
        'survivingCount': len(surviving_strings),
      },
      'integers': {
        'originalCount': len(original_ints),
        'survivingVerbatim': surviving_ints[:top],
        'survivingCount': len(surviving_ints),
        'note': 'Advisory: many integers are legitimately kept; a non-zero count is not a defect.',
      },
    }


def render_text(result) -> str:
  lines = []
  lines.append('Residual-leak scan')
  lines.append(f"  original:   {result['originalJar']}")
  lines.append(f"  obfuscated: {result['obfuscatedJar']}")
  names = result['names']
  if names['mappingProvided']:
    lines.append(f"  renamed names leaked verbatim: {names['leakedCount']} of {names['renamedChecked']} checked")
    for name in names['leakedVerbatim']:
      lines.append(f"    - {name}")
    if names['leakedCount'] > len(names['leakedVerbatim']):
      lines.append(f"    ... (+{names['leakedCount'] - len(names['leakedVerbatim'])} more)")
  else:
    lines.append(f"  renamed names: {names['note']}")
  strings = result['strings']
  lines.append(f"  string literals surviving verbatim: {strings['survivingCount']} of {strings['originalCount']} original")
  for value in strings['survivingVerbatim']:
    lines.append(f"    - {value!r}")
  if strings['survivingCount'] > len(strings['survivingVerbatim']):
    lines.append(f"    ... (+{strings['survivingCount'] - len(strings['survivingVerbatim'])} more)")
  integers = result['integers']
  lines.append(f"  integer literals surviving verbatim: {integers['survivingCount']} of {integers['originalCount']} original (advisory)")
  return '\n'.join(lines)


def _compile_jar(work: Path, name: str, package: str, classname: str, sources: dict) -> Path:
  src_dir = work / f'{name}-src'
  classes = work / f'{name}-classes'
  for rel, text in sources.items():
    path = src_dir / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
  classes.mkdir(parents=True, exist_ok=True)
  run(['javac', '-d', str(classes)] + [str(src_dir / rel) for rel in sources])
  jar = work / f'{name}.jar'
  with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as zf:
    for file in sorted(classes.rglob('*.class')):
      zf.write(file, file.relative_to(classes))
  return jar


def self_test() -> int:
  """Hermetic: javac only, no Gradle and no obfuscator. Validates the *scanner's* detection logic.

  Builds an "original" with a distinctive class/method name, string and int literal, then checks that
  a hand-built clean output (names changed, literals gone) is reported clean and that a non-obfuscated
  output (the original reused as its own "obfuscated" JAR) is reported as leaking all three.
  """
  secret_string = 'SENSITIVE-LICENSE-TOKEN-7'
  magic_int = 90909091
  with tempfile.TemporaryDirectory() as tmp:
    work = Path(tmp)
    original = _compile_jar(work, 'original', 'app', 'LicenseGuard', {
      'app/LicenseGuard.java': (
        'package app;\n'
        'public final class LicenseGuard {\n'
        '  private LicenseGuard() {}\n'
        f'  public static String validateLicenseToken() {{ return "{secret_string}"; }}\n'
        f'  public static int magicConstant() {{ return {magic_int}; }}\n'
        '}\n'
      ),
    })
    # Clean output: different class/method names, no secret string, no magic int.
    clean = _compile_jar(work, 'clean', 'a', 'b', {
      'a/b.java': (
        'package a;\n'
        'public final class b {\n'
        '  private b() {}\n'
        '  public static String c() { return "x"; }\n'
        '  public static int d() { return 1; }\n'
        '}\n'
      ),
    })
    mapping = work / 'mapping.txt'
    mapping.write_text(
      'app/LicenseGuard -> a/b\n'
      '  app/LicenseGuard.validateLicenseToken()Ljava/lang/String; -> c\n'
      '  app/LicenseGuard.magicConstant()I -> d\n'
    )

    clean_result = scan(original, clean, mapping, min_name_length=4, top=20)
    assert clean_result['names']['leakedCount'] == 0, clean_result['names']
    assert clean_result['strings']['survivingCount'] == 0, clean_result['strings']
    assert secret_string not in clean_result['strings']['survivingVerbatim']

    # Leaky output: the original reused unchanged -- every name and literal must be detected.
    leaky_result = scan(original, original, mapping, min_name_length=4, top=20)
    assert 'LicenseGuard' in leaky_result['names']['leakedVerbatim'], leaky_result['names']
    assert 'validateLicenseToken' in leaky_result['names']['leakedVerbatim'], leaky_result['names']
    assert secret_string in leaky_result['strings']['survivingVerbatim'], leaky_result['strings']
    assert magic_int in leaky_result['integers']['survivingVerbatim'], leaky_result['integers']

    # Without a mapping the name section is skipped, not guessed.
    no_mapping = scan(original, original, None, min_name_length=4, top=20)
    assert no_mapping['names']['mappingProvided'] is False

  print('self-test OK')
  return 0


def main() -> int:
  parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  parser.add_argument('--original', type=Path, help='the pre-obfuscation input JAR')
  parser.add_argument('--obfuscated', type=Path, help='the obfuscated output JAR to scan')
  parser.add_argument('--mapping', type=Path, help='the obfuscator --mapping file (enables name-leak detection)')
  parser.add_argument('--min-name-length', type=int, default=4, help='skip renamed names shorter than this (default 4)')
  parser.add_argument('--top', type=int, default=20, help='how many sample leaks to list per section (default 20)')
  parser.add_argument('--json', type=Path, help='write the machine-readable result here')
  parser.add_argument('--fail-on-leak', action='store_true',
                      help='exit non-zero if any name or string leak is found (for CI gating)')
  parser.add_argument('--self-test', action='store_true', help='run built-in detection checks and exit')
  args = parser.parse_args()

  if args.self_test:
    return self_test()
  if not args.original or not args.obfuscated:
    parser.error('--original and --obfuscated are required (or use --self-test)')
  for jar in (args.original, args.obfuscated):
    if not jar.is_file():
      parser.error(f'not a file: {jar}')

  result = scan(args.original, args.obfuscated, args.mapping, args.min_name_length, args.top)
  print(render_text(result))
  if args.json:
    args.json.write_text(json.dumps(result, indent=2, sort_keys=True))

  if args.fail_on_leak:
    leaked = result['names'].get('leakedCount', 0) + result['strings']['survivingCount']
    if leaked:
      return 1
  return 0


if __name__ == '__main__':
  sys.exit(main())
