#!/usr/bin/env bash
set -euo pipefail

export PATH=$HOME/.cargo/bin:/usr/bin/:/bin:$PATH
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/29.0.14206865

if ! command -v npm >/dev/null 2>&1; then
  export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  if [ -s "$NVM_DIR/nvm.sh" ]; then
    # Android Studio does not launch Gradle through an interactive shell, so
    # NVM-managed Node installs are otherwise invisible here.
    set +u
    . "$NVM_DIR/nvm.sh"
    set -u
  fi
fi

if ! command -v npm >/dev/null 2>&1; then
  for npm_candidate in "$HOME"/.nvm/versions/node/*/bin/npm; do
    if [ -x "$npm_candidate" ]; then
      export PATH="$(dirname "$npm_candidate"):$PATH"
      break
    fi
  done
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "npm was not found. Install Node.js/npm or configure Android Studio's PATH to include npm." >&2
  exit 127
fi

# Make sure output directories exist
mkdir -p prebuild/arm64-v8a prebuild/armeabi-v7a prebuild/x86 prebuild/x86_64

(
  cd gephgui
  npm run build
)

(
  cd geph5/binaries/geph5-client
  export CARGO_TARGET_DIR=../../target/
  # Build for arm64-v8a
  ~/.cargo/bin/cargo ndk -t arm64-v8a --platform 21 build --release --features aws_lambda

  # Build for armeabi-v7a
  ~/.cargo/bin/cargo ndk -t armeabi-v7a --platform 21 build --release --features aws_lambda
)

# Copy the resulting binaries to the correct folders
cp geph5/target/aarch64-linux-android/release/geph5-client       prebuild/arm64-v8a/libgeph.so
cp geph5/target/armv7-linux-androideabi/release/geph5-client     prebuild/armeabi-v7a/libgeph.so
