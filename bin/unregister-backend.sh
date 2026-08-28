#!/usr/bin/env bash
# shellcheck disable=SC2031
# velocity-toml.sh keeps its generator inputs function-local.
# Removes one owned backend registration from a RUNNING proxy.
#
# Usage: unregister-backend.sh <NAME> [--stop] [--force]
#   <NAME>     backend to unregister.
#   --stop     also stop the managed server process (never an external server).
#   --force    bypass the owner check; reserved for the network controller.
#
# REGISTRATION_OWNER must match runtime/<NAME>.owner unless --force is used.
# Registry, ownership metadata, config regeneration, and reload are serialized
# by runtime/register.lock. The proxy itself is never stopped here.

set -eo pipefail

NAME="${1:?usage: unregister-backend.sh <NAME> [--stop] [--force]}"
STOP=0
FORCE=0
for a in "${@:2}"; do
  case "$a" in
    --stop) STOP=1 ;;
    --force) FORCE=1 ;;
    *) echo "unregister: unknown option '$a'" >&2; exit 1 ;;
  esac
done

[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "invalid backend name: $NAME" >&2; exit 1; }

BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY_FILE="$BASE/runtime/backends.txt"
OWNER_FILE="$BASE/runtime/$NAME.owner"
PORT_FILE="$BASE/runtime/$NAME.port"
READY_FILE="$BASE/runtime/$NAME.ready"
PID_FILE="$BASE/runtime/$NAME.pid"

read_owner() {
  [ -f "$1" ] || return 0
  sed -n 's/^owner=//p' "$1" | sed -n '1p'
}

proxy_controller_lock_held() {
  exec 9>"$BASE/runtime/proxy.lock"
  if flock -n 9; then
    flock -u 9
    exec 9>&-
    return 1
  fi
  exec 9>&-
  return 0
}

proxy_controller_alive() {
  local controller_pid proxy_pid
  [ -f "$BASE/runtime/proxy.owner" ] || return 1
  controller_pid="$(sed -n 's/^pid=//p' "$BASE/runtime/proxy.owner" | sed -n '1p')"
  [[ "$controller_pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$controller_pid" 2>/dev/null || return 1
  [ -f "$BASE/runtime/proxy.pid" ] || return 1
  proxy_pid="$(cat "$BASE/runtime/proxy.pid")"
  [[ "$proxy_pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$proxy_pid" 2>/dev/null || return 1
  proxy_controller_lock_held || return 1
  [ -f "$BASE/runtime/proxy.ready" ]
}


mkdir -p "$BASE/runtime"
exec 8>"$BASE/runtime/register.lock"
flock 8

if [ ! -f "$REGISTRY_FILE" ] || ! grep -qx "$NAME" "$REGISTRY_FILE"; then
  echo "unregister: '$NAME' is not in the registry ($REGISTRY_FILE)" >&2
  exit 1
fi
PROXY_LIVE=0
if proxy_controller_alive; then
  PROXY_LIVE=1
fi

CURRENT_OWNER="$(read_owner "$OWNER_FILE")"
REQUESTED_OWNER="${REGISTRATION_OWNER:-}"
if [ "$FORCE" != 1 ]; then
  if [ -z "$CURRENT_OWNER" ]; then
    echo "unregister: '$NAME' has no owner metadata; use --force" >&2
    exit 1
  fi
  if [ -z "$REQUESTED_OWNER" ] || [ "$REQUESTED_OWNER" != "$CURRENT_OWNER" ]; then
    echo "unregister: '$NAME' is owned by $CURRENT_OWNER; owner token required" >&2
    exit 1
  fi
fi

# Hold the registry lock while stopping a managed process so a replacement
# cannot claim its port/name before the old process is gone.
if [ "$STOP" = 1 ]; then
  if [ -f "$PID_FILE" ]; then
    PID="$(cat "$PID_FILE")"
    if [[ "$PID" =~ ^[0-9]+$ ]] && kill -0 "$PID" 2>/dev/null; then
      echo "   unregister: stopping $NAME (pid $PID)"
      kill "$PID" 2>/dev/null || true
      for _ in $(seq 1 30); do
        kill -0 "$PID" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$PID" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
  rm -f "$READY_FILE"
fi

sed -i "/^$NAME\$/d" "$REGISTRY_FILE"
REGISTRY="$(printf '%s ' "$(cat "$REGISTRY_FILE")")"

if [ -z "${PROXY_PORT:-}" ]; then
  PROXY_PORT="$(sed -n 's/^bind = "0.0.0.0:\([0-9][0-9]*\)".*/\1/p' "$BASE/runtime/velocity.toml" | sed -n '1p')"
  PROXY_PORT="${PROXY_PORT:-25565}"
fi
( export BASE BACKENDS="$REGISTRY" PROXY_PORT="$PROXY_PORT"
  # shellcheck source=velocity-toml.sh
  # shellcheck disable=SC2031
  . "$BIN_DIR/velocity-toml.sh"
  write_velocity_toml )

if [ "$PROXY_LIVE" = 1 ]; then
  if [ -p "$BASE/runtime/velocity.cmd" ]; then
    printf 'velocity reload\n' > "$BASE/runtime/velocity.cmd"
    echo "   unregister: velocity reload sent; proxy dropped $NAME"
  else
    echo "!! unregister: proxy is live but command FIFO is missing; reload it separately" >&2
  fi
else
  echo "   unregister: proxy is not live; registry updated for the next proxy start"
fi

rm -f "$OWNER_FILE" "$PORT_FILE" "$READY_FILE" "$BASE/runtime/$NAME.auto-dir"
flock -u 8