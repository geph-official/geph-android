#!/bin/sh

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

PREBUILD="./prebuild"
# ABI names must match with arguments provided to android.defaultConfig.ndk.abiFilters
ARM_DIR=$PREBUILD"/armeabi-v7a"
ARM64_DIR=$PREBUILD"/arm64-v8a"
X86_DIR=$PREBUILD"/x86"
TARGET="libgeph.so"

mkdir -p $ARM_DIR
mkdir -p $X86_DIR
mkdir -p $ARM64_DIR
curl https://dl.geph.io/XGO_BUILD/geph-$1-android-21-arm > $ARM_DIR/$TARGET
curl https://dl.geph.io/XGO_BUILD/geph-$1-android-21-arm64 > $ARM64_DIR/$TARGET
curl https://dl.geph.io/XGO_BUILD/geph-$1-android-21-386 > $X86_DIR/$TARGET
