#!/bin/bash
# Publishes this run's build log, test results and emulator screenshots to the android-ci branch,
# so they can be read without signing in to GitHub.
set -u
OUT=ci-out
mkdir -p "$OUT"
cp -r android/core/build/reports/tests "$OUT/core-tests" 2>/dev/null || true
cp -r android/core/build/test-results "$OUT/core-test-results" 2>/dev/null || true
ls -la android/app/build/outputs/apk/*/ > "$OUT/apks.txt" 2>/dev/null || true
# The full logcat is big; the app's own lines are kept (logcat-app.txt).
rm -f "$OUT/logcat.txt"
{
  echo "# Android CI run ${GITHUB_RUN_NUMBER:-?}"
  echo
  echo "Commit ${GITHUB_SHA:-?}, $(date -u), job status: ${JOB_STATUS:-?}"
} > "$OUT/README.md"
cd "$OUT" || exit 0
git init -q -b android-ci
git add -A
git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit -q -m "Android CI run ${GITHUB_RUN_NUMBER:-?} (${GITHUB_SHA::7})"
git push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" HEAD:android-ci
