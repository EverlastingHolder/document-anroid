#!/usr/bin/env bash

set -euo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly repo_root="$(cd -- "${script_dir}/.." && pwd)"
readonly tools_dir="${repo_root}/.tools"
readonly jdk_dir="${tools_dir}/jdk17"
readonly archive_name="OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.21_9.tar.gz"
readonly download_url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/${archive_name}"
readonly expected_sha256="78b08c7f5a0598242d5719c022e19479cb4a3aa45c37c19726a757a469ed1c15"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
    printf 'This pinned Temurin archive is for macOS arm64 only.\n' >&2
    exit 1
fi

if [[ -e "${jdk_dir}" ]]; then
    if [[ -x "${jdk_dir}/Contents/Home/bin/java" ]] &&
        "${jdk_dir}/Contents/Home/bin/java" -version 2>&1 | head -n 1 | rg -q '17\.0\.21'; then
        printf 'Using existing local Temurin JDK 17.0.21 at %s\n' "${jdk_dir}/Contents/Home"
        exit 0
    fi
    printf 'Refusing to overwrite existing local JDK path: %s\n' "${jdk_dir}" >&2
    exit 1
fi

mkdir -p "${tools_dir}"
readonly temp_dir="$(mktemp -d "${tools_dir}/.jdk17-bootstrap.XXXXXX")"
trap 'rm -rf -- "${temp_dir}"' EXIT
readonly archive_path="${temp_dir}/${archive_name}"
readonly extract_dir="${temp_dir}/extract"
readonly extracted_bundle="${extract_dir}/jdk-17.0.21+9"

printf 'Downloading pinned Temurin JDK 17.0.21+9 from the official Adoptium release.\n'
curl --fail --location --show-error --retry 2 --connect-timeout 30 --max-time 1200 \
    "${download_url}" --output "${archive_path}"
printf '%s  %s\n' "${expected_sha256}" "${archive_path}" | shasum --check --algorithm 256

mkdir -p "${extract_dir}"
tar -xzf "${archive_path}" -C "${extract_dir}"
if [[ ! -x "${extracted_bundle}/Contents/Home/bin/java" ]]; then
    printf 'Expected JDK bundle was not found in the verified archive.\n' >&2
    exit 1
fi

if [[ -e "${jdk_dir}" ]]; then
    printf 'Refusing to overwrite local JDK path created during setup: %s\n' "${jdk_dir}" >&2
    exit 1
fi
mv "${extracted_bundle}" "${jdk_dir}"

if ! "${jdk_dir}/Contents/Home/bin/java" -version 2>&1 | head -n 1 | rg -q '17\.0\.21'; then
    printf 'Installed JDK did not report version 17.0.21.\n' >&2
    exit 1
fi

printf 'Installed isolated Temurin JDK at %s\n' "${jdk_dir}/Contents/Home"
