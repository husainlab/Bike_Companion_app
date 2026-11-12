#!/usr/bin/env bash
set -euo pipefail

# Manual GitHub Release upload fallback via curl + jq.
# Usage: GITHUB_TOKEN=ghp_... ./scripts/github-release.sh 1.0.1 ./Pillion-1.0.1.apk

OWNER="husainlab"
REPO="Bike_Companion_app"
APP_NAME="Pillion"

VERSION="${1:-}"
APK_PATH="${2:-./${APP_NAME}-release.apk}"

if [[ -z "${VERSION}" ]]; then
  echo "Usage: $0 <version> [apk_path]" >&2
  exit 1
fi

if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "Error: GITHUB_TOKEN environment variable is required." >&2
  exit 1
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: APK file not found at ${APK_PATH}" >&2
  exit 1
fi

TAG="v${VERSION#v}"
API_BASE="https://api.github.com/repos/${OWNER}/${REPO}"

create_payload=$(jq -n \
  --arg tag "${TAG}" \
  --arg name "${APP_NAME} ${VERSION#v}" \
  --arg body "Production signed APK for ${APP_NAME} ${VERSION#v}" \
  '{tag_name: $tag, name: $name, body: $body, draft: false, prerelease: false}')

release_json=$(curl -sS -X POST "${API_BASE}/releases" \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  -d "${create_payload}")

release_id=$(echo "${release_json}" | jq -r '.id // empty')
if [[ -z "${release_id}" ]]; then
  release_json=$(curl -sS "${API_BASE}/releases/tags/${TAG}" \
    -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "X-GitHub-Api-Version: 2022-11-28")
  release_id=$(echo "${release_json}" | jq -r '.id // empty')
fi

if [[ -z "${release_id}" ]]; then
  echo "Failed to create or locate release for ${TAG}: ${release_json}" >&2
  exit 1
fi

upload_asset() {
  local file_path="$1"
  local asset_name="$2"
  local existing_id
  existing_id=$(echo "${release_json}" | jq -r --arg name "${asset_name}" '.assets[]? | select(.name == $name) | .id')
  if [[ -n "${existing_id}" ]]; then
    curl -sS -X DELETE "${API_BASE}/releases/assets/${existing_id}" \
      -H "Authorization: Bearer ${GITHUB_TOKEN}" \
      -H "X-GitHub-Api-Version: 2022-11-28" >/dev/null
  fi
  curl -sS -X POST "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release_id}/assets?name=${asset_name}" \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@${file_path}" >/dev/null
  echo "Uploaded ${asset_name}"
}

upload_asset "${APK_PATH}" "${APP_NAME}-${VERSION#v}.apk"
upload_asset "${APK_PATH}" "${APP_NAME}-release.apk"

