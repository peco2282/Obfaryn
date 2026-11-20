#!/usr/bin/env python3
"""Build localized copies of Dokka HTML without modifying API structure."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

from bs4 import BeautifulSoup, NavigableString, Tag


SCHEMA_VERSION = 1
TRANSLATABLE_SELECTOR = "p.paragraph"
PROTECTED_SELECTOR = "a, code, pre, .symbol"
TOKEN_PATTERN = re.compile(r"ZXQOBFARYN(\d+)QXZ")
HIGH_RISK = re.compile(
    r"\b(must(?:\s+not)?|nullable|non-null|thread-safe|not\s+thread-safe|"
    r"throws?|exception|security|required|forbidden|unless|only\s+if)\b",
    re.IGNORECASE,
)


def read_json(path: Path) -> dict:
  if not path.exists():
    return {"schemaVersion": SCHEMA_VERSION, "locale": path.parent.name, "entries": {}}
  return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict) -> None:
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def source_hash(source: str) -> str:
  return hashlib.sha256(source.encode("utf-8")).hexdigest()


def html_files(root: Path):
  yield from sorted(root.rglob("*.html"))


def protected_source(block: Tag) -> tuple[str, list[str]]:
  clone = BeautifulSoup(str(block), "html.parser").select_one(block.name)
  protected: list[str] = []
  for node in list(clone.select(PROTECTED_SELECTOR)):
    if node.find_parent(PROTECTED_SELECTOR):
      continue
    # Letter-only sentinels behave as unknown proper names in Argos models. In
    # contrast, underscore-heavy placeholders are commonly split or rewritten.
    token = f"ZXQOBFARYN{len(protected)}QXZ"
    protected.append(str(node))
    node.replace_with(NavigableString(token))
  return clone.decode_contents().strip(), protected


def entry_id(relative: Path, index: int) -> str:
  return f"{relative.as_posix()}#paragraph-{index}"


def extract(root: Path) -> dict[str, dict]:
  result: dict[str, dict] = {}
  for page in html_files(root):
    relative = page.relative_to(root)
    soup = BeautifulSoup(page.read_text(encoding="utf-8"), "html.parser")
    for index, block in enumerate(soup.select(TRANSLATABLE_SELECTOR)):
      source, protected = protected_source(block)
      if not source or not BeautifulSoup(source, "html.parser").get_text(strip=True):
        continue
      key = entry_id(relative, index)
      result[key] = {
          "source": source,
          "sourceHash": source_hash(source),
          "protectedNodes": protected,
          "highRisk": bool(HIGH_RISK.search(BeautifulSoup(source, "html.parser").get_text(" "))),
      }
  return result


def sync(args: argparse.Namespace) -> int:
  catalog = read_json(args.catalog)
  entries = catalog.setdefault("entries", {})
  found = extract(args.input)
  for key, current in found.items():
    previous = entries.get(key)
    if previous is None:
      entries[key] = {**current, "translation": "", "status": "missing", "engine": None}
      continue
    if previous.get("sourceHash") != current["sourceHash"]:
      previous["status"] = "stale" if previous.get("translation") else "missing"
    previous.update(current)
  for key in sorted(set(entries) - set(found)):
    entries[key]["orphaned"] = True
  catalog["schemaVersion"] = SCHEMA_VERSION
  catalog["entries"] = dict(sorted(entries.items()))
  write_json(args.catalog, catalog)
  counts: dict[str, int] = {}
  for value in entries.values():
    counts[value.get("status", "unknown")] = counts.get(value.get("status", "unknown"), 0) + 1
  print(f"Synchronized {len(found)} fragments: {counts}")
  return 0


def install_argos_model(from_code: str, to_code: str) -> None:
  import argostranslate.package

  argostranslate.package.update_package_index()
  packages = argostranslate.package.get_available_packages()
  package = next((p for p in packages if p.from_code == from_code and p.to_code == to_code), None)
  if package is None:
    raise RuntimeError(f"No Argos model is available for {from_code}->{to_code}")
  argostranslate.package.install_from_path(package.download())


def translate(args: argparse.Namespace) -> int:
  import argostranslate.translate

  if args.install_model:
    install_argos_model("en", args.locale)
  catalog = read_json(args.catalog)
  changed = 0
  selected = 0
  rejected = 0
  for key, value in catalog.get("entries", {}).items():
    if args.key_prefix and not key.startswith(args.key_prefix):
      continue
    if value.get("status") not in {"missing", "stale"}:
      continue
    if value.get("status") == "stale" and not args.include_stale:
      continue
    selected += 1
    source = value["source"]
    translated = argostranslate.translate.translate(source, "en", args.locale)
    expected = TOKEN_PATTERN.findall(source)
    actual = TOKEN_PATTERN.findall(translated)
    if expected != actual:
      print(f"Rejected translation that changed protected-node tokens: {key}", file=sys.stderr)
      rejected += 1
      continue
    value["translation"] = translated
    value["status"] = "machine"
    value["engine"] = "argos-translate-1.11.0"
    changed += 1
    if args.limit and changed >= args.limit:
      break
  write_json(args.catalog, catalog)
  print(f"Selected {selected}; translated {changed}; rejected {rejected} fragments")
  if args.require_complete and rejected:
    return 1
  return 0


def restore_fragment(translated: str, protected: list[str]) -> str:
  expected = [str(i) for i in range(len(protected))]
  if TOKEN_PATTERN.findall(translated) != expected:
    raise ValueError("translation does not preserve protected-node tokens")
  for index, html in enumerate(protected):
    translated = translated.replace(f"ZXQOBFARYN{index}QXZ", html)
  return translated


def apply_catalog(page: Path, relative: Path, catalog: dict) -> None:
  soup = BeautifulSoup(page.read_text(encoding="utf-8"), "html.parser")
  if soup.html:
    soup.html["lang"] = catalog.get("locale", "ja")
  for index, block in enumerate(soup.select(TRANSLATABLE_SELECTOR)):
    value = catalog.get("entries", {}).get(entry_id(relative, index))
    if not value or value.get("status") not in {"machine", "reviewed"}:
      continue
    fragment = restore_fragment(value["translation"], value.get("protectedNodes", []))
    replacement = BeautifulSoup(fragment, "html.parser")
    block.clear()
    for child in list(replacement.contents):
      block.append(child)
    if value.get("status") == "machine":
      block["data-translation-status"] = "machine"
  page.write_text(str(soup), encoding="utf-8")


def inject_switch(page: Path, relative: Path, localized: bool) -> None:
  soup = BeautifulSoup(page.read_text(encoding="utf-8"), "html.parser")
  controls = soup.select_one(".navigation-controls")
  if controls is None or soup.select_one(".docs-language-switch"):
    return
  depth = len(relative.parts) - 1
  if localized:
    target = "../" * (depth + 1) + relative.as_posix()
    label = "English"
  else:
    target = "../" * depth + "ja/" + relative.as_posix()
    label = "日本語"
  link = soup.new_tag("a", href=target)
  link["class"] = "docs-language-switch"
  link.string = label
  controls.insert(0, link)
  page.write_text(str(soup), encoding="utf-8")


def build(args: argparse.Namespace) -> int:
  if args.output.exists():
    shutil.rmtree(args.output)
  shutil.copytree(args.input, args.output)
  localized = args.output / args.locale
  shutil.copytree(args.input, localized)
  catalog = read_json(args.catalog)
  for english_page in html_files(args.output):
    if localized in english_page.parents:
      continue
    inject_switch(english_page, english_page.relative_to(args.output), localized=False)
  for page in html_files(localized):
    relative = page.relative_to(localized)
    apply_catalog(page, relative, catalog)
    inject_switch(page, relative, localized=True)
  print(f"Built English and {args.locale} sites at {args.output}")
  return 0


def normalized_structure(path: Path) -> tuple[list[str], list[str], list[str]]:
  soup = BeautifulSoup(path.read_text(encoding="utf-8"), "html.parser")
  links = [node.get("href", "") for node in soup.select("a[href]") if "docs-language-switch" not in node.get("class", [])]
  symbols = [node.get_text(" ", strip=True) for node in soup.select(".symbol")]
  code = [node.get_text(" ", strip=True) for node in soup.select("code, pre")]
  return links, symbols, code


def verify(args: argparse.Namespace) -> int:
  failures: list[str] = []
  english_pages = list(html_files(args.english))
  for english in english_pages:
    relative = english.relative_to(args.english)
    localized = args.localized / relative
    if not localized.exists():
      failures.append(f"missing localized page: {relative}")
      continue
    if normalized_structure(english) != normalized_structure(localized):
      failures.append(f"API structure changed: {relative}")
  if failures:
    print("\n".join(failures[:50]), file=sys.stderr)
    return 1
  print(f"Verified {len(english_pages)} localized pages; links, symbols, and code are unchanged")
  return 0


def parser() -> argparse.ArgumentParser:
  root = argparse.ArgumentParser()
  commands = root.add_subparsers(dest="command", required=True)
  sync_parser = commands.add_parser("sync")
  sync_parser.add_argument("--input", type=Path, required=True)
  sync_parser.add_argument("--catalog", type=Path, required=True)
  sync_parser.set_defaults(run=sync)
  translate_parser = commands.add_parser("translate")
  translate_parser.add_argument("--catalog", type=Path, required=True)
  translate_parser.add_argument("--locale", default="ja")
  translate_parser.add_argument("--install-model", action="store_true")
  translate_parser.add_argument("--include-stale", action="store_true")
  translate_parser.add_argument("--limit", type=int)
  translate_parser.add_argument("--key-prefix")
  translate_parser.add_argument("--require-complete", action="store_true")
  translate_parser.set_defaults(run=translate)
  build_parser = commands.add_parser("build")
  build_parser.add_argument("--input", type=Path, required=True)
  build_parser.add_argument("--output", type=Path, required=True)
  build_parser.add_argument("--catalog", type=Path, required=True)
  build_parser.add_argument("--locale", default="ja")
  build_parser.set_defaults(run=build)
  verify_parser = commands.add_parser("verify")
  verify_parser.add_argument("--english", type=Path, required=True)
  verify_parser.add_argument("--localized", type=Path, required=True)
  verify_parser.set_defaults(run=verify)
  return root


if __name__ == "__main__":
  arguments = parser().parse_args()
  raise SystemExit(arguments.run(arguments))
