#!/usr/bin/env bash

set -euo pipefail

readonly sdk_root="${ANDROID_SDK_ROOT:-${HOME}/Library/Android/sdk}"
readonly avd_name="PassportVerification"
readonly image_package="system-images;android-36;google_apis;arm64-v8a"

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    else
        printf 'JAVA_HOME must point to JDK 17.\n' >&2
        exit 1
    fi
fi
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ "$(java -version 2>&1 | head -n 1)" != *'17.'* ]]; then
    printf 'Expected JDK 17; got: ' >&2
    java -version 2>&1 | head -n 1 >&2
    exit 1
fi

readonly sdkmanager_path="${sdk_root}/cmdline-tools/latest/bin/sdkmanager"
readonly avdmanager_path="${sdk_root}/cmdline-tools/latest/bin/avdmanager"
readonly emulator_path="${sdk_root}/emulator/emulator"
readonly image_path="${sdk_root}/system-images/android-36/google_apis/arm64-v8a"

if [[ ! -x "${sdkmanager_path}" || ! -x "${avdmanager_path}" ]]; then
    printf 'Android command-line tools are missing under %s. Run bootstrap-android-sdk.sh first.\n' "${sdk_root}" >&2
    exit 1
fi

packages_to_install=()
if [[ ! -x "${emulator_path}" ]]; then
    packages_to_install+=(emulator)
fi
if [[ ! -f "${image_path}/package.xml" ]]; then
    packages_to_install+=("${image_package}")
fi

if (( ${#packages_to_install[@]} > 0 )); then
    printf 'Installing missing emulator packages: %s\n' "${packages_to_install[*]}"
    "${sdkmanager_path}" --sdk_root="${sdk_root}" --channel=0 "${packages_to_install[@]}"
fi

if [[ ! -x "${emulator_path}" ]]; then
    printf 'Android emulator executable is missing: %s\n' "${emulator_path}" >&2
    exit 1
fi
if [[ ! -f "${image_path}/package.xml" || ! -f "${image_path}/system.img" ]]; then
    printf 'Android API 36 Google APIs ARM64 system image is incomplete: %s\n' "${image_path}" >&2
    exit 1
fi

if "${avdmanager_path}" list avd -c | grep -Fxq "${avd_name}"; then
    printf 'Preserving existing AVD %s.\n' "${avd_name}"
else
    printf 'Creating AVD %s.\n' "${avd_name}"
    printf 'no\n' | "${avdmanager_path}" create avd \
        --name "${avd_name}" \
        --package "${image_package}" \
        --device pixel_8
fi

if ! "${avdmanager_path}" list avd -c | grep -Fxq "${avd_name}"; then
    printf 'AVD creation failed; %s is not listed.\n' "${avd_name}" >&2
    exit 1
fi

printf 'Android emulator SDK and AVD are ready. The emulator was not launched.\n'
