"use strict";

/** ProcessBuilder argv: filesDir/app/server/questEntry.js */
const fs = require("fs");
const http = require("http");
const net = require("net");
const path = require("path");
const { createServer } = require("./httpApi");
const { resolveAccessSettings, getLastCwd, setLastCwd } = require("./remoteAccess");

const home = process.env.HOME || "/data/data/dev.grokdesktop.quest/files/home";
const desktop = path.join(home, ".grok-desktop");
fs.mkdirSync(desktop, { recursive: true });

/**
 * Musl grok cannot read Android DNS (no /etc/resolv.conf). Node uses bionic
 * getaddrinfo, so a loopback CONNECT proxy lets grok reach auth.x.ai / api.x.ai.
 */
function startGrokDnsProxy() {
  const server = http.createServer((_req, res) => {
    res.writeHead(501);
    res.end();
  });
  server.on("connect", (req, clientSocket, head) => {
    const raw = String(req.url || "");
    const host = raw.split(":")[0];
    const port = Number(raw.split(":")[1]) || 443;
    if (!host || host === "127.0.0.1" || host === "localhost") {
      clientSocket.end();
      return;
    }
    const dest = net.connect({ host, port }, () => {
      clientSocket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      if (head && head.length) dest.write(head);
      dest.pipe(clientSocket);
      clientSocket.pipe(dest);
    });
    dest.on("error", () => clientSocket.destroy());
    clientSocket.on("error", () => dest.destroy());
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

function writeRuntime(port) {
  const payload = {
    port,
    pid: process.pid,
    startedAt: new Date().toISOString(),
    grokBin: process.env.GROK_BIN || null,
  };
  fs.writeFileSync(path.join(desktop, "runtime.json"), JSON.stringify(payload, null, 2));
}

async function main() {
  const proxy = await startGrokDnsProxy();
  const proxyPort = proxy.address().port;
  const proxyUrl = "http://127.0.0.1:" + proxyPort;
  process.env.HTTP_PROXY = proxyUrl;
  process.env.HTTPS_PROXY = proxyUrl;
  process.env.ALL_PROXY = proxyUrl;
  process.env.http_proxy = proxyUrl;
  process.env.https_proxy = proxyUrl;
  process.env.all_proxy = proxyUrl;
  process.env.NO_PROXY = "127.0.0.1,localhost,::1";
  process.env.no_proxy = "127.0.0.1,localhost,::1";
  console.log("[questEntry] grok DNS proxy " + proxyUrl);

  const access = resolveAccessSettings();
  const staticDir = path.join(__dirname, "..", "renderer");
  const workspace =
    process.env.GROK_QUEST_WORKSPACE || path.join(home, "workspace");
  if (!getLastCwd()) {
    setLastCwd(workspace);
  }

  const preferred = Number(process.env.GROK_DESKTOP_PORT) || access.port || 3847;
  const portsToTry = [preferred, preferred + 1, preferred + 2, 0];
  let lastErr = null;
  let api = null;

  for (const port of portsToTry) {
    try {
      api = await createServer({
        port,
        staticDir,
        token: access.token,
        allowLan: false,
        host: "127.0.0.1",
      });
      break;
    } catch (err) {
      lastErr = err;
      if (err && err.code === "EADDRINUSE") {
        continue;
      }
      throw err;
    }
  }
  if (!api) {
    throw lastErr || new Error("createServer failed");
  }

  writeRuntime(api.port);
  console.log("[questEntry] listening " + api.url);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
