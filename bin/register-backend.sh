#!/usr/bin/env bash
# Registers one backend with a RUNNING, independently-owned proxy.
#
# Usage: register-backend.sh <NAME> [PORT] [SERVER_DIR]
#   <NAME>       valid backend name ([A-Za-z0-9_-]+)
#   [PORT]       explicit port; default = next free port for a managed backend.
#                An external backend must already be listening on its port.
#   [SERVER_DIR] server folder to BOOT (managed backend). Omit to register an
#                already-running server (external semantics: never started,
#                never stopped).
#
# Ownership:
#   REGISTRATION_OWNER identifies the caller and is persisted in
#   runtime/<NAME>.owner. A duplicate name is rejected; unregister requires the
#   same owner or an explicit --force.
#
# The proxy is never started or stopped here. Registry, port reservations,
# ownership metadata, config regeneration, and live reload are serialized by
# runtime/register.lock.

set -eo pipefail

NAME="${1:?usage: register-backend.sh <NAME> [PORT] [SERVER_DIR]}"
PORT="${2:-}"
SERVER_DIR="${3:-}"

[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "invalid backend name: $NAME" >&2; exit 1; }

BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRATION_OWNER="${REGISTRATION_OWNER:-manual-${HOSTNAME:-local}-$$}"
[[ "$REGISTRATION_OWNER" =~ ^[A-Za-z0-9_.:/-]+$ ]] \
  || { echo "invalid REGISTRATION_OWNER: $REGISTRATION_OWNER" >&2; exit 1; }

MODE="external"
[ -n "$SERVER_DIR" ] && MODE="managed"

mkdir -p "$BASE/runtime" "$BASE/logs"

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

port_bound() { # <port> -> 0 iff nothing is listening
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

port_claimed_by_other() { # <port> -> 0 iff no active registration claims it
  local candidate="$1" f other
  for f in "$BASE/runtime"/*.port; do
    [ -f "$f" ] || continue
    [ "$f" = "$PORT_FILE" ] && continue
    if [ "$(cat "$f")" = "$candidate" ]; then
      other="${f##*/}"
      other="${other%.port}"
      if [ -f "$BASE/runtime/$other.owner" ] || grep -qx "$other" "$REGISTRY_FILE"; then
        return 1
      fi
    fi
  done
  return 0
}

valid_port() {
  [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}

write_owner() {
  local state="$1" pid="${2:-}" tmp="$OWNER_FILE.tmp.$$"
  {
    printf 'owner=%s\n' "$REGISTRATION_OWNER"
    printf 'mode=%s\n' "$MODE"
    printf 'state=%s\n' "$state"
    printf 'port=%s\n' "$PORT"
    printf 'server_dir=%s\n' "$SERVER_DIR"
    printf 'pid=%s\n' "$pid"
    printf 'updated_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$tmp"
  mv -f "$tmp" "$OWNER_FILE"
}

REGISTRY_ADDED=0
REGISTRATION_COMMITTED=0
BOOT_PID=""

cleanup_failed() {
  [ "$REGISTRY_ADDED" = 1 ] || return 0
  [ "$REGISTRATION_COMMITTED" = 0 ] || return 0

  if [ -n "$BOOT_PID" ] && kill -0 "$BOOT_PID" 2>/dev/null; then
    kill "$BOOT_PID" 2>/dev/null || true
  fi

  flock 8 2>/dev/null || true
  CURRENT_OWNER="$(read_owner "$OWNER_FILE")"
  if [ -z "$CURRENT_OWNER" ] || [ "$CURRENT_OWNER" = "$REGISTRATION_OWNER" ]; then
    sed -i "/^$NAME\$/d" "$REGISTRY_FILE"
    rm -f "$OWNER_FILE" "$PORT_FILE" "$READY_FILE" "$BASE/runtime/$NAME.auto-dir"
  fi
  flock -u 8 2>/dev/null || true
}
trap cleanup_failed EXIT

[ -f "$BASE/runtime/proxy.pid" ] || { echo "register: no running proxy" >&2; exit 1; }
[ -f "$BASE/runtime/proxy.ready" ] || { echo "register: proxy not ready" >&2; exit 1; }
proxy_controller_alive || {
  echo "register: proxy is not controlled by a live runProxy/dev-network controller" >&2
  exit 1
}

# --- reserve registry entry, port, and owner atomically ----------------------
exec 8>"$BASE/runtime/register.lock"
flock 8
proxy_controller_alive || {
  echo "register: proxy controller stopped before registration" >&2
  exit 1
}

touch "$REGISTRY_FILE"
if grep -qx "$NAME" "$REGISTRY_FILE"; then
  CURRENT_OWNER="$(read_owner "$OWNER_FILE")"
  if [ -n "$CURRENT_OWNER" ]; then
    echo "register: backend '$NAME' is already owned by $CURRENT_OWNER" >&2
  else
    echo "register: backend '$NAME' is already registered without owner metadata" >&2
  fi
  echo "register: choose a unique name or unregister it with --force" >&2
  exit 1
fi
printf '%s\n' "$NAME" >> "$REGISTRY_FILE"
REGISTRY_ADDED=1

EXPLICIT_PORT="$PORT"
if [ -n "$PORT" ]; then
  valid_port "$PORT" || { echo "invalid port: $PORT" >&2; exit 1; }
elif [ "$MODE" = "external" ]; then
  # An external server has no process for this script to start. Use the
  # deterministic default only to produce a useful missing-listener error.
  IDX=0
  for x in $(printf '%s\n' "$(cat "$REGISTRY_FILE")" | sort -u); do
    [ "$x" = "$NAME" ] && break
    IDX=$((IDX + 1))
  done
  PORT=$((30067 + IDX))
fi

if [ "$MODE" = "managed" ]; then
  if [ -z "$EXPLICIT_PORT" ]; then
    PORT=30067
    while ! port_bound "$PORT" || ! port_claimed_by_other "$PORT"; do
      PORT=$((PORT + 1))
      [ "$PORT" -le 65535 ] || { echo "register: no free backend port" >&2; exit 1; }
    done
  else
    port_claimed_by_other "$PORT" || { echo "register: port $PORT is already claimed" >&2; exit 1; }
    port_bound "$PORT" || { echo "register: port $PORT is already in use" >&2; exit 1; }
  fi
else
  port_claimed_by_other "$PORT" || { echo "register: port $PORT is already claimed" >&2; exit 1; }
  if port_bound "$PORT"; then
    echo "register: external server is not listening on $PORT" >&2
    exit 1
  fi
fi

printf '%s\n' "$PORT" > "$PORT_FILE"
write_owner registering
REGISTRY="$(printf '%s ' "$(cat "$REGISTRY_FILE")")"
echo "   register: $NAME -> port $PORT (owner $REGISTRATION_OWNER)"
flock -u 8

# --- optional managed boot, lock released while Paper starts -----------------
if [ "$MODE" = "managed" ]; then
  if ! port_bound "$PORT"; then
    echo "register: port $PORT became occupied before managed boot" >&2
    exit 1
  fi
  setsid env BASE="$BASE" BACKENDS="$REGISTRY" \
    SERVER_DIR="$SERVER_DIR" DEV_USERS="${DEV_USERS:-dev}" TARGET_SERVER="${TARGET_SERVER:-localhost}" \
    "$BIN_DIR/boot-backend.sh" "$NAME" >> "$BASE/logs/$NAME.log" 2>&1 8>&- &
  BOOT_PID=$!
  for _ in $(seq 1 300); do
    [ -f "$READY_FILE" ] && break
    if ! kill -0 "$BOOT_PID" 2>/dev/null; then
      echo "register: $NAME boot failed" >&2
      exit 1
    fi
    sleep 1
  done
  if [ ! -f "$READY_FILE" ]; then
    echo "register: $NAME did not become ready in 300s" >&2
    exit 1
  fi
  echo "   register: $NAME server is up"
else
  # External server: it must have remained reachable after reservation.
  if port_bound "$PORT"; then
    echo "register: external server is not listening on $PORT" >&2
    exit 1
  fi
  touch "$READY_FILE"
fi

# --- regenerate config and reload under the final serialized write -----------
flock 8
if [ "$(read_owner "$OWNER_FILE")" != "$REGISTRATION_OWNER" ]; then
  echo "register: backend '$NAME' ownership changed while it was starting" >&2
  exit 1
fi

PID=""
[ -f "$PID_FILE" ] && PID="$(cat "$PID_FILE")"
write_owner ready "$PID"
REGISTRY="$(printf '%s ' "$(cat "$REGISTRY_FILE")")"
if [ -n "${PROXY_PORT:-}" ]; then
  GENERATOR_PROXY_PORT="$PROXY_PORT"
else
  GENERATOR_PROXY_PORT="$(sed -n 's/^bind = "0.0.0.0:\([0-9][0-9]*\)".*/\1/p' "$BASE/runtime/velocity.toml" | sed -n '1p')"
  GENERATOR_PROXY_PORT="${GENERATOR_PROXY_PORT:-25565}"
fi
(
  export BASE BACKENDS="$REGISTRY" PROXY_PORT="$GENERATOR_PROXY_PORT"
  # shellcheck source=velocity-toml.sh
  . "$BIN_DIR/velocity-toml.sh"
  write_velocity_toml
)

# shellcheck disable=SC2031
if [ -p "$BASE/runtime/velocity.cmd" ]; then
  printf 'velocity reload\n' > "$BASE/runtime/velocity.cmd"
  echo "   register: velocity reload sent; proxy is routing to $NAME now"
else
  echo "!! register: command FIFO missing (runtime/velocity.cmd); proxy can't hot-reload" >&2
  exit 1
fi

REGISTRATION_COMMITTED=1
echo "   connect:  /server $NAME (proxy at localhost:$GENERATOR_PROXY_PORT)"