#!/usr/bin/env bash
# Boots one backend Paper server for the dev network.
#
# Usage: boot-backend.sh <NAME>
#   Name must match [A-Za-z0-9_-]+ and be listed in the registry.
#   Port:   $PORT_<NAME> (default: 30067 + index in the sorted backend list).
#   Plugin: $PLUGIN_<NAME> = path to a built jar to install (stale CalVer jars
#           are cleared first). Falls back to $PLUGIN_JAR for single-backend use.
#   Ops:    developer accounts (DEV_USERS, default "dev") get operator level 4.
#   Ready:  writes $BASE/runtime/<NAME>.ready; pidfile $BASE/runtime/<NAME>.pid
#
# Pins verified 2026-08-27 against https://fill.papermc.io/v3/projects/paper
# (Paper 26.2, build 119) and the official docs at
# https://docs.papermc.io/velocity/player-information-forwarding.

set -eo pipefail

NAME="${1:?usage: boot-backend.sh <NAME>}"
[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "invalid backend name: $NAME" >&2; exit 1; }

VERSION="26.2"
BUILD="119"
SHA256="a8c9140c3075bd7c04973e9cdc491b21bfe6bad472b674ef932a4ae0fec19629"
BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_SERVER="${TARGET_SERVER:-localhost}"

[ -d "$BASE" ] || { echo "base dir missing: $BASE" >&2; exit 1; }
mkdir -p "$BASE/binaries" "$BASE/runtime"

JAR="$BASE/binaries/paper-$VERSION-$BUILD.jar"
"$BIN_DIR/fetch-jar.sh" \
  "https://fill-data.papermc.io/v1/objects/$SHA256/paper-$VERSION-$BUILD.jar" \
  "$SHA256" "$JAR"

# --- port from the sorted registry (same math as boot-proxy.sh) -------------
REGISTRY="${BACKENDS:-$(cat "$BASE/runtime/backends.txt" 2>/dev/null || echo dev)}"
if [ -f "$BASE/runtime/$NAME.port" ]; then
  # Runtime registration persists the live port; it is authoritative even
  # when a later registry reindex would choose a different default.
  SERVER_PORT="$(cat "$BASE/runtime/$NAME.port")"
elif [[ "$NAME" =~ ^[A-Za-z0-9_]+$ ]]; then
  PORT_KEY="PORT_${NAME^^}"
  if [ -n "${!PORT_KEY:-}" ]; then
    SERVER_PORT="${!PORT_KEY}"
  else
    IDX=0
    SERVER_PORT=30067
    for x in $(printf '%s\n' "$REGISTRY" | tr ' ' '\n' | sort -u); do
      if [ "$x" = "$NAME" ]; then
        break
      fi
      IDX=$((IDX + 1))
    done
    SERVER_PORT=$((30067 + IDX))
    while (exec 3<>"/dev/tcp/127.0.0.1/$SERVER_PORT") 2>/dev/null; do
      exec 3>&- 3<&-
      SERVER_PORT=$((SERVER_PORT + 1))
    done
    exec 3>&- 3<&- 2>/dev/null || true
  fi
else
  IDX=0
  SERVER_PORT=30067
  for x in $(printf '%s\n' "$REGISTRY" | tr ' ' '\n' | sort -u); do
    if [ "$x" = "$NAME" ]; then
      break
    fi
    IDX=$((IDX + 1))
  done
  SERVER_PORT=$((30067 + IDX))
  while (exec 3<>"/dev/tcp/127.0.0.1/$SERVER_PORT") 2>/dev/null; do
    exec 3>&- 3<&-
    SERVER_PORT=$((SERVER_PORT + 1))
  done
  exec 3>&- 3<&- 2>/dev/null || true
fi
printf '%s\n' "$SERVER_PORT" > "$BASE/runtime/$NAME.port"

# Server dir: explicit SERVER_DIR (auto-discovered folder) wins; default runtime/<NAME>.
WORKDIR="${SERVER_DIR:-$BASE/runtime/$NAME}"
mkdir -p "$WORKDIR/plugins"

# Auto-discovered servers live in runtime/auto/<NAME>: expose their dir to the
# rest of the harness by recording it on the ready marker.
if [ "$WORKDIR" != "$BASE/runtime/$NAME" ]; then
  echo "$WORKDIR" > "$BASE/runtime/$NAME.auto-dir"
fi

# Plugin install: $PLUGIN_<NAME> wins, then $PLUGIN_JAR. Names containing '-'
# cannot address a shell variable; their auto backend directory is already
# populated by the caller.
PLUGIN_PATH="${PLUGIN_JAR:-}"
if [[ "$NAME" =~ ^[A-Za-z0-9_]+$ ]]; then
  PLUGIN_KEY="PLUGIN_${NAME^^}"
  PLUGIN_PATH="${!PLUGIN_KEY:-${PLUGIN_JAR:-}}"
fi
# CalVer names change every build: stale jars must go, or old versions stay loaded.
if [ -n "$PLUGIN_PATH" ]; then
  rm -f "$WORKDIR/plugins/"*.jar
  cp "$PLUGIN_PATH" "$WORKDIR/plugins/"
  echo "   $NAME: installed plugin $(basename "$PLUGIN_PATH")"
fi

# --- config ---------------------------------------------------------------
cat > "$WORKDIR/server.properties" <<EOF
server-port=$SERVER_PORT
online-mode=false
level-name=world
motd=dev-network $NAME
EOF

mkdir -p "$WORKDIR/config"
cat > "$WORKDIR/config/paper-global.yml" <<EOF
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "dev-local-forwarding-secret-change-me"
EOF

cat > "$WORKDIR/eula.txt" <<EOF
eula=true
EOF

# Velocity modern forwarding (and offline mode) require BungeeCord forwarding off.
cat > "$WORKDIR/spigot.yml" <<EOF
settings:
  bungeecord: false
EOF

# Developer accounts get operator level 4 (offline UUIDs, DEV_USERS default "dev").
"$BIN_DIR/write-ops.sh" "$WORKDIR"

# --- run --------------------------------------------------------------------
cd "$WORKDIR"
java -Xms512M -Xmx1G -XX:+UseG1GC \
  -jar "$JAR" nogui &

SERVER_PID=$!
echo "$SERVER_PID" > "$BASE/runtime/$NAME.pid"
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 240); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$SERVER_PORT") 2>/dev/null; then
    exec 3>&- 3<&-
    touch "$BASE/runtime/$NAME.ready"
    break
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "$NAME process died" >&2; exit 1; }
  sleep 1
done

[ -f "$BASE/runtime/$NAME.ready" ] || { echo "$NAME did not open port $SERVER_PORT" >&2; exit 1; }

sleep 2
wait "$SERVER_PID"