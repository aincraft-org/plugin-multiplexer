#!/usr/bin/env bash
# Dev Velocity network harness: one proxy, one lobby, N isolated dev backends.
# The user connects to ONE address (localhost:25565) and multiplexes with
# the built-in /server command.
#
# Backends:   BACKENDS="name1 name2 ..." (or backends.txt persists the registry).
#             Each managed backend is a fully isolated Paper server
#             (runtime/<name>/), one plugin per backend via PLUGIN_<NAME>.
# External:   EXTERNAL_BACKENDS="name ..." joins ALREADY-RUNNING servers
#             (e.g. a plugin's own runServer) without managing their lifecycle;
#             boot-external.sh configures + registers them.
# Runtime:    $BASE (default ./development-network).
# Role:       NETWORK_ROLE=full (default) owns proxy+lobby+listed backends;
#             NETWORK_ROLE=proxy owns only proxy+lobby for shared agent mode.
# Teardown:   Ctrl-C here (SIGINT to all booters; their EXIT traps stop java),
#             or ./bin/stop-dev-network.sh.

set -eo pipefail

BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_ROLE="${NETWORK_ROLE:-full}"
PROXY_PORT="${PROXY_PORT:-25565}"
TARGET_SERVER="${TARGET_SERVER:-localhost}"
if [ -z "${PROXY_ONLINE_MODE+x}" ]; then
  if [ -f "$BASE/runtime/velocity.toml" ]; then
    PROXY_ONLINE_MODE="$(sed -n 's/^online-mode = \(true\|false\)$/\1/p' "$BASE/runtime/velocity.toml" | sed -n '1p')"
    PROXY_ONLINE_MODE="${PROXY_ONLINE_MODE:-false}"
  else
    PROXY_ONLINE_MODE="false"
  fi
fi

case "$NETWORK_ROLE" in
  full|proxy) ;;
  *) echo "!! dev-network: invalid NETWORK_ROLE '$NETWORK_ROLE' (use full or proxy)" >&2; exit 1 ;;
esac

case "$PROXY_ONLINE_MODE" in
  true|false) ;;
  *) echo "!! dev-network: invalid PROXY_ONLINE_MODE '$PROXY_ONLINE_MODE' (use true or false)" >&2; exit 1 ;;
esac

mkdir -p "$BASE/logs" "$BASE/runtime"

# One controller owns the proxy/lobby for the lifetime of this process. The
# advisory lock is authoritative; proxy.owner makes failures explainable and
# lets registration reject stale proxy pid/ready markers.
exec 7>"$BASE/runtime/proxy.lock"
if ! flock -n 7; then
  CURRENT_OWNER="$(sed -n 's/^owner=//p' "$BASE/runtime/proxy.owner" 2>/dev/null | sed -n '1p' || true)"
  if [ -n "$CURRENT_OWNER" ]; then
    echo "!! dev-network: proxy already owned by $CURRENT_OWNER (BASE=$BASE)" >&2
  else
    echo "!! dev-network: proxy is already controlled for BASE=$BASE" >&2
  fi
  exit 1
fi

PROXY_OWNER_ID="${PROXY_OWNER_ID:-proxy-controller-${HOSTNAME:-local}-$$}"
PROXY_OWNER_FILE="$BASE/runtime/proxy.owner"
OWNER_TMP="$PROXY_OWNER_FILE.tmp.$$"
{
  printf 'owner=%s\n' "$PROXY_OWNER_ID"
  printf 'pid=%s\n' "$$"
  printf 'port=%s\n' "$PROXY_PORT"
  printf 'role=%s\n' "$NETWORK_ROLE"
  printf 'started_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$OWNER_TMP"
mv -f "$OWNER_TMP" "$PROXY_OWNER_FILE"

# A failed startup must not leave markers that make a later registration
# believe a dead controller is still serving.
rm -f "$BASE/runtime/proxy.ready" "$BASE/runtime/proxy.pid"

cleanup_owner() {
  if [ -f "$PROXY_OWNER_FILE" ] \
    && [ "$(sed -n 's/^owner=//p' "$PROXY_OWNER_FILE" | sed -n '1p')" = "$PROXY_OWNER_ID" ]; then
    rm -f "$PROXY_OWNER_FILE"
  fi
  flock -u 7 2>/dev/null || true
}
trap cleanup_owner EXIT

# Auto-discovery is a full-network convenience. A proxy-only controller uses
# the persisted registry but never starts those backends.
AUTO_NAMES=""
if [ "$NETWORK_ROLE" = "proxy" ]; then
  if [ -z "${BACKENDS+x}" ]; then
    BACKENDS="$(cat "$BASE/runtime/backends.txt" 2>/dev/null || true)"
  fi
else
  if [ -d "$BASE/runtime/auto" ]; then
    for d in "$BASE/runtime/auto"/*/; do
      [ -d "$d" ] || continue
      AUTO_NAMES="$AUTO_NAMES $(basename "$d")"
    done
  fi
  AUTO_NAMES="$(printf '%s\n' "$AUTO_NAMES" | tr ' ' '\n' | sort -u | tr '\n' ' ')"

  # Resolve registry: explicit BACKENDS wins; else AUTO dirs REPLACE the
  # persisted file (drop-in mode owns the registry); else persisted file;
  # else default dev.
  if [ -n "${BACKENDS:-}" ]; then
    printf '%s\n' "$BACKENDS" | tr ' ' '\n' > "$BASE/runtime/backends.txt"
  elif [ -n "$AUTO_NAMES" ]; then
    BACKENDS=""
  elif [ -f "$BASE/runtime/backends.txt" ]; then
    BACKENDS="$(cat "$BASE/runtime/backends.txt")"
  else
    printf '%s\n' dev > "$BASE/runtime/backends.txt"
    BACKENDS=dev
  fi
fi

if [ "$NETWORK_ROLE" = "proxy" ]; then
  REGISTRY="$(printf '%s\n' "$BACKENDS" | tr ' ' '\n' | sort -u)"
else
  REGISTRY="$(printf '%s\n' "$BACKENDS" "$AUTO_NAMES" "${EXTERNAL_BACKENDS:-}" | tr ' ' '\n' | sort -u)"
fi
if [ -n "$REGISTRY" ]; then
  printf '%s\n' "$REGISTRY" > "$BASE/runtime/backends.txt"
else
  : > "$BASE/runtime/backends.txt"
fi

echo "== auto-discovered backends: ${AUTO_NAMES:-none}"
echo "== network role: $NETWORK_ROLE"

# --- free-port pass ----------------------------------------------------------
port_free() { # <port> -> 0 if free, 1 if in use
  ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}
if ! port_free "$PROXY_PORT"; then
  echo "!! dev-network: proxy port $PROXY_PORT already in use" >&2; exit 1
fi
if ! port_free 30066; then
  echo "!! dev-network: lobby port 30066 already in use" >&2; exit 1
fi

if [ "$NETWORK_ROLE" = "full" ]; then
  # One shared mapping: explicit PORT_<NAME> wins; otherwise use a persisted
  # live port or DEFAULT = 30067 + sorted-registry index. Persist every
  # resolved port so names containing '-' do not depend on shell variables and
  # later reindexing never moves a live backend.
  port_override() {
    local name="$1" key
    if [[ "$name" =~ ^[A-Za-z0-9_]+$ ]]; then
      key="PORT_${name^^}"
      printf '%s' "${!key:-}"
    fi
  }
  port_valid() {
    [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
  }
  port_reserved_in_pass() {
    local candidate="$1" reserved
    for reserved in $RESERVED; do
      [ "$reserved" = "$candidate" ] && return 0
    done
    return 1
  }
  RESERVED=""

  # Reserve external + explicit managed ports FIRST, then assign auto ports,
  # skipping anything already reserved or occupied.
  for name in $REGISTRY; do
    IS_EXTERNAL=0
    if printf '%s\n' "${EXTERNAL_BACKENDS:-}" | tr ' ' '\n' | grep -qx "$name"; then
      IS_EXTERNAL=1
    fi
    PERSISTED=""
    [ -f "$BASE/runtime/$name.port" ] && PERSISTED="$(cat "$BASE/runtime/$name.port")"
    OVERRIDE="$(port_override "$name")"
    if [ -n "$PERSISTED" ]; then
      P="$PERSISTED"
    elif [ -n "$OVERRIDE" ]; then
      P="$OVERRIDE"
    elif [ "$IS_EXTERNAL" = 1 ]; then
      IDX=0
      for x in $REGISTRY; do
        [ "$x" = "$name" ] && break
        IDX=$((IDX + 1))
      done
      P=$((30067 + IDX))
    else
      continue
    fi
    port_valid "$P" || { echo "!! dev-network: invalid port $P for $name" >&2; exit 1; }
    if port_reserved_in_pass "$P"; then
      echo "!! dev-network: port $P is claimed more than once" >&2
      exit 1
    fi
    printf '%s\n' "$P" > "$BASE/runtime/$name.port"
    RESERVED="$RESERVED $P"
    if [ "$IS_EXTERNAL" = 1 ]; then
      echo "   $name (external) -> port $P"
    else
      echo "   $name -> port $P (explicit)"
    fi
  done

  port_used() { # <port> -> 0 free, 1 used (occupied or reserved)
    local p="$1"
    if ! port_free "$p"; then return 1; fi
    for r in $RESERVED; do
      [ "$r" = "$p" ] && return 1
    done
    return 0
  }

  for name in $REGISTRY; do
    [ -f "$BASE/runtime/$name.port" ] && continue
    IDX=0
    for x in $REGISTRY; do
      [ "$x" = "$name" ] && break
      IDX=$((IDX + 1))
    done
    P=$((30067 + IDX))
    while ! port_used "$P"; do P=$((P + 1)); done
    printf '%s\n' "$P" > "$BASE/runtime/$name.port"
    RESERVED="$RESERVED $P"
    echo "   $name -> port $P (auto)"
  done
fi

echo "== dev-network: launching components (logs in $BASE/logs) =="
echo "== backends: ${REGISTRY:-none}"
echo "== external backends: ${EXTERNAL_BACKENDS:-none}"

lobby_supervisor() {
  local lobby_pid="" exit_code
  local shutting_down=0

  stop_lobby() {
    if [ -n "$lobby_pid" ] && kill -0 "$lobby_pid" 2>/dev/null; then
      kill "$lobby_pid" 2>/dev/null || true
      wait "$lobby_pid" 2>/dev/null || true
    fi
  }

  # shellcheck disable=SC2329
  on_shutdown() {
    shutting_down=1
    stop_lobby
  }
  trap on_shutdown INT TERM
  trap stop_lobby EXIT

  while [ "$shutting_down" = 0 ]; do
    rm -f "$BASE/runtime/lobby.ready" "$BASE/runtime/lobby.pid"
    echo "== lobby supervisor: starting lobby =="
    "$BIN_DIR/boot-lobby.sh" 7>&- &
    lobby_pid=$!
    if wait "$lobby_pid"; then
      exit_code=0
    else
      exit_code=$?
    fi
    lobby_pid=""
    [ "$shutting_down" = 0 ] || break
    rm -f "$BASE/runtime/lobby.ready" "$BASE/runtime/lobby.pid"
    echo "!! lobby supervisor: lobby exited with code $exit_code; restarting in 2s" >&2
    sleep 2
  done
  stop_lobby
}

PIDS=()
spawn() {
  "$@" &
  PIDS+=("$!")
}

teardown() {
  trap - INT TERM EXIT
  echo "== dev-network: shutting down =="
  kill "${PIDS[@]}" 2>/dev/null || true
  for p in "${PIDS[@]}"; do
    wait "$p" 2>/dev/null || true
  done
  cleanup_owner
}
trap teardown INT TERM EXIT

cd "$BASE"
spawn lobby_supervisor 7>&-
spawn env BACKENDS="$REGISTRY" PROXY_PORT="$PROXY_PORT" TARGET_SERVER="$TARGET_SERVER" \
  DEV_USERS="${DEV_USERS:-dev}" PROXY_ONLINE_MODE="$PROXY_ONLINE_MODE" "$BIN_DIR/boot-proxy.sh" 7>&-
if [ "$NETWORK_ROLE" = "full" ]; then
  for name in $REGISTRY; do
    if printf '%s\n' "${EXTERNAL_BACKENDS:-}" | tr ' ' '\n' | grep -qx "$name"; then
      spawn env BACKENDS="$REGISTRY" "$BIN_DIR/boot-external.sh" "$name" 7>&-
    elif [ -d "$BASE/runtime/auto/$name" ]; then
      spawn env BACKENDS="$REGISTRY" SERVER_DIR="$BASE/runtime/auto/$name" \
        "$BIN_DIR/boot-backend.sh" "$name" 7>&-
    else
      spawn env BACKENDS="$REGISTRY" "$BIN_DIR/boot-backend.sh" "$name" 7>&-
    fi
  done
fi

echo "== waiting for components to become ready =="
WAIT_COMPONENTS="proxy lobby"
if [ "$NETWORK_ROLE" = "full" ]; then
  WAIT_COMPONENTS="$WAIT_COMPONENTS $REGISTRY"
fi
for c in $WAIT_COMPONENTS; do
  ok=0
  for _ in $(seq 1 240); do
    if [ -f "$BASE/runtime/$c.ready" ]; then
      ok=1
      break
    fi
    sleep 1
  done
  if [ "$ok" = 1 ]; then
    echo "== $c ready =="
  else
    echo "!! $c did not become ready; check $BASE/logs/$c.log" >&2
    sed 's/^/    /' "$BASE/logs/$c.log" >&2 || true
    exit 1
  fi
done

echo
echo "== network is up =="
echo "    Connect Minecraft to  localhost:$PROXY_PORT"
echo "    Initial server:       lobby"
echo "    Switch with:          /server <name>"
for name in $REGISTRY; do
  echo "                          /server $name"
done
if [ "$NETWORK_ROLE" = "proxy" ]; then
  echo "    Proxy controller:     runProxy (backend agents register separately)"
else
  echo "    Console admin:        log in as ${DEV_USERS:-dev} (opped on every server)"
fi
echo "    Component logs:       $BASE/logs/{proxy,lobby,<name>}.log"
echo "    Rebuild + restart a backend:"
echo "        ./bin/restart-backend.sh <name> /path/to/plugin.jar"
echo "    Stop everything:      Ctrl-C here, or ./bin/stop-dev-network.sh"

# Blocks until all booters exit (booter exit = proxy/backend shutdown).
wait