#!/usr/bin/env bash
# Probes the network's endpoints (proxy + all backends) with a Minecraft
# status request and prints each reply. The MOTDs are unique per backend, so a
# reply proves that endpoint accepts connections.
#
# IMPORTANT (labeling): the proxy's reply is its OWN status (velocity.toml
# motd / ping-passthrough = DISABLED); it does NOT prove that connecting
# through the proxy lands on a backend (routing). Backend replies are direct
# and prove reachability only. Routing/multiplexing is proven by an actual
# login: join localhost:25565, /server <name>.
#
# Requires: python3. Ports: proxy 25565; lobby 30066; backends per registry.

set -eo pipefail

BASE="${BASE:-$PWD/development-network}"
PROXY_PORT="${PROXY_PORT:-25565}"

REGISTRY="${BACKENDS:-$(cat "$BASE/runtime/backends.txt" 2>/dev/null || echo dev)}"
PORTS="$PROXY_PORT 30066"
for name in $REGISTRY; do
  PKEY="PORT_${name^^}"
  # A running backend's persisted port is authoritative. Fall back to the
  # environment/default mapping only for pre-registration compatibility.
  if [ -f "$BASE/runtime/$name.port" ]; then
    P="$(tr -d '[:space:]' < "$BASE/runtime/$name.port")"
  elif [[ "$name" =~ ^[A-Za-z0-9_]+$ ]] && [ -n "${!PKEY:-}" ]; then
    P="${!PKEY}"
  else
    IDX=0
    P=30067
    for x in $(printf '%s\n' "$REGISTRY" | tr ' ' '\n' | sort -u); do
      if [ "$x" = "$name" ]; then
        P=$((30067 + IDX))
        break
      fi
      IDX=$((IDX + 1))
    done
  fi
  PORTS="$PORTS $P"
done

# shellcheck disable=SC2086
python3 - $PORTS <<'PY'
import json, socket, struct, sys

ports = [int(p) for p in sys.argv[1:]]

def read_varint(sock):
    value = 0
    for i in range(5):
        b = sock.recv(1)
        if not b:
            raise EOFError("connection closed mid-varint")
        value |= (b[0] & 0x7F) << (7 * i)
        if not (b[0] & 0x80):
            return value
    raise ValueError("varint too long")

def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError(f"connection closed; got {len(buf)}/{n} bytes")
        buf += chunk
    return buf

def ping(port, timeout=5.0):
    s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    try:
        # Handshake: protocol -1 (status), host, port, then state 1.
        payload = b"\x00\xff\xff\xff\x0f" + b"\x09localhost" \
                  + struct.pack(">H", port) + b"\x01"
        s.sendall(bytes([len(payload)]) + payload)
        s.sendall(b"\x01\x00")  # status request: packet id 0, no payload
        read_varint(s)          # packet length
        read_varint(s)          # packet id (0)
        body_len = read_varint(s)
        body = recv_exact(s, body_len)
        data = json.loads(body)
        desc = data.get("description", {})
        if isinstance(desc, dict):
            desc = desc.get("text", "")
        elif isinstance(desc, list):
            desc = "".join(p.get("text", "") for p in desc)
        return desc, data.get("version", {}).get("name", "?")
    finally:
        s.close()

labels = {25565: "proxy", 30066: "lobby"}
for port in ports:
    label = labels.get(port, f"backend:{port} ({port})")
    try:
        desc, ver = ping(port)
        print(f"{label:22s} reachable  motd={desc!r} version={ver}")
    except Exception as e:
        print(f"{label:22s} UNREACHABLE ({e})")
PY
echo
echo "== note: status replies prove reachability only. Routing/multiplexing is"
echo "== proven by a real login: join localhost:25565, then /server <name>."