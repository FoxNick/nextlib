#!/bin/bash

# Versions
VPX_VERSION=1.13.0
MBEDTLS_VERSION=3.4.1
DAV1D_VERSION=1.5.3
FFMPEG_VERSION=6.0

# Directories
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
BUILD_DIR=$BASE_DIR/build
OUTPUT_DIR=$BASE_DIR/output
SOURCES_DIR=$BASE_DIR/sources
FFMPEG_DIR=$SOURCES_DIR/ffmpeg-$FFMPEG_VERSION
VPX_DIR=$SOURCES_DIR/libvpx-$VPX_VERSION
MBEDTLS_DIR=$SOURCES_DIR/mbedtls-$MBEDTLS_VERSION
DAV1D_DIR=$SOURCES_DIR/dav1d-$DAV1D_VERSION

# Configuration
ANDROID_ABIS="x86 x86_64 armeabi-v7a arm64-v8a"
ANDROID_PLATFORM=21
ENABLED_DECODERS="vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd h264 hevc mpeg2video mpegvideo libvpx_vp8 libvpx_vp9 libdav1d"
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || sysctl -n hw.pysicalcpu || echo 4)

# Set up host platform variables
HOST_PLATFORM="linux-x86_64"
case "$OSTYPE" in
darwin*) HOST_PLATFORM="darwin-x86_64" ;;
linux*) HOST_PLATFORM="linux-x86_64" ;;
msys)
  case "$(uname -m)" in
  x86_64) HOST_PLATFORM="windows-x86_64" ;;
  i686) HOST_PLATFORM="windows" ;;
  esac
  ;;
esac

# Build tools
TOOLCHAIN_PREFIX="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_PLATFORM}"
CMAKE_EXECUTABLE="${ANDROID_SDK_HOME}/cmake/${ANDROID_CMAKE_VERSION}/bin/cmake"

# Check if sdkmanager is in PATH
if command -v sdkmanager &> /dev/null; then
  # Use sdkmanager from PATH
  echo "Using sdkmanager from PATH"
  echo y | sdkmanager --sdk_root="${ANDROID_SDK_HOME}" "cmake;${ANDROID_CMAKE_VERSION}"
else
  # Use sdkmanager from Android SDK
  SDKMANAGER_EXECUTABLE="${ANDROID_SDK_HOME}/cmdline-tools/latest/bin/sdkmanager"
  if [[ -x "$SDKMANAGER_EXECUTABLE" ]]; then
    echo "Using sdkmanager from Android SDK"
    echo y | "$SDKMANAGER_EXECUTABLE" --sdk_root="${ANDROID_SDK_HOME}" "cmake;${ANDROID_CMAKE_VERSION}"
  else
    echo "Error: sdkmanager not found in PATH or Android SDK"
    exit 1
  fi
fi

mkdir -p $SOURCES_DIR

function downloadLibVpx() {
  pushd $SOURCES_DIR
  echo "Downloading Vpx source code of version $VPX_VERSION..."
  VPX_FILE=libvpx-$VPX_VERSION.tar.gz
  curl -L "https://github.com/webmproject/libvpx/archive/refs/tags/v${VPX_VERSION}.tar.gz" -o $VPX_FILE
  [ -e $VPX_FILE ] || { echo "$VPX_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $VPX_FILE
  rm $VPX_FILE
  popd
}

function downloadMbedTLS() {
  pushd $SOURCES_DIR
  echo "Downloading mbedtls source code of version $MBEDTLS_VERSION..."
  MBEDTLS_FILE=mbedtls-$MBEDTLS_VERSION.tar.gz
  curl -L "https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v${MBEDTLS_VERSION}.tar.gz" -o $MBEDTLS_FILE
  [ -e $MBEDTLS_FILE ] || { echo "$MBEDTLS_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $MBEDTLS_FILE
  rm $MBEDTLS_FILE
  popd
}

function downloadDav1d() {
  pushd $SOURCES_DIR
  echo "Downloading dav1d source code of version $DAV1D_VERSION..."
  DAV1D_FILE=dav1d-$DAV1D_VERSION.tar.gz
  curl -L "https://code.videolan.org/videolan/dav1d/-/archive/${DAV1D_VERSION}/dav1d-${DAV1D_VERSION}.tar.gz" -o $DAV1D_FILE
  [ -e $DAV1D_FILE ] || { echo "$DAV1D_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $DAV1D_FILE
  rm $DAV1D_FILE
  popd
}

function downloadFfmpeg() {
  pushd $SOURCES_DIR
  echo "Downloading FFmpeg source code of version $FFMPEG_VERSION..."
  FFMPEG_FILE=ffmpeg-$FFMPEG_VERSION.tar.gz
  curl -L "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz" -o $FFMPEG_FILE
  [ -e $FFMPEG_FILE ] || { echo "$FFMPEG_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $FFMPEG_FILE
  rm $FFMPEG_FILE
  popd
}

function buildLibVpx() {
  pushd $VPX_DIR

  VPX_AS=${TOOLCHAIN_PREFIX}/bin/llvm-as
  for ABI in $ANDROID_ABIS; do
    # Set up environment variables
    case $ABI in
    armeabi-v7a)
      EXTRA_BUILD_FLAGS="--force-target=armv7-android-gcc --disable-neon"
      TOOLCHAIN=armv7a-linux-androideabi21-
      ;;
    arm64-v8a)
      EXTRA_BUILD_FLAGS="--force-target=armv8-android-gcc"
      TOOLCHAIN=aarch64-linux-android21-
      ;;
    x86)
      EXTRA_BUILD_FLAGS="--force-target=x86-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic"
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=i686-linux-android21-
      ;;
    x86_64)
      EXTRA_BUILD_FLAGS="--force-target=x86_64-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic --disable-neon --disable-neon-asm"
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=x86_64-linux-android21-
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    CC=${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang \
      CXX=${CC}++ \
      LD=${CC} \
      AR=${TOOLCHAIN_PREFIX}/bin/llvm-ar \
      AS=${VPX_AS} \
      STRIP=${TOOLCHAIN_PREFIX}/bin/llvm-strip \
      NM=${TOOLCHAIN_PREFIX}/bin/llvm-nm \
      LDFLAGS="-Wl,-z,max-page-size=16384" \
      ./configure \
      --prefix=$BUILD_DIR/external/$ABI \
      --libc="${TOOLCHAIN_PREFIX}/sysroot" \
      --enable-vp8 \
      --enable-vp9 \
      --enable-static \
      --disable-shared \
      --disable-examples \
      --disable-docs \
      --enable-realtime-only \
      --enable-install-libs \
      --enable-multithread \
      --disable-webm-io \
      --disable-libyuv \
      --enable-better-hw-compatibility \
      --disable-runtime-cpu-detect \
      ${EXTRA_BUILD_FLAGS}

    make clean
    make -j$JOBS
    make install
  done
  popd
}

function buildMbedTLS() {
    pushd $MBEDTLS_DIR

    for ABI in $ANDROID_ABIS; do

      CMAKE_BUILD_DIR=$MBEDTLS_DIR/mbedtls_build_${ABI}
      rm -rf ${CMAKE_BUILD_DIR}
      mkdir -p ${CMAKE_BUILD_DIR}
      cd ${CMAKE_BUILD_DIR}

      ${CMAKE_EXECUTABLE} .. \
       -DANDROID_PLATFORM=${ANDROID_PLATFORM} \
       -DANDROID_ABI=$ABI \
       -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
       -DCMAKE_INSTALL_PREFIX=$BUILD_DIR/external/$ABI \
       -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
       -DENABLE_TESTING=0

      make -j$JOBS
      make install

    done
    popd
}

function buildDav1d() {
    echo "=========================================="
    echo "Building dav1d ${DAV1D_VERSION}"
    echo "=========================================="

    for ABI in $ANDROID_ABIS; do
        echo "--- dav1d $ABI ---"

        case $ABI in
            arm64-v8a)  T=aarch64-linux-android21-; ARCH=aarch64; ASM=true ;;
            armeabi-v7a) T=armv7a-linux-androideabi21-; ARCH=arm; ASM=true ;;
            x86)        T=i686-linux-android21-; ARCH=x86; ASM=false ;;
            x86_64)     T=x86_64-linux-android21-; ARCH=x86_64; ASM=false ;;
        esac

        local DAV1D_BUILD=/tmp/dav1d_build_$ABI
        rm -rf $DAV1D_BUILD && mkdir -p $DAV1D_BUILD

        # Create cross-file for meson
        cat > /tmp/dav1d_cross_$ABI.ini <<EOF
[binaries]
c = '${TOOLCHAIN_PREFIX}/bin/${T}clang'
ar = '${TOOLCHAIN_PREFIX}/bin/llvm-ar'
strip = '${TOOLCHAIN_PREFIX}/bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = '${ARCH}'
cpu = '${ARCH}'
endian = 'little'
EOF

        CC=${TOOLCHAIN_PREFIX}/bin/${T}clang \
        AR=${TOOLCHAIN_PREFIX}/bin/llvm-ar \
        STRIP=${TOOLCHAIN_PREFIX}/bin/llvm-strip \
        meson setup $DAV1D_BUILD $SOURCES_DIR/dav1d-${DAV1D_VERSION} \
          --cross-file=/tmp/dav1d_cross_$ABI.ini \
          -Ddefault_library=static \
          -Denable_asm=$ASM \
          -Denable_tools=false -Denable_tests=false -Denable_examples=false \
          -Db_staticpic=true \
          -Dc_args="-fPIC -O3"

        meson compile -C $DAV1D_BUILD

        # Install to external dir
        mkdir -p $EXTERNAL_DIR/$ABI/lib $EXTERNAL_DIR/$ABI/include/dav1d
        cp $DAV1D_BUILD/src/libdav1d.a $EXTERNAL_DIR/$ABI/lib/
        cp $SOURCES_DIR/dav1d-${DAV1D_VERSION}/include/dav1d/*.h $EXTERNAL_DIR/$ABI/include/dav1d/

        # Create pkg-config file (MUST include Description field!)
        mkdir -p $BUILD_DIR/external/$ABI/lib/pkgconfig
        cat > $BUILD_DIR/external/$ABI/lib/pkgconfig/dav1d.pc <<EOF
prefix=$EXTERNAL_DIR/$ABI
libdir=$EXTERNAL_DIR/$ABI/lib
includedir=$EXTERNAL_DIR/$ABI/include
Name: dav1d
Description: AV1 decoder
Version: ${DAV1D_VERSION}
Libs: -L$EXTERNAL_DIR/$ABI/lib -ldav1d
Cflags: -I$EXTERNAL_DIR/$ABI/include
EOF
    done
popd
}

function buildFfmpeg() {
  pushd $FFMPEG_DIR
  EXTRA_BUILD_CONFIGURATION_FLAGS=""
  COMMON_OPTIONS=""

  # Add enabled decoders to FFmpeg build configuration
  for decoder in $ENABLED_DECODERS; do
    COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"
  done

  # Build FFmpeg for each architecture and platform
  for ABI in $ANDROID_ABIS; do

    # Set up environment variables
    case $ABI in
    armeabi-v7a)
      TOOLCHAIN=armv7a-linux-androideabi21-
      CPU=armv7-a
      ARCH=arm
      ;;
    arm64-v8a)
      TOOLCHAIN=aarch64-linux-android21-
      CPU=armv8-a
      ARCH=aarch64
      ;;
    x86)
      TOOLCHAIN=i686-linux-android21-
      CPU=i686
      ARCH=i686
      EXTRA_BUILD_CONFIGURATION_FLAGS=--disable-asm
      ;;
    x86_64)
      TOOLCHAIN=x86_64-linux-android21-
      CPU=x86_64
      ARCH=x86_64
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    # Referencing dependencies without pkgconfig
    DEP_CFLAGS="-I$BUILD_DIR/external/$ABI/include"
    DEP_LD_FLAGS="-L$BUILD_DIR/external/$ABI/lib"

    # Configure FFmpeg build
    ./configure \
      --prefix=$BUILD_DIR/$ABI \
      --enable-cross-compile \
      --arch=$ARCH \
      --cpu=$CPU \
      --cross-prefix="${TOOLCHAIN_PREFIX}/bin/$TOOLCHAIN" \
      --nm="${TOOLCHAIN_PREFIX}/bin/llvm-nm" \
      --ar="${TOOLCHAIN_PREFIX}/bin/llvm-ar" \
      --ranlib="${TOOLCHAIN_PREFIX}/bin/llvm-ranlib" \
      --strip="${TOOLCHAIN_PREFIX}/bin/llvm-strip" \
      --extra-cflags="-O3 -fPIC $DEP_CFLAGS" \
      --extra-ldflags="$DEP_LD_FLAGS -Wl,-z,max-page-size=16384" \
      --pkg-config="$(which pkg-config)" \
      --target-os=android \
      --enable-shared \
      --disable-static \
      --disable-doc \
      --disable-programs \
      --disable-everything \
      --disable-vulkan \
      --disable-avdevice \
      --disable-postproc \
      --disable-avfilter \
      --disable-symver \
      --enable-parsers \
      --enable-demuxers \
      --enable-swresample \
      --enable-avformat \
      --enable-libvpx \
      --enable-libdav1d \
      --enable-protocol=file,http,https,mmsh,mmst,pipe,rtmp,rtmps,rtmpt,rtmpts,rtp,tls \
      --enable-version3 \
      --enable-mbedtls \
      --extra-ldexeflags=-pie \
      --disable-debug \
      --build-suffix=-exo \
      ${EXTRA_BUILD_CONFIGURATION_FLAGS} \
      ${COMMON_OPTIONS}

    # Build FFmpeg
    echo "Building FFmpeg for $ARCH..."
    make clean
    make -j$JOBS
    make install

    OUTPUT_LIB=${OUTPUT_DIR}/lib/${ABI}
    mkdir -p "${OUTPUT_LIB}"
    cp "${BUILD_DIR}"/"${ABI}"/lib/*.so "${OUTPUT_LIB}"

    OUTPUT_HEADERS=${OUTPUT_DIR}/include/${ABI}
    mkdir -p "${OUTPUT_HEADERS}"
    cp -r "${BUILD_DIR}"/"${ABI}"/include/* "${OUTPUT_HEADERS}"

  done
  popd
}

if [[ ! -d "$OUTPUT_DIR" && ! -d "$BUILD_DIR" ]]; then
  # Download MbedTLS source code if it doesn't exist
  if [[ ! -d "$MBEDTLS_DIR" ]]; then
    downloadMbedTLS
  fi

  # Download Vpx source code if it doesn't exist
  if [[ ! -d "$VPX_DIR" ]]; then
    downloadLibVpx
  fi

  # Download dav1d source code if it doesn't exist
  if [[ ! -d "$DAV1D_DIR" ]]; then
    downloadDav1d
  fi

  # Download Ffmpeg source code if it doesn't exist
  if [[ ! -d "$FFMPEG_DIR" ]]; then
    downloadFfmpeg
  fi

  # Building library
  buildMbedTLS
  buildLibVpx
  buildDav1d
  buildFfmpeg
fi
