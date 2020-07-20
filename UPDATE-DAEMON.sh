#!/bin/sh

export PREFIX="https://binaries.geph.io"
export VERSION="v0.22.2"

PREBUILD="./prebuild"
# ABI names must match with arguments provided to android.defaultConfig.ndk.abiFilters
ARM_DIR=$PREBUILD"/armeabi-v7a"
ARM64_DIR=$PREBUILD"/arm64-v8a"
X86_DIR=$PREBUILD"/x86"
X86_64_DIR=$PREBUILD"/x86_64"
TARGET="libgeph.so"

mkdir -p $ARM_DIR
mkdir -p $X86_DIR
mkdir -p $ARM64_DIR
mkdir -p $X86_64_DIR
curl "$PREFIX/geph-client-android-armeabi-$VERSION" > $ARM_DIR/$TARGET
curl "$PREFIX/geph-client-android-arm64-$VERSION" > $ARM64_DIR/$TARGET
curl "$PREFIX/geph-client-linux-i386-$VERSION" > $X86_DIR/$TARGET
curl "$PREFIX/geph-client-linux-amd64-$VERSION" > $X86_64_DIR/$TARGET
