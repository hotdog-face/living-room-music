#!/usr/bin/env node
// One-click YouTube Music sign-in for the music server.
//
// Opens a throwaway Chrome window (its own empty profile, like Incognito) at YouTube Music's sign-in page. Mitchell signs
// in there himself. As soon as YouTube Music shows him signed in, this reads that window's youtube.com cookies and
// account number through Chrome's DevTools protocol, hands them straight to /srv/music/signin.py over ssh, closes the
// window and deletes the profile. Nothing uses that sign-in afterwards, so Google doesn't rotate it: it lasts months.
// Never prints, logs or saves the values anywhere on this Mac.
//
//     MUSIC_HOST=<ssh-host> node signin/ytm-signin.mjs        (macOS, Google Chrome, Node 22+)

import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PORT = 9333;
const START = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=" +
  encodeURIComponent("https://music.youtube.com/");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const say = (s) => console.log(s);

const profile = mkdtempSync(join(tmpdir(), "ytm-signin-"));
const chrome = spawn(CHROME, [
  `--user-data-dir=${profile}`, `--remote-debugging-port=${PORT}`, "--no-first-run", "--no-default-browser-check",
  "--disable-sync", "--new-window", "--window-size=1100,850", START,
], { stdio: "ignore" });

let finished = false;
function cleanup() {
  try { chrome.kill(); } catch {}
  spawnSync("sleep", ["1.5"]);                                   // let Chrome let go of its files
  try { rmSync(profile, { recursive: true, force: true }); } catch {}
}
process.on("SIGINT", () => { say("\nStopped. Nothing was changed."); cleanup(); process.exit(1); });
chrome.on("exit", () => { if (!finished) { say("The sign-in window was closed before signing in. Nothing was changed."); cleanup(); process.exit(1); } });

// ---- a tiny DevTools-protocol client --------------------------------------------------------------------------------
async function targets() {
  for (let i = 0; i < 60; i++) {
    try { return await (await fetch(`http://127.0.0.1:${PORT}/json`)).json(); } catch { await sleep(500); }
  }
  throw new Error("Chrome didn't start");
}

function connect(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let id = 0;
  const waiting = new Map();
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  };
  const ready = new Promise((ok, bad) => { ws.onopen = ok; ws.onerror = bad; });
  const send = (method, params = {}) => new Promise((ok) => { const n = ++id; waiting.set(n, ok); ws.send(JSON.stringify({ id: n, method, params })); });
  return { ready, send, close: () => ws.close() };
}

// ---- wait until YouTube Music says he's signed in -------------------------------------------------------------------
say("A Chrome window opened. Sign in to YouTube Music there (pick the account you use for music).");
say("Leave this window open; it finishes by itself once you're signed in.\n");

let page, info;
for (;;) {
  await sleep(2000);
  const list = await targets().catch(() => []);
  const t = list.find((x) => x.type === "page" && x.url.startsWith("https://music.youtube.com"));
  if (!t) continue;
  if (!page || page.url !== t.webSocketDebuggerUrl) {
    page?.close();
    page = connect(t.webSocketDebuggerUrl);
    page.url = t.webSocketDebuggerUrl;
    try { await page.ready; } catch { page = null; continue; }
  }
  const r = await page.send("Runtime.evaluate", {
    returnByValue: true,
    expression: `(() => { try { const g = (k) => window.ytcfg && ytcfg.get(k);
      return { in: !!g("LOGGED_IN"), authuser: String(g("SESSION_INDEX") ?? "0"), brand: g("DELEGATED_SESSION_ID") || "",
               visitor: g("VISITOR_DATA") || "" }; } catch (e) { return null; } })()`,
  });
  const v = r?.result?.result?.value;
  if (v?.in) { info = v; break; }
}

// A moment for YouTube to finish setting its cookies, then take them.
await sleep(2500);
const got = await page.send("Network.getCookies", { urls: ["https://music.youtube.com/", "https://www.youtube.com/"] });
const cookies = (got?.result?.cookies || []).filter((c) => c.domain.endsWith("youtube.com"));
const seen = new Set();
const cookie = cookies.filter((c) => !seen.has(c.name) && seen.add(c.name)).map((c) => `${c.name}=${c.value}`).join("; ");
if (!/(^|; )(__Secure-3PAPISID|SAPISID)=/.test(cookie)) {
  say("Signed in, but YouTube didn't give the sign-in cookie this tool needs. Nothing was changed; try again.");
  finished = true; cleanup(); process.exit(1);
}

const headers = [`cookie: ${cookie}`, `x-goog-authuser: ${info.authuser}`];
if (info.visitor) headers.push(`x-goog-visitor-id: ${info.visitor}`);
if (info.brand) headers.push(`x-goog-pageid: ${info.brand}`);

say("Signed in. Sending it to the music server…");
finished = true;
try { await page.send("Browser.close"); } catch {}
page.close();
cleanup();

const r = spawnSync("ssh", [process.env.MUSIC_HOST || "music-server", "/srv/music/venv/bin/python /srv/music/signin.py"],
  { input: headers.join("\n") + "\n", encoding: "utf8", timeout: 120000 });
const out = `${r.stdout || ""}\n${r.stderr || ""}`.split("\n").map((l) => l.trim())
  .filter((l) => l && !l.startsWith("Paste the request headers"));
const ok = out.find((l) => l.startsWith("SIGNED-IN-OK"));
if (ok) {
  say(`\nDone: ${ok.replace("SIGNED-IN-OK", "signed in")}.`);
  say("The TV's Music app picks it up by itself. You can close this window.");
  process.exit(0);
}
say("\nThe music server didn't accept it:");
for (const l of out.slice(-3)) say("  " + l);
process.exit(1);
