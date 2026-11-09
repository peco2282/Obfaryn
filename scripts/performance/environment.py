"""Shared environment metadata for performance-benchmark result files.

Every benchmark result should be traceable back to the machine and commit that produced it:
without this, a shared CI runner's noise or an unrelated code change is indistinguishable from a
real regression. This module adds the fields the synthetic benchmarks (`run.py`,
`virtualization.py`) do not already collect themselves (`java -version`, `platform.platform()`).
"""
import os
import platform
import re
import subprocess
from pathlib import Path


def git_commit(root: Path) -> str | None:
  """The repository's current commit SHA, or `None` outside a git checkout (e.g. a source tarball)."""
  try:
    return subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, text=True,
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True).stdout.strip()
  except (OSError, subprocess.CalledProcessError):
    return None


def cpu_model() -> str:
  """A human-readable CPU model string; `platform.processor()` is often empty on Linux."""
  processor = platform.processor()
  if processor:
    return processor
  if platform.system() == 'Linux':
    try:
      text = Path('/proc/cpuinfo').read_text()
      match = re.search(r'^model name\s*:\s*(.+)$', text, re.MULTILINE)
      if match:
        return match.group(1).strip()
    except OSError:
      pass
  return platform.machine() or 'unknown'


def collect(root: Path) -> dict:
  """OS, CPU and git-commit fields to merge into a benchmark's `results.json`."""
  return {
    'os': platform.platform(),
    'cpu': cpu_model(),
    'cpuCount': os.cpu_count(),
    'gitCommit': git_commit(root),
  }
