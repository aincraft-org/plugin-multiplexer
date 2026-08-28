#!/usr/bin/env bash
# Stops the dev network: kills proxy, lobby, and every backend via pidfiles.
# Per-process SIGTERM (escalate to SIGKILL after 30s) so Paper's world-save
# hooks run. Uses pidfiles only, never pkill patterns.
#
# Stops every backend named in the registry (EXTERNAL_BACKENDS are never
# stopped — no pidfile; they belong to the developer), plus proxy + lobby.

set -eo pipefail

BASE="${BASE:-$PWD/development-network}"

# All components: proxy, lobby, and every backend in the registry.
COMPONENTS="proxy lobby"
if [ -f "$BASE/runtime/backends.txt" ]; then
  COMPONENTS="$COMPONENTS $(cat "$BASE/runtime/backends.txt")"
fi

for name in $COMPONENTS; do
  pidfile="$BASE/runtime/$name.pid"
  if [ ! -f "$pidfile" ]; then
    echo "== stop-dev-network: $name not running (no pidfile)"
    continue
  fi
  pid="$(cat "$pidfile")"
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "== stop-dev-network: $name not running (pid $pid gone)"
    rm -f "$pidfile"
    continue
  fi
  echo "== stop-dev-network: stopping $name (pid $pid)"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 30); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "== stop-dev-network: $name did not exit; SIGKILL"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pidfile"
done

find "$BASE/runtime" -maxdepth 1 -name '*.ready' -delete 2>/dev/null || true
echo "== stop-dev-network: done"