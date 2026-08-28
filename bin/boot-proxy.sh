#!/usr/bin/env bash
# Boots the Velocity proxy (4.1.1, build 24) with a generated velocity.toml.
# Writes $BASE/runtime/proxy.ready when the proxy is accepting connections.
#
# Backends come from the registry (BACKENDS env or $BASE/runtime/backends.txt):
# one [servers] entry each, try = lobby + backends, deterministic ports
# (30067 + sorted-name index; override with PORT_<NAME>=<port>).
#
# RUNTIME REGISTRATION: the proxy's stdin is a FIFO
# ($BASE/runtime/velocity.cmd). A backend added while the network is up
# (bin/register-backend.sh) regenerates velocity.toml and sends
#   printf 'velocity reload\n' > "$BASE/runtime/velocity.cmd"
# Velocity hot-discovers the new upstream without a proxy restart.
#
# Pins verified 2026-08-27 against https://fill.papermc.io/v3/projects/velocity
# and the official docs at https://docs.papermc.io/velocity/getting-started.

set -eo pipefail

VERSION="4.1.1"
BUILD="24"
SHA256="846411d2d0560fed0f23496ffb89681be528d2c0650ecdcf21724d2d7bd9c1ee"
BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXY_PORT="${PROXY_PORT:-25565}"
TARGET_SERVER="${TARGET_SERVER:-localhost}"

[ -d "$BASE" ] || { echo "base dir missing: $BASE" >&2; exit 1; }
mkdir -p "$BASE/binaries" "$BASE/runtime"

# Persist every backend's resolved port to runtime/<NAME>.port so the
# runtime-registration machinery (register-backend.sh reservation scan,
# velocity-toml.sh generator) honors the LIVE ports of boot-time servers —
# index math shifts when a new name sorts before them.
port_override() {
  local n="$1" key
  if [[ "$n" =~ ^[A-Za-z0-9_]+$ ]]; then
    key="PORT_${n^^}"
    printf '%s' "${!key:-}"
  fi
}
for n in $(printf '%s\n' "$BACKENDS" | tr ' ' '\n' | sort -u); do
  [ -f "$BASE/runtime/$n.port" ] && continue
  OVERRIDE="$(port_override "$n")"
  if [ -n "$OVERRIDE" ]; then
    P="$OVERRIDE"
  else
    IDX=0
    P=30067
    for x in $(printf '%s\n' "$BACKENDS" | tr ' ' '\n' | sort -u); do
      if [ "$x" = "$n" ]; then
        P=$((30067 + IDX))
        break
      fi
      IDX=$((IDX + 1))
    done
  fi
  printf '%s\n' "$P" > "$BASE/runtime/$n.port"
done

JAR="$BASE/binaries/velocity-$VERSION-$BUILD.jar"
"$BIN_DIR/fetch-jar.sh" \
  "https://fill-data.papermc.io/v1/objects/$SHA256/velocity-$VERSION-$BUILD.jar" \
  "$SHA256" "$JAR"

# --- write velocity.toml (shared generator; identical on hot-reload) ---------
# shellcheck source=velocity-toml.sh
. "$BIN_DIR/velocity-toml.sh"
write_velocity_toml

# --- command FIFO for runtime reloads ---------------------------------------
# The proxy's stdin is this FIFO. register-backend.sh sends "velocity reload"
# and the live proxy hot-discovers the new upstream. The O_RDWR holder keeps
# opens non-blocking (writers never block waiting for a reader while java is
# still starting) and never writes, so java starts even before any writer.
CMDFIFO="$BASE/runtime/velocity.cmd"
rm -f "$CMDFIFO"
mkfifo "$CMDFIFO"
exec 9<>"$CMDFIFO"

# --- run --------------------------------------------------------------------
cd "$BASE/runtime"
java -Xms256M -Xmx512M -XX:+UseG1GC -XX:G1HeapRegionSize=4M \
  -XX:+ParallelRefProcEnabled -XX:+AlwaysPreTouch -XX:MaxInlineLevel=15 \
  -jar "$JAR" < "$CMDFIFO" &

PROXY_PID=$!
echo "$PROXY_PID" > "$BASE/runtime/proxy.pid"
trap 'kill "$PROXY_PID" 2>/dev/null || true; exec 9>&- 2>/dev/null || true; rm -f "$CMDFIFO"' EXIT

for _ in $(seq 1 120); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$PROXY_PORT") 2>/dev/null; then
    exec 3>&- 3<&-
    touch "$BASE/runtime/proxy.ready"
    break
  fi
  kill -0 "$PROXY_PID" 2>/dev/null || { echo "proxy process died" >&2; exit 1; }
  sleep 1
done

[ -f "$BASE/runtime/proxy.ready" ] || { echo "proxy did not open port $PROXY_PORT" >&2; exit 1; }

# Give the JVM a moment to finish booting and log its banner.
sleep 2
wait "$PROXY_PID"