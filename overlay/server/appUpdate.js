"use strict";

const https = require("https");

const FETCH_INTERVAL_MS = 30 * 60 * 1000;

let cache = {
  fetchedAt: 0,
  snapshot: null,
  fetchPromise: null,
};

function updateRepo() {
  return String(process.env.GROK_UPDATE_REPO || "").trim();
}

function appVersion() {
  return String(process.env.GROK_APP_VERSION || "").trim();
}

function normalizeTag(tag) {
  return String(tag || "")
    .trim()
    .replace(/^v/i, "");
}

function disabledSnapshot() {
  return {
    available: false,
    supported: false,
    disabled: true,
    applying: false,
    current: { subject: appVersion() || "sideload" },
    latest: null,
    commits: [],
    summary: "",
    behind: 0,
    ahead: 0,
    apkUrl: null,
    htmlUrl: null,
    latestTag: null,
    checkedAt: Date.now(),
    nextCheckAt: Date.now() + FETCH_INTERVAL_MS,
    error: null,
    code: "UPDATE_DISABLED",
  };
}

function githubJson(pathname) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      {
        hostname: "api.github.com",
        path: pathname,
        method: "GET",
        headers: {
          Accept: "application/vnd.github+json",
          "User-Agent": "GrokDesktopQuest/" + (appVersion() || "sideload"),
          "X-GitHub-Api-Version": "2022-11-28",
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const raw = Buffer.concat(chunks).toString("utf8");
          if (res.statusCode < 200 || res.statusCode >= 300) {
            const err = new Error("GitHub Releases HTTP " + res.statusCode);
            err.status = res.statusCode;
            reject(err);
            return;
          }
          try {
            resolve(JSON.parse(raw));
          } catch (err) {
            reject(err);
          }
        });
      }
    );
    req.setTimeout(12000, () => {
      req.destroy(new Error("GitHub Releases timed out"));
    });
    req.on("error", reject);
    req.end();
  });
}

function apkAssetUrl(release) {
  const assets = release && Array.isArray(release.assets) ? release.assets : [];
  for (const a of assets) {
    const name = String((a && a.name) || "").toLowerCase();
    const url = a && a.browser_download_url;
    if (name.endsWith(".apk") && url) return String(url);
  }
  return null;
}

async function inspectReleases() {
  const repo = updateRepo();
  if (!repo) return disabledSnapshot();
  const current = appVersion();
  const release = await githubJson("/repos/" + repo + "/releases/latest");
  const latestTag = String((release && release.tag_name) || "").trim();
  const available =
    !!latestTag &&
    !!current &&
    normalizeTag(latestTag) !== normalizeTag(current);
  const apkUrl = apkAssetUrl(release);
  const htmlUrl = (release && release.html_url) || "https://github.com/" + repo + "/releases";
  const subject = (release && (release.name || release.tag_name)) || latestTag;
  return {
    available,
    supported: true,
    disabled: false,
    applying: false,
    current: { subject: current || "sideload" },
    latest: { subject: subject || latestTag },
    commits: [],
    summary: subject || latestTag || "",
    behind: available ? 1 : 0,
    ahead: 0,
    apkUrl,
    htmlUrl,
    latestTag: latestTag || null,
    checkedAt: Date.now(),
    nextCheckAt: Date.now() + FETCH_INTERVAL_MS,
    error: null,
    code: available ? "SIDELOAD_AVAILABLE" : null,
  };
}

async function getUpdateStatus({ force = false } = {}) {
  if (!updateRepo()) return disabledSnapshot();
  const now = Date.now();
  if (!force && cache.snapshot && now - cache.fetchedAt < FETCH_INTERVAL_MS) {
    return { ...cache.snapshot, cached: true };
  }
  if (cache.fetchPromise) return cache.fetchPromise;
  cache.fetchPromise = inspectReleases()
    .then((snap) => {
      cache.snapshot = snap;
      cache.fetchedAt = Date.now();
      return snap;
    })
    .catch((err) => {
      const fail = {
        ...disabledSnapshot(),
        disabled: false,
        supported: true,
        error: (err && err.message) || "Could not check GitHub Releases",
        code: "UPDATE_CHECK_FAILED",
      };
      cache.snapshot = fail;
      cache.fetchedAt = Date.now();
      return fail;
    })
    .finally(() => {
      cache.fetchPromise = null;
    });
  return cache.fetchPromise;
}

async function applyAppUpdate() {
  const status = await getUpdateStatus({ force: true });
  const apkUrl = status.apkUrl || null;
  const htmlUrl = status.htmlUrl || null;
  const err = new Error("Sideload the APK from GitHub Releases. This app cannot git pull.");
  err.code = "SIDELOAD_REQUIRED";
  err.status = 200;
  return {
    ok: false,
    pulled: false,
    installed: false,
    restarting: false,
    alreadyCurrent: !status.available,
    code: "SIDELOAD_REQUIRED",
    apkUrl,
    htmlUrl,
    available: !!status.available,
    latestTag: status.latestTag || null,
    error: err.message,
  };
}

function resetUpdateCache() {
  cache = { fetchedAt: 0, snapshot: null, fetchPromise: null };
}

function parseCommitLines() {
  return [];
}

function formatUpdateSummary() {
  return "";
}

function repoRoot() {
  return process.env.HOME || ".";
}

module.exports = {
  FETCH_INTERVAL_MS,
  parseCommitLines,
  formatUpdateSummary,
  getUpdateStatus,
  applyAppUpdate,
  resetUpdateCache,
  repoRoot,
};
