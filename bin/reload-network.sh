#!/usr/bin/env bash
# Applies the current backend registry to a RUNNING proxy — the agent-visible
# "reload the network" command. If velocity.toml was edited by hand (or a
# previous register/unregister crashed mid-way), this regenerates it from the
# authoritative registry (bin/velocity-toml.sh) and sends "velocity reload"
# through the proxy's command FIFO. No proxy restart.
#
# The regenerated config is byte-identical to what boot-proxy.sh generated, so
# an idempotent re-apply is a config no-op plus a harmless reload.
#
# Usage: reload-network.sh
#
# Requires a running proxy (runtime/proxy.pid + proxy.ready). The proxy's
# bound port is re-read from its velocity.toml.

set -eo pipefail

BASE="${BASE:-$PWD/development-network}"
BIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

[ -f "$BASE/runtime/proxy.pid" ] || { echo "reload: no running proxy (missing runtime/proxy.pid)" >&2; exit 1; }

mkdir -p "$BASE/runtime" "$BASE/logs"

# Serialize with concurrent register/unregister (they hold the same lock).
exec 8>"$BASE/runtime/register.lock"
flock 8

if [ -z "${PROXY_PORT:-}" ]; then
  PROXY_PORT="$(sed -n 's/^bind = "0.0.0.0:\([0-9][0-9]*\)".*/\1/p' "$BASE/runtime/velocity.toml" | head -1)"
  PROXY_PORT="${PROXY_PORT:-25565}"
fi

(
  export BASE PROXY_PORT
  # shellcheck source=velocity-toml.sh
  . "$BIN_DIR/velocity-toml.sh"
  write_velocity_toml
)

# shellcheck disable=SC2031
if [ -p "$BASE/runtime/velocity.cmd" ]; then
  printf 'velocity reload\n' > "$BASE/runtime/velocity.cmd"
  echo "   reload: velocity reload sent; registry applied"
else
  echo "!! reload: command FIFO missing (runtime/velocity.cmd)" >&2
  exit 1
fi