#!/bin/sh

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

PREBUILD="./prebuild"
# ABI names must match with arguments provided to android.defaultConfig.ndk.abiFilters
ARM_DIR=$PREBUILD"/armeabi"
X86_DIR=$PREBUILD"/x86"
TARGET="libgeph.so"

mkdir -p $ARM_DIR
mkdir -p $X86_DIR
curl https://dl.geph.io/XGO_BUILD/geph-$1-android-16-arm > $ARM_DIR/$TARGET
curl https://dl.geph.io/XGO_BUILD/geph-$1-android-16-386 > $X86_DIR/$TARGET
