#!/usr/bin/env bash

set -euo pipefail

readonly sdk_root="${ANDROID_SDK_ROOT:-${HOME}/Library/Android/sdk}"
readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly repo_root="$(cd -- "${script_dir}/.." && pwd)"
readonly local_jdk_home="${repo_root}/.tools/jdk17/Contents/Home"
readonly command_line_tools_revision="15859902"
readonly command_line_tools_sha256_arm64="835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e"
readonly command_line_tools_sha256_x86_64="c5a6378ab5cf7e0d5701921405115befff13e9ff7417fb588389338f8bd050f3"

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -x "${local_jdk_home}/bin/java" ]]; then
        JAVA_HOME="${local_jdk_home}"
    elif [[ -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    else
        printf 'JAVA_HOME must point to JDK 17 or newer.\n' >&2
        exit 1
    fi
fi
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ "$(java -version 2>&1 | head -n 1)" != *'17.'* ]]; then
    printf 'Expected JDK 17 for the pinned AGP/Gradle toolchain; got: ' >&2
    java -version 2>&1 | head -n 1 >&2
    exit 1
fi

readonly sdkmanager_path="${sdk_root}/cmdline-tools/latest/bin/sdkmanager"
mkdir -p "${sdk_root}/cmdline-tools"

if [[ ! -x "${sdkmanager_path}" ]]; then
    case "$(uname -m)" in
        arm64)
            readonly archive_name="commandlinetools-mac_arm64-${command_line_tools_revision}_latest.zip"
            readonly expected_sha256="${command_line_tools_sha256_arm64}"
            ;;
        x86_64)
            readonly archive_name="commandlinetools-mac_x86_64-${command_line_tools_revision}_latest.zip"
            readonly expected_sha256="${command_line_tools_sha256_x86_64}"
            ;;
        *)
            printf 'Unsupported macOS architecture: %s\n' "$(uname -m)" >&2
            exit 1
            ;;
    esac

    readonly temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/passport-android-sdk.XXXXXX")"
    trap 'rm -rf "${temp_dir}"' EXIT
    readonly archive_path="${temp_dir}/${archive_name}"
    readonly download_url="https://dl.google.com/android/repository/${archive_name}"

    printf 'Downloading pinned Android command-line tools from %s\n' "${download_url}"
    curl --fail --location --show-error --retry 2 --connect-timeout 30 --max-time 900 \
        "${download_url}" --output "${archive_path}"
    printf '%s  %s\n' "${expected_sha256}" "${archive_path}" | shasum --check --algorithm 256

    mkdir -p "${temp_dir}/unpacked" "${sdk_root}/cmdline-tools"
    ditto -x -k "${archive_path}" "${temp_dir}/unpacked"
    rm -rf "${sdk_root}/cmdline-tools/latest"
    mv "${temp_dir}/unpacked/cmdline-tools" "${sdk_root}/cmdline-tools/latest"
else
    printf 'Using existing Android command-line tools at %s\n' "${sdkmanager_path}"
fi

printf '\nInstalled SDK license files (no license text):\n'
if [[ -d "${sdk_root}/licenses" ]]; then
    find "${sdk_root}/licenses" -type f -print | sort || true
else
    printf '  none found\n'
fi

printf '\nAccepting standard Android SDK package licenses through sdkmanager.\n'
set +e
yes | "${sdkmanager_path}" --sdk_root="${sdk_root}" --channel=0 --licenses
license_status="${PIPESTATUS[1]}"
set -e
if [[ "${license_status}" -ne 0 ]]; then
    printf 'Android SDK license acceptance failed with exit status %s.\n' "${license_status}" >&2
    exit "${license_status}"
fi

printf '\nAvailable stable SDK packages (channel 0):\n'
"${sdkmanager_path}" --sdk_root="${sdk_root}" --channel=0 --list

printf '\nInstalling compileSdk 36, Build Tools 36.0.0, and Platform Tools.\n'
"${sdkmanager_path}" --sdk_root="${sdk_root}" --channel=0 \
    'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'

printf '\nVerifying required Android SDK files.\n'
for required_file in \
    "${sdk_root}/platforms/android-36/android.jar" \
    "${sdk_root}/build-tools/36.0.0/aapt2" \
    "${sdk_root}/platform-tools/adb"; do
    if [[ ! -f "${required_file}" ]]; then
        printf 'Required Android SDK file is missing: %s\n' "${required_file}" >&2
        exit 1
    fi
done

printf '\nAndroid SDK bootstrap completed at %s\n' "${sdk_root}"
