#!/usr/bin/env bash
# build-native.sh — build the Veilid wrapper native library for Android.
#
# Prerequisites:
#   - Rust toolchain (rustup) with Android targets
#   - Android NDK 26+
#   - veilid source at /home/seyon/dev/veilid-src (or set VEILID_SRC)
#
# Usage:
#   ./build-native.sh
#
set -euo pipefail

VEILID_SRC="${VEILID_SRC:-/home/seyon/dev/veilid-src}"
NDK_HOME="${ANDROID_NDK_HOME:-$HOME/android-sdk/ndk/26.1.10909125}"
WRAPPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/native/veilid-wrapper"

export ANDROID_NDK_HOME="$NDK_HOME"
export PATH="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
source "$HOME/.cargo/env"

# Create wrapper if it doesn't exist
if [ ! -d "$WRAPPER_DIR" ]; then
    echo "Creating wrapper crate..."
    mkdir -p "$WRAPPER_DIR/src"
    # Copy from reference location
    cp -r /home/seyon/dev/veilid-wrapper/* "$WRAPPER_DIR/" 2>/dev/null || true
fi

cd "$WRAPPER_DIR"
for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
    echo "Building for $target..."
    cargo build --target "$target" 2>&1 | tail -1
done

# Map Rust targets to Android ABIs
declare -A ABI_MAP=(
    ["aarch64-linux-android"]="arm64-v8a"
    ["armv7-linux-androideabi"]="armeabi-v7a"
    ["x86_64-linux-android"]="x86_64"
    ["i686-linux-android"]="x86"
)

APP_JNI="app/app/src/main/jniLibs"
mkdir -p "$APP_JNI"
for target in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android; do
    abi="${ABI_MAP[$target]}"
    mkdir -p "$APP_JNI/$abi"
    cp "target/$target/debug/libveilid_wrapper.so" "$APP_JNI/$abi/"
    echo "  → $APP_JNI/$abi/libveilid_wrapper.so"
done

echo "Done. Native libraries copied to jniLibs/."
