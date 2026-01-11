#!/bin/bash
set -e

cd /mnt/c/Users/pars/AndroidStudioProjects/tailscale-android

export GOROOT=/home/ubuntu/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.25.5.linux-amd64
export GOPATH=$HOME/go
export PATH=$GOROOT/bin:$GOPATH/bin:$PWD/android/build/go/bin:$PATH
export ANDROID_HOME=~/android-sdk
export ANDROID_NDK_HOME=~/android-sdk/ndk/23.1.7779620
export GOTOOLCHAIN=local

echo "=== Version info ==="
cat tailscale.version
echo ""

echo "=== Getting ldflags ==="
LDFLAGS=$(./version-ldflags.sh | tr '\n' ' ')
echo "LDFLAGS: $LDFLAGS"
echo ""

echo "=== Building libtailscale.aar with version info ==="
mkdir -p android/libs

gomobile bind -v -target android -androidapi 26 \
    -ldflags "$LDFLAGS" \
    -o android/libs/libtailscale.aar ./libtailscale

echo ""
echo "=== Build complete ==="
ls -la android/libs/
