#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/docs/api"
MANIFEST="$SRC_DIR/wiki-manifest.tsv"
OUT_DIR="${1:-$ROOT_DIR/docs/github-wiki}"

if [[ ! -f "$MANIFEST" ]]; then
  echo "Manifest not found: $MANIFEST" >&2
  exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

SIDEBAR_FILE="$OUT_DIR/_Sidebar.md"
{
  echo "# ThreadForge Wiki"
  echo
} > "$SIDEBAR_FILE"

current_section=""

while IFS=$'\t' read -r section title source; do
  if [[ -z "${section:-}" ]]; then
    continue
  fi
  if [[ "$section" == \#* ]]; then
    continue
  fi

  src_file="$SRC_DIR/$source"
  if [[ ! -f "$src_file" ]]; then
    echo "Source page not found: $src_file" >&2
    exit 1
  fi

  target_file="$OUT_DIR/$title.md"
  cp "$src_file" "$target_file"

  if [[ "$title" == "Home" ]]; then
    cp "$src_file" "$OUT_DIR/Home.md"
  fi

  if [[ "$section" != "$current_section" ]]; then
    if [[ -n "$current_section" ]]; then
      echo >> "$SIDEBAR_FILE"
    fi
    echo "## $section" >> "$SIDEBAR_FILE"
    current_section="$section"
  fi

  echo "- [[$title]]" >> "$SIDEBAR_FILE"
done < "$MANIFEST"

if [[ ! -f "$OUT_DIR/Home.md" ]]; then
  echo "Manifest must provide a Home page." >&2
  exit 1
fi

echo "Wiki pages generated at: $OUT_DIR"
