#!/usr/bin/env bash
# Verifies rust-pruner natives are staged and packaged without invoking Gradle
# (Gradle would configure every variants/* project). Keep in sync with
# rustNativeTargets in build.gradle.kts.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE_ROOT="${1:-$ROOT/build/generated/rust-pruner-resources}"
LIBS_DIR="${2:-$ROOT/build/libs}"

# We can hardcode this. I don't care
EXPECTED=(
  "natives/windows-x86_64/rust_pruner.dll"
  "natives/windows-aarch64/rust_pruner.dll"
  "natives/linux-x86_64/librust_pruner.so"
  "natives/linux-aarch64/librust_pruner.so"
  "natives/macos-x86_64/librust_pruner.dylib"
  "natives/macos-aarch64/librust_pruner.dylib"
)

if [[ ! -d "$NATIVE_ROOT" ]]; then
  echo "Missing staged natives dir: $NATIVE_ROOT" >&2
  exit 1
fi
if [[ ! -d "$LIBS_DIR" ]]; then
  echo "Missing jars dir: $LIBS_DIR (run buildAll first)" >&2
  exit 1
fi

missing_staged=()
for entry in "${EXPECTED[@]}"; do
  if [[ ! -f "$NATIVE_ROOT/$entry" ]]; then
    missing_staged+=("$entry")
  fi
done
if ((${#missing_staged[@]} > 0)); then
  echo "Missing staged rust-pruner natives under $NATIVE_ROOT:" >&2
  printf '  - %s\n' "${missing_staged[@]}" >&2
  exit 1
fi

shopt -s nullglob
jars=("$LIBS_DIR"/chronosbackups-*.jar)
if ((${#jars[@]} == 0)); then
  echo "No chronosbackups jars in $LIBS_DIR" >&2
  exit 1
fi

problems=()
for jar in "${jars[@]}"; do
  listing="$(unzip -Z1 "$jar")"
  missing=()
  for entry in "${EXPECTED[@]}"; do
    if ! grep -Fxq "$entry" <<<"$listing"; then
      missing+=("$entry")
    fi
  done
  if ((${#missing[@]} > 0)); then
    problems+=("$(basename "$jar"): missing ${missing[*]}")
  fi
done

if ((${#problems[@]} > 0)); then
  echo "rust-pruner natives missing from jars (${#problems[@]}):" >&2
  printf '  - %s\n' "${problems[@]}" >&2
  exit 1
fi

echo "verify-rust-pruner-natives: OK (${#EXPECTED[@]} natives in ${#jars[@]} jars)."
