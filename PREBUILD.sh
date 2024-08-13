
#!/bin/bash

export PREFIX="https://f001.backblazeb2.com/file/geph-dl/geph4-binaries/v$(cat app/build.gradle | grep versionName | awk '{print $2}' | tr -d \')"

echo $PREFIX

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

# Function to download the file only if local and remote file last modified dates differ or the local file doesn't exist
download_if_needed() {
  remote_url="$1"
  local_file="$2"
  if [ -e "$local_file" ]; then
    local_date=$(date -r "$local_file" +%s)
    remote_date=$(curl -sI "$remote_url" | grep -i last-modified | sed 's/Last-Modified: //i' | date -f - +%s)
    if [ "$local_date" = "$remote_date" ]; then
      echo "File $local_file is up to date, skipping download."
      return 0
    fi
  fi
  curl -R "$remote_url" > "$local_file"
}

download_if_needed "$PREFIX/geph4-client-android-armv7" $ARM_DIR/$TARGET
download_if_needed "$PREFIX/geph4-client-android-aarch64" $ARM64_DIR/$TARGET
#download_if_needed "$PREFIX/geph4-client-android-amd64" $X86_64_DIR/$TARGET
#download_if_needed "$PREFIX/geph4-client-android-i386" $X86_DIR/$TARGET
