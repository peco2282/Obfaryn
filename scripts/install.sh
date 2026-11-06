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

REPO="peco2282/Obfaryn"
INSTALL_DIR="${OBFARYN_HOME:-$HOME/.obfaryn}"
BIN_DIR="${OBFARYN_BIN_DIR:-$HOME/.local/bin}"

if [ -z "${OBFARYN_VERSION:-}" ]; then
  echo "Resolving latest release..."
  VERSION="$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep -o '"tag_name": *"v[^"]*"' | head -1 | sed -E 's/.*"v([^"]+)"/\1/')"
  if [ -z "$VERSION" ]; then
    echo "error: could not resolve the latest release; set OBFARYN_VERSION explicitly" >&2
    exit 1
  fi
else
  VERSION="$OBFARYN_VERSION"
fi

ARCHIVE="obfaryn-${VERSION}.tar.gz"
URL="https://github.com/${REPO}/releases/download/v${VERSION}/${ARCHIVE}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "Downloading obfaryn ${VERSION}..."
curl -fsSL "$URL" -o "${TMP_DIR}/${ARCHIVE}"

echo "Installing to ${INSTALL_DIR}..."
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
echo "Installed obfaryn ${VERSION} to ${INSTALL_DIR}"
case ":$PATH:" in
  *":${BIN_DIR}:"*) ;;
  *) echo "Add ${BIN_DIR} to your PATH, e.g.: export PATH=\"${BIN_DIR}:\$PATH\"" ;;
esac
echo "Bash completion installed to ${COMPLETION_DIR}/obfaryn (needs the bash-completion package to be auto-sourced)."
echo "For zsh completion instead: obfaryn completion zsh > ~/.zsh/completions/_obfaryn (see README)."
echo "Run: obfaryn --help"
