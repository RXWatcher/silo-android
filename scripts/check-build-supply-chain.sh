#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
failed=0

for workflow in "${repo_root}"/.github/workflows/*.yml "${repo_root}"/.github/workflows/*.yaml; do
  [[ -e "${workflow}" ]] || continue

  while IFS= read -r line; do
    if [[ "${line}" =~ ^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*([^[:space:]\#]+) ]]; then
      action="${BASH_REMATCH[2]}"
      if [[ ! "${action}" =~ ^[^@]+@[0-9a-fA-F]{40}$ ]]; then
        printf 'Unpinned GitHub Action in %s: %s\n' \
          "${workflow#${repo_root}/}" "${action}" >&2
        failed=1
      fi
    fi
  done < "${workflow}"
done

wrapper_properties="${repo_root}/gradle/wrapper/gradle-wrapper.properties"
if ! grep -Eq '^distributionSha256Sum=[0-9a-fA-F]{64}$' "${wrapper_properties}"; then
  printf 'Missing or invalid Gradle wrapper distributionSha256Sum in %s\n' \
    "${wrapper_properties#${repo_root}/}" >&2
  failed=1
fi

verification_metadata="${repo_root}/gradle/verification-metadata.xml"
if [[ ! -s "${verification_metadata}" ]] ||
  ! grep -q '<verification-metadata' "${verification_metadata}"; then
  printf 'Missing or invalid dependency verification metadata: %s\n' \
    "${verification_metadata#${repo_root}/}" >&2
  failed=1
fi

exit "${failed}"
