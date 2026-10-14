#!/bin/sh
# Installs the J# command line (jsharp) for the current user on Linux or macOS, with its own Java
# runtime.
#
#   curl -fsSL https://matrixidot.github.io/J-Sharp/install.sh | sh
#
# Installs into ~/.local/share/jsharp, links ~/.local/bin/jsharp, and adds ~/.local/bin to your
# shell's PATH if needed. Running it again updates J#. A specific version:
# curl ... | JSHARP_VERSION=0.2.0-beta sh
set -eu

REPO=matrixidot/J-Sharp
INSTALL_DIR=${JSHARP_HOME:-$HOME/.local/share/jsharp}
BIN_DIR=$HOME/.local/bin

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) PLATFORM=linux-x64 ;;
  Linux-aarch64 | Linux-arm64) PLATFORM=linux-arm64 ;;
  Darwin-x86_64) PLATFORM=darwin-x64 ;;
  Darwin-arm64) PLATFORM=darwin-arm64 ;;
  *) echo "J#: no download for $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

TAG=${JSHARP_VERSION:-}
if [ -z "$TAG" ]; then
  # The newest release, pre-releases included (J# is in beta).
  TAG=$(curl -fsSL -H "User-Agent: jsharp-installer" "https://api.github.com/repos/$REPO/releases?per_page=20" \
    | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')
  [ -n "$TAG" ] || { echo "J#: no release found at https://github.com/$REPO/releases" >&2; exit 1; }
fi
VERSION=${TAG#v}
URL="https://github.com/$REPO/releases/download/$TAG/jsharp-$VERSION-$PLATFORM.tar.gz"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
echo "Downloading J# $VERSION for $PLATFORM..."
curl -fSL --progress-bar "$URL" -o "$TMP/jsharp.tar.gz"
tar -xzf "$TMP/jsharp.tar.gz" -C "$TMP"
rm -rf "$INSTALL_DIR"
mkdir -p "$(dirname "$INSTALL_DIR")" "$BIN_DIR"
mv "$TMP/jsharp-$VERSION" "$INSTALL_DIR"
ln -sf "$INSTALL_DIR/bin/jsharp" "$BIN_DIR/jsharp"

echo
echo "J# $VERSION is installed in $INSTALL_DIR"
"$BIN_DIR/jsharp" --version

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    case "${SHELL:-}" in
      */zsh) RC=$HOME/.zshrc ;;
      */bash) RC=$HOME/.bashrc ;;
      *) RC=$HOME/.profile ;;
    esac
    printf '\n# J#\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$RC"
    echo "Added ~/.local/bin to your PATH in $RC; open a new terminal to use it."
    ;;
esac
echo
echo "Try:  jsharp new app my-app     (or: jsharp new --list)"
