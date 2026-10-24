#!/usr/bin/env bash
# One version, everywhere.
#
#   scripts/version.sh bump 0.4.0      rewrite every version stamp in the tree
#   scripts/version.sh check           verify they all agree (run by CI)
#   scripts/version.sh check v0.4.0    ...and that they match a release tag
#
# Stamps: android/app/build.gradle.kts (versionName + versionCode),
# fdroid/in.aboobacker.airrelay.yml (build entry + current version),
# macos/project.yml and the generated macos/Support/Info.plist.
#
# versionCode is derived: major * 10000 + minor * 100 + patch (0.4.0 -> 400).

set -euo pipefail
cd "$(dirname "$0")/.."

ANDROID=android/app/build.gradle.kts
FDROID=fdroid/in.aboobacker.airrelay.yml
PROJECT=macos/project.yml
PLIST=macos/Support/Info.plist

die() { echo "version: $*" >&2; exit 1; }

code_for() {
  local major minor patch
  IFS=. read -r major minor patch <<<"$1"
  echo $((10#$major * 10000 + 10#$minor * 100 + 10#$patch))
}

current_version() { sed -n 's/^val appVersion = "\(.*\)"$/\1/p' "$ANDROID"; }
current_code() { sed -n 's/^val appVersionCode = \([0-9][0-9]*\).*$/\1/p' "$ANDROID"; }

plist_version() {
  perl -0777 -ne 'print $1 if m{<key>CFBundleShortVersionString</key>\s*<string>([^<]*)</string>}' "$PLIST"
}
plist_build() {
  perl -0777 -ne 'print $1 if m{<key>CFBundleVersion</key>\s*<string>([^<]*)</string>}' "$PLIST"
}

bump() {
  local version=$1 code
  [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "not a X.Y.Z version: $version"
  code=$(code_for "$version")

  V=$version C=$code perl -pi -e '
    s{^val appVersion = ".*"$}{val appVersion = "$ENV{V}"};
    s{^val appVersionCode = [0-9]+.*$}{val appVersionCode = $ENV{C}};
  ' "$ANDROID"

  V=$version C=$code perl -pi -e '
    s{^  - versionName: .*$}{  - versionName: $ENV{V}};
    s{^    versionCode: [0-9]+.*$}{    versionCode: $ENV{C}};
    s{^    commit: .*$}{    commit: v$ENV{V}};
    s{^CurrentVersion: .*$}{CurrentVersion: $ENV{V}};
    s{^CurrentVersionCode: [0-9]+.*$}{CurrentVersionCode: $ENV{C}};
  ' "$FDROID"

  V=$version C=$code perl -pi -e '
    s{^(        CFBundleShortVersionString: ").*(")$}{$1$ENV{V}$2};
    s{^(        CFBundleVersion: ").*(")$}{$1$ENV{C}$2};
  ' "$PROJECT"

  V=$version C=$code perl -0777 -pi -e '
    s{(<key>CFBundleShortVersionString</key>\s*<string>)[^<]*}{$1$ENV{V}};
    s{(<key>CFBundleVersion</key>\s*<string>)[^<]*}{$1$ENV{C}};
  ' "$PLIST"
}

check() {
  local version code expected tag=${1:-}
  version=$(current_version)
  [ -n "$version" ] || die "cannot read appVersion from $ANDROID"
  code=$(current_code)
  expected=$(code_for "$version")

  [[ $code == "$expected" ]] || die "appVersionCode $code does not match $version (expected $expected)"

  grep -qx "  - versionName: $version" "$FDROID" || die "fdroid versionName is not $version"
  grep -qx "    versionCode: $code" "$FDROID" || die "fdroid versionCode is not $code"
  grep -qx "    commit: v$version" "$FDROID" || die "fdroid commit is not v$version"
  grep -qx "CurrentVersion: $version" "$FDROID" || die "fdroid CurrentVersion is not $version"
  grep -qx "CurrentVersionCode: $code" "$FDROID" || die "fdroid CurrentVersionCode is not $code"

  grep -qx "        CFBundleShortVersionString: \"$version\"" "$PROJECT" \
    || die "macos/project.yml version is not $version"
  grep -qx "        CFBundleVersion: \"$code\"" "$PROJECT" \
    || die "macos/project.yml build is not $code"
  [[ $(plist_version) == "$version" ]] || die "Info.plist version is not $version"
  [[ $(plist_build) == "$code" ]] || die "Info.plist build is not $code"

  if [ -n "$tag" ]; then
    [[ $version == "${tag#v}" ]] || die "version $version does not match tag $tag"
  fi

  echo "version: $version ($code), all stamps agree"
}

case ${1:-} in
  bump)
    [ $# -eq 2 ] || die "usage: $0 bump X.Y.Z"
    bump "$2"
    check
    echo "next: git commit -am 'chore(release): v$2' && git tag v$2 && git push --follow-tags"
    ;;
  check)
    check "${2:-}"
    ;;
  *)
    die "usage: $0 bump X.Y.Z | check [vX.Y.Z]"
    ;;
esac
