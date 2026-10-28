#!/usr/bin/env node
/**
 * Upload a LocalKeep AAB to a Play track from the command line (ported from Chairly's
 * scripts/play-upload.mjs, same zero-dependency JWT flow).
 *
 * Uses the same Play developer account's service account as Chairly
 * (chairly-play-upload@chairly-publisher.iam.gserviceaccount.com). It must be granted
 * "Release to testing tracks" for LocalKeep in Play Console → Users and permissions.
 * The key lives outside the repo; override with PLAY_SERVICE_ACCOUNT_JSON.
 *
 * Usage:
 *   ./gradlew :app:checkReleaseSigning :app:bundlePlayRelease
 *   node tools/play-upload.mjs --track internal
 *   node tools/play-upload.mjs --aab path/to/app.aab --track alpha --notes notes.json
 *   node tools/play-upload.mjs --track internal,alpha   # one upload, several tracks, one edit
 *
 * Release notes JSON is [{ "language": "en-US", "text": "..." }, ...]; omit for none.
 */

import { readFileSync } from 'node:fs';
import { createSign } from 'node:crypto';
// Imported rather than taken as a global: this file is linted with the app's browser-ish
// env, where `Buffer` is not defined.
import { Buffer } from 'node:buffer';
import { homedir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3';
const UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3';

function arg(name, fallback = null) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? fallback : process.argv[i + 1];
}

function die(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

const aabPath = arg('aab', resolve(dirname(fileURLToPath(import.meta.url)), '../app/build/outputs/bundle/playRelease/app-play-release.aab'));
// Comma-separated: one bundle can serve several tracks, and it has to — Play refuses a second upload
// of the same versionCode, so internal and closed testers on one build means one edit.
const tracks = arg('track', 'internal').split(',').map((t) => t.trim()).filter(Boolean);
const notesPath = arg('notes');
// 'completed' publishes to the track immediately; 'draft' leaves it for a human to roll out.
const status = arg('status', 'completed');


const keyPath =
  process.env.PLAY_SERVICE_ACCOUNT_JSON ??
  resolve(homedir(), '.config/chairly/play-service-account.json');

let key;
try {
  key = JSON.parse(readFileSync(keyPath, 'utf8'));
} catch {
  die(
    `no service-account key at ${keyPath}\n` +
      '  Set PLAY_SERVICE_ACCOUNT_JSON, or place the key at that path. See the header of this file.',
  );
}

const pkg = arg('package', 'com.localkeep');

const b64url = (input) =>
  Buffer.from(input).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/**
 * Exchange the service-account key for an access token.
 *
 * Google's JWT-bearer flow: sign our own assertion with the private key and trade it in.
 * There is no refresh token and no interactive step, which is the whole point — this runs
 * on CI or a laptop with no browser.
 */
async function accessToken() {
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: key.client_email,
    scope: SCOPE,
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${b64url(JSON.stringify(claims))}`;
  const signature = createSign('RSA-SHA256')
    .update(unsigned)
    .sign(key.private_key)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${unsigned}.${signature}`,
    }),
  });
  const body = await res.json();
  if (!res.ok) die(`token exchange failed: ${JSON.stringify(body)}`);
  return body.access_token;
}

async function call(token, method, url, { body, contentType } = {}) {
  const res = await fetch(url, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(contentType ? { 'Content-Type': contentType } : {}),
    },
    body,
  });
  const text = await res.text();
  if (!res.ok) die(`${method} ${url}\n  ${res.status} ${text}`);
  return text ? JSON.parse(text) : {};
}

const token = await accessToken();

// An "edit" is a transaction: nothing below is visible on Play until the commit at the end,
// so a failure halfway through leaves the track exactly as it was rather than half-updated.
const edit = await call(token, 'POST', `${API}/applications/${pkg}/edits`);
console.log(`edit ${edit.id}`);

const bundle = await call(
  token,
  'POST',
  `${UPLOAD}/applications/${pkg}/edits/${edit.id}/bundles?uploadType=media`,
  { body: readFileSync(aabPath), contentType: 'application/octet-stream' },
);
console.log(`uploaded versionCode ${bundle.versionCode}`);

const release = {
  versionCodes: [String(bundle.versionCode)],
  status,
  ...(notesPath ? { releaseNotes: JSON.parse(readFileSync(notesPath, 'utf8')) } : {}),
};

for (const track of tracks) {
  await call(token, 'PUT', `${API}/applications/${pkg}/edits/${edit.id}/tracks/${track}`, {
    body: JSON.stringify({ track, releases: [release] }),
    contentType: 'application/json',
  });
}

await call(token, 'POST', `${API}/applications/${pkg}/edits/${edit.id}:commit`);
console.log(`published versionCode ${bundle.versionCode} to ${tracks.join(', ')} (${status})`);
