"use strict";

/** ProcessBuilder argv: filesDir/app/server/questEntry.js */
const fs = require("fs");
const path = require("path");
const { createServer } = require("./httpApi");
const { resolveAccessSettings, getLastCwd, setLastCwd } = require("./remoteAccess");

const home = process.env.HOME || "/data/data/dev.grokdesktop.quest/files/home";
const desktop = path.join(home, ".grok-desktop");
fs.mkdirSync(desktop, { recursive: true });

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
