#!/usr/bin/env node
/**
 * Push store/android/<locale>/ to the Play listing, for every listing language that has a folder.
 *
 *   node tools/play-listing.mjs screenshots      # replace phone screenshots with screenshots/*.png
 *   node tools/play-listing.mjs text             # set title.txt and short_description.txt
 *   node tools/play-listing.mjs text --dry-run   # show what would change, commit nothing
 *
 * The full description is deliberately not pushed: it is edited in Console as often as here, and
 * a blind overwrite would undo a Console-side correction. Committing an edit sends it for review.
 *
 * Same service account and zero-dependency JWT flow as tools/play-upload.mjs. It needs the
 * "Manage store presence" permission for LocalKeep, which the testing-track grant does not include.
 * Files go up in name order (01-..., 02-...), which is the order Play shows them in.
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { createSign } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3';
const UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3';
const pkg = 'com.localkeep';
const dryRun = process.argv.includes('--dry-run');
const mode = process.argv[2];
if (!['screenshots', 'text'].includes(mode)) throw new Error('usage: play-listing.mjs screenshots|text [--dry-run]');

const keyPath = process.env.PLAY_SERVICE_ACCOUNT_JSON ?? resolve(homedir(), '.config/chairly/play-service-account.json');
const key = JSON.parse(readFileSync(keyPath, 'utf8'));
const b64url = (s) => Buffer.from(s).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

async function accessToken() {
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${b64url(JSON.stringify({
    iss: key.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600,
  }))}`;
  const sig = createSign('RSA-SHA256').update(unsigned).sign(key.private_key)
    .toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${sig}` }),
  });
  const body = await res.json();
  if (!res.ok) throw new Error(`token exchange failed: ${JSON.stringify(body)}`);
  return body.access_token;
}

const token = await accessToken();
async function call(method, url, { body, contentType } = {}) {
  const res = await fetch(url, { method, headers: { Authorization: `Bearer ${token}`, ...(contentType ? { 'Content-Type': contentType } : {}) }, body });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url}\n  ${res.status} ${text}`);
  return text ? JSON.parse(text) : {};
}

const edit = await call('POST', `${API}/applications/${pkg}/edits`);
const base = `${API}/applications/${pkg}/edits/${edit.id}`;
const { listings = [] } = await call('GET', `${base}/listings`);

for (const listing of listings) {
  const { language } = listing;
  const dir = join(ROOT, 'store/android', language);
  if (!existsSync(dir)) { console.log(`${language}: no folder, left as is`); continue; }

  if (mode === 'text') {
    const read = (f) => readFileSync(join(dir, f), 'utf8').trim();
    const want = { title: read('title.txt'), shortDescription: read('short_description.txt') };
    const changed = Object.keys(want).filter((k) => listing[k] !== want[k]);
    for (const k of changed) console.log(`${language} ${k}: "${listing[k]}" -> "${want[k]}"`);
    if (!changed.length) console.log(`${language}: unchanged`);
    if (changed.length && !dryRun) {
      await call('PATCH', `${base}/listings/${language}`, { body: JSON.stringify(want), contentType: 'application/json' });
    }
    continue;
  }

  const shots = join(dir, 'screenshots');
  const files = readdirSync(shots).filter((f) => /^\d\d-.*\.png$/.test(f)).sort();
  if (dryRun) { console.log(`${language}: would replace with ${files.length}`); continue; }
  await call('DELETE', `${base}/listings/${language}/phoneScreenshots`);
  for (const f of files) {
    await call('POST', `${UPLOAD}/applications/${pkg}/edits/${edit.id}/listings/${language}/phoneScreenshots?uploadType=media`,
      { body: readFileSync(join(shots, f)), contentType: 'image/png' });
  }
  console.log(`${language}: ${files.length} uploaded`);
}

if (dryRun) {
  await call('DELETE', base);
  console.log('dry run: edit discarded');
} else {
  await call('POST', `${base}:commit`);
  console.log('committed');
}
