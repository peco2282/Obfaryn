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

try:
  from bs4 import BeautifulSoup, NavigableString, Tag
except ModuleNotFoundError:
  BeautifulSoup = None
  NavigableString = None
  Tag = object


SCHEMA_VERSION = 2
TRANSLATABLE_SELECTOR = "p.paragraph"
PROTECTED_SELECTOR = "a, code, pre, .symbol"
TOKEN_PATTERN = re.compile(r"ZXQOBFARYN(\d+)QXZ")
LANGUAGE_STYLESHEET = Path(__file__).with_name("docs-i18n.css")
HIGH_RISK = re.compile(
    r"\b(must(?:\s+not)?|nullable|non-null|thread-safe|not\s+thread-safe|"
    r"throws?|exception|security|required|forbidden|unless|only\s+if)\b",
    re.IGNORECASE,
)
GENERATED_DOKKA_TEXT = {
    "Returns a representation of an immutable list of all enum entries, in the order they're declared.",
    "This method may be used to iterate over the enum entries.",
    "Returns the enum constant of this type with the specified name. The string must match exactly an identifier used to declare an enum constant in this type. (Extraneous whitespace characters are not permitted.)",
    "Returns an array containing the constants of this enum type, in the order they're declared.",
    "if this enum type has no constant with the specified name",
    "This method may be used to iterate over the constants.",
}


def require_html_parser() -> None:
  if BeautifulSoup is None:
    raise RuntimeError(
        "Beautiful Soup is required for HTML processing; install scripts/docs-i18n/requirements.txt"
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
  require_html_parser()
  result: dict[str, dict] = {}
  for page in html_files(root):
    relative = page.relative_to(root)
    soup = BeautifulSoup(page.read_text(encoding="utf-8"), "html.parser")
    for index, block in enumerate(soup.select(TRANSLATABLE_SELECTOR)):
      source, protected = protected_source(block)
      plain_text = BeautifulSoup(source, "html.parser").get_text(" ", strip=True)
      if not source or not plain_text or plain_text in GENERATED_DOKKA_TEXT:
        continue
      key = entry_id(relative, index)
      result[key] = {
          "source": source,
          "sourceHash": source_hash(source),
          "protectedNodes": protected,
          "highRisk": bool(HIGH_RISK.search(plain_text)),
      }
  return result


def sync(args: argparse.Namespace) -> int:
  catalog = read_json(args.catalog)
  entries = catalog.setdefault("entries", {})
  found = extract(args.input)
  for key, current in found.items():
    previous = entries.get(key)
    if previous is None:
      entries[key] = current
      continue
    previous.update(current)
    previous.pop("orphaned", None)
    previous.pop("translation", None)
    previous.pop("status", None)
    previous.pop("engine", None)
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


def normalize(args: argparse.Namespace) -> int:
  """Normalize a generated catalog after importing it from an Actions artifact."""
  catalog = read_json(args.catalog)
  for value in catalog.get("entries", {}).values():
    value.pop("translation", None)
    value.pop("status", None)
    value.pop("engine", None)
  catalog["schemaVersion"] = SCHEMA_VERSION
  write_json(args.catalog, catalog)
  print(f"Normalized {len(catalog.get('entries', {}))} catalog entries")
  return 0


def validate_translations(args: argparse.Namespace) -> int:
  catalog = read_json(args.catalog)
  translations = read_json(args.translations).get("translations", {})
  known_hashes = {value["sourceHash"] for value in catalog.get("entries", {}).values()}
  failures: list[str] = []
  for digest, value in translations.items():
    source = value.get("source", "")
    translation = value.get("translation", "")
    if source_hash(source) != digest:
      failures.append(f"source hash mismatch: {digest}")
    if digest not in known_hashes:
      failures.append(f"translation is not used by the catalog: {digest}")
    if not translation:
      failures.append(f"empty translation: {digest}")
    if TOKEN_PATTERN.findall(source) != TOKEN_PATTERN.findall(translation):
      failures.append(f"protected-node tokens changed: {digest}")
    if value.get("status") not in {"machine", "reviewed"}:
      failures.append(f"invalid translation status: {digest}")
  if failures:
    print("\n".join(failures), file=sys.stderr)
    return 1
  print(f"Validated {len(translations)} translations")
  return 0


def restore_fragment(translated: str, protected: list[str]) -> str:
  expected = [str(i) for i in range(len(protected))]
  if TOKEN_PATTERN.findall(translated) != expected:
    raise ValueError("translation does not preserve protected-node tokens")
  for index, html in enumerate(protected):
    translated = translated.replace(f"ZXQOBFARYN{index}QXZ", html)
  return translated


def apply_catalog(page: Path, relative: Path, catalog: dict, translations: dict) -> None:
  require_html_parser()
  soup = BeautifulSoup(page.read_text(encoding="utf-8"), "html.parser")
  if soup.html:
    soup.html["lang"] = catalog.get("locale", "ja")
  for index, block in enumerate(soup.select(TRANSLATABLE_SELECTOR)):
    value = catalog.get("entries", {}).get(entry_id(relative, index))
    if not value:
      continue
    translated = translations.get("translations", {}).get(value["sourceHash"])
    if not translated or translated.get("status") not in {"machine", "reviewed"}:
      continue
    fragment = restore_fragment(translated["translation"], value.get("protectedNodes", []))
    replacement = BeautifulSoup(fragment, "html.parser")
    block.clear()
    for child in list(replacement.contents):
      block.append(child)
    if translated.get("status") == "machine":
      block["data-translation-status"] = "machine"
  page.write_text(str(soup), encoding="utf-8")


def inject_switch(page: Path, relative: Path, localized: bool) -> None:
  require_html_parser()
  soup = BeautifulSoup(page.read_text(encoding="utf-8"), "html.parser")
  controls = soup.select_one(".navigation-controls")
  if controls is None or soup.select_one(".docs-language-switch"):
    return
  depth = len(relative.parts) - 1
  if localized:
    target = "../" * (depth + 1) + relative.as_posix()
    current = "日本語"
  else:
    target = "../" * depth + "ja/" + relative.as_posix()
    current = "English"

  stylesheet = soup.new_tag(
      "link",
      rel="stylesheet",
      href="../" * depth + "styles/docs-i18n.css",
  )
  if soup.head:
    soup.head.append(stylesheet)

  menu = soup.new_tag("details")
  menu["class"] = "docs-language-switch"
  summary = soup.new_tag("summary")
  summary["aria-label"] = f"Language: {current}"
  icon = soup.new_tag("span")
  icon["class"] = "docs-language-icon"
  icon["aria-hidden"] = "true"
  icon.string = "文"
  label = soup.new_tag("span")
  label["class"] = "docs-language-current"
  label.string = current
  summary.extend([icon, label])
  menu.append(summary)

  options = soup.new_tag("nav")
  options["class"] = "docs-language-options"
  options["aria-label"] = "Languages"
  for language in ("English", "日本語"):
    if language == current:
      option = soup.new_tag("span")
      option["aria-current"] = "page"
      option["class"] = "docs-language-option is-active"
    else:
      option = soup.new_tag("a", href=target)
      option["class"] = "docs-language-option"
    option.string = language
    options.append(option)
  menu.append(options)
  controls.insert(0, menu)
  page.write_text(str(soup), encoding="utf-8")


def build(args: argparse.Namespace) -> int:
  if args.output.exists():
    shutil.rmtree(args.output)
  shutil.copytree(args.input, args.output)
  localized = args.output / args.locale
  shutil.copytree(args.input, localized)
  for root in (args.output, localized):
    styles = root / "styles"
    styles.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(LANGUAGE_STYLESHEET, styles / LANGUAGE_STYLESHEET.name)
  catalog = read_json(args.catalog)
  translations = read_json(args.translations)
  for english_page in html_files(args.output):
    if localized in english_page.parents:
      continue
    inject_switch(english_page, english_page.relative_to(args.output), localized=False)
  for page in html_files(localized):
    relative = page.relative_to(localized)
    apply_catalog(page, relative, catalog, translations)
    inject_switch(page, relative, localized=True)
  print(f"Built English and {args.locale} sites at {args.output}")
  return 0


def normalized_structure(path: Path) -> tuple[list[str], list[str], list[str]]:
  require_html_parser()
  soup = BeautifulSoup(path.read_text(encoding="utf-8"), "html.parser")
  links = [
      node.get("href", "")
      for node in soup.select("a[href]")
      if node.find_parent(class_="docs-language-switch") is None
  ]
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
  normalize_parser = commands.add_parser("normalize")
  normalize_parser.add_argument("--catalog", type=Path, required=True)
  normalize_parser.set_defaults(run=normalize)
  validate_parser = commands.add_parser("validate-translations")
  validate_parser.add_argument("--catalog", type=Path, required=True)
  validate_parser.add_argument("--translations", type=Path, required=True)
  validate_parser.set_defaults(run=validate_translations)
  build_parser = commands.add_parser("build")
  build_parser.add_argument("--input", type=Path, required=True)
  build_parser.add_argument("--output", type=Path, required=True)
  build_parser.add_argument("--catalog", type=Path, required=True)
  build_parser.add_argument("--translations", type=Path, required=True)
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
