#!/bin/bash
#
# Bumps the Hikaru version for the NEXT release.
#
# Scheme (soft preference):
#   versionName = MAJOR.MINOR.PATCH   e.g. 0.5.220
#   versionCode = PATCH               e.g. 220
#
# Only the last number is increased:
#   0.5.220 (220)  ->  0.5.221 (221)  ->  0.5.222 (222) ...
#
# Output contract:
#   stderr -> human readable log lines
#   stdout -> machine readable `NEW_VN=<version>` and `NEW_VC=<code>` lines
#
# Usage:
#   ./scripts/bump_version.sh [path/to/build.gradle.kts]

set -euo pipefail

GRADLE_FILE="${1:-app/build.gradle.kts}"

if [[ ! -f "$GRADLE_FILE" ]]; then
    echo "error: $GRADLE_FILE not found" >&2
    exit 1
fi

CURRENT_VN=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE_FILE" | head -n1)
CURRENT_VC=$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -n1)

if [[ -z "$CURRENT_VN" ]]; then
    echo "error: could not read versionName from $GRADLE_FILE" >&2
    exit 1
fi
if [[ -z "$CURRENT_VC" ]]; then
    echo "error: could not read versionCode from $GRADLE_FILE" >&2
    exit 1
fi

# Strip any legacy -MOD suffix before parsing.
CLEAN_VN="${CURRENT_VN%-MOD}"

MAJOR="${CLEAN_VN%%.*}"
REMAINDER="${CLEAN_VN#*.}"
MINOR="${REMAINDER%%.*}"
PATCH="${REMAINDER#*.}"

for part in "$MAJOR" "$MINOR" "$PATCH"; do
    if [[ ! "$part" =~ ^[0-9]+$ ]]; then
        echo "error: versionName '$CURRENT_VN' is not in MAJOR.MINOR.PATCH form" >&2
        exit 1
    fi
done

NEW_PATCH=$((PATCH + 1))
NEW_VN="${MAJOR}.${MINOR}.${NEW_PATCH}"
NEW_VC="$NEW_PATCH"

TMP_FILE=$(mktemp)
sed \
    -e "s/versionCode = ${CURRENT_VC}/versionCode = ${NEW_VC}/" \
    -e "s/versionName = \"${CURRENT_VN}\"/versionName = \"${NEW_VN}\"/" \
    "$GRADLE_FILE" > "$TMP_FILE"
mv "$TMP_FILE" "$GRADLE_FILE"

# Fail loudly if the substitution did not take effect.
if ! grep -q "versionName = \"${NEW_VN}\"" "$GRADLE_FILE"; then
    echo "error: failed to write versionName $NEW_VN into $GRADLE_FILE" >&2
    exit 1
fi
if ! grep -q "versionCode = ${NEW_VC}" "$GRADLE_FILE"; then
    echo "error: failed to write versionCode $NEW_VC into $GRADLE_FILE" >&2
    exit 1
fi

echo "versionName: $CURRENT_VN -> $NEW_VN" >&2
echo "versionCode: $CURRENT_VC -> $NEW_VC" >&2

# Expose the results to later workflow steps.
if [[ -n "${GITHUB_ENV:-}" ]]; then
    {
        echo "NEW_VN=$NEW_VN"
        echo "NEW_VC=$NEW_VC"
    } >> "$GITHUB_ENV"
fi

echo "NEW_VN=$NEW_VN"
echo "NEW_VC=$NEW_VC"
