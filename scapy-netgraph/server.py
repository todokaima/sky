"""
The web server:
  GET  /                the page: the captured exchanges that pass the filter, newest first, refreshed every second.
  GET  /api/exchanges   the same as JSON.
  GET  /api/exchangesByPorts   the same grouped by "clientPort:serverPort", just URI and JSON both ways.
  GET  /api/filter      the filter, e.g. {"uris": ["/orders"], "methods": [], "sourcePorts": [], "destinationPorts": [8085]}
  POST /api/filter      replaces it (same JSON; each value may also be a single value or "a, b").
  POST /api/clear       empties the list.
  POST /api/resend      sends a captured request again, as edited on the page (see resend.py)
  GET  /api/capture     {"capturing", "since", "endpoints": {"service1": ["POST /orders", …]}}
  POST /api/capture     reads the services' /v3/api-docs pasted in NetCanvas again and starts a capture: from now on
                        the requests to those endpoints, and their responses, are drawn in NetCanvas. Empties the
                        list. The client's Send calls it. Answers the same as GET, plus "apiDocs": {"service1": "ok"
                        or why not}.
  POST /api/capture/stop  stops drawing.
"""

import html
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from capture import ExchangeFilter
from resend import resend


# Headless (the default): only what the client needs to start and stop captures, and how far the traffic gets.
# The page, the list, the filter and resend are only there with --page (scapy-minimal is the page to look at)
CONTROL_PATHS = {("GET", "/api/capture"), ("GET", "/api/status"), ("POST", "/api/capture"), ("POST", "/api/capture/stop")}


def startServer(capture, host, port, description, onCapture=lambda: None, page=False):
    """Serves the capture on http://host:port until Ctrl+C (host 127.0.0.1: only this machine, 0.0.0.0: every address). The description says what is being sniffed.
    onCapture runs when a capture starts, once the endpoints are read (main sniffs their ports)."""
    pageHtml = PAGE_HTML.replace("DESCRIPTION", html.escape(description))

    class ExchangeHandler(BaseHTTPRequestHandler):
        # do_GET, do_POST and log_message are the names http.server calls, so they keep its naming

        def do_GET(self):
            if not page and ("GET", self.path.split("?")[0]) not in CONTROL_PATHS:
                self.send_error(404)
            elif self.path == "/api/status":
                self.sendJson({"sniffing": description, **capture.getCapture()["stats"]})
            elif self.path.split("?")[0] == "/":
                self.sendResponse(pageHtml.encode(), "text/html; charset=utf-8")
            elif self.path == "/api/exchanges":
                self.sendJson(capture.getExchanges())
            elif self.path == "/api/exchangesByPorts":
                self.sendJson(capture.getExchangesByPorts())
            elif self.path == "/api/filter":
                self.sendJson(capture.getFilter())
            elif self.path == "/api/capture":
                self.sendJson(capture.getCapture())
            else:
                self.send_error(404)

        def do_POST(self):
            # A JSON content type makes browsers ask first (CORS preflight), so another site can't post here
            if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                self.send_error(415, "Send application/json")
            elif not page and ("POST", self.path) not in CONTROL_PATHS:
                self.send_error(404)
            elif self.path == "/api/filter":
                try:
                    body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                    capture.setFilter(ExchangeFilter.fromDict(json.loads(body or b"{}")))
                except (ValueError, AttributeError) as error:
                    self.send_error(400, f"Bad filter: {error}")
                    return
                self.sendJson(capture.getFilter())
            elif self.path == "/api/clear":
                capture.clear()
                self.sendJson({})
            elif self.path == "/api/resend":
                try:
                    data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                except ValueError as error:
                    self.send_error(400, f"Bad request: {error}")
                    return
                self.sendJson(resend(data))
            elif self.path == "/api/capture":
                try:
                    options = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                except ValueError:
                    options = {}
                apiDocs = capture.apiEndpoints.load()
                onCapture()
                capture.startCapture(time.time(), str(options.get("by") or "the page"))
                for name, result in apiDocs.items():
                    if result != "ok":
                        print(f"Capture: {name}: {result}")
                self.sendJson({**capture.getCapture(), "apiDocs": apiDocs})
            elif self.path == "/api/capture/stop":
                try:
                    options = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                    grace = min(10.0, max(0.0, float(options.get("graceSeconds", 0))))
                except (ValueError, TypeError):
                    grace = 0
                capture.stopCapture(grace)
                self.sendJson(capture.getCapture())
            else:
                self.send_error(404)

        def sendJson(self, value):
            self.sendResponse(json.dumps(value).encode(), "application/json")

        def sendResponse(self, body, contentType):
            self.send_response(200)
            self.send_header("Content-Type", contentType)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *arguments):   # the page polls every second: keep the terminal quiet
            pass

    ThreadingHTTPServer((host, port), ExchangeHandler).serve_forever()


# Captured traffic is shown with textContent, never innerHTML, so a request can't inject HTML into the page
PAGE_HTML = """<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>HTTP capture</title>
<style>
  :root {
    --background: #0f1115; --surface: #171a21; --surfaceRaised: #1e222b; --border: #2a2f3a;
    --text: #e6e8ee; --textMuted: #8b93a5; --accent: #7aa2f7;
    --get: #7aa2f7; --post: #9ece6a; --put: #e0af68; --patch: #bb9af7; --delete: #f7768e;
    --ok: #9ece6a; --redirect: #7dcfff; --clientError: #e0af68; --serverError: #f7768e;
    --mono: ui-monospace, "JetBrains Mono", "SF Mono", Menlo, Consolas, monospace;
    color-scheme: dark;
  }
  * { box-sizing: border-box; }
  body { margin: 0; background: var(--background); color: var(--text); font: 14px/1.45 system-ui, sans-serif; }
  header { position: sticky; top: 0; z-index: 1; background: rgba(15, 17, 21, 0.92); backdrop-filter: blur(8px);
           border-bottom: 1px solid var(--border); padding: 14px 20px 12px; }
  .titleRow { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
  h1 { font-size: 16px; font-weight: 600; margin: 0; }
  .description { color: var(--textMuted); font-size: 12px; font-family: var(--mono); }
  .count { margin-left: auto; color: var(--textMuted); font-size: 12px; }
  .live { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--ok); margin-right: 6px; }
  .live.paused { background: var(--textMuted); }

  form { display: flex; flex-wrap: wrap; gap: 10px; align-items: end; margin-top: 12px; }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 11px; color: var(--textMuted);
          text-transform: uppercase; letter-spacing: 0.04em; }
  input[type=text] { background: var(--surface); border: 1px solid var(--border); color: var(--text); border-radius: 6px;
                     padding: 6px 9px; font: 13px var(--mono); width: 170px; }
  input[type=text]:focus { outline: none; border-color: var(--accent); }
  .methods { display: flex; gap: 4px; }
  .methodToggle { background: var(--surface); border: 1px solid var(--border); color: var(--textMuted); border-radius: 6px;
                  padding: 5px 8px; font: 600 11px var(--mono); cursor: pointer; }
  .methodToggle.on { color: var(--background); border-color: transparent; }
  button { background: var(--surfaceRaised); border: 1px solid var(--border); color: var(--text); border-radius: 6px;
           padding: 6px 12px; font: 13px system-ui, sans-serif; cursor: pointer; }
  button:hover { border-color: var(--accent); }
  button.primary { background: var(--accent); border-color: var(--accent); color: var(--background); font-weight: 600; }
  .actions { display: flex; gap: 6px; margin-left: auto; }
  .status { font-size: 12px; color: var(--textMuted); min-height: 1em; }
  .captureBar { display: flex; align-items: center; gap: 10px; margin-top: 10px; font-size: 12px; color: var(--textMuted); }
  .captureBar button { padding: 4px 10px; font-size: 12px; }
  .captureState { font-weight: 600; color: var(--text); }
  .captureState.on { color: var(--ok); }
  .endpoints { font-family: var(--mono); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
  .exchange.captured { border-left: 3px solid var(--ok); }
  .status.error { color: var(--serverError); }

  main { padding: 14px 20px 40px; }
  .byPorts { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 16px; }
  .byPortsHeader { display: flex; align-items: center; gap: 10px; padding: 9px 14px; border-bottom: 1px solid var(--border); }
  .byPortsHeader h2 { font-size: 13px; font-weight: 600; margin: 0; }
  .byPortsHeader .hint { color: var(--textMuted); font-size: 12px; font-family: var(--mono); }
  .byPortsHeader .actions button { padding: 4px 10px; font-size: 12px; }
  .byPorts pre { border: none; border-radius: 0 0 8px 8px; max-height: 320px; overflow: auto; }
  .byPorts.collapsed pre { display: none; }
  .byPorts.collapsed .byPortsHeader { border-bottom: none; }
  .jsonKey { color: var(--accent); } .jsonString { color: var(--post); } .jsonNumber { color: var(--put); }
  .jsonLiteral { color: var(--patch); }
  .empty { color: var(--textMuted); text-align: center; padding: 60px 0; }
  .exchange { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 8px; overflow: hidden; }
  .summary { display: grid; grid-template-columns: 64px 1fr auto auto auto; gap: 14px; align-items: center;
             padding: 9px 14px; cursor: pointer; font-family: var(--mono); font-size: 13px; }
  .summary:hover { background: var(--surfaceRaised); }
  .method { font-weight: 700; font-size: 12px; }
  .uri { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .server, .time { color: var(--textMuted); font-size: 12px; }
  .statusCode { font-weight: 700; min-width: 72px; text-align: right; }
  .details { display: none; border-top: 1px solid var(--border); padding: 12px 14px; }
  .exchange.open .details { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
  .messageTitle { font-size: 11px; color: var(--textMuted); text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 6px; }
  pre { background: var(--background); border: 1px solid var(--border); border-radius: 6px; padding: 10px; margin: 0;
        font: 12.5px/1.5 var(--mono); white-space: pre-wrap; word-break: break-word; }
  .startLine { color: var(--accent); font-weight: 600; }
  /* Edit & resend: under a captured request, its copy to change and send again */
  .resendBar { grid-column: 1 / -1; display: flex; justify-content: flex-end; }
  .resendEditor { grid-column: 1 / -1; display: grid; gap: 8px; background: var(--surfaceRaised); border: 1px solid var(--border);
                  border-radius: 8px; padding: 12px; }
  .resendEditor .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .resendEditor input, .resendEditor textarea, .resendEditor select { background: var(--background); border: 1px solid var(--border);
                  color: var(--text); border-radius: 6px; padding: 6px 9px; font: 12.5px/1.5 var(--mono); }
  .resendEditor input:focus, .resendEditor textarea:focus { outline: none; border-color: var(--accent); }
  .resendEditor .uriInput { flex: 1; min-width: 200px; }
  .resendEditor .targetInput { width: 190px; }
  .resendEditor textarea { width: 100%; resize: vertical; }
  .resendEditor .hint { color: var(--textMuted); font-size: 11px; }
  .resendEditor .error { color: var(--serverError); }
  .headerName { color: var(--textMuted); }

  @media (max-width: 800px) {
    header, main { padding-left: 16px; padding-right: 16px; }
    .summary { grid-template-columns: 56px 1fr auto; }
    .server, .time { display: none; }
    .exchange.open .details { grid-template-columns: 1fr; }
    .actions { margin-left: 0; }
  }
</style>

<header>
  <div class="titleRow">
    <h1>HTTP capture</h1>
    <span class="description">DESCRIPTION</span>
    <span class="count"><span class="live" id="live"></span><span id="count">0 exchanges</span></span>
  </div>
  <form id="filterForm">
    <label>URI prefixes <input type="text" id="uris" placeholder="/orders, /menu"></label>
    <label>Methods <span class="methods" id="methods"></span></label>
    <label>Server ports <input type="text" id="destinationPorts" placeholder="8085, 8086"></label>
    <label>Client ports <input type="text" id="sourcePorts" placeholder="any"></label>
    <div class="actions">
      <button type="submit" class="primary">Apply</button>
      <button type="button" id="resetButton">Show all</button>
      <button type="button" id="pauseButton">Pause</button>
      <button type="button" id="clearButton">Clear</button>
    </div>
  </form>
  <div class="captureBar">
    <span class="captureState" id="captureState">Not capturing</span>
    <span class="description" id="stats"></span>
    <button type="button" id="captureButton">Capture</button>
    <button type="button" id="stopCaptureButton">Stop</button>
    <span class="endpoints" id="endpoints"></span>
  </div>
  <div class="status" id="status"></div>
</header>
<main>
  <section class="byPorts" id="byPorts">
    <div class="byPortsHeader">
      <h2>By ports</h2>
      <span class="hint" id="byPortsCount">clientPort:serverPort → [{method, uri, request, statusCode, response}]</span>
      <div class="actions">
        <button type="button" id="copyButton">Copy JSON</button>
        <button type="button" id="downloadButton">Download</button>
        <button type="button" id="collapseButton">Hide</button>
      </div>
    </div>
    <pre id="byPortsJson">{}</pre>
  </section>
  <div id="exchangeList"><div class="empty">Waiting for requests…</div></div>
</main>

<script>
const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];
const openIds = new Set();      // the exchanges you expanded stay expanded across refreshes
let selectedMethods = new Set();
let lastJson = "";
let lastByPortsJson = "";
let isPaused = false;

function createElement(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}

function methodColor(method) {
  return METHODS.includes(method) ? `var(--${method.toLowerCase()})` : "var(--text)";
}

function statusColor(statusCode) {
  if (!statusCode) return "var(--textMuted)";
  return ["", "var(--ok)", "var(--ok)", "var(--redirect)", "var(--clientError)", "var(--serverError)"][statusCode[0]]
         || "var(--text)";
}

function createMessage(title, message) {
  const box = createElement("div");
  box.append(createElement("div", "messageTitle", title));
  const pre = createElement("pre");
  pre.append(createElement("span", "startLine", message.startLine), "\\n");
  for (const [name, value] of message.headers) {
    pre.append(createElement("span", "headerName", name + ": "), value + "\\n");
  }
  if (message.body) pre.append("\\n" + message.body);
  box.append(pre);
  return box;
}

function createExchangeCard(exchange) {
  const card = createElement("div", "exchange" + (openIds.has(exchange.id) ? " open" : "")
                                     + (exchange.captured ? " captured" : ""));
  if (exchange.captured) card.title = "Captured: drawn in NetCanvas";
  const summary = createElement("div", "summary");
  const method = createElement("span", "method", exchange.method);
  method.style.color = methodColor(exchange.method);
  const statusText = exchange.statusCode
      ? `${exchange.statusCode} · ${exchange.durationMs} ms` : "waiting…";
  const status = createElement("span", "statusCode", statusText);
  status.style.color = statusColor(exchange.statusCode);
  summary.append(method, createElement("span", "uri", exchange.uri), createElement("span", "server", exchange.server),
                 createElement("span", "time", exchange.time), status);
  summary.onclick = () => {
    card.classList.toggle("open");
    card.classList.contains("open") ? openIds.add(exchange.id) : openIds.delete(exchange.id);
  };
  const details = createElement("div", "details");
  details.append(createMessage("Request", exchange.request));
  details.append(exchange.response ? createMessage("Response", exchange.response)
                                   : createElement("div", "empty", "No response yet"));
  addResend(details, exchange);
  card.append(summary, details);
  return card;
}

// ---- Edit & resend: a captured request, changed, sent again (from this machine, over a new connection) ----
const NL = String.fromCharCode(10);
const editors = new Map();      // exchange id -> its open editor, kept across refreshes so typing isn't lost

function createResendEditor(exchange) {
  const editor = createElement("div", "resendEditor");
  const request = exchange.request;
  const [method, uri] = request.startLine.split(" ");
  const methodInput = createElement("select");
  for (const name of [...new Set([method, ...METHODS, "HEAD", "OPTIONS"])]) methodInput.append(new Option(name, name, false, name === method));
  const uriInput = Object.assign(createElement("input", "uriInput"), {value: uri, spellcheck: false, title: "URI"});
  // The server as captured; a container's IP works from this machine too, or type localhost:8085
  const targetInput = Object.assign(createElement("input", "targetInput"), {value: exchange.server, spellcheck: false, title: "Send to host:port"});
  const headersInput = Object.assign(createElement("textarea"), {rows: Math.min(10, request.headers.length + 1), spellcheck: false,
      value: request.headers.filter(([name]) => !["content-length", "transfer-encoding", "connection"].includes(name.toLowerCase()))
                            .map(([name, value]) => name + ": " + value).join(NL)});
  const bodyInput = Object.assign(createElement("textarea"), {rows: Math.min(16, (request.body || "").split(NL).length + 2), spellcheck: false, value: request.body || ""});
  const sendButton = Object.assign(createElement("button", "primary", "Resend"), {type: "button"});
  const closeButton = Object.assign(createElement("button", "", "Close"), {type: "button"});
  const result = createElement("div");
  editor.append(
      Object.assign(createElement("div", "row"), {}), createElement("div", "hint",
          "Change anything, then Resend. Content-Length is worked out again; the request shows up in the list like any other."),
      createElement("div", "messageTitle", "Headers"), headersInput,
      createElement("div", "messageTitle", "Body"), bodyInput, result);
  editor.firstChild.append(methodInput, uriInput, createElement("span", "hint", "to"), targetInput, sendButton, closeButton);

  closeButton.onclick = () => { editors.delete(exchange.id); editor.remove(); };
  sendButton.onclick = async () => {
    sendButton.disabled = true;
    result.replaceChildren(createElement("div", "hint", "Sending…"));
    const headers = headersInput.value.split(NL).filter(line => line.trim()).map(line => {
      const colon = line.indexOf(":");
      return colon > 0 ? [line.slice(0, colon).trim(), line.slice(colon + 1).trim()] : [line.trim(), ""];
    });
    try {
      const reply = await post("/api/resend", {target: targetInput.value, method: methodInput.value, uri: uriInput.value,
                                               headers, body: bodyInput.value});
      if (!reply.ok) {
        result.replaceChildren(createElement("div", "error", reply.error));
      } else {
        const title = createElement("div", "messageTitle", `Response · ${reply.durationMs} ms`);
        const shown = createMessage("", {startLine: reply.statusLine, headers: reply.headers, body: reply.body});
        shown.querySelector(".startLine").style.color = statusColor(String(reply.statusCode || ""));
        shown.firstChild.remove();
        result.replaceChildren(title, shown);
      }
    } catch (error) {
      result.replaceChildren(createElement("div", "error", error.message));
    } finally {
      sendButton.disabled = false;
    }
  };
  return editor;
}

function addResend(details, exchange) {
  if (editors.has(exchange.id)) {
    details.append(editors.get(exchange.id));
    return;
  }
  const bar = createElement("div", "resendBar");
  const open = Object.assign(createElement("button", "", "Edit & resend"), {type: "button", title: "Change this request and send it again"});
  open.onclick = () => {
    const editor = createResendEditor(exchange);
    editors.set(exchange.id, editor);
    bar.replaceWith(editor);
    editor.querySelector(".uriInput").focus();
  };
  bar.append(open);
  details.append(bar);
}


// Pretty JSON as coloured spans, built with textContent like everything else on the page
function appendJson(parent, value, indent) {
  const padding = "  ".repeat(indent);
  if (Array.isArray(value) || (value !== null && typeof value === "object")) {
    const isArray = Array.isArray(value);
    const entries = isArray ? value.map(item => [null, item]) : Object.entries(value);
    if (entries.length === 0) return parent.append(isArray ? "[]" : "{}");
    parent.append(isArray ? "[\\n" : "{\\n");
    entries.forEach(([key, item], index) => {
      parent.append(padding + "  ");
      if (key !== null) parent.append(createElement("span", "jsonKey", JSON.stringify(key)), ": ");
      appendJson(parent, item, indent + 1);
      parent.append(index < entries.length - 1 ? ",\\n" : "\\n");
    });
    return parent.append(padding + (isArray ? "]" : "}"));
  }
  const className = typeof value === "string" ? "jsonString" : typeof value === "number" ? "jsonNumber" : "jsonLiteral";
  parent.append(createElement("span", className, JSON.stringify(value)));
}

async function refreshByPorts() {
  const json = await (await fetch("/api/exchangesByPorts")).text();
  if (json === lastByPortsJson) return;
  lastByPortsJson = json;
  const exchangesByPorts = JSON.parse(json);
  const keyCount = Object.keys(exchangesByPorts).length;
  const exchangeCount = Object.values(exchangesByPorts).reduce((total, list) => total + list.length, 0);
  document.getElementById("byPortsCount").textContent =
      `${keyCount} port pair${keyCount === 1 ? "" : "s"}, ${exchangeCount} request${exchangeCount === 1 ? "" : "s"}`;
  const pre = document.getElementById("byPortsJson");
  pre.replaceChildren();
  appendJson(pre, exchangesByPorts, 0);
}

// The client's Send starts a capture too, so this is read on every refresh
function showCapture(state) {
  const captureState = document.getElementById("captureState");
  captureState.textContent = state.capturing ? `Capturing since ${state.since} (${state.by})`
      : state.last ? `Not capturing · last: ${state.last.by}, ${state.last.from}–${state.last.to}` : "Not capturing";
  captureState.classList.toggle("on", state.capturing);
  const endpoints = Object.entries(state.endpoints)
      .map(([service, list]) => `${service}: ${list.length ? list.join(", ") : "none"}`);
  const endpointsText = endpoints.length ? endpoints.join(" · ")
      : "no endpoints pasted in NetCanvas: a capture draws every request (but /actuator, /v3/api-docs)";
  document.getElementById("endpoints").textContent = endpointsText;
  const s = state.stats || {};
  document.getElementById("stats").textContent = `seen ${s.packets ?? 0} packets (${s.withData ?? 0} with data) · `
      + `HTTP ${s.httpRequests ?? 0} requests, ${s.httpCaptured ?? 0} captured · stores ${s.storeCommands ?? 0} commands, ${s.storeCaptured ?? 0} captured`;
  document.getElementById("endpoints").title = endpoints.join("\\n");
}

async function refresh() {
  if (isPaused) return;
  let json;
  try {
    json = await (await fetch("/api/exchanges")).text();
    await refreshByPorts();
    showCapture(await (await fetch("/api/capture")).json());
  } catch {
    return showStatus("Can't reach the capture: is main.py still running?", true);
  }
  if (json === lastJson) return;          // nothing new: don't redraw (keeps your text selection)
  lastJson = json;
  const exchanges = JSON.parse(json);
  document.getElementById("count").textContent = `${exchanges.length} exchange${exchanges.length === 1 ? "" : "s"}`;
  const list = document.getElementById("exchangeList");
  if (exchanges.length === 0) {
    list.replaceChildren(createElement("div", "empty", "No requests match the filter yet"));
  } else {
    list.replaceChildren(...exchanges.map(createExchangeCard));
  }
}

function showStatus(text, isError) {
  const status = document.getElementById("status");
  status.textContent = text;
  status.className = "status" + (isError ? " error" : "");
}

function drawMethodToggles() {
  const container = document.getElementById("methods");
  container.replaceChildren(...METHODS.map(method => {
    const toggle = createElement("button", "methodToggle" + (selectedMethods.has(method) ? " on" : ""), method);
    toggle.type = "button";
    if (selectedMethods.has(method)) toggle.style.background = methodColor(method);
    toggle.onclick = () => {
      selectedMethods.has(method) ? selectedMethods.delete(method) : selectedMethods.add(method);
      drawMethodToggles();
    };
    return toggle;
  }));
}

function showFilter(exchangeFilter) {
  document.getElementById("uris").value = exchangeFilter.uris.join(", ");
  document.getElementById("destinationPorts").value = exchangeFilter.destinationPorts.join(", ");
  document.getElementById("sourcePorts").value = exchangeFilter.sourcePorts.join(", ");
  selectedMethods = new Set(exchangeFilter.methods.map(method => method.toUpperCase()));
  drawMethodToggles();
}

async function post(path, body) {
  const response = await fetch(path, {method: "POST", headers: {"Content-Type": "application/json"},
                                      body: JSON.stringify(body)});
  if (!response.ok) throw new Error((await response.text()).match(/<p>Message: (.*?)\\./)?.[1] || response.statusText);
  return response.json();
}

async function applyFilter(exchangeFilter) {
  try {
    showFilter(await post("/api/filter", exchangeFilter));
    showStatus("Filter applied");
    lastJson = "";
    lastByPortsJson = "";
    refresh();
  } catch (error) {
    showStatus(error.message, true);
  }
}

document.getElementById("filterForm").onsubmit = event => {
  event.preventDefault();
  applyFilter({uris: document.getElementById("uris").value, methods: [...selectedMethods],
               destinationPorts: document.getElementById("destinationPorts").value,
               sourcePorts: document.getElementById("sourcePorts").value});
};
document.getElementById("resetButton").onclick = () =>
    applyFilter({uris: [], methods: [], destinationPorts: [], sourcePorts: []});
document.getElementById("clearButton").onclick = async () => {
  await post("/api/clear", {});
  openIds.clear();
  lastJson = "";
  lastByPortsJson = "";
  refresh();
};
document.getElementById("captureButton").onclick = async () => {
  const state = await post("/api/capture", {});
  const failed = Object.entries(state.apiDocs).filter(([, result]) => result !== "ok");
  showStatus(failed.length ? failed.map(([service, result]) => `${service}: ${result}`).join(" · ")
                           : "Capture started", failed.length > 0);
  showCapture(state);
  openIds.clear();
  lastJson = "";
  lastByPortsJson = "";
  refresh();
};
document.getElementById("stopCaptureButton").onclick = async () => showCapture(await post("/api/capture/stop", {}));
document.getElementById("pauseButton").onclick = event => {
  isPaused = !isPaused;
  event.target.textContent = isPaused ? "Resume" : "Pause";
  document.getElementById("live").classList.toggle("paused", isPaused);
  if (!isPaused) refresh();
};

document.getElementById("copyButton").onclick = async () => {
  try {
    await navigator.clipboard.writeText(JSON.stringify(JSON.parse(lastByPortsJson || "{}"), null, 2));
    showStatus("Copied");
  } catch {
    showStatus("The browser didn't allow copying", true);
  }
};
document.getElementById("downloadButton").onclick = () => {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(new Blob([JSON.stringify(JSON.parse(lastByPortsJson || "{}"), null, 2)],
                                           {type: "application/json"}));
  link.download = "exchangesByPorts.json";
  link.click();
  URL.revokeObjectURL(link.href);
};
document.getElementById("collapseButton").onclick = event => {
  const isCollapsed = document.getElementById("byPorts").classList.toggle("collapsed");
  event.target.textContent = isCollapsed ? "Show" : "Hide";
};

fetch("/api/filter").then(response => response.json()).then(showFilter);
setInterval(refresh, 1000);
refresh();
</script>
"""
