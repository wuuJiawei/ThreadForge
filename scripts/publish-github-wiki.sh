#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <repo-url|wiki-url> [commit-message]" >&2
  echo "Example: $0 git@github.com:owner/repo.git" >&2
  exit 1
fi

REPO_URL="$1"
COMMIT_MESSAGE="${2:-docs: sync wiki from docs/api}"

if [[ "$REPO_URL" == *.wiki.git ]]; then
  WIKI_URL="$REPO_URL"
else
  WIKI_URL="${REPO_URL%.git}.wiki.git"
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
BUILD_DIR="$TMP_DIR/wiki-build"
WIKI_DIR="$TMP_DIR/wiki-repo"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

"$ROOT_DIR/scripts/generate-github-wiki.sh" "$BUILD_DIR"

git clone "$WIKI_URL" "$WIKI_DIR" >/dev/null

if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete --exclude '.git/' "$BUILD_DIR/" "$WIKI_DIR/"
else
  find "$WIKI_DIR" -maxdepth 1 -type f -name '*.md' -delete
  cp "$BUILD_DIR"/*.md "$WIKI_DIR/"
fi

cd "$WIKI_DIR"

if [[ -z "$(git status --porcelain)" ]]; then
  echo "No wiki changes to publish."
  exit 0
fi

git add .
git commit -m "$COMMIT_MESSAGE" >/dev/null
git push origin HEAD >/dev/null

echo "Wiki published: $WIKI_URL"
