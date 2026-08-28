#!/usr/bin/env bash
# Joins an ALREADY-RUNNING external server (e.g. a plugin's own `runServer`
# launched in its project dir) to the dev network as a named backend.
#
# The harness NEVER modifies the external server's files: the server's config
# belongs to the developer. boot-external.sh only:
#   1. registers the backend (registry + port math) so the proxy knows it;
#   2. VERIFIES the external server is configured for Velocity modern
#      forwarding; if not, prints the exact config to add and its path.
#   3. writes the ready marker when the server is reachable.
#
# It does NOT start or stop the server.
#
# Requirements on the external server:
#   - Paper 26.2 (the same pinned version as the backends)
#   - currently listening on $PORT_<NAME> (default 30067 + registry index)
#   - config/paper-global.yml: proxies.velocity.enabled=true,
#     proxies.velocity.online-mode=false,
#     proxies.velocity.secret="dev-local-forwarding-secret-change-me"
#   - server.properties: online-mode=false
#
# Usage: boot-external.sh <NAME>
#   NAME: the Velocity server name (must match [A-Za-z0-9_-]+ and be in the
#         registry). $EXTERNAL_DIR_<NAME> points at the server directory;
#         falls back to $EXTERNAL_DIR, then $BASE/runtime/external/<NAME>.

set -eo pipefail

NAME="${1:?usage: boot-external.sh <NAME>}"
[[ "$NAME" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "invalid backend name: $NAME" >&2; exit 1; }

BASE="${BASE:-$PWD/development-network}"

WORKDIR=""
if [[ "$NAME" =~ ^[A-Za-z0-9_]+$ ]]; then
  DIR_KEY="EXTERNAL_DIR_${NAME^^}"
  WORKDIR="${!DIR_KEY:-}"
fi
if [ -z "$WORKDIR" ] && [ -n "${EXTERNAL_DIR:-}" ]; then
  WORKDIR="$EXTERNAL_DIR"
fi
if [ -z "$WORKDIR" ]; then
  WORKDIR="$BASE/runtime/external/$NAME"
fi

[ -d "$WORKDIR" ] || { echo "boot-external: no such server dir $WORKDIR" >&2; exit 1; }

# --- registration + port (same math as boot-proxy.sh) -----------------------
mkdir -p "$BASE/runtime"
REGISTRY="${BACKENDS:-$(cat "$BASE/runtime/backends.txt" 2>/dev/null || echo dev)}"
if ! grep -qx "$NAME" "$BASE/runtime/backends.txt"; then
  echo "boot-external: '$NAME' is not in the backend registry: $REGISTRY" >&2
  echo "  add it via BACKENDS (persisted in backends.txt) or bump the registry file." >&2
  exit 1
fi

if [ -f "$BASE/runtime/$NAME.port" ]; then
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
        SERVER_PORT=$((30067 + IDX))
        break
      fi
      IDX=$((IDX + 1))
    done
  fi
else
  IDX=0
  SERVER_PORT=30067
  for x in $(printf '%s\n' "$REGISTRY" | tr ' ' '\n' | sort -u); do
    if [ "$x" = "$NAME" ]; then
      SERVER_PORT=$((30067 + IDX))
      break
    fi
    IDX=$((IDX + 1))
  done
fi
printf '%s\n' "$SERVER_PORT" > "$BASE/runtime/$NAME.port"

echo "== boot-external: registered '$NAME' @127.0.0.1:$SERVER_PORT"

# --- verify modern-forwarding config (NEVER write to the server dir) --------
CFG="$WORKDIR/config/paper-global.yml"
MISSING=""
if [ -f "$CFG" ]; then
  grep -q 'velocity:' "$CFG"                       || MISSING="$MISSING velocity-block"
  grep -q 'enabled: true' "$CFG"                    || MISSING="$MISSING enabled:true"
  grep -q 'online-mode: false' "$CFG"               || MISSING="$MISSING online-mode:false"
  grep -q 'secret: dev-local-forwarding-secret-change-me' "$CFG" || MISSING="$MISSING secret"
else
  MISSING="paper-global.yml (file absent)"
fi

if [ -n "$MISSING" ]; then
  echo "!! boot-external: $NAME is NOT configured for Velocity modern forwarding ($MISSING)."
  echo "!! The harness never edits external server files. Add this block to $CFG"
  echo "!! (Paper merges it on restart; keep the rest of the file as-is):"
  echo "!!"
  echo "!!   proxies:"
  echo "!!     velocity:"
  echo "!!       enabled: true"
  echo "!!       online-mode: false"
  echo "!!       secret: \"dev-local-forwarding-secret-change-me\""
  echo "!!"
  echo "!! Then RESTART the external server once. (server.properties must also"
  echo "!! have online-mode=false.)"
else
  echo "== boot-external: modern-forwarding config present."
fi

# --- reachability -----------------------------------------------------------
if (exec 3<>"/dev/tcp/127.0.0.1/$SERVER_PORT") 2>/dev/null; then
  exec 3>&- 3<&-
  touch "$BASE/runtime/$NAME.ready"
  echo "== boot-external: server reachable on $SERVER_PORT; ready."
else
  echo "!! boot-external: nothing listening on $SERVER_PORT — is the external server running?" >&2
  exit 1
fi