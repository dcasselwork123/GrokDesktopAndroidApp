"use strict";

/**
 * Lock the Quest device-auth stdio parser against overlay/fixtures/device-auth.stdout.txt.
 * Keep in sync with the inlined copy in overlay/patches/grokService.js.diff.
 */

function isShortUserCode(s) {
  const t = String(s || "");
  if (!t || t.length > 17) return false;
  if (/[._]/.test(t)) return false;
  return /^[A-Z0-9]{4,8}(?:-[A-Z0-9]{4,8})?$/i.test(t);
}

function parseDeviceAuth(text) {
  const raw = String(text || "");
  let url = null;
  const urlMatch = raw.match(/https:\/\/[^\s"'<>\\]+/i);
  if (urlMatch) {
    url = urlMatch[0].replace(/[.,;:!?)\]>]+$/g, "");
  }

  let userCode = null;
  const labeled = raw.match(
    /(?:user[\s_-]*code|enter(?:\s+the)?\s+code|code)\s*[:=]?\s*([A-Z0-9]{4,8}(?:-[A-Z0-9]{4,8})?)/i
  );
  if (labeled && isShortUserCode(labeled[1])) {
    userCode = labeled[1].toUpperCase();
  }
  if (!userCode) {
    const hyphen = raw.match(/\b([A-Z0-9]{4,8}-[A-Z0-9]{4,8})\b/i);
    if (hyphen && isShortUserCode(hyphen[1])) {
      userCode = hyphen[1].toUpperCase();
    }
  }
  if (!url && !userCode) return null;
  return { url: url || null, userCode: userCode || null };
}

function redactLoginChunk(chunk) {
  let text = String(chunk || "");
  text = text.replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9._-]+\b/g, "[redacted-jwt]");
  text = text.replace(
    /\b(access_token|refresh_token|device_code|id_token)\s*[:=]\s*\S+/gi,
    (_, name) => `${name}=[redacted]`
  );
  text = text.replace(/\b[A-Za-z0-9_-]{40,}\b/g, "[redacted]");
  return text;
}

module.exports = { parseDeviceAuth, redactLoginChunk, isShortUserCode };

if (require.main === module) {
  const fs = require("fs");
  const path = require("path");
  const assert = require("assert");
  const fixture = fs.readFileSync(path.join(__dirname, "device-auth.stdout.txt"), "utf8");
  const got = parseDeviceAuth(fixture);
  assert.deepStrictEqual(got, {
    url: "https://grok.com/device",
    userCode: "ABCD-EFGH",
  });
  const redacted = redactLoginChunk(fixture);
  assert.ok(!/eyJhbGciOi/.test(redacted), "jwt must be redacted");
  assert.ok(!/aaaaaaaaaaaaaaaa/.test(redacted), "long blobs must be redacted");
  assert.ok(redacted.includes("ABCD-EFGH"), "user code must remain");
  assert.ok(redacted.includes("https://grok.com/device"), "device url must remain");

  const jwtOnly = parseDeviceAuth(
    "token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbb\n"
  );
  assert.ok(!jwtOnly || !jwtOnly.userCode, "must not treat JWT as userCode");

  const applied = path.join(
    __dirname,
    "..",
    "..",
    "app",
    "src",
    "main",
    "assets",
    "grok-desktop",
    "server",
    "grokService.js"
  );
  if (fs.existsSync(applied)) {
    const src = fs.readFileSync(applied, "utf8");
    assert.ok(src.includes('["login", "--device-auth"]'), "overlay must spawn --device-auth");
    assert.ok(src.includes("function parseDeviceAuth"), "overlay must include parseDeviceAuth");
    assert.ok(src.includes("debugTail"), "overlay must expose debugTail");
    assert.ok(src.includes("--no-auto-update"), "PR 3 --no-auto-update hunk must remain");
    const start = src.indexOf("function isShortUserCode(s)");
    const end = src.indexOf("function redactLoginChunk", start);
    assert.ok(start >= 0 && end > start, "could not slice parseDeviceAuth from overlay");
    const extracted = src.slice(start, end) + "module.exports = { parseDeviceAuth, isShortUserCode };\n";
    const tmp = path.join(require("os").tmpdir(), "parse-device-auth.extracted.js");
    fs.writeFileSync(tmp, extracted);
    try {
      delete require.cache[require.resolve(tmp)];
      const overlayParse = require(tmp).parseDeviceAuth;
      assert.deepStrictEqual(overlayParse(fixture), got, "overlay parser must match fixture lock");
    } finally {
      try {
        fs.unlinkSync(tmp);
      } catch {
        /* ignore */
      }
    }
  }

  console.log("parse-device-auth: ok", got);
}
