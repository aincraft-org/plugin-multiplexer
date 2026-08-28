#!/usr/bin/env bash
# Stops the dev network: kills its proxy controller, proxy, lobby, and managed
# backends via pidfiles. Per-process SIGTERM (escalate to SIGKILL after 30s) so
# Paper's world-save hooks run. Uses pidfiles only, never pkill patterns.
#
# An explicit proxy-only controller does not stop backend agents when it is
# interrupted directly; this command remains the forceful whole-network stop.

set -eo pipefail

BASE="${BASE:-$PWD/development-network}"

# Ask the controller shell to run its own trap first. That releases the
# proxy.owner lock and stops proxy+lobby cleanly.
OWNER_FILE="$BASE/runtime/proxy.owner"
CONTROLLER_LOCK_HELD=0
exec 9>"$BASE/runtime/proxy.lock"
if ! flock -n 9; then
  CONTROLLER_LOCK_HELD=1
fi
flock -u 9 2>/dev/null || true
exec 9>&-

if [ -f "$OWNER_FILE" ]; then
  CONTROLLER_PID="$(sed -n 's/^pid=//p' "$OWNER_FILE" | sed -n '1p')"
  if [ "$CONTROLLER_LOCK_HELD" = 1 ] && [[ "$CONTROLLER_PID" =~ ^[0-9]+$ ]] \
    && [ "$CONTROLLER_PID" != "$$" ] && kill -0 "$CONTROLLER_PID" 2>/dev/null; then
    echo "== stop-dev-network: stopping proxy controller (pid $CONTROLLER_PID)"
    kill "$CONTROLLER_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do
      kill -0 "$CONTROLLER_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$CONTROLLER_PID" 2>/dev/null || true
  fi
fi

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
  if ! [[ "$pid" =~ ^[0-9]+$ ]] || ! kill -0 "$pid" 2>/dev/null; then
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
if [ -f "$OWNER_FILE" ]; then
  CONTROLLER_PID="$(sed -n 's/^pid=//p' "$OWNER_FILE" | sed -n '1p')"
  if ! [[ "$CONTROLLER_PID" =~ ^[0-9]+$ ]] || ! kill -0 "$CONTROLLER_PID" 2>/dev/null; then
    rm -f "$OWNER_FILE"
  fi
fi
echo "== stop-dev-network: done"