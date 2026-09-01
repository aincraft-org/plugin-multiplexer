#!/usr/bin/env bash
# Shared velocity.toml generator for the dev network.
# Sourced by boot-proxy.sh (boot-time) and register-backend.sh (runtime
# hot-add): both must produce byte-identical configs so a reload after a hot
# registration picks up exactly the [servers]/try the network would have had
# if the backend had been registered at boot.
#
# Env inputs:
#   BASE          runtime dir (default $PWD/development-network)
#   PROXY_PORT    proxy bind port (default 25565)
#   PROXY_BIND    bind address (default 127.0.0.1). Non-loopback requires
#                 ALLOW_NON_LOOPBACK_BIND=true (or PROXY_BIND_ALL=true → 0.0.0.0).
#   FORWARDING_SECRET  optional explicit modern-forwarding secret; otherwise
#                 reuse runtime/forwarding.secret when present, else generate.
#   TARGET_SERVER host the proxy dials backends on (default localhost)
#   BACKENDS      optional override; else runtime/backends.txt; else "dev"
#   PORT_<NAME>   optional per-backend port override
#
# Writes: $BASE/runtime/velocity.toml and $BASE/runtime/forwarding.secret.

# Resolve (and persist) the modern-forwarding secret for this BASE.
resolve_forwarding_secret() {
  local forwarding_file="$1"
  local existing
  if [ -n "${FORWARDING_SECRET+x}" ] && [ -n "$FORWARDING_SECRET" ]; then
    printf '%s' "$FORWARDING_SECRET"
    return 0
  fi
  if [ -f "$forwarding_file" ]; then
    existing="$(tr -d '\r\n' < "$forwarding_file" || true)"
    if [ -n "$existing" ]; then
      printf '%s' "$existing"
      return 0
    fi
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
    return 0
  fi
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
}

# True when the address is loopback-only.
is_loopback_bind() {
  case "$1" in
    127.0.0.1|::1|localhost) return 0 ;;
    *) return 1 ;;
  esac
}

write_velocity_toml() {
  local BASE="${BASE:-$PWD/development-network}"
  local PROXY_PORT="${PROXY_PORT:-25565}"
  local TARGET_SERVER="${TARGET_SERVER:-localhost}"
  local PROXY_BIND
  if [ "${PROXY_BIND_ALL:-false}" = "true" ]; then
    PROXY_BIND="0.0.0.0"
  else
    PROXY_BIND="${PROXY_BIND:-127.0.0.1}"
  fi
  if ! is_loopback_bind "$PROXY_BIND"; then
    if [ "${ALLOW_NON_LOOPBACK_BIND:-false}" != "true" ] && [ "${PROXY_BIND_ALL:-false}" != "true" ]; then
      echo "PROXY_BIND=$PROXY_BIND is not loopback." >&2
      echo "Dev network defaults to 127.0.0.1. Set ALLOW_NON_LOOPBACK_BIND=true" >&2
      echo "(or PROXY_BIND_ALL=true for 0.0.0.0) to opt in — offline mode + shared" >&2
      echo "forwarding secret must not be exposed on a shared network." >&2
      return 1
    fi
  fi

  local online_mode
  if [ -n "${PROXY_ONLINE_MODE+x}" ]; then
    online_mode="$PROXY_ONLINE_MODE"
  elif [ -f "$BASE/runtime/velocity.toml" ]; then
    online_mode="$(sed -n 's/^online-mode = \(true\|false\)$/\1/p' "$BASE/runtime/velocity.toml" | sed -n '1p')"
    online_mode="${online_mode:-false}"
  else
    online_mode="false"
  fi
  case "$online_mode" in
    true|false) ;;
    *) echo "invalid PROXY_ONLINE_MODE '$online_mode' (use true or false)" >&2; return 1 ;;
  esac

  local BACKENDS_SORTED
  if [ -z "${BACKENDS+x}" ]; then
    if [ -f "$BASE/runtime/backends.txt" ]; then
      BACKENDS="$(cat "$BASE/runtime/backends.txt")"
    else
      BACKENDS="dev"
    fi
  fi
  BACKENDS_SORTED="$(printf '%s\n' "$BACKENDS" | tr ' ' '\n' | sort -u | tr '\n' ' ')"

  # Runtime-registered EXTERNAL servers persist their live port in
  # runtime/<name>.port (bin/register-backend.sh). Honor it on every regen so
  # a live server's port never changes across a reload or a proxy reboot.
  backend_port() {
    local n="$1" key idx=0 x
    if [ -f "$BASE/runtime/$n.port" ]; then
      cat "$BASE/runtime/$n.port"
      return
    fi
    # Shell variable names cannot contain '-', so only inspect PORT_<NAME>
    # directly for names that can be represented as environment variables.
    if [[ "$n" =~ ^[A-Za-z0-9_]+$ ]]; then
      key="PORT_${n^^}"
      if [ -n "${!key:-}" ]; then
        echo "${!key}"
        return
      fi
    fi
    for x in $BACKENDS_SORTED; do
      if [ "$x" = "$n" ]; then
        echo $((30067 + idx))
        return
      fi
      idx=$((idx + 1))
    done
    echo 30067
  }

  # Modern forwarding needs offline mode + a shared secret, mirrored in
  # paper-global.yml on each backend. Persist per BASE; never a fixed string.
  local FORWARDING_FILE="$BASE/runtime/forwarding.secret"
  mkdir -p "$BASE/runtime"
  local SECRET
  SECRET="$(resolve_forwarding_secret "$FORWARDING_FILE")"
  if [ -z "$SECRET" ]; then
    echo "failed to resolve a non-empty forwarding secret" >&2
    return 1
  fi
  printf '%s\n' "$SECRET" > "$FORWARDING_FILE"
  chmod 600 "$FORWARDING_FILE" 2>/dev/null || true

  {
    cat <<EOF
config-version = "2.8"
bind = "$PROXY_BIND:$PROXY_PORT"
motd = "<#09add3>dev-network"
show-max-players = 20
online-mode = $online_mode
force-key-authentication = true
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "$FORWARDING_FILE"
announce-forge = false
kick-existing-players = false
ping-passthrough = "DISABLED"
sample-players-in-ping = false
enable-player-address-logging = true
auto-connect-upstreams = true

[servers]
EOF
    printf 'lobby = "%s:30066"\n' "$TARGET_SERVER"
    for n in $BACKENDS_SORTED; do
      printf '%s = "%s:%s"\n' "$n" "$TARGET_SERVER" "$(backend_port "$n")"
    done
    printf 'try = ["lobby"'
    for n in $BACKENDS_SORTED; do
      printf ', "%s"' "$n"
    done
    printf ']\n'
    cat <<EOF

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 3000
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false
enable-reuse-port = false
command-rate-limit = 50
forward-commands-if-rate-limited = true
kick-after-rate-limited-commands = 0
tab-complete-rate-limit = 10
kick-after-rate-limited-tab-completes = 0

[query]
enabled = false
port = 25565
map = "dev-network"
show-plugins = false
EOF
  } > "$BASE/runtime/velocity.toml"
}
