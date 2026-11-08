#!/usr/bin/env bash
# Run inside the tag workflow. Signing keys remain exclusively in the build job.
set -euo pipefail
source_tag="${GITHUB_REF_NAME:?}"
release_tag="$source_tag"
hotfix=false
release_repo="${GITHUB_REPOSITORY:?}"
release_commit="${GITHUB_SHA:?}"
if [[ "$source_tag" =~ ^([0-9]+\.[0-9]+(\.[0-9]+)?)-hotfix\.[1-9][0-9]*$ ]]; then
  release_tag="${BASH_REMATCH[1]}"
  hotfix=true
elif [[ ! "$release_tag" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
  echo "Not a version tag; no release published."
  exit 0
fi
test "$(git rev-parse HEAD)" = "$release_commit"
test "$(git rev-parse "refs/tags/$source_tag^{commit}")" = "$release_commit"
if [ "$hotfix" = true ]; then
  git merge-base --is-ancestor "refs/tags/$release_tag^{commit}" "$release_commit"
fi
source_notes="docs/releases/$release_tag.md"
test -s "$source_notes"
release_temp="$(mktemp -d "${RUNNER_TEMP:-/tmp}/ig-guard-release.XXXXXX")"
trap 'rm -rf "$release_temp"' EXIT
release_notes="$release_temp/notes.md"
cp "$source_notes" "$release_notes"
if [ "$hotfix" = true ]; then
  printf '\nHotfix source: `%s` (tag `%s`). The original `%s` source tag is unchanged.\n' "$release_commit" "$source_tag" "$release_tag" >> "$release_notes"
fi
run_url="${GITHUB_SERVER_URL:-https://github.com}/$release_repo/actions/runs/${GITHUB_RUN_ID:?}"
release_assets=()
shopt -s nullglob
if [[ "${BUILD_RESULT:-}" = success && "${ARTIFACT_NAME:-}" = ig-guard-signed-apk &&
      "${APK_VERIFIED:-}" = true && "${DOWNLOAD_RESULT:-}" = success ]]; then
  candidates=(release-artifacts/*.apk)
  if [[ "${#candidates[@]}" = 1 && -s "${candidates[0]}" ]]; then
    apk_name="IG-Guard-$release_tag.apk"
    cp "${candidates[0]}" "$release_temp/$apk_name"
    (cd "$release_temp" && sha256sum "$apk_name" > SHA256SUMS)
    release_assets=("$release_temp/$apk_name" "$release_temp/SHA256SUMS")
  fi
fi
if [ "$hotfix" = true ] && [ "${#release_assets[@]}" = 0 ]; then
  echo "::error::A hotfix requires a verified signed APK. Existing release notes and assets are unchanged."
  exit 1
fi
if [[ "${#release_assets[@]}" -gt 0 ]]; then
  printf '\n## Build artifact\nSigned APK verified by the build job; SHA256SUMS accompanies the download.\n\nWorkflow: %s\n' "$run_url" >> "$release_notes"
else
  printf '\n## Build artifact\nSigned APK unavailable for this run. This publication contains the update log; existing release assets, if any, are unchanged.\n\nBuild result: %s. Workflow: %s\n' "${BUILD_RESULT:-unknown}" "$run_url" >> "$release_notes"
fi
if gh release view "$release_tag" --repo "$release_repo" >/dev/null 2>&1; then
  gh release edit "$release_tag" --repo "$release_repo" --title "IG Guard $release_tag" --notes-file "$release_notes" --latest
else
  if [ "$hotfix" = true ]; then
    echo "::error::The original release must exist before publishing its hotfix."
    exit 1
  fi
  gh release create "$release_tag" --repo "$release_repo" --verify-tag --title "IG Guard $release_tag" --notes-file "$release_notes" --latest
fi
if [[ "${#release_assets[@]}" -gt 0 ]]; then
  if ! gh release upload "$release_tag" "${release_assets[@]}" --repo "$release_repo" --clobber; then
    cp "$source_notes" "$release_notes"
    printf '\n## Build artifact\nSigned APK asset upload was incomplete. The update log is published; inspect existing assets and the workflow before downloading.\n\nWorkflow: %s\n' "$run_url" >> "$release_notes"
    gh release edit "$release_tag" --repo "$release_repo" --notes-file "$release_notes"
  fi
fi
