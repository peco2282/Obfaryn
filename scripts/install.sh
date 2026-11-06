#!/usr/bin/env bash
# Installs the obfaryn CLI from a GitHub Release tarball (Linux and macOS).
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/peco2282/Obfaryn/main/scripts/install.sh | bash
#
# Env vars:
#   OBFARYN_VERSION   Version to install (default: latest release)
#   OBFARYN_HOME      Install directory (default: ~/.obfaryn)
#   OBFARYN_BIN_DIR   Where to symlink the launcher (default: ~/.local/bin)
set -euo pipefail

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  BOLD="$(printf '\033[1m')"; DIM="$(printf '\033[2m')"
  GREEN="$(printf '\033[32m')"; YELLOW="$(printf '\033[33m')"
  RED="$(printf '\033[31m')"; CYAN="$(printf '\033[36m')"
  RESET="$(printf '\033[0m')"
else
  BOLD=""; DIM=""; GREEN=""; YELLOW=""; RED=""; CYAN=""; RESET=""
fi
info()  { printf '%s==>%s %s\n' "$CYAN" "$RESET" "$1"; }
ok()    { printf '%s✓%s %s\n' "$GREEN" "$RESET" "$1"; }
warn()  { printf '%s!%s %s\n' "$YELLOW" "$RESET" "$1"; }
die()   { printf '%serror:%s %s\n' "$RED" "$RESET" "$1" >&2; exit 1; }

REPO="peco2282/Obfaryn"
INSTALL_DIR="${OBFARYN_HOME:-$HOME/.obfaryn}"
BIN_DIR="${OBFARYN_BIN_DIR:-$HOME/.local/bin}"

if [ -z "${OBFARYN_VERSION:-}" ]; then
  info "Resolving latest release..."
  VERSION="$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep -o '"tag_name": *"v[^"]*"' | head -1 | sed -E 's/.*"v([^"]+)"/\1/')"
  [ -n "$VERSION" ] || die "could not resolve the latest release; set OBFARYN_VERSION explicitly"
else
  VERSION="$OBFARYN_VERSION"
fi

ARCHIVE="obfaryn-${VERSION}.tar.gz"
URL="https://github.com/${REPO}/releases/download/v${VERSION}/${ARCHIVE}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

info "Downloading ${BOLD}obfaryn ${VERSION}${RESET}..."
curl -fsSL "$URL" -o "${TMP_DIR}/${ARCHIVE}" || die "download failed: $URL"

info "Installing to ${INSTALL_DIR}..."
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR" "$BIN_DIR"
tar -xzf "${TMP_DIR}/${ARCHIVE}" -C "$INSTALL_DIR" --strip-components=1

ln -sf "${INSTALL_DIR}/bin/obfaryn" "${BIN_DIR}/obfaryn"

# Shell completion (bash-completion / zsh's bashcompinit convention): installed
# best-effort, never fails the install.
COMPLETION_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/bash-completion/completions"
if mkdir -p "$COMPLETION_DIR" 2>/dev/null; then
  "${INSTALL_DIR}/bin/obfaryn" completion bash > "${COMPLETION_DIR}/obfaryn" 2>/dev/null || true
fi

echo
ok "Installed ${BOLD}obfaryn ${VERSION}${RESET} to ${DIM}${INSTALL_DIR}${RESET}"
case ":$PATH:" in
  *":${BIN_DIR}:"*) ;;
  *) warn "Add ${BIN_DIR} to your PATH, e.g.: ${DIM}export PATH=\"${BIN_DIR}:\$PATH\"${RESET}" ;;
esac
echo "${DIM}Bash completion installed to ${COMPLETION_DIR}/obfaryn (needs the bash-completion package to be auto-sourced).${RESET}"
echo "${DIM}For zsh completion instead: obfaryn completion zsh > ~/.zsh/completions/_obfaryn (see README).${RESET}"
echo "Run: ${BOLD}obfaryn --help${RESET}"
