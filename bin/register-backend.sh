#!/usr/bin/env bash
# Hot-registers a backend into a RUNNING dev network without restarting the
# proxy. This is the agent-side runtime registration entry point: another
# process/agent can join its Paper server to an already-booted Velocity
# network by calling this script.
#
# Usage: register-backend.sh <NAME> [PORT] [SERVER_DIR]
#   <NAME>       valid backend name ([A-Za-z0-9_-]+)
#   [PORT]       explicit port; default = next free port from 30067.
#                For an EXTERNAL server the port MUST be the port it is
#                already listening on (the in-use check is skipped for the
#                external path for exactly that reason).
#   [SERVER_DIR] server folder to BOOT (managed backend). Omit to register an
#                already-running server on the given port (EXTERNAL semantics:
#                never started, never stopped — but its modern-forwarding
#                config is YOUR responsibility, as with boot-external.sh).
#
# What it does:
#   1. appends <NAME> to runtime/backends.txt
#   2. under a flock on runtime/register.lock: scans a port that is neither
#      bound nor reserved in any runtime/<other>.port file, and RESERVES it
#      by writing runtime/<NAME>.port — concurrent agents never pick the same
#      port (the reservation is what the scan skips).
#   3. (optional) boots the server dir (with the reserved port) and waits for
#      ready; external path verifies the live port and marks ready
#   4. re-locks, regenerates velocity.toml with the SAME generator the proxy
#      used at boot (velocity-toml.sh honors the persisted .port), sends
#      "velocity reload" via runtime/velocity.cmd — the proxy re-reads
#      velocity.toml LIVE and routes to the new backend; NO proxy restart.
#
# The lock is released during the (minutes-long) managed boot so another agent
# can register in parallel; the fd stays open and is re-acquired for the final
# registry write, so the last registration wins with the FULL registry.
#
# Teardown: bin/unregister-backend.sh <NAME> [--stop], or stop-dev-network.sh
# (which stops every managed backend; externals have no pidfile).

set -eo pipefail

NAME="${1:?usage: register-backend.sh <NAME> [PORT] [SERVER_DIR]}"
PORT="${2:-}"
SERVER_DIR="${3:-}"

[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "invalid backend name: $NAME" >&2; exit 1; }

BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ -f "$BASE/runtime/proxy.pid" ] || { echo "register: no running proxy (missing runtime/proxy.pid); boot the network first" >&2; exit 1; }
[ -f "$BASE/runtime/proxy.ready" ] || { echo "register: proxy not ready yet (no runtime/proxy.ready)" >&2; exit 1; }

mkdir -p "$BASE/runtime" "$BASE/logs"

REGISTRY_FILE="$BASE/runtime/backends.txt"

port_bound() { # <port> -> 0 iff nothing is listening
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

port_reserved() { # <port> -> 0 iff no runtime/*.port file claims it
  local f
  for f in "$BASE/runtime"/*.port; do
    [ -f "$f" ] || continue
    [ "$(cat "$f")" = "$1" ] && return 1
  done
  return 0
}

# --- section 1: registry + port reservation (serialized) ---------------------
exec 8>"$BASE/runtime/register.lock"
flock 8

touch "$REGISTRY_FILE"
if ! grep -qx "$NAME" "$REGISTRY_FILE"; then
  printf '%s\n' "$NAME" >> "$REGISTRY_FILE"
fi

if [ -n "$PORT" ]; then
  [[ "$PORT" =~ ^[0-9]+$ ]] && [ "$PORT" -ge 1024 ] && [ "$PORT" -le 65535 ] \
    || { echo "invalid port: $PORT" >&2; exit 1; }
else
  PORT=30067
  while :; do
    if port_bound "$PORT" && port_reserved "$PORT"; then
      break
    fi
    PORT=$((PORT + 1))
  done
  exec 3>&- 3<&- 2>/dev/null || true
fi
echo "   register: $NAME -> port $PORT"
printf '%s\n' "$PORT" > "$BASE/runtime/$NAME.port"   # reservation
REGISTRY="$(printf '%s ' $(cat "$REGISTRY_FILE"))"

# --- section 2: boot (lock released; other agents may register) --------------
flock -u 8   # UNLOCK but KEEP fd 8 open for the re-acquire in section 3
if [ -n "$SERVER_DIR" ]; then
  if ! port_bound "$PORT"; then
    echo "register: port $PORT already in use; pick another or stop the other server" >&2
    exit 1
  fi
  setsid "$BIN_DIR/boot-backend.sh" "$NAME" >> "$BASE/logs/$NAME.log" 2>&1 8>&- &
  REG_PID=$!
  for _ in $(seq 1 300); do
    if [ -f "$BASE/runtime/$NAME.ready" ]; then
      break
    fi
    if ! kill -0 "$REG_PID" 2>/dev/null; then
      echo "register: $NAME boot failed" >&2
      exit 1
    fi
    sleep 1
  done
  if [ ! -f "$BASE/runtime/$NAME.ready" ]; then
    echo "register: $NAME did not become ready in 300s" >&2
    exit 1
  fi
  echo "   register: $NAME server is up"
else
  # External server: must already be listening on the given (or picked) port.
  if port_bound "$PORT"; then
    echo "register: nothing listening on $PORT — external server must be running before registration" >&2
    exit 1
  fi
  touch "$BASE/runtime/$NAME.ready"
fi

# --- section 3: toml regen + live reload (serialized last-writer-wins) -------
flock 8   # re-acquire; the last regen writes the FULL registry
if [ -z "${PROXY_PORT:-}" ]; then
  PROXY_PORT="$(sed -n 's/^bind = "0.0.0.0:\([0-9][0-9]*\)".*/\1/p' "$BASE/runtime/velocity.toml" | head -1)"
  PROXY_PORT="${PROXY_PORT:-25565}"
fi
( export BASE BACKENDS="$(printf '%s ' $(cat "$REGISTRY_FILE"))" PROXY_PORT="$PROXY_PORT"
  . "$BIN_DIR/velocity-toml.sh"
  write_velocity_toml )

if [ -p "$BASE/runtime/velocity.cmd" ]; then
  printf 'velocity reload\n' > "$BASE/runtime/velocity.cmd"
  echo "   register: velocity reload sent; proxy is routing to $NAME now"
else
  echo "!! register: command FIFO missing (runtime/velocity.cmd); proxy can't hot-reload" >&2
  exit 1
fi

echo "   connect:  /server $NAME (proxy at localhost:${PROXY_PORT:-25565})"