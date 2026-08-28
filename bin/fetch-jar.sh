#!/usr/bin/env bash
# Atomically downloads a pinned server jar: temp file in the SAME directory,
# SHA-256 verify, then atomic mv into place. A per-jar lock (flock) serializes
# concurrent booters so a fresh multi-backend boot cannot race and let Java
# read a partially written jar.
#
# If the destination already exists, it is (re)verified and reused — no
# download. Missing temp files from a previous crash are cleaned up.
#
# Usage: fetch-jar.sh <URL> <SHA256> <DEST>

set -eo pipefail

URL="${1:?usage: fetch-jar.sh <URL> <SHA256> <DEST>}"
SHA256="${2:?usage: fetch-jar.sh <URL> <SHA256> <DEST>}"
DEST="${3:?usage: fetch-jar.sh <URL> <SHA256> <DEST>}"

mkdir -p "$(dirname "$DEST")"
LOCK="$DEST.lock"
exec 9>"$LOCK"
flock 9   # wait for any other booter downloading the same jar

if [ -f "$DEST" ]; then
  if echo "$SHA256  $DEST" | sha256sum -c - >/dev/null 2>&1; then
    echo "   fetch: reusing $(basename "$DEST") (checksum ok)"
    exit 0
  fi
  echo "   fetch: stale $(basename "$DEST"); re-downloading"
  rm -f "$DEST"
fi

rm -f "$DEST.tmp"
echo "   fetch: downloading $(basename "$DEST")"
curl -fsSL "$URL" -o "$DEST.tmp"
echo "$SHA256  $DEST.tmp" | sha256sum -c - >/dev/null
mv -f "$DEST.tmp" "$DEST"
echo "   fetch: installed $(basename "$DEST")"