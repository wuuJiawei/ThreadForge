#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <current-tag> [repo-url] [previous-tag]" >&2
  exit 1
}

resolve_repo_url() {
  local remote
  remote="$(git remote get-url origin 2>/dev/null || true)"

  case "$remote" in
    git@github.com:*)
      printf 'https://github.com/%s\n' "${remote#git@github.com:}" | sed 's/\.git$//'
      ;;
    https://github.com/*|http://github.com/*)
      printf '%s\n' "$remote" | sed 's/\.git$//'
      ;;
    *)
      return 1
      ;;
  esac
}

classify_subject() {
  local subject="$1"

  if printf '%s\n' "$subject" | grep -Eq '^feat(\([^)]+\))?!?: '; then
    echo "features"
  elif printf '%s\n' "$subject" | grep -Eq '^fix(\([^)]+\))?!?: '; then
    echo "fixes"
  elif printf '%s\n' "$subject" | grep -Eq '^docs(\([^)]+\))?!?: '; then
    echo "docs"
  elif printf '%s\n' "$subject" | grep -Eq '^(build|ci)(\([^)]+\))?!?: |^chore\((build|ci|deps|release)\)!?: '; then
    echo "build"
  else
    echo "improvements"
  fi
}

strip_prefix() {
  printf '%s\n' "$1" | sed -E 's/^(feat|fix|docs|build|ci|refactor|perf|test|chore)(\([^)]+\))?!?:[[:space:]]*//'
}

append_section() {
  local title="$1"
  local file="$2"

  if [ -s "$file" ]; then
    printf '## %s\n\n' "$title"
    cat "$file"
    printf '\n'
  fi
}

[ "$#" -ge 1 ] || usage

current_tag="$1"
repo_url="${2:-}"
previous_tag="${3:-}"

if [ -z "$repo_url" ]; then
  repo_url="$(resolve_repo_url || true)"
fi

if [ -z "$repo_url" ]; then
  echo "Unable to resolve repository URL. Pass it as the second argument." >&2
  exit 1
fi

if ! git rev-parse --verify "$current_tag^{tag}" >/dev/null 2>&1 && ! git rev-parse --verify "$current_tag^{commit}" >/dev/null 2>&1; then
  echo "Tag or ref '$current_tag' does not exist." >&2
  exit 1
fi

if [ -z "$previous_tag" ]; then
  previous_tag="$(
    git tag --sort=-version:refname |
      grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' |
      while IFS= read -r tag; do
        if [ "$tag" != "$current_tag" ]; then
          printf '%s\n' "$tag"
          break
        fi
      done
  )"
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

features_file="$tmp_dir/features.md"
improvements_file="$tmp_dir/improvements.md"
fixes_file="$tmp_dir/fixes.md"
docs_file="$tmp_dir/docs.md"
build_file="$tmp_dir/build.md"

if [ -n "$previous_tag" ]; then
  range="${previous_tag}..${current_tag}"
else
  range="$current_tag"
fi

while IFS=$'\t' read -r sha subject; do
  [ -n "$sha" ] || continue

  short_sha="$(printf '%s' "$sha" | cut -c1-7)"
  clean_subject="$(strip_prefix "$subject")"
  if [ -z "$clean_subject" ]; then
    clean_subject="$subject"
  fi

  line="- ${clean_subject} ([\`${short_sha}\`](${repo_url}/commit/${sha}))"

  case "$(classify_subject "$subject")" in
    features)
      printf '%s\n' "$line" >> "$features_file"
      ;;
    fixes)
      printf '%s\n' "$line" >> "$fixes_file"
      ;;
    docs)
      printf '%s\n' "$line" >> "$docs_file"
      ;;
    build)
      printf '%s\n' "$line" >> "$build_file"
      ;;
    *)
      printf '%s\n' "$line" >> "$improvements_file"
      ;;
  esac
done < <(git log --no-merges --reverse --pretty=tformat:'%H%x09%s' "$range")

version="${current_tag#v}"

printf '# ThreadForge %s\n\n' "$current_tag"
printf -- '- Maven 坐标：`pub.lighting:threadforge-core:%s`\n' "$version"

if [ -n "$previous_tag" ]; then
  printf -- '- Compare：[%s...%s](%s/compare/%s...%s)\n' "$previous_tag" "$current_tag" "$repo_url" "$previous_tag" "$current_tag"
else
  printf -- '- Compare：首次 tag release，无上一版本可对比\n'
fi

printf '\n'

append_section "Features" "$features_file"
append_section "Improvements" "$improvements_file"
append_section "Fixes" "$fixes_file"
append_section "Docs" "$docs_file"
append_section "Build / CI" "$build_file"

if [ ! -s "$features_file" ] && [ ! -s "$improvements_file" ] && [ ! -s "$fixes_file" ] && [ ! -s "$docs_file" ] && [ ! -s "$build_file" ]; then
  printf '## Changes\n\n'
  printf -- '- No commits found between the selected tags.\n'
fi
