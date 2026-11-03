#!/usr/bin/env bash
# Starts the main apps with one command; Ctrl+C stops all of them:
#   netgraph        NetCanvas, the graph                                   http://localhost:29994
#   client          the workbench                                          http://localhost:29993/client
#   scapy-netgraph  headless: sniffs the Docker bridges and draws the client's captures in NetCanvas
#                   (no page; only the client's capture calls on :8000)    log: logs/scapy-netgraph.log
#   scapy-minimal   the page with every request and response on lo         http://localhost:29922/scapyminimal
#
#   ./run.sh                      all four
#   ./run.sh --paste 8085,8086    the same, and paste each port's own /v3/api-docs in NetCanvas once it's up
#
# The test services are separate: docker compose up -d --build (here), or ./testclient.sh
# The first time, the client gets three sample sessions for them (Sessions, bottom left): testclient orders flow,
# testclient tour and testclient advanced.
# netgraph's, the client's and scapy-netgraph's output goes to logs/; scapy-minimal's stays in this terminal.

set -u
ROOT="$(cd "$(dirname "$0")" && pwd)"
VENV="$ROOT/.venv"
PYTHON="$VENV/bin/python"
PASTE_PORTS=""
while [ $# -gt 0 ]; do
    case "$1" in
        --paste) PASTE_PORTS="${2:-}"; shift ;;
        -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
        *) echo "Unknown option $1 (--paste 8085,8086)"; exit 1 ;;
    esac
    shift
done

command -v mvn >/dev/null || { echo "mvn not found: install Maven (and Java 21)"; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found"; exit 1; }
command -v docker >/dev/null || echo "Note: docker not found: the client's query cards and scripts need it"

# The sniffers' python, with scapy, made the first time
if [ ! -x "$PYTHON" ]; then
    echo "Creating $VENV (scapy)…"
    python3 -m venv "$VENV" && "$VENV/bin/pip" install -q -r "$ROOT/scapy-netgraph/requirements.txt" \
        || { echo "Couldn't create the venv (on Debian/Ubuntu: sudo apt install python3-venv)"; rm -rf "$VENV"; exit 1; }
fi

for port in 29994 29993 8000 29922; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
        echo "Port $port is taken: is something already running there? Free it with: sudo fuser -k $port/tcp"
        exit 1
    fi
done

# Ask for the password now (the sniffers need root), not halfway through the output
sudo -v || exit 1

mkdir -p "$ROOT/logs"
# Everything started here is in this script's process group: Ctrl+C reaches all of it, and this stops the rest
# (sudo passes the signal on to the sniffer it started)
trap 'trap - EXIT INT TERM; echo; echo "Stopping…"; kill 0 2>/dev/null' EXIT INT TERM

isUp() {   # url: answers at all (curl if there is one, else the port is open)
    if command -v curl >/dev/null; then
        curl -s -o /dev/null "$1" 2>/dev/null
    else
        local hostPort="${1#http://}"; hostPort="${hostPort%%/*}"
        (exec 3<>"/dev/tcp/${hostPort%:*}/${hostPort#*:}") 2>/dev/null
    fi
}

waitFor() {   # name, url, log, pid
    for _ in $(seq 180); do
        if ! kill -0 "$4" 2>/dev/null; then
            echo "  $1 stopped while starting. The end of $3:"
            tail -n 25 "$3" | sed 's/^/    /'
            exit 1
        fi
        isUp "$2" && { echo "  $1 is up"; return 0; }
        sleep 1
    done
    echo "  $1 didn't start in 3 minutes, see $3"
    return 1
}

echo "Starting netgraph and the client (logs in $ROOT/logs)…"
(cd "$ROOT/netgraph" && exec mvn -q spring-boot:run) > "$ROOT/logs/netgraph.log" 2>&1 &
NETGRAPH_PID=$!
(cd "$ROOT/client" && exec mvn -q spring-boot:run) > "$ROOT/logs/client.log" 2>&1 &
CLIENT_PID=$!

waitFor netgraph http://localhost:29994/api/config "$ROOT/logs/netgraph.log" "$NETGRAPH_PID"
waitFor client http://localhost:29993/client "$ROOT/logs/client.log" "$CLIENT_PID"

if [ -n "$PASTE_PORTS" ]; then
    for port in ${PASTE_PORTS//,/ }; do
        "$PYTHON" -I - "$port" <<'EOF'
import sys, urllib.request
port = sys.argv[1]
try:
    docs = urllib.request.urlopen(f"http://localhost:{port}/v3/api-docs", timeout=5).read()
    request = urllib.request.Request(f"http://localhost:29994/api/apidocs/{port}", data=docs, method="PUT",
                                     headers={"Content-Type": "application/json"})
    urllib.request.urlopen(request, timeout=5).read()
    print(f"  pasted :{port}'s /v3/api-docs in NetCanvas")
except Exception as error:
    print(f"  :{port} has no /v3/api-docs (is it up?), not pasted: {error}")
EOF
    done
fi

# The sample sessions, the first time (never over ones already there), and the services' api-docs pasted in
# NetCanvas when they're up
if "$ROOT/testclient.sh" --session > "$ROOT/logs/sessions.log" 2>&1; then
    echo "  sample sessions ready (Sessions, bottom left), see logs/sessions.log"
else
    echo "  couldn't write the sample sessions, see logs/sessions.log"
fi

# Headless, in the background: -n because the password was asked for above
echo "Starting scapy-netgraph (headless, log in $ROOT/logs/scapy-netgraph.log)…"
(cd "$ROOT/scapy-netgraph" && exec sudo -n "$PYTHON" -u main.py) > "$ROOT/logs/scapy-netgraph.log" 2>&1 &
SNIFFER_PID=$!
waitFor scapy-netgraph http://localhost:8000/api/status "$ROOT/logs/scapy-netgraph.log" "$SNIFFER_PID"

echo
echo "  NetCanvas       http://localhost:29994"
echo "  client          http://localhost:29993/client"
echo "  scapy-minimal   http://localhost:29922/scapyminimal"
echo "  scapy-netgraph  headless: tail -f $ROOT/logs/scapy-netgraph.log"
echo
cd "$ROOT/scapy-minimal" && sudo "$PYTHON" main.py
