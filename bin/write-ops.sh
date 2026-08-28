#!/usr/bin/env bash
# Writes ops.json (operator level 4) for the developer accounts listed in
# DEV_USERS (space-separated, default "dev") into a server workdir.
#
# Backend servers are OFFLINE mode, so players are identified by the
# name-derived offline UUID, computed exactly like the server does:
# java.util.UUID.nameUUIDFromBytes("OfflinePlayer:"+name) -- md5 of the name
# bytes (NO namespace prefix), then version-3/variant bits. Verified 2026-08-27
# against Java's UUID.nameUUIDFromBytes on this machine.
#
# Usage: write-ops.sh <server-workdir>

set -eo pipefail

WORKDIR="${1:?usage: write-ops.sh <workdir>}"
DEV_USERS="${DEV_USERS:-dev}"

[ -d "$WORKDIR" ] || { echo "write-ops: no such dir $WORKDIR" >&2; exit 1; }

for u in $DEV_USERS; do
  [[ "$u" =~ ^[A-Za-z0-9_]{1,16}$ ]] || { echo "write-ops: invalid dev username '$u'" >&2; exit 1; }
done

python3 - "$WORKDIR" "$DEV_USERS" <<'PY'
import hashlib, json, sys, uuid

workdir, users = sys.argv[1], sys.argv[2].split()

entries = []
for name in users:
    raw = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode()).digest())
    raw[6] = (raw[6] & 0x0F) | 0x30  # version 3
    raw[8] = (raw[8] & 0x3F) | 0x80  # IETF variant
    entries.append({
        "uuid": str(uuid.UUID(bytes=bytes(raw))),
        "name": name,
        "level": 4,
        "bypassesPlayerLimit": False,
    })

with open(f"{workdir}/ops.json", "w") as f:
    json.dump(entries, f, indent=2)

print("   ops: developer accounts opped (level 4): " + ", ".join(users))
PY