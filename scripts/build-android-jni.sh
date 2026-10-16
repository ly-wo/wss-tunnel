#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROOT}/android/app/src/main/jniLibs"

for candidate in \
  "${ANDROID_TOOLCHAIN_ROOT:-}" \
  "${ROOT}/../../work/android-toolchain"
do
  if [[ -n "${candidate}" && -f "${candidate}/env.sh" ]]; then
    # shellcheck source=/dev/null
    source "${candidate}/env.sh"
    break
  fi
done

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME or source android-toolchain env.sh}"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk is required (cargo install cargo-ndk --locked)" >&2
  exit 1
fi

rustup target add aarch64-linux-android
TARGETS=(arm64-v8a)
if [[ "${BUILD_ANDROID_X86_64:-0}" == "1" ]]; then
  rustup target add x86_64-linux-android
  TARGETS+=(x86_64)
fi

mkdir -p "${OUT}"
NDK_ARGS=(-o "${OUT}" -P 26)
for t in "${TARGETS[@]}"; do
  NDK_ARGS+=(-t "$t")
done
cargo ndk "${NDK_ARGS[@]}" build --release --locked -p tunnel-client-jni

echo "JNI libraries copied to ${OUT}"
