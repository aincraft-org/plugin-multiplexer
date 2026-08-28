#!/usr/bin/env bash
# Removes a backend from a RUNNING dev network — the counterpart to
# register-backend.sh. The proxy keeps running; routing is dropped via
# "velocity reload" (same FIFO), no proxy restart.
#
# Usage: unregister-backend.sh <NAME> [--stop]
#   <NAME>   backend to unregister (must be in the registry)
#   --stop   ALSO stop the managed server process (kill via its pidfile,
#            graceful SIGTERM). Without --stop the server process keeps
#            running; only routing is removed.
#
# What it does:
#   1. removes <NAME> from runtime/backends.txt
#   2. by default leaves the server alone (external or managed) UNLESS --stop
#   3. regenerates velocity.toml from the shrunken registry (velocity-toml.sh)
#   4. sends "velocity reload" via runtime/velocity.cmd — the live proxy drops
#      the upstream; NO proxy restart
#
# Markers: <NAME>.pid is removed with --stop (and the ready marker cleared);
# <NAME>.port is kept so a later re-register reuses the same live port.

set -eo pipefail

NAME="${1:?usage: unregister-backend.sh <NAME> [--stop]}"
STOP=0
for a in "${@:2}"; do
  [ "$a" = "--stop" ] && STOP=1
done

[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "invalid backend name: $NAME" >&2; exit 1; }

BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ -f "$BASE/runtime/proxy.pid" ] || { echo "unregister: no running proxy (missing runtime/proxy.pid)" >&2; exit 1; }

REGISTRY_FILE="$BASE/runtime/backends.txt"
if [ ! -f "$REGISTRY_FILE" ] || ! grep -qx "$NAME" "$REGISTRY_FILE"; then
  echo "unregister: '$NAME' is not in the registry ($REGISTRY_FILE)" >&2
  exit 1
fi

# --- drop from the registry (persisted port stays for a later re-register) ---
sed -i "/^$NAME\$/d" "$REGISTRY_FILE"
REGISTRY="$(printf '%s ' $(cat "$REGISTRY_FILE"))"

# --- optionally stop the managed server --------------------------------------
if [ "$STOP" = 1 ]; then
  pidfile="$BASE/runtime/$NAME.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "   unregister: stopping $NAME (pid $pid)"
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  fi
  rm -f "$BASE/runtime/$NAME.ready"
fi

# --- regenerate config WITHOUT the backend + live reload ---------------------
# The proxy's bound port is re-read so a reload always matches the live bind.
if [ -z "${PROXY_PORT:-}" ]; then
  PROXY_PORT="$(sed -n 's/^bind = "0.0.0.0:\([0-9][0-9]*\)".*/\1/p' "$BASE/runtime/velocity.toml" | head -1)"
  PROXY_PORT="${PROXY_PORT:-25565}"
fi
( export BASE BACKENDS="$REGISTRY" PROXY_PORT="$PROXY_PORT"
  . "$BIN_DIR/velocity-toml.sh"
  write_velocity_toml )

if [ -p "$BASE/runtime/velocity.cmd" ]; then
  printf 'velocity reload\n' > "$BASE/runtime/velocity.cmd"
  echo "   unregister: velocity reload sent; proxy dropped $NAME"
else
  echo "!! unregister: command FIFO missing (runtime/velocity.cmd)" >&2
  exit 1
fi