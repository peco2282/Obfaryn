#!/usr/bin/env python3
"""Aggregate a schema-version-2 transformation report into a protection-evaluation summary.

Turns the per-method rows written by `--transformation-report` into per-pass and overall figures:
how many methods each pass actually transformed, how many it skipped or rolled back and why, the
aggregate instruction growth, and which committed methods ran close to a structural limit. Optionally
folds in the `--report` summary JSON (top-line protection counts) and the input/output JAR sizes.

This quantifies *protection application*, which is not the same as analysis difficulty or security
strength: a high application rate does not prove a method is hard to reverse, and a skipped method is
not necessarily a weakness (it may be an intentional keep/exclusion). Residual original-name/string
scanning and decompiled-structure comparison are deliberately out of scope here.

The transformation report carries original names; keep it, and any summary derived from it, out of
distributed artifacts.
"""
import argparse
import json
import sys
from pathlib import Path

NEAR_DEFAULT = 0.9


def near_limit(row, threshold):
  """True when a committed method ran within `threshold` of any structural limit in effect."""
  budget = row.get('appliedConstraints')
  if not budget or row.get('status') != 'applied':
    return False
  checks = []
  grow = budget.get('maxGrowthPercent')
  if grow and row.get('growthPercent', -1) >= 0:
    checks.append(row['growthPercent'] >= threshold * grow)
  code = budget.get('maxOutputCodeBytes')
  if code:
    checks.append(row.get('outputCodeBytes', 0) >= threshold * code)
  added = budget.get('maxAddedInstructions')
  if added is not None and added > 0:
    checks.append((row.get('outputInstructions', 0) - row.get('inputInstructions', 0)) >= threshold * added)
  return any(checks)


def histogram(rows, status, field='reason'):
  out = {}
  for row in rows:
    if row.get('status') == status:
      out[row.get(field, '')] = out.get(row.get(field, ''), 0) + 1
  return dict(sorted(out.items(), key=lambda kv: (-kv[1], kv[0])))


def summarize(report, threshold=NEAR_DEFAULT):
  if report.get('schemaVersion') != 2:
    raise ValueError(f"Expected transformation report schemaVersion 2, got {report.get('schemaVersion')!r}")
  passes = []
  for pass_report in report.get('passes', []):
    rows = pass_report.get('methods', [])
    statuses = {s: sum(1 for r in rows if r.get('status') == s) for s in ('applied', 'skipped', 'rejected')}
    input_insns = sum(r.get('inputInstructions', 0) for r in rows if r.get('status') == 'applied')
    output_insns = sum(r.get('outputInstructions', 0) for r in rows if r.get('status') == 'applied')
    passes.append({
      'id': pass_report.get('id'),
      'methods': len(rows),
      'applied': statuses['applied'],
      'skipped': statuses['skipped'],
      'rejected': statuses['rejected'],
      'rejectionReasons': histogram(rows, 'rejected'),
      'skipReasons': histogram(rows, 'skipped'),
      'appliedInputInstructions': input_insns,
      'appliedOutputInstructions': output_insns,
      'appliedGrowthPercent': round(output_insns * 100 / input_insns, 1) if input_insns else None,
      'methodsNearLimit': sum(1 for r in rows if near_limit(r, threshold)),
      # Wall-clock cost of running this one pass over the whole input, in milliseconds; absent
      # from reports written before this field existed. One run, one process: a profiling figure
      # for where obfuscation time goes, not a JMH-grade steady-state measurement.
      'elapsedMillis': round(pass_report.get('metrics', {})['elapsedNanos'] / 1e6, 3)
        if 'elapsedNanos' in pass_report.get('metrics', {}) else None,
    })
  total = {
    'passes': len(passes),
    'applied': sum(p['applied'] for p in passes),
    'skipped': sum(p['skipped'] for p in passes),
    'rejected': sum(p['rejected'] for p in passes),
    'methodsNearLimit': sum(p['methodsNearLimit'] for p in passes),
    'elapsedMillis': round(sum(p['elapsedMillis'] for p in passes if p['elapsedMillis'] is not None), 3)
      if any(p['elapsedMillis'] is not None for p in passes) else None,
  }
  decisions = total['applied'] + total['skipped'] + total['rejected']
  total['appliedFraction'] = round(total['applied'] / decisions, 4) if decisions else None
  return {'schemaVersion': 2, 'nearLimitThreshold': threshold, 'passes': passes, 'total': total}


def with_context(result, summary_json, input_jar, output_jar):
  if summary_json is not None:
    result['protectionCounts'] = {k: summary_json.get(k) for k in (
      'stringsEncrypted', 'constantsObfuscated', 'classesRenamed', 'methodsRenamed',
      'fieldsRenamed', 'branchesObfuscated', 'methodsVirtualized') if k in summary_json}
  sizes = {}
  if input_jar and input_jar.is_file():
    sizes['inputBytes'] = input_jar.stat().st_size
  if output_jar and output_jar.is_file():
    sizes['outputBytes'] = output_jar.stat().st_size
  if 'inputBytes' in sizes and 'outputBytes' in sizes and sizes['inputBytes']:
    sizes['growthPercent'] = round(sizes['outputBytes'] * 100 / sizes['inputBytes'], 1)
  if sizes:
    result['jarSize'] = sizes
  return result


def render_text(result):
  lines = ['Protection application summary (application rate != security strength)', '']
  t = result['total']
  lines.append(f"Passes: {t['passes']}  applied: {t['applied']}  skipped: {t['skipped']}  "
               f"rejected: {t['rejected']}  near-limit: {t['methodsNearLimit']}")
  if t['appliedFraction'] is not None:
    lines.append(f"Applied fraction of all method decisions: {t['appliedFraction']:.1%}")
  if 'protectionCounts' in result:
    lines.append('Protection counts: ' + '  '.join(f"{k}={v}" for k, v in result['protectionCounts'].items()))
  if 'jarSize' in result:
    s = result['jarSize']
    grow = f" ({s['growthPercent']}% of input)" if 'growthPercent' in s else ''
    lines.append(f"JAR size: input={s.get('inputBytes', '?')}B output={s.get('outputBytes', '?')}B{grow}")
  lines.append('')
  for p in result['passes']:
    grow = f"{p['appliedGrowthPercent']}%" if p['appliedGrowthPercent'] is not None else 'n/a'
    time = f" time={p['elapsedMillis']}ms" if p.get('elapsedMillis') is not None else ''
    lines.append(f"[{p['id']}] methods={p['methods']} applied={p['applied']} skipped={p['skipped']} "
                 f"rejected={p['rejected']} growth={grow} near-limit={p['methodsNearLimit']}{time}")
    for reason, count in p['rejectionReasons'].items():
      lines.append(f"    rejected x{count}: {reason}")
  if t.get('elapsedMillis') is not None:
    lines.append(f"\nTotal pass time: {t['elapsedMillis']}ms")
  return '\n'.join(lines) + '\n'


def render_html(result):
  def esc(text):
    return (str(text).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'))
  rows = []
  for p in result['passes']:
    grow = f"{p['appliedGrowthPercent']}%" if p['appliedGrowthPercent'] is not None else 'n/a'
    time = f"{p['elapsedMillis']}" if p.get('elapsedMillis') is not None else '&mdash;'
    reasons = '<br>'.join(f"{esc(r)} &times;{c}" for r, c in p['rejectionReasons'].items()) or '&mdash;'
    rows.append(f"<tr><td>{esc(p['id'])}</td><td>{p['methods']}</td><td>{p['applied']}</td>"
                f"<td>{p['skipped']}</td><td>{p['rejected']}</td><td>{grow}</td>"
                f"<td>{p['methodsNearLimit']}</td><td>{time}</td><td class='r'>{reasons}</td></tr>")
  t = result['total']
  counts = ''
  if 'protectionCounts' in result:
    counts = '<p>' + ' &middot; '.join(f"{esc(k)}: <b>{esc(v)}</b>" for k, v in result['protectionCounts'].items()) + '</p>'
  size = ''
  if 'jarSize' in result:
    s = result['jarSize']
    grow = f" ({s['growthPercent']}% of input)" if 'growthPercent' in s else ''
    size = f"<p>JAR size: input {s.get('inputBytes', '?')} B &rarr; output {s.get('outputBytes', '?')} B{grow}</p>"
  return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Obfaryn protection summary</title>
<style>
:root {{ color-scheme: light dark; --fg:#1a1a1a; --bg:#fff; --muted:#666; --line:#ddd; --head:#f3f3f3; }}
@media (prefers-color-scheme: dark) {{ :root {{ --fg:#e6e6e6; --bg:#17191c; --muted:#9aa0a6; --line:#333; --head:#22262b; }} }}
body {{ font: 15px/1.5 system-ui, sans-serif; color: var(--fg); background: var(--bg); margin: 0; padding: 24px; }}
h1 {{ font-size: 1.3rem; margin: 0 0 .3rem; }}
.note {{ color: var(--muted); margin: 0 0 1rem; }}
table {{ border-collapse: collapse; width: 100%; }}
th, td {{ border: 1px solid var(--line); padding: 6px 10px; text-align: right; vertical-align: top; }}
th:first-child, td:first-child, td.r {{ text-align: left; }}
thead th {{ background: var(--head); }}
td.r {{ color: var(--muted); font-size: .85em; }}
</style></head><body>
<h1>Obfaryn protection application summary</h1>
<p class="note">Application rate is not security strength: a committed method is not proven hard to
reverse, and a skipped method may be an intentional keep/exclusion.</p>
<p>Passes: <b>{t['passes']}</b> &middot; applied <b>{t['applied']}</b> &middot; skipped
<b>{t['skipped']}</b> &middot; rejected <b>{t['rejected']}</b> &middot; near-limit
<b>{t['methodsNearLimit']}</b>{'' if t['appliedFraction'] is None else f" &middot; applied fraction <b>{t['appliedFraction']:.1%}</b>"}</p>
{counts}{size}
<table><thead><tr><th>Pass</th><th>Methods</th><th>Applied</th><th>Skipped</th><th>Rejected</th>
<th>Growth</th><th>Near limit</th><th>Time (ms)</th><th>Rejection reasons</th></tr></thead>
<tbody>
{chr(10).join(rows)}
</tbody></table>
</body></html>
"""


def self_test():
  report = {'schemaVersion': 2, 'passes': [
    {'id': 'integer-arithmetic', 'metrics': {}, 'methods': [
      {'method': 'a/B.f()I', 'status': 'applied', 'reason': 'transformed', 'inputInstructions': 10,
       'outputInstructions': 18, 'inputBranches': 1, 'outputBranches': 1, 'inputCodeBytes': 20,
       'outputCodeBytes': 40, 'growthPercent': 180, 'appliedConstraints': {
         'maxInputInstructions': None, 'maxInputBranches': None, 'maxAddedInstructions': 1024,
         'maxGrowthPercent': 200, 'growthAllowanceInstructions': 64, 'maxOutputCodeBytes': 60000}},
      {'method': 'a/B.g()I', 'status': 'rejected', 'reason': 'class-rollback:budget:growth-percent:a/B.g()I',
       'inputInstructions': 5, 'outputInstructions': 5, 'inputBranches': 0, 'outputBranches': 0,
       'inputCodeBytes': 10, 'outputCodeBytes': 10, 'growthPercent': 100, 'appliedConstraints': {
         'maxInputInstructions': None, 'maxInputBranches': None, 'maxAddedInstructions': 1024,
         'maxGrowthPercent': 200, 'growthAllowanceInstructions': 64, 'maxOutputCodeBytes': 60000}},
      {'method': 'a/B.h()I', 'status': 'skipped', 'reason': 'no-eligible-instructions-or-unsupported',
       'inputInstructions': 3, 'outputInstructions': 3, 'inputBranches': 0, 'outputBranches': 0,
       'inputCodeBytes': 6, 'outputCodeBytes': 6, 'growthPercent': 100, 'appliedConstraints': None},
    ]}]}
  result = summarize(report, threshold=0.9)
  p = result['passes'][0]
  assert p['applied'] == 1 and p['skipped'] == 1 and p['rejected'] == 1, p
  assert p['appliedGrowthPercent'] == 180.0, p  # 18*100/10
  assert p['methodsNearLimit'] == 1, p          # 180 >= 0.9*200
  assert 'class-rollback:budget:growth-percent:a/B.g()I' in p['rejectionReasons'], p
  assert result['total']['appliedFraction'] == round(1 / 3, 4), result['total']
  text = render_text(result)
  assert 'integer-arithmetic' in text and 'near-limit=1' in text, text
  html = render_html(result)
  assert '<table>' in html and 'integer-arithmetic' in html and 'security strength' in html, 'html'
  # Reject a wrong schema version.
  try:
    summarize({'schemaVersion': 1, 'passes': []})
    raise AssertionError('should reject schemaVersion 1')
  except ValueError:
    pass
  print('self-test OK')
  return 0


def main():
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument('--transformation-report', type=Path, help='schema version 2 JSON from --transformation-report')
  parser.add_argument('--summary', type=Path, help='optional JSON from --report')
  parser.add_argument('--input', type=Path, help='optional input JAR for a size baseline')
  parser.add_argument('--jar', type=Path, help='optional output JAR for a size delta')
  parser.add_argument('--near-threshold', type=float, default=NEAR_DEFAULT, help='fraction of a limit counted as near (default 0.9)')
  parser.add_argument('--json', type=Path, help='write the machine-readable summary here')
  parser.add_argument('--html', type=Path, help='write a self-contained HTML report here')
  parser.add_argument('--self-test', action='store_true', help='run built-in checks and exit')
  args = parser.parse_args()
  if args.self_test:
    return self_test()
  if not args.transformation_report:
    parser.error('--transformation-report is required (or use --self-test)')
  if not 0 < args.near_threshold <= 1:
    parser.error('--near-threshold must be in (0, 1]')
  report = json.loads(args.transformation_report.read_text())
  summary_json = json.loads(args.summary.read_text()) if args.summary else None
  result = with_context(summarize(report, args.near_threshold), summary_json, args.input, args.jar)
  if args.json:
    args.json.write_text(json.dumps(result, indent=2) + '\n')
  if args.html:
    args.html.write_text(render_html(result))
  sys.stdout.write(render_text(result))
  return 0


if __name__ == '__main__':
  raise SystemExit(main())
