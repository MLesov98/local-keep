/**
 * Compose the captioned Play screenshots from raw device captures.
 *
 *   node tools/store-frames.mjs                  # every locale in store/screenshots.json
 *   node tools/store-frames.mjs --locale de-DE   # one locale
 *
 * Ported from Chairly's scripts/store-frames.mjs, whose reasoning carries over unchanged: the caption
 * is the design and it has to wrap correctly in five languages, so it is laid out by headless Chrome
 * rather than ImageMagick (which has no font registry here and cannot pick a weight). The output has
 * no alpha channel, because Play rejects a PNG that has one without naming the file.
 *
 * What differs from Chairly is the ground. LocalKeep is dark-only, and a dark screenshot on a dark
 * canvas loses its edges, so every device gets a hairline ring and a faint jade glow behind it, and
 * the canvas is lifted a step above the app's own ground colour (#0A0C0E in LkColors.kt).
 *
 * Two layouts, named per frame in the manifest:
 *   single   one device
 *   stack    cropped bands of one or two screens, stacked, each with an optional label
 *
 * Nothing is composited into a screen: every pixel inside a device or panel is the app's own, so
 * the set cannot show the app doing something it does not do (Play requires that it does not).
 */

import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, existsSync, rmSync, copyFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const RAW = join(ROOT, 'build/store-raw/android');

const args = Object.fromEntries(
  process.argv.slice(2).flatMap((a, i, all) => (a.startsWith('--') ? [[a.slice(2), all[i + 1]]] : [])),
);
const manifest = JSON.parse(readFileSync(join(ROOT, 'store/screenshots.json'), 'utf8'));

// 1080x2160: Play caps the long side at twice the short side, and the emulator's screen is
// 1080x2400, so this is the tallest legal canvas — the same reasoning as Chairly's.
const W = 1080;
const H = 2160;

// The app's own palette (core/ui/.../theme/LkColors.kt), so the frame reads as the product.
const C = {
  ink: '#F4F7F8',
  sub: '#A8B2B7',
  jade: '#3ED9AE',
  jadeLit: '#7BEFD0',
};

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** "[word]" -> accent span. The line breaks are authored per language and kept as written. */
function captionHtml(caption) {
  return esc(caption)
    .replace(/\[([^\]]+)\]/g, `<em>$1</em>`)
    .split('\n')
    .join('<br>');
}

const file = (p) => `file://${p}`;

function device(src, cls = '') {
  return `<div class="dev ${cls}"><img src="${file(src)}"></div>`;
}

function body(frame, shots, labels) {
  if (frame.layout === 'single') return `<div class="stage single">${device(shots[0])}</div>`;
  if (frame.layout === 'stack') {
    // Cropped bands of one or two screens, stacked. A before/after pair shown as two whole phones
    // side by side shrinks each to under half the canvas width, where no text on them is legible;
    // the band that carries the point is all either frame needs.
    // A lone panel is shorter than a phone, so it is centred; a pair starts at the device top line.
    return `<div class="stage stack" style="justify-content:${shots.length > 1 ? 'flex-start' : 'center'}">${shots.map((s, i) => `<div class="panel">
      ${labels?.[i] ? `<div class="pill${i === shots.length - 1 && shots.length > 1 ? ' on' : ''}">${esc(labels[i])}</div>` : ''}
      <img src="${file(s)}"></div>`).join('')}</div>`;
  }
  throw new Error(`unknown layout ${frame.layout}`);
}

function page(frame, caption, shots, labels) {
  // The caption band is a fixed fraction of the canvas, so every device sits at the same height
  // across the set (a band sized to its text moves the phone whenever German wraps differently).
  const band = Math.round(H * 0.2);
  const size = Math.round(W * 0.064);
  const pad = Math.round(W * 0.06);
  return `<!doctype html><html><head><meta charset="utf-8"><style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:${W}px;height:${H}px;overflow:hidden}
  :root{--edge:0 0 0 3px rgba(255,255,255,.18), 0 0 0 10px #0A0C0E, 0 0 0 11px rgba(255,255,255,.12),
    0 ${Math.round(W * 0.03)}px ${Math.round(W * 0.08)}px rgba(0,0,0,.65), 0 0 ${Math.round(W * 0.14)}px 6px rgba(62,217,174,.22)}
  body{font-family:-apple-system,'SF Pro Display',Helvetica,sans-serif;color:${C.ink};
    background:
      radial-gradient(80% 45% at 50% 62%, rgba(62,217,174,.20) 0%, rgba(62,217,174,0) 70%),
      radial-gradient(120% 60% at 50% 0%, #17352E 0%, #11201E 45%, #0F1A19 100%);
    display:flex;flex-direction:column;align-items:center}
  .cap{height:${band}px;width:100%;padding:${Math.round(band * 0.3)}px ${pad}px 0;
    display:flex;justify-content:center;text-align:center}
  .cap h1{font-size:${size}px;line-height:1.16;font-weight:700;letter-spacing:-0.022em;white-space:nowrap}
  .cap em{font-style:normal;color:${C.jade}}
  .stage{flex:1;min-height:0;width:100%;position:relative;display:flex;justify-content:center;
    align-items:flex-start;padding-bottom:${Math.round(W * 0.07)}px}
  .dev{height:100%;display:flex;align-items:flex-start}
  .dev img{max-height:100%;max-width:${Math.round(W * 0.8)}px;width:auto;display:block;
    border-radius:${Math.round(W * 0.05)}px;
    /* The app's ground is #0A0C0E, so a dark screen on a dark canvas has no edge of its own: a
       bright hairline, a bezel band, an outer hairline, then lift and a jade glow. Measured on
       the first render (2026-10-22): a .10 ring vanished at thumbnail size and the lower half of
       every phone dissolved into the canvas. */
    box-shadow:var(--edge)}
  .stack{flex-direction:column;align-items:center;justify-content:flex-start;gap:${Math.round(W * 0.1)}px;
    padding:${Math.round(W * 0.07)}px 0 ${Math.round(W * 0.07)}px}
  .panel{position:relative;width:var(--pw)}
  .panel img{width:100%;display:block;border-radius:${Math.round(W * 0.04)}px;box-shadow:var(--edge)}
  .pill{position:absolute;left:${Math.round(W * 0.03)}px;top:-${Math.round(W * 0.075)}px;z-index:2;
    font-size:${Math.round(W * 0.03)}px;font-weight:600;color:${C.ink};padding:${Math.round(W * 0.011)}px ${Math.round(W * 0.028)}px;
    border-radius:999px;background:#2A3236;box-shadow:0 6px 18px rgba(0,0,0,.5);white-space:nowrap}
  .pill.on{color:#04211A;background:${C.jade}}
  </style></head><body>
  <div class="cap"><h1>${captionHtml(caption)}</h1></div>
  ${body(frame, shots, labels)}
  <script>
  // Authored line breaks are kept (nowrap), so a long translated line shrinks instead of wrapping
  // into a third line — the failure the first render of this set showed in English already.
  const h = document.querySelector('.cap h1'), box = document.querySelector('.cap');
  const room = box.clientWidth - ${pad * 2};
  let px = ${size};
  while (h.scrollWidth > room && px > 30) h.style.fontSize = (--px) + 'px';
  // Stacked panels: the widest that lets every band fit, capped at a whole phone's width (~65%)
  // so the set does not jump wider on 02 and 06 when the frames are shown in a row.
  const stage = document.querySelector('.stack');
  if (stage) {
    const imgs = [...stage.querySelectorAll('img')];
    Promise.all(imgs.map((i) => i.decode())).then(() => {
      const cs = getComputedStyle(stage);
      const gap = parseFloat(cs.rowGap) * (imgs.length - 1) + parseFloat(cs.paddingTop);
      const ratio = imgs.reduce((a, i) => a + i.naturalHeight / i.naturalWidth, 0);
      const fit = (stage.clientHeight - parseFloat(cs.paddingBottom) - gap) / ratio;
      document.body.style.setProperty('--pw', Math.min(imgs.length > 1 ? ${Math.round(W * 0.65)} : ${Math.round(W * 0.88)}, fit) + 'px');
    });
  }
  </script>
  </body></html>`;
}

/**
 * The band holding a bottom sheet: from a little above its top edge to the gesture bar.
 *
 * A sheet's height is set by its content, and German lays the add-item sheet out in one column
 * where English uses two — measured 2026-10-22, its top at y=680 against 1360 — so no fixed crop
 * fits both. The sheet is the first bright run below the scrim in a column at the left margin.
 */
function sheetBand(src) {
  const col = execFileSync('magick', [src, '-crop', '1x2400+30+0', '+repage', '-colorspace', 'gray', '-depth', '8', 'txt:-'],
    { encoding: 'utf8', timeout: 60000 }).split('\n').slice(1);
  const lum = col.map((l) => Number((l.match(/\((\d+)/) ?? [])[1] ?? 0));
  const top = lum.findIndex((v, y) => y > 300 && v > 18);
  if (top < 0) throw new Error(`no sheet found in ${src}`);
  return [Math.max(0, top - 2), 2330];  // from the sheet's own top edge: no slivers of the list behind it
}

function render(html, out) {
  const tmp = out.replace(/\.png$/, '.html');
  writeFileSync(tmp, html, 'utf8');
  // stdio 'ignore' plus a timeout: with 'pipe', a Chrome helper that inherits stderr holds the
  // pipe open after the main process exits and the call never returns (Chairly, 2026-08-09).
  // One retry: headless Chrome hangs past the timeout about once per full 64-frame run on this
  // machine (twice on 2026-10-22), and a second attempt has rendered every time.
  const shoot = () => execFileSync(CHROME, ['--headless', '--disable-gpu', '--hide-scrollbars',
    '--force-device-scale-factor=1', '--allow-file-access-from-files', `--window-size=${W},${H}`,
    `--screenshot=${out}`, file(tmp)], { stdio: 'ignore', timeout: 120000 });
  try { shoot(); } catch { shoot(); }
  rmSync(tmp, { force: true });
  execFileSync('magick', [out, '-background', '#0A0C0E', '-alpha', 'remove', '-alpha', 'off', '-strip',
    'PNG24:' + out], { stdio: 'ignore', timeout: 120000 });
}

let written = 0;
const missing = [];
const cache = new Map(); // English storefronts share captures and captions: render once, copy.

for (const [locale, captureLocale] of Object.entries(manifest.captureLocale)) {
  if (args.locale && args.locale !== locale) continue;
  const lang = locale.split('-')[0];
  const captions = manifest.captions[lang];
  if (!captions) { missing.push(`${locale}: no captions for "${lang}"`); continue; }
  const labels = manifest.labels?.[lang] ?? {};
  const outDir = join(ROOT, 'store/android', locale, 'screenshots');
  mkdirSync(outDir, { recursive: true });
  // The old set used different file names; clear it so no stale frame is left to upload.
  for (const f of execFileSync('ls', [outDir], { encoding: 'utf8' }).split('\n').filter((f) => f.endsWith('.png'))) {
    rmSync(join(outDir, f));
  }

  manifest.frames.forEach((frame, i) => {
    const shots = frame.shots.map((s) => join(RAW, captureLocale, `${s}.png`));
    const gone = shots.filter((s) => !existsSync(s));
    if (gone.length) { missing.push(...gone.map((g) => g.replace(`${ROOT}/`, ''))); return; }
    const out = join(outDir, `${frame.name}.png`);
    const key = `${captureLocale}/${lang}/${frame.name}`;
    if (cache.has(key)) { copyFileSync(cache.get(key), out); written++; return; }
    const temps = [];
    (frame.crops ?? []).forEach((band, k) => {
      const [y0, y1] = band === 'sheet' ? sheetBand(shots[k]) : band;
      const t = out.replace(/\.png$/, `.crop${k}.png`);
      execFileSync('magick', [shots[k], '-crop', `1080x${y1 - y0}+0+${y0}`, '+repage', t], { stdio: 'ignore', timeout: 60000 });
      temps.push(shots[k] = t);
    });
    // finally: a Chrome timeout must not leave crop files beside the PNGs that get uploaded.
    try { render(page(frame, captions[i], shots, labels[frame.name]), out); } finally {
      temps.forEach((t) => rmSync(t, { force: true }));
    }
    cache.set(key, out);
    written++;
    console.log(`${locale}/${frame.name}`);
  });
}

console.log(`store-frames: wrote ${written} image(s)`);
if (missing.length) {
  console.error(`store-frames: ${missing.length} missing:\n  ${missing.join('\n  ')}`);
  process.exit(1);
}
