#!/usr/bin/env bash
# Verifies a RUNNING development network without changing its lifecycle or
# configuration. It checks registry/port state, endpoint reachability, and the
# plugin artifact/load evidence for harness-managed backends. Client routing
# still requires a real login through the proxy.

set -euo pipefail

BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATUS_SCRIPT="$BIN_DIR/dev-network-status.sh"
RUNTIME="$BASE/runtime"

failures=0
fail() {
  printf '!! network-test: %s\n' "$*" >&2
  failures=$((failures + 1))
}

die() {
  fail "$*"
  exit 1
}

[ -d "$RUNTIME" ] || die "runtime directory missing: $RUNTIME"
[ -f "$STATUS_SCRIPT" ] || die "status probe missing: $STATUS_SCRIPT"
[ -x "$STATUS_SCRIPT" ] || die "status probe is not executable: $STATUS_SCRIPT"

proxy_pid_file="$RUNTIME/proxy.pid"
[ -f "$proxy_pid_file" ] || die "proxy is not running (missing $proxy_pid_file)"
proxy_pid="$(cat "$proxy_pid_file")"
if [[ ! "$proxy_pid" =~ ^[0-9]+$ ]] || ! kill -0 "$proxy_pid" 2>/dev/null; then
  die "proxy pid is not alive (pidfile $proxy_pid_file)"
fi
[ -f "$RUNTIME/proxy.ready" ] || die "proxy is not ready (missing $RUNTIME/proxy.ready)"

PROXY_PORT="${PROXY_PORT:-}"
if [ -z "$PROXY_PORT" ] && [ -f "$RUNTIME/velocity.toml" ]; then
  PROXY_PORT="$(sed -n 's/^bind = "0.0.0.0:\([0-9][0-9]*\)".*/\1/p' "$RUNTIME/velocity.toml" | sed -n '1p')"
fi
PROXY_PORT="${PROXY_PORT:-25565}"
[[ "$PROXY_PORT" =~ ^[0-9]+$ ]] && [ "$PROXY_PORT" -ge 1024 ] && [ "$PROXY_PORT" -le 65535 ] \
  || die "invalid proxy port: $PROXY_PORT"

registry_file="$RUNTIME/backends.txt"
[ -f "$registry_file" ] || die "backend registry missing: $registry_file"

mapfile -t registry < "$registry_file"
[ "${#registry[@]}" -gt 0 ] || die "backend registry is empty: $registry_file"

declare -A seen_names=()
declare -A seen_ports=()
declare -A backend_ports=()
declare -A backend_dirs=()
declare -A backend_managed=()

for name in "${registry[@]}"; do
  [[ "$name" =~ ^[A-Za-z0-9_-]+$ ]] || die "invalid backend name in registry: '$name'"
  [ -n "$name" ] || die "blank backend name in registry"
  if [ -n "${seen_names[$name]+yes}" ]; then
    die "duplicate backend in registry: $name"
  fi
  seen_names["$name"]=1

  port_file="$RUNTIME/$name.port"
  [ -f "$port_file" ] || die "missing persisted port for backend '$name': $port_file"
  port="$(tr -d '[:space:]' < "$port_file")"
  [[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] \
    || die "invalid persisted port for backend '$name': '$port'"
  if [ "$port" = "$PROXY_PORT" ] || [ "$port" = "30066" ]; then
    die "backend '$name' collides with reserved port $port"
  fi
  if [ -n "${seen_ports[$port]+yes}" ]; then
    die "backend '$name' shares port $port with '${seen_ports[$port]}'"
  fi
  seen_ports["$port"]="$name"
  backend_ports["$name"]="$port"

  if [ -f "$RUNTIME/$name.auto-dir" ]; then
    backend_dirs["$name"]="$(cat "$RUNTIME/$name.auto-dir")"
  elif [ -d "$RUNTIME/auto/$name" ]; then
    backend_dirs["$name"]="$RUNTIME/auto/$name"
  else
    backend_dirs["$name"]="$RUNTIME/$name"
  fi

  if [ -f "$RUNTIME/$name.pid" ]; then
    backend_managed["$name"]=1
    backend_pid="$(cat "$RUNTIME/$name.pid")"
    if [[ ! "$backend_pid" =~ ^[0-9]+$ ]] || ! kill -0 "$backend_pid" 2>/dev/null; then
      fail "managed backend '$name' pid is not alive (pidfile $RUNTIME/$name.pid)"
    fi
  else
    backend_managed["$name"]=0
  fi

  [ -f "$RUNTIME/$name.ready" ] || fail "backend '$name' is not ready (missing $RUNTIME/$name.ready)"
done

mapfile -t sorted_registry < <(printf '%s\n' "${registry[@]}" | LC_ALL=C sort)

echo "== network-test: registry and endpoint checks (BASE=$BASE)"
echo "   proxy: localhost:$PROXY_PORT"
for name in "${sorted_registry[@]}"; do
  echo "   $name: ${backend_ports[$name]}"
done

# dev-network-status.sh prints a useful per-endpoint report but historically
# exits zero even when a socket fails. Inspect both its status and output.
status_rc=0
status_output=""
if status_output="$(
  BASE="$BASE" PROXY_PORT="$PROXY_PORT" BACKENDS="$(printf '%s ' "${sorted_registry[@]}")" \
    "$STATUS_SCRIPT" 2>&1
)"; then
  status_rc=0
else
  status_rc=$?
fi
printf '%s\n' "$status_output"
if [ "$status_rc" -ne 0 ]; then
  fail "status probe exited with code $status_rc"
fi
if [[ "$status_output" == *UNREACHABLE* ]]; then
  fail "one or more network endpoints are unreachable"
fi

if ! command -v unzip >/dev/null 2>&1; then
  die "unzip is required to inspect managed plugin jars"
fi

plugin_name_from_descriptor() {
  # The descriptor is already read with unzip; Python handles YAML's simple
  # top-level name field without adding a YAML parser dependency.
  python3 -c '
import re
import sys

text = sys.stdin.read()
match = re.search(r"(?m)^[ \\t]*name:[ \\t]*([^#\\r\\n]+)", text)
if not match:
    raise SystemExit(1)
name = match.group(1).strip().strip(chr(34) + chr(39))
if not name:
    raise SystemExit(1)
print(name)
'
}

check_managed_plugin() {
  local name="$1"
  local workdir="${backend_dirs[$name]}"
  local plugin_dir="$workdir/plugins"
  local jar descriptor plugin_name log_file
  local -a jars

  if [ ! -d "$workdir" ]; then
    fail "managed backend '$name' directory is missing: $workdir"
    return
  fi
  if [ ! -d "$plugin_dir" ]; then
    fail "managed backend '$name' plugin directory is missing: $plugin_dir"
    return
  fi

  shopt -s nullglob
  jars=("$plugin_dir"/*.jar)
  shopt -u nullglob
  if [ "${#jars[@]}" -ne 1 ]; then
    fail "managed backend '$name' must have exactly one plugin jar (found ${#jars[@]} in $plugin_dir)"
    return
  fi
  jar="${jars[0]}"

  descriptor="$(unzip -p "$jar" plugin.yml 2>/dev/null || true)"
  if [ -z "$descriptor" ]; then
    descriptor="$(unzip -p "$jar" paper-plugin.yml 2>/dev/null || true)"
  fi
  if [ -z "$descriptor" ]; then
    fail "managed backend '$name' jar has no plugin.yml or paper-plugin.yml: $jar"
    return
  fi

  if ! plugin_name="$(printf '%s' "$descriptor" | plugin_name_from_descriptor)"; then
    fail "managed backend '$name' descriptor has no top-level plugin name: $jar"
    return
  fi

  log_file="$workdir/logs/latest.log"
  if [ ! -f "$log_file" ]; then
    fail "managed backend '$name' has no Paper log for plugin-load evidence: $log_file"
    return
  fi
  if grep -Fq "[$plugin_name] Enabling $plugin_name" "$log_file" \
      || grep -Fq "Enabling $plugin_name" "$log_file"; then
    echo "   $name: plugin loaded ($plugin_name) from $(basename "$jar")"
  else
    fail "managed backend '$name' has no enable event for plugin '$plugin_name' in $log_file"
  fi
}

for name in "${sorted_registry[@]}"; do
  if [ "${backend_managed[$name]}" = 1 ]; then
    check_managed_plugin "$name"
  else
    echo "   $name: external backend (reachability only; plugin files are not managed)"
  fi
done

echo
echo "== network-test: routing matrix (requires an authorized real client)"
echo "   Connect to localhost:$PROXY_PORT, land on lobby, then run:"
for name in "${sorted_registry[@]}"; do
  echo "     /server $name"
done
echo "   Status responses prove reachability; only the real login proves routing."

if [ "$failures" -ne 0 ]; then
  echo "!! network-test: $failures check(s) failed" >&2
  exit 1
fi

echo "== network-test: automated checks passed"
