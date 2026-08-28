#!/usr/bin/env bash
# Restarts ONE backend Paper server (fast plugin iteration):
#   1. stop the backend (port from registry; pidfile runtime/<name>.pid)
#   2. install the NEW built plugin jar (from $2 or PLUGIN_<NAME>/PLUGIN_JAR),
#      clearing stale CalVer jars
#   3. boot it again
# Lobby, proxy, and other backends stay up; players on the restarted backend
# are kicked back to the lobby by the proxy.
#
# Usage: restart-backend.sh <NAME> [/path/to/plugin.jar]
#        PLUGIN_<NAME>=/path PLUGIN_JAR=/path restart-backend.sh <NAME>
#
# NOTE: boot-backend.sh blocks (it waits on the server), so the stop+install
# happen here, then exec into a fresh boot.

set -eo pipefail

NAME="${1:?usage: restart-backend.sh <NAME> [plugin.jar]}"
BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_ARG="${2:-}"

[ -d "$BASE" ] || { echo "base dir missing: $BASE" >&2; exit 1; }

# --- stop old backend -------------------------------------------------------
pidfile="$BASE/runtime/$NAME.pid"
OLDPID=""
if [ -f "$pidfile" ]; then
  OLDPID="$(cat "$pidfile")"
  if kill -0 "$OLDPID" 2>/dev/null; then
    echo "== restart-backend: stopping $NAME (pid $OLDPID)"
    kill "$OLDPID" 2>/dev/null || true
    for _ in $(seq 1 60); do
      kill -0 "$OLDPID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$OLDPID" 2>/dev/null || true
  fi
  rm -f "$pidfile"
fi
rm -f "$BASE/runtime/$NAME.ready"

# --- install new plugin jar --------------------------------------------------
if [ -n "$JAR_ARG" ]; then
  rm -f "$BASE/runtime/$NAME/plugins/"*.jar
  cp "$JAR_ARG" "$BASE/runtime/$NAME/plugins/"
  echo "== restart-backend: installed plugin $(basename "$JAR_ARG")"
fi

# --- boot backend (fresh): use its auto-discovered dir if it has one ---------
if [ -d "$BASE/runtime/auto/$NAME" ]; then
  exec env SERVER_DIR="$BASE/runtime/auto/$NAME" "$BIN_DIR/boot-backend.sh" "$NAME"
fi
exec "$BIN_DIR/boot-backend.sh" "$NAME"