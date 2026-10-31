#!/usr/bin/env bash

set -euo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly repo_root="$(cd -- "${script_dir}/.." && pwd)"
readonly seven_zip="${repo_root}/.tools/7zip/7zz"
readonly archive="${repo_root}/build/verification/archive-proof.7z"
readonly expected="${repo_root}/build/verification/archive-proof.expected.txt"

if [[ ! -x "${seven_zip}" ]]; then
    printf '7zz executable is missing: %s\n' "${seven_zip}" >&2
    exit 1
fi
if [[ ! -f "${archive}" || ! -f "${expected}" ]]; then
    printf 'Archive proof or expected TXT fixture is missing under %s/build/verification.\n' "${repo_root}" >&2
    exit 1
fi

readonly temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/passport-android-archive-proof.XXXXXX")"
trap 'rm -rf -- "${temp_dir}"' EXIT

"${seven_zip}" t -p123 "${archive}"
"${seven_zip}" x -p123 -y "-o${temp_dir}" "${archive}"
if [[ ! -f "${temp_dir}/document.txt" ]]; then
    printf 'Archive must contain document.txt.\n' >&2
    exit 1
fi
cmp "${expected}" "${temp_dir}/document.txt"

"${seven_zip}" l -slt -p123 "${archive}" > "${temp_dir}/listing.txt"
if ! awk -v archive_path="${archive}" '
    /^Path = / {
        path = substr($0, 8)
        if (path != archive_path) {
            entry_count++
            entry_name = path
            if (path != "document.txt") {
                unexpected_entry = 1
            }
        }
    }
    /^Encrypted = \+$/ && entry_name == "document.txt" {
        encrypted = 1
    }
    END {
        exit !(entry_count == 1 && !unexpected_entry && encrypted)
    }
' "${temp_dir}/listing.txt"; then
    printf 'Archive must contain only an encrypted document.txt entry.\n' >&2
    exit 1
fi

if "${seven_zip}" t -pwrong "${archive}" > "${temp_dir}/wrong-password.txt" 2>&1; then
    printf 'Archive unexpectedly accepted the wrong password.\n' >&2
    exit 1
fi

printf 'Archive proof passed: encrypted document.txt matches the expected fixture; wrong password was rejected.\n'
