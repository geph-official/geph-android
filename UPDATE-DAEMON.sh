#!/bin/sh

export PREFIX="https://f001.backblazeb2.com/file/geph-dl/geph4-binaries/v4.6.0-beta.17"

PREBUILD="./prebuild"
# ABI names must match with arguments provided to android.defaultConfig.ndk.abiFilters
ARM_DIR=$PREBUILD"/armeabi-v7a"
ARM64_DIR=$PREBUILD"/arm64-v8a"
#X86_DIR=$PREBUILD"/x86"
#X86_64_DIR=$PREBUILD"/x86_64"
TARGET="libgeph.so"

mkdir -p $ARM_DIR
#mkdir -p $X86_DIR
mkdir -p $ARM64_DIR
#mkdir -p $X86_64_DIR
curl "$PREFIX/geph4-client-android-armv7" > $ARM_DIR/$TARGET
curl "$PREFIX/geph4-client-android-aarch64" > $ARM64_DIR/$TARGET
#curl "$PREFIX/geph-client-linux-i386-$VERSION" > $X86_DIR/$TARGET
#curl "$PREFIX/geph-client-linux-amd64-$VERSION" > $X86_64_DIR/$TARGET
