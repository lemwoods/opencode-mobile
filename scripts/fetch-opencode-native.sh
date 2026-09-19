#!/usr/bin/env bash
#
# Fetch the embedded on-device opencode runtime for OpenCode Mobile.
#
# Downloads the "native mainline" single-ELF opencode build from the community
# opencode-termux project (MIT, https://github.com/Hope2333/opencode-termux)
# and installs it into the app's jniLibs directory:
#
#   android/app/src/main/jniLibs/arm64-v8a/libopencode.so            (the opencode ELF)
#   android/app/src/main/jniLibs/arm64-v8a/libopencode-crhandler.so  (optional seccomp shim)
#
# Rationale (see docs/EMBEDDED-RUNTIME.md):
# - Apps targeting API 29+ cannot exec() files from their writable data dir,
#   so the binary must live in the extracted nativeLibraryDir. Anything under
#   jniLibs is installed there by the package manager; naming it lib*.so and
#   setting expo.useLegacyPackaging=true makes the file land on disk, executable.
# - The bionic native build is a self-contained ELF (zero glibc deps, Android
#   API >= 28), so it runs from nativeLibraryDir with HOME pointed at app storage.
#
# Usage: scripts/fetch-opencode-native.sh [version]     (default: pinned below)
set -euo pipefail

# Pin the release tag + artifact. Bump deliberately; record the sha256 too.
RELEASE_TAG="${RELEASE_TAG:-Push260912}"
VERSION="${1:-1.18.30}"
RELEASE_REPO="Hope2333/opencode-termux"
DEB_NAME="opencode_${VERSION}_aarch64.deb"
BASE_URL="https://github.com/${RELEASE_REPO}/releases/download/${RELEASE_TAG}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_DIR="${ROOT}/android/app/src/main/jniLibs/arm64-v8a"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo ">> opencode-termux native ${VERSION} (release ${RELEASE_TAG})"
echo ">> workdir: ${WORK}"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v tar >/dev/null || { echo "tar is required" >&2; exit 1; }

# ar: binutils on Linux, present on macOS. dpkg-deb is a fine substitute.
extract_deb() {
  local deb="$1" out="$2"
  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "${deb}" "${out}"
  elif command -v ar >/dev/null 2>&1; then
    mkdir -p "${out}"
    (cd "${out}" && ar x "${deb}" data.tar.xz 2>/dev/null || ar x "${deb}" data.tar.gz)
    (cd "${out}" && tar -xf data.tar.xz 2>/dev/null || tar -xzf data.tar.gz)
    rm -f "${out}/data.tar.xz" "${out}/data.tar.gz" "${out}/debian-binary" || true
  else
    echo "need dpkg-deb or ar+tar to extract the .deb" >&2
    exit 1
  fi
}

curl -fL --retry 3 -o "${WORK}/${DEB_NAME}" "${BASE_URL}/${DEB_NAME}"

extract_deb "${WORK}/${DEB_NAME}" "${WORK}/root"

PREFIX_DIR="${WORK}/root/data/data/com.termux/files/usr"
BIN="${PREFIX_DIR}/bin/opencode"
[ -f "${BIN}" ] || { echo "expected ${BIN} inside the deb, not found" >&2; exit 1; }

mkdir -p "${DEST_DIR}"
install -m 0644 "${BIN}" "${DEST_DIR}/libopencode.so"
echo ">> installed ${DEST_DIR}/libopencode.so ($(du -h "${DEST_DIR}/libopencode.so" | cut -f1))"

# Optional self-activating seccomp shim (DT_NEEDED libopencode-crhandler.so).
SHIM="${PREFIX_DIR}/lib/opencode/libopencode-crhandler.so"
if [ -f "${SHIM}" ]; then
  install -m 0644 "${SHIM}" "${DEST_DIR}/libopencode-crhandler.so"
  echo ">> installed ${DEST_DIR}/libopencode-crhandler.so"
else
  rm -f "${DEST_DIR}/libopencode-crhandler.so"
  echo ">> no seccomp shim in this build (unhardened) — skipping"
fi

sha256sum "${DEST_DIR}/libopencode.so" 2>/dev/null || shasum -a 256 "${DEST_DIR}/libopencode.so"

cat > "${DEST_DIR}/../opencode-native.txt" <<EOF
source: github.com/${RELEASE_REPO} release ${RELEASE_TAG}
version: ${VERSION}
artifact: ${DEB_NAME}
installed-by: scripts/fetch-opencode-native.sh
date: $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

echo ">> done. Build with: cd android && ./gradlew assembleRelease"
