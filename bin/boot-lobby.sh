#!/usr/bin/env bash
# Boots a basic lobby Paper server on port 30066 behind the proxy.
# No plugins; just a stable lobby the user returns to.
# Developer accounts (DEV_USERS, default "dev") are opped via ops.json.
#
# Lobby world: a pinned map is downloaded on first setup (a URL that plain
# curl can fetch + SHA-256) and unpacked into runtime/lobby/world before the
# server starts. Map hosts (Planet Minecraft, CurseForge, BuiltByBit,
# MinecraftMaps) all 403 plain curl / need browser sessions and ad-shortener
# clicks, so there is NO hardcoded remote lobby URL in this repo: point at a
# world zip you trust (prefer a versioned GitHub release asset with a recorded
# SHA-256 and license/attribution), then every subsequent boot reuses the
# installed world untouched.
#   URL=https://example.com/lobby.zip \  LOBBY_MAP_SHA256=<64-hex> \
#     ./development-network/bin/dev-network.sh
# Without URL+LOBBY_MAP_SHA256 the vanilla empty world is generated as before.
#
# Immutability: the map is installed EXACTLY ONCE. If runtime/lobby/world
# already has a level.dat, this block never runs again — an existing world
# (generated or from a previous map) is never overwritten, even if URL changed.
#
# Pins verified 2026-08-27 against https://fill.papermc.io/v3/projects/paper
# (Paper 26.2, build 119, Java 25 minimum) and the official docs at
# https://docs.papermc.io/velocity/player-information-forwarding.

set -eo pipefail

VERSION="26.2"
BUILD="119"
PAPER_SHA256="a8c9140c3075bd7c04973e9cdc491b21bfe6bad472b674ef932a4ae0fec19629"
BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_PORT="${SERVER_PORT:-30066}"
TARGET_SERVER="${TARGET_SERVER:-localhost}"

[ -d "$BASE" ] || { echo "base dir missing: $BASE" >&2; exit 1; }
mkdir -p "$BASE/binaries" "$BASE/runtime"

JAR="$BASE/binaries/paper-$VERSION-$BUILD.jar"
"$BIN_DIR/fetch-jar.sh" \
  "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$VERSION-$BUILD.jar" \
  "$PAPER_SHA256" "$JAR"

WORKDIR="$BASE/runtime/lobby"
mkdir -p "$WORKDIR"

# --- lobby world (first setup only; never overwrites an existing world) ------
# URL+LOBBY_MAP_SHA256 are optional; when absent the server generates a fresh
# world. When set AND no world exists yet: download (or reuse cache), verify
# the pinned SHA-256, unpack into runtime/lobby/world BEFORE the server
# starts. If a world already exists, NOTHING is downloaded or extracted.
# Partial config (URL without LOBBY_MAP_SHA256, or vice versa) fails fast —
# it is a typo waiting to hide the requested map.
if [ -n "${URL:-}" ] && [ -n "${LOBBY_MAP_SHA256:-}" ]; then
  if [ -e "$WORKDIR/world/level.dat" ]; then
    echo "   lobby: world already exists; keeping it (map download skipped)"
  else
    if [ -f "$WORKDIR/.world.zip" ]; then
      echo "   lobby: cached $WORKDIR/.world.zip"
    else
      mkdir -p "$WORKDIR"
      echo "   lobby: downloading world from $URL"
      curl -fsSL "$URL" -o "$WORKDIR/.world.zip"
    fi
    if ! echo "$LOBBY_MAP_SHA256  $WORKDIR/.world.zip" | sha256sum -c - >/dev/null 2>&1; then
      echo "lobby: world zip checksum mismatch; remove runtime/lobby/.world.zip and retry" >&2
      exit 1
    fi
    echo "   lobby: unpacking world (zip sha256 ok)"
    rm -rf "$WORKDIR/_extract"
    mkdir -p "$WORKDIR/_extract"
    unzip -q "$WORKDIR/.world.zip" -d "$WORKDIR/_extract"
    WORLD_SRC="$WORKDIR/_extract"
    SUB="$(find "$WORKDIR/_extract" -mindepth 1 -maxdepth 1 -type d | wc -l)"
    [ "$SUB" = 1 ] && [ -f "$(find "$WORKDIR/_extract" -mindepth 1 -maxdepth 1 -type d)/level.dat" ] \
      && WORLD_SRC="$(find "$WORKDIR/_extract" -mindepth 1 -maxdepth 1 -type d)"
    [ -f "$WORLD_SRC/level.dat" ] || { echo "lobby: zip has no level.dat (bad map)" >&2; exit 1; }
    mkdir -p "$WORKDIR/world"
    cp -a "$WORLD_SRC/." "$WORKDIR/world/"
    rm -rf "$WORKDIR/_extract" "$WORKDIR/.world.zip"
    echo "   lobby: map ready in runtime/lobby/world (level-name=world)"
  fi
elif [ -n "${URL:-}" ] || [ -n "${LOBBY_MAP_SHA256:-}" ]; then
  echo "lobby: set BOTH URL and LOBBY_MAP_SHA256, or neither (URL='${URL:-}' SHA256='${LOBBY_MAP_SHA256:-}')" >&2
  exit 1
else
  echo "   lobby: no URL/LOBBY_MAP_SHA256 set; using the generated empty world"
fi

# --- config ---------------------------------------------------------------
cat > "$WORKDIR/server.properties" <<EOF
server-port=$SERVER_PORT
online-mode=false
level-name=world
motd=dev-network lobby
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
echo "$SERVER_PID" > "$BASE/runtime/lobby.pid"
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 240); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$SERVER_PORT") 2>/dev/null; then
    exec 3>&- 3<&-
    touch "$BASE/runtime/lobby.ready"
    break
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "lobby process died" >&2; exit 1; }
  sleep 1
done

[ -f "$BASE/runtime/lobby.ready" ] || { echo "lobby did not open port $SERVER_PORT" >&2; exit 1; }

# Let the server print its Done banner before the proxy starts probing it.
sleep 2
wait "$SERVER_PID"