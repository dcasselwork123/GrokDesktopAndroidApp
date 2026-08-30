"use strict";

/** ProcessBuilder argv: filesDir/app/server/questEntry.js */
const fs = require("fs");
const http = require("http");
const https = require("https");
const dns = require("dns");
const path = require("path");
const { spawn } = require("child_process");

const home = process.env.HOME || "/data/data/dev.grokdesktop.quest/files/home";
const desktop = path.join(home, ".grok-desktop");
fs.mkdirSync(desktop, { recursive: true });

const results = {
  kind: "pr1-spike",
  nodeVersion: process.version,
  execPath: process.execPath,
  pid: process.pid,
  ppid: process.ppid,
  platform: process.platform,
  arch: process.arch,
  grokBin: process.env.GROK_BIN || null,
  shell: process.env.SHELL || null,
  home,
  cwd: process.cwd(),
  argv: process.argv,
  startedAt: new Date().toISOString(),
  checks: {},
};

function writeResults() {
  fs.writeFileSync(
    path.join(desktop, "spike-results.json"),
    JSON.stringify(results, null, 2),
  );
}

function writeRuntime(port) {
  const payload = {
    port,
    pid: process.pid,
    startedAt: results.startedAt,
    grokBin: process.env.GROK_BIN || null,
  };
  fs.writeFileSync(path.join(desktop, "runtime.json"), JSON.stringify(payload, null, 2));
}

function spawnCapture(bin, args, timeoutMs) {
  return new Promise((resolve) => {
    const out = { bin, args, exitCode: null, signal: null, stdout: "", stderr: "", error: null };
    if (!bin || !fs.existsSync(bin)) {
      out.error = "missing binary";
      resolve(out);
      return;
    }
    let settled = false;
    const child = spawn(bin, args, {
      stdio: ["ignore", "pipe", "pipe"],
      detached: false,
    });
    const timer = setTimeout(() => {
      try {
        child.kill("SIGKILL");
      } catch (_) {
        /* ignore */
      }
      if (!settled) {
        settled = true;
        out.error = "timeout";
        resolve(out);
      }
    }, timeoutMs);
    child.stdout.on("data", (d) => {
      out.stdout += d.toString("utf8");
    });
    child.stderr.on("data", (d) => {
      out.stderr += d.toString("utf8");
    });
    child.on("error", (err) => {
      out.error = err.message;
    });
    child.on("close", (code, signal) => {
      clearTimeout(timer);
      out.exitCode = code;
      out.signal = signal;
      if (!settled) {
        settled = true;
        resolve(out);
      }
    });
  });
}

function lookup(host) {
  return new Promise((resolve) => {
    dns.lookup(host, { all: true }, (err, addrs) => {
      if (err) {
        resolve({ ok: false, error: err.message, code: err.code });
      } else {
        resolve({ ok: true, addresses: addrs });
      }
    });
  });
}

function tlsProbe(url) {
  return new Promise((resolve) => {
    const req = https.get(url, { timeout: 15000 }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        resolve({
          ok: true,
          statusCode: res.statusCode,
          headers: { server: res.headers.server || null },
          bodyBytes: Buffer.concat(chunks).length,
        });
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error("timeout"));
    });
    req.on("error", (err) => {
      resolve({ ok: false, error: err.message, code: err.code });
    });
  });
}

function listenOn(server, port) {
  return new Promise((resolve, reject) => {
    const onError = (err) => {
      server.off("listening", onListen);
      reject(err);
    };
    const onListen = () => {
      server.off("error", onError);
      resolve(server.address());
    };
    server.once("error", onError);
    server.once("listening", onListen);
    server.listen(port, "127.0.0.1");
  });
}

async function bindLoopback(server) {
  const ports = [Number(process.env.GROK_DESKTOP_PORT) || 3847, 3848, 3849, 0];
  let lastErr;
  for (const port of ports) {
    try {
      const addr = await listenOn(server, port);
      return addr;
    } catch (err) {
      lastErr = err;
    }
  }
  throw lastErr;
}

function readMaybe(p) {
  try {
    const st = fs.lstatSync(p);
    return {
      path: p,
      exists: true,
      isSymbolicLink: st.isSymbolicLink(),
      mode: st.mode,
      target: st.isSymbolicLink() ? fs.readlinkSync(p) : null,
    };
  } catch (err) {
    return { path: p, exists: false, error: err.message };
  }
}

async function runChecks() {
  results.checks.nodeVersion = { ok: true, version: process.version };

  results.checks.shell = {
    SHELL: process.env.SHELL || null,
    binSh: readMaybe("/bin/sh"),
    systemBinSh: readMaybe("/system/bin/sh"),
  };

  try {
    const resolv = fs.readFileSync("/etc/resolv.conf", "utf8");
    results.checks.resolvConf = {
      ok: true,
      text: resolv,
      hasNameserver: /nameserver\s+\S+/i.test(resolv),
    };
  } catch (err) {
    results.checks.resolvConf = { ok: false, error: err.message };
  }

  results.checks.nodeDns = await lookup("api.x.ai");
  results.checks.nodeTls = await tlsProbe("https://api.x.ai/");

  const grokBin = process.env.GROK_BIN;
  results.checks.grokVersion = await spawnCapture(grokBin, ["--version"], 15000);
  results.checks.grokHelp = await spawnCapture(grokBin, ["--help"], 15000);
  const resolvOk = !!(results.checks.resolvConf && results.checks.resolvConf.hasNameserver);
  results.checks.grokDns = {
    probed: false,
    note: "grok --version/--help do not call getaddrinfo; Node dns.lookup is not musl getaddrinfo",
    resolvHasNameserver: resolvOk,
    muslReadsEtcResolvConf: true,
  };
  results.checks.grokTls = {
    probed: false,
    note: "Node https.get uses the Android CA store; musl grok bundles rustls — not the same row",
  };

  writeResults();
}

const server = http.createServer((req, res) => {
  const url = req.url || "/";
  if (url === "/" || url.startsWith("/api/health") || url.startsWith("/spike")) {
    res.statusCode = 200;
    res.setHeader("content-type", "application/json");
    res.end(JSON.stringify({ ok: true, spike: true, port: results.port || null, ...results }));
    return;
  }
  res.statusCode = 404;
  res.end("not found");
});

(async () => {
  writeResults();
  const addr = await bindLoopback(server);
  const port = typeof addr === "object" && addr ? addr.port : addr;
  results.port = port;
  results.checks.httpBind = { ok: true, host: "127.0.0.1", port };
  writeRuntime(port);
  writeResults();
  console.log("SPIKE listening 127.0.0.1:" + port);
  try {
    await runChecks();
    console.log("SPIKE checks done");
  } catch (err) {
    results.checks.runError = { ok: false, error: String(err && err.stack ? err.stack : err) };
    writeResults();
    console.error("SPIKE checks failed", err);
  }
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
