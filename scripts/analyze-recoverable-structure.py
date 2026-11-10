#!/usr/bin/env python3
"""Decompilation-resistance inventory: what structural information survives obfuscation.

Builds a small, fully-known fixture (so every name, string literal, numeric literal and branch is
enumerable in advance), obfuscates it with a representative "strong" profile (renaming, string
encryption, number obfuscation, lightweight control flow, call indirection, and one explicitly
virtualized method), and compares the two JARs' class files directly at the constant-pool/bytecode
level using the JDK's own `javap` -- not any particular third-party decompiler.

This measures *recoverable structural information*, which is a narrower and more defensible claim
than "decompiler resistance": `javap` disassembles valid bytecode unconditionally (it is not a
decompiler and never fails to "show" a class), so a method `javap` can print in full is not evidence
of weak protection, and a method a *specific* decompiler renders badly is not by itself evidence of
strong protection -- a different decompiler, or a future version of the same one, may do better.
What this script reports is independent of any decompiler's own quirks: which original class/method/
field names, string literals and numeric literals are still present verbatim in the output JAR's own
class files, and which bytecode-level indicators (branch instructions, invokedynamic call sites, the
virtualization interpreter's own dispatch shape) remain visible.

Run from the repository root; needs the JDK (`javac`, `javap`) and `./gradlew`.
"""
import argparse
import json
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PACKAGE = "sample"
# Every name, literal and branch below is deliberately known in advance, so "recoverable" below
# means "found verbatim in the *other* JAR's class files", not "found by some heuristic".
STRING_LITERALS = ["top-secret-license-key", "customer-database-url", "forbidden-admin-token"]
INT_LITERALS = [733297, -418, 20261110]
FIXTURE_SOURCE = f"""
package {PACKAGE};
public final class LicenseValidator {{
  private LicenseValidator() {{}}
  private static String licenseKey() {{ return "{STRING_LITERALS[0]}"; }}
  private static String databaseUrl() {{ return "{STRING_LITERALS[1]}"; }}
  private static String adminToken() {{ return "{STRING_LITERALS[2]}"; }}

  // Branchy: a decent proxy for how much of the original control-flow shape survives.
  public static int classifyAttempt(int code) {{
    int result;
    if (code == {INT_LITERALS[0]}) {{
      result = 1;
    }} else if (code == {INT_LITERALS[1]}) {{
      result = -1;
    }} else {{
      int acc = 0;
      for (int i = 0; i < 4; i++) {{
        if ((code & (1 << i)) != 0) acc += i; else acc -= i;
      }}
      result = acc;
    }}
    return result;
  }}

  // Three ordinary calls: call-indirection's natural target.
  public static String describe(int code) {{
    String key = licenseKey();
    String db = databaseUrl();
    String admin = adminToken();
    return key.length() + ":" + db.length() + ":" + admin.length() + ":" + classifyAttempt(code);
  }}

  // Dedicated, explicitly virtualized target: a small integer computation with no field/string
  // access, so its eligibility does not depend on anything else in this fixture.
  public static int hotCompute(int seed) {{
    int acc = seed;
    for (int i = 1; i <= 6; i++) {{ acc = acc * 31 + i; acc ^= (acc >>> 3) + {INT_LITERALS[2]}; }}
    return acc;
  }}
}}
"""


def run(args, cwd=ROOT, check=True):
  result = subprocess.run(list(map(str, args)), cwd=cwd, text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
  if check and result.returncode:
    raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, args))}\n{result.stdout[-6000:]}")
  return result.stdout


def build_cli(out: Path) -> str:
  run([ROOT / 'gradlew', ':obfaryn-cli:installDist'])
  jars = sorted((ROOT / 'obfaryn-cli/build/install/obfaryn/lib').glob('*.jar'))
  return ':'.join(map(str, jars))


def compile_fixture(out: Path) -> Path:
  source = out / f'src/{PACKAGE}/LicenseValidator.java'
  source.parent.mkdir(parents=True, exist_ok=True)
  source.write_text(FIXTURE_SOURCE)
  classes = out / 'classes'
  classes.mkdir(exist_ok=True)
  run(['javac', '--release', '8', '-d', classes, source])
  jar = out / 'original.jar'
  with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as zf:
    for file in sorted(classes.rglob('*.class')):
      zf.write(file, file.relative_to(classes))
  return jar


def obfuscate(cli: str, original: Path, out: Path):
  output = out / 'protected.jar'
  mapping = out / 'mapping.txt'
  cmd = ['java', '-cp', cli, 'com.peco2282.obfaryn.cli.MainKt', original, '--output', output,
         '--mapping', mapping, '--application-package', PACKAGE, '--protection', 'strong',
         '--lightweight-control-flow', '--call-indirection', '--integer-arithmetic',
         '--arithmetic-operations', '4', '--virtualize-method', rf'{PACKAGE}/LicenseValidator\.hotCompute\(I\)I',
         '--exclude-virtualization', rf'{PACKAGE}/LicenseValidator\.(?!hotCompute).*',
         '--flatten-packages', '--target-package', 'hidden', '--seed', '20261110',
         '--verify-output']
  run(cmd, cwd=out)
  return output, mapping


def read_mapping(path: Path) -> dict:
  """Class/method/field rename entries, keyed by original identity (CLI --mapping format)."""
  if not path.is_file():
    return {}
  entries = {}
  for line in path.read_text().splitlines():
    if ' -> ' not in line:
      continue
    old, new = line.split(' -> ', 1)
    entries[old.strip()] = new.strip()
  return entries


def mapped_method_name(mapping: dict, owner: str, name: str, descriptor: str) -> str:
  """The renamed simple method name for `owner.name(descriptor)`, or `name` if kept."""
  key = f'{owner}.{name}{descriptor}'
  entry = mapping.get(key)
  if entry is None:
    return name
  # Method entries are indented two spaces and read "  Owner.name(desc) -> newName" in the file;
  # read_mapping already split on " -> ", so `entry` here is just the right-hand side.
  return entry.strip()


def extract(jar: Path, into: Path):
  with zipfile.ZipFile(jar) as zf:
    zf.extractall(into)
  return sorted(into.rglob('*.class'))


def javap(classfile: Path, verbose: bool) -> str:
  args = ['javap', '-p', '-c'] + (['-v'] if verbose else []) + [str(classfile)]
  return run(args, check=False)


def constant_pool_literals(class_files):
  """Every literal `String`/`Integer` constant pool entry's value, across every class in the JAR."""
  strings, ints = set(), set()
  string_entry = re.compile(r'^\s*#\d+\s*=\s*Utf8\s+(.*)$')
  int_entry = re.compile(r'^\s*#\d+\s*=\s*Integer\s+(-?\d+)')
  for classfile in class_files:
    text = javap(classfile, verbose=True)
    for line in text.splitlines():
      m = string_entry.match(line)
      if m:
        strings.add(m.group(1))
      m = int_entry.match(line)
      if m:
        ints.add(int(m.group(1)))
  return strings, ints


def method_blocks(classfile: Path):
  text = javap(classfile, verbose=False)
  return re.split(r'\n(?=\s*(?:public|private|protected|static|final)\b[^\n]*\()', text)


def method_disassembly(classfile: Path, method_name: str) -> str:
  for block in method_blocks(classfile):
    header = block.strip().splitlines()[0] if block.strip() else ''
    if re.search(rf'\b{re.escape(method_name)}\s*\(', header):
      return block
  return ''


def count_opcodes(disassembly: str, pattern: str) -> int:
  return len(re.findall(rf'^\s*\d+:\s*{pattern}\b', disassembly, re.MULTILINE))


def branch_instruction_count(disassembly: str) -> int:
  return count_opcodes(disassembly,
    r'(if_[a-z_]+|ifeq|ifne|iflt|ifge|ifgt|ifle|if_icmp\w+|if_acmp\w+|goto|tableswitch|lookupswitch)')


def analyze(original_jar: Path, protected_jar: Path, mapping_path: Path, work: Path) -> dict:
  mapping = read_mapping(mapping_path)
  original_dir, protected_dir = work / 'orig-extract', work / 'prot-extract'
  original_classes = extract(original_jar, original_dir)
  protected_classes = extract(protected_jar, protected_dir)

  original_class_names = {p.relative_to(original_dir).with_suffix('').as_posix() for p in original_classes}
  protected_class_names = {p.relative_to(protected_dir).with_suffix('').as_posix() for p in protected_classes}
  classes_renamed = sum(1 for name in original_class_names if mapping.get(name, name) != name)
  classes_with_original_name_still_present = sorted(original_class_names & protected_class_names)

  original_strings, original_ints = constant_pool_literals(original_classes)
  protected_strings, protected_ints = constant_pool_literals(protected_classes)
  recoverable_strings = sorted(set(STRING_LITERALS) & protected_strings)
  recoverable_ints = sorted(set(INT_LITERALS) & protected_ints)

  mapped_name = mapping.get(f'{PACKAGE}/LicenseValidator', f'{PACKAGE}/LicenseValidator')
  protected_class_file = protected_dir / (mapped_name + '.class')
  original_class_file = original_dir / f'{PACKAGE}/LicenseValidator.class'

  classify_original = method_disassembly(original_class_file, 'classifyAttempt')
  hot_original = method_disassembly(original_class_file, 'hotCompute')
  protected_blocks = method_blocks(protected_class_file)

  # `classifyAttempt`'s branch structure (lightweight-control-flow reorders blocks; it does not add
  # a dispatcher) is attributable by instruction shape even if the method is also renamed: the only
  # non-virtualized method here with more than one branch instruction.
  candidate_branch_counts = []
  for block in protected_blocks:
    if 'Code:' not in block or 'execute' in block:
      continue
    branches = branch_instruction_count(block)
    if branches > 0:
      candidate_branch_counts.append(branches)
  classify_protected_branches = max(candidate_branch_counts) if candidate_branch_counts else 0

  invokedynamic_original = count_opcodes(classify_original + hot_original, r'invokedynamic')
  invokedynamic_protected = sum(count_opcodes(b, r'invokedynamic') for b in protected_blocks)

  # Method names (including the interpreter's own "executeInt" etc.) may be renamed too, so detect
  # virtualization by its one renaming-independent signature instead: an LDC `String` long enough
  # to be the Base64-encoded instruction program (ordinary string/number obfuscation here produces
  # short, few-dozen-character ciphertexts; the instruction-encoded program is reliably far longer).
  long_string = re.compile(r'String\s+\S{80,}')
  virtualized_present = any(long_string.search(b) for b in protected_blocks)
  # `hotCompute` is this fixture's only user of `imul` (`acc * 31`). Checking the whole class would
  # false-positive on `imul` legitimately introduced by *other* passes' own helpers (e.g. an
  # injected decoder using a multiplicative PRNG step) that have nothing to do with whether
  # `hotCompute`'s own multiply survived; look only at `hotCompute`'s own (possibly renamed) block.
  hot_compute_new_name = mapped_method_name(mapping, 'sample/LicenseValidator', 'hotCompute', '(I)I')
  hot_compute_block = next(
    (b for b in protected_blocks if re.search(rf'\b{re.escape(hot_compute_new_name)}\s*\(', b.strip().splitlines()[0] if b.strip() else '')), '')
  imul_still_present = count_opcodes(hot_compute_block, 'imul') > 0

  return {
    'schemaVersion': 1,
    'note': ("Measures what the JDK's own javap -- not a specific decompiler -- can read back "
             "verbatim from the output JAR's own class files. A method javap fully disassembles "
             "is not evidence of weak protection (javap disassembles any valid bytecode "
             "unconditionally); a method a particular decompiler renders badly is not by itself "
             "evidence of strong protection, since a different decompiler or version may do better."),
    'classes': {
      'total': len(original_class_names),
      'renamed': classes_renamed,
      'originalNameStillPresentInOutput': classes_with_original_name_still_present,
    },
    'stringLiterals': {
      'total': len(STRING_LITERALS),
      'recoverableVerbatim': recoverable_strings,
      'recoverableCount': len(recoverable_strings),
    },
    'numericLiterals': {
      'total': len(INT_LITERALS),
      'recoverableVerbatim': recoverable_ints,
      'recoverableCount': len(recoverable_ints),
    },
    'controlFlow': {
      'classifyAttemptOriginalBranches': branch_instruction_count(classify_original),
      'classifyAttemptObfuscatedBranchesBestGuess': classify_protected_branches,
      'note': 'Best-guess match by instruction shape, since the method name may be renamed too.',
    },
    'callIndirection': {
      'invokedynamicSitesOriginal': invokedynamic_original,
      'invokedynamicSitesObfuscated': invokedynamic_protected,
    },
    'virtualization': {
      'hotComputeDispatchesToInterpreter': virtualized_present,
      'hotComputeStillMultipliesInPlainBytecode': imul_still_present,
    },
  }


def render_text(result: dict) -> str:
  lines = ['Recoverable-structure inventory (javap-level, not decompiler-specific)', '', result['note'], '']
  c = result['classes']
  lines.append(f"Classes: {c['total']} total, {c['renamed']} renamed")
  s = result['stringLiterals']
  lines.append(f"String literals: {s['recoverableCount']}/{s['total']} recoverable verbatim: {s['recoverableVerbatim']}")
  n = result['numericLiterals']
  lines.append(f"Numeric literals: {n['recoverableCount']}/{n['total']} recoverable verbatim: {n['recoverableVerbatim']}")
  cf = result['controlFlow']
  lines.append(f"classifyAttempt branches: {cf['classifyAttemptOriginalBranches']} original -> "
               f"{cf['classifyAttemptObfuscatedBranchesBestGuess']} obfuscated")
  ci = result['callIndirection']
  lines.append(f"invokedynamic call sites: {ci['invokedynamicSitesOriginal']} original -> "
               f"{ci['invokedynamicSitesObfuscated']} obfuscated")
  v = result['virtualization']
  lines.append(f"hotCompute dispatches to the VM interpreter: {v['hotComputeDispatchesToInterpreter']}")
  lines.append(f"hotCompute's own imul still present in plain bytecode: {v['hotComputeStillMultipliesInPlainBytecode']}")
  return '\n'.join(lines) + '\n'


def main():
  parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
  parser.add_argument('--work-dir', type=Path, default=ROOT / 'build/recoverable-structure')
  parser.add_argument('--json', type=Path, help='write the machine-readable result here')
  args = parser.parse_args()
  work = args.work_dir.resolve()
  work.mkdir(parents=True, exist_ok=True)
  cli = build_cli(work)
  original = compile_fixture(work)
  protected, mapping = obfuscate(cli, original, work)
  result = analyze(original, protected, mapping, work)
  print(render_text(result))
  if args.json:
    args.json.write_text(json.dumps(result, indent=2) + '\n')
  return 0


if __name__ == '__main__':
  raise SystemExit(main())
