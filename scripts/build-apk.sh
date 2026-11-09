#!/usr/bin/env bash
# Builds a phone APK from a fresh checkout: fetches and verifies every missing input first.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: scripts/build-apk.sh [--release] [--abi ABI] [--install] [Gradle options]

  --release   build the release APK instead of the debug one
  --abi ABI   build one architecture only, for example arm64-v8a
  --install   install the APK on the running emulator or connected phone and open it
EOF
}

build_type=debug
install=0
gradle_args=()
while [ $# -gt 0 ]; do
    case "$1" in
        --release) build_type=release ;;
        --abi) [ $# -ge 2 ] || { usage >&2; exit 2; }; gradle_args+=("-PjugglucoAbi=$2"); shift ;;
        --install) install=1 ;;
        -h|--help) usage; exit 0 ;;
        *) gradle_args+=("$1") ;;
    esac
    shift
done

java_major() {
    "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ] || [ "$(java_major "$JAVA_HOME")" != 21 ]; then
    for candidate in "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/lib/jvm/java-21-openjdk*; do
        if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ "$(java_major "$candidate")" = 21 ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ] || [ "$(java_major "$JAVA_HOME")" != 21 ]; then
    echo "JDK 21 is required (macOS: brew install openjdk@21)." >&2
    exit 1
fi

if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
        ANDROID_HOME=$ANDROID_SDK_ROOT
    elif [ "$(uname)" = Darwin ]; then
        ANDROID_HOME=$HOME/Library/Android/sdk
    else
        ANDROID_HOME=$HOME/Android/Sdk
    fi
fi
export ANDROID_HOME

gradle_value() {
    sed -n "s/^ *$1 *= *'\([^']*\)'.*/\1/p" Common/build.gradle | head -1
}
ndk=$(gradle_value ndkver)
cmake=$(gradle_value CMAKEVERSION)
build_tools=$(gradle_value buildToolsVersion)
compile_sdk=$(sed -n 's/^ *version *= *release(\([0-9]*\)).*/\1/p' Common/build.gradle | head -1)
compile_minor=$(sed -n 's/^ *minorApiLevel *= *\([0-9]*\).*/\1/p' Common/build.gradle | head -1)
platform="android-$compile_sdk${compile_minor:+.$compile_minor}"
packages=()
[ -d "$ANDROID_HOME/ndk/$ndk" ] || packages+=("ndk;$ndk")
[ -d "$ANDROID_HOME/cmake/$cmake" ] || packages+=("cmake;$cmake")
[ -d "$ANDROID_HOME/build-tools/$build_tools" ] || packages+=("build-tools;$build_tools")
[ -d "$ANDROID_HOME/platforms/$platform" ] || packages+=("platforms;$platform")
if [ ${#packages[@]} -gt 0 ]; then
    sdkmanager=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
    if [ ! -x "$sdkmanager" ]; then
        echo "Android SDK command-line tools not found under $ANDROID_HOME; install them from Android Studio's SDK Manager." >&2
        exit 1
    fi
    echo "Installing Android SDK packages: ${packages[*]}" >&2
    "$sdkmanager" "${packages[@]}"
fi

if [ "$(git submodule status Common/src/main/cpp/libjuice | cut -c1)" != " " ]; then
    git submodule sync --quiet Common/src/main/cpp/libjuice
    git submodule update --init Common/src/main/cpp/libjuice
fi

if ! python3 scripts/dist/dist.py check-inputs >/dev/null 2>&1; then
    echo "Restoring vendor sensor libraries from the pinned release APK" >&2
    python3 scripts/dist/dist.py restore
fi
python3 scripts/dist/clarity-provider.py

task=":Common:assembleMobile$(printf '%s' "${build_type:0:1}" | tr '[:lower:]' '[:upper:]')${build_type:1}"
./gradlew "$task" ${gradle_args[@]+"${gradle_args[@]}"}

apk=$(ls -t Common/build/outputs/apk/mobile/$build_type/*.apk | head -1)
echo "APK: $apk"

if [ "$install" = 1 ]; then
    adb=$ANDROID_HOME/platform-tools/adb
    package=$("$ANDROID_HOME/build-tools/$build_tools/aapt2" dump packagename "$apk")
    "$adb" install -r "$apk"
    "$adb" shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    echo "Installed and opened $package" >&2
fi
