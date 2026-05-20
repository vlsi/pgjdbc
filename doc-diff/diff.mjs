#!/usr/bin/env node
//
// Walk the freshly-built new site (docs/public) and annotate each <p>/<li>/<pre>
// with data-origin="reused|modified|new" plus data-source pointing at the
// corresponding fragment on the live baseline site. A toggle button on the
// page (see overlay/overlay.js) turns the highlighting on/off.
//
// Matching strategy:
//   1. Normalise text (lowercase, collapse whitespace).
//   2. Exact SHA1 hash match against the baseline => "reused".
//   3. lunr fuzzy search; if top score >= SCORE_THRESHOLD => "modified".
//   4. Otherwise "new".
//
// Skip blocks shorter than MIN_WORDS — they collide too easily with boilerplate.
//
import { readFileSync, writeFileSync, readdirSync, mkdirSync, copyFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';
import * as cheerio from 'cheerio';
import lunr from 'lunr';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// ─── Configuration ─────────────────────────────────────────────────────────
const REPO_ROOT = path.resolve(__dirname, '..');
const NEW_DIR = process.env.NEW_DIR || path.join(REPO_ROOT, 'docs', 'public');
const BASELINE_DIR = (() => {
  if (process.env.BASELINE_DIR) {
    return path.join(process.env.BASELINE_DIR, 'docs', 'public');
  }
  const f = path.join(__dirname, '.baseline-path');
  if (existsSync(f)) {
    const line = readFileSync(f, 'utf8').trim();
    const dir = line.split('=')[1];
    return path.join(dir, 'docs', 'public');
  }
  throw new Error('Baseline not configured. Run build-baseline.sh first, or set BASELINE_DIR.');
})();
const LIVE_BASE = (process.env.LIVE_BASE || 'https://jdbc.postgresql.org').replace(/\/+$/, '');
// data-source attributes in the overlay always point at LIVE_BASE (the
// canonical "old" site reviewers compare against). The summary table's links
// can point somewhere else — typically the freshly-deployed preview, so a
// reviewer clicking a row lands on the annotated page. Defaults to LIVE_BASE
// when not set, which is the local-dev case.
const SUMMARY_BASE = (process.env.SUMMARY_BASE || LIVE_BASE).replace(/\/+$/, '');
// URL path prefix for sites deployed under a subpath (e.g. /pgjdbc/ on a
// fork's GitHub Pages). Without it, overlay assets are injected as
// /_doc-diff/overlay.css and the browser hits the host root, not the
// Pages basePath. Accepts either a full URL (so CI can pass
// steps.pages.outputs.base_url verbatim) or a pathname; auto-detected
// from <link rel=canonical> / <link rel=icon> on the first page when unset.
let BASE_PATH = process.env.BASE_PATH || '';
if (BASE_PATH) {
  try { BASE_PATH = new URL(BASE_PATH).pathname; } catch { /* already a path */ }
  BASE_PATH = BASE_PATH.replace(/\/+$/, '');
}
let basePathLocked = !!process.env.BASE_PATH;
// Jaccard-similarity threshold on the word sets of the candidate (top lunr
// hit) and the new fragment. lunr's own score is unbounded and can't be
// thresholded reliably, so we use it only for candidate retrieval and decide
// reused/modified/new from the Jaccard overlap. Stop-words and 1-2 letter
// tokens are dropped first (see toWordSet) so short connectives like
// "the/in/of" don't inflate matches.
const SIMILARITY_THRESHOLD = Number.parseFloat(process.env.SIMILARITY_THRESHOLD || '0.5');
const MIN_WORDS = Number.parseInt(process.env.MIN_WORDS || '5', 10);
const FRAGMENT_TAGS = 'p, li, pre';

// Common English stop-words, scraped down to the ones that actually appear
// frequently in pgjdbc docs. Anything filtered here also gets ignored by the
// Jaccard comparison.
const STOPWORDS = new Set([
  'the','a','an','and','or','but','if','then','else','of','to','in','on','at',
  'by','for','with','from','into','onto','as','is','are','was','were','be',
  'been','being','it','its','this','that','these','those','you','your','we',
  'our','can','will','may','should','would','must','also','not','no','do',
  'does','did','so','than','such','use','used','using','via','per','any','all',
]);

// ─── Helpers ───────────────────────────────────────────────────────────────
const normalise = (s) => s.replace(/\s+/g, ' ').trim().toLowerCase();
const hash = (s) => crypto.createHash('sha1').update(s).digest('hex').slice(0, 16);

function toWordSet(text) {
  const out = new Set();
  for (const raw of text.split(/[^a-z0-9]+/)) {
    if (raw.length < 3) continue;
    if (STOPWORDS.has(raw)) continue;
    out.add(raw);
  }
  return out;
}

function jaccard(a, b) {
  if (a.size === 0 || b.size === 0) return 0;
  let inter = 0;
  for (const x of a) if (b.has(x)) inter++;
  return inter / (a.size + b.size - inter);
}

function* walkHtml(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith('_doc-diff')) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* walkHtml(full);
    else if (entry.name.endsWith('.html')) yield full;
  }
}

function inferBasePath($, file, baseDir) {
  // Canonical (when present) carries the full deployment URL, so we can
  // diff it against the file's path-from-root to extract the prefix.
  const canonical = $('link[rel="canonical"]').attr('href');
  if (canonical) {
    try {
      const canonicalPath = new URL(canonical).pathname;
      let fileRel = '/' + path.relative(baseDir, file).split(path.sep).join('/');
      fileRel = fileRel.replace(/\/?index\.html$/, '/');
      if (canonicalPath.endsWith(fileRel) && canonicalPath.length > fileRel.length) {
        return canonicalPath.slice(0, -fileRel.length).replace(/\/+$/, '');
      }
    } catch { /* fall through */ }
  }
  // Favicon: Hugo writes it root-relative with the basePath baked in, e.g.
  // <link rel="icon" href="/pgjdbc/favicon.ico"> — strip the filename and
  // we've got the prefix.
  const icon = $('link[rel="icon"]').attr('href');
  if (icon && icon.startsWith('/') && !icon.startsWith('//')) {
    const m = icon.match(/^(.*)\/favicon\.[a-z]+$/i);
    if (m && m[1]) return m[1].replace(/\/+$/, '');
  }
  return '';
}

function pageUrl($, file, baseDir) {
  // Prefer the canonical URL Hugo emitted — it carries the correct basePath.
  const canonical = $('link[rel="canonical"]').attr('href');
  if (canonical) {
    try {
      return new URL(canonical).pathname;
    } catch { /* fall through */ }
  }
  let rel = '/' + path.relative(baseDir, file).split(path.sep).join('/');
  return rel.replace(/\/?index\.html$/, '/');
}

function findAnchor($, $el) {
  const own = $el.attr('id');
  if (own) return own;
  // Walk back through preceding siblings looking for an id.
  let cur = $el;
  while (cur.length) {
    const prev = cur.prevAll('[id]').first();
    if (prev.length) return prev.attr('id');
    cur = cur.parent();
    if (!cur.length || cur.is('main, body, html')) break;
    if (cur.attr('id')) return cur.attr('id');
  }
  return null;
}

function extractFragments($, file, baseDir) {
  const $main = $('main.main-inner');
  if ($main.length === 0) return [];
  const url = pageUrl($, file, baseDir);
  const out = [];
  // Navigation/TOC chrome that lives inside <main> would otherwise contribute
  // its leaf links as fragments — and they'd all flag as "new" on every page,
  // drowning out actual content diffs.
  const CHROME_SELECTOR = 'nav, aside, .sidebar, .toc, .pagination, .page-meta, .nav-mob';
  $main.find(FRAGMENT_TAGS).each((_, el) => {
    const $el = $(el);
    if ($el.parents('pre').length) return; // <code> inside <pre>, etc.
    if ($el.closest(CHROME_SELECTOR).length) return;
    // Structural <li> that just wraps a nested list — their .text()
    // concatenates every descendant, which never hashes to anything in the
    // baseline. Their leaf children get visited separately.
    if (el.tagName === 'li' && $el.children('ul, ol').length) return;
    const raw = el.tagName === 'pre' ? $el.text() : $el.text();
    const text = normalise(raw);
    if (text.length < 10) return;
    if (text.split(' ').length < MIN_WORDS) return;
    out.push({
      $el,
      tag: el.tagName,
      text,
      hash: hash(text),
      url,
      anchor: findAnchor($, $el),
    });
  });
  return out;
}

// ─── Index baseline ────────────────────────────────────────────────────────
console.error('Indexing baseline:', BASELINE_DIR);
const oldByHash = new Map(); // hash -> {url, anchor, text}
const lunrDocs = [];
let lunrId = 0;
let baselinePages = 0;
for (const file of walkHtml(BASELINE_DIR)) {
  const $ = cheerio.load(readFileSync(file, 'utf8'));
  const frags = extractFragments($, file, BASELINE_DIR);
  if (frags.length) baselinePages++;
  for (const f of frags) {
    if (!oldByHash.has(f.hash)) {
      oldByHash.set(f.hash, { url: f.url, anchor: f.anchor, text: f.text });
    }
    lunrDocs.push({
      id: ++lunrId,
      text: f.text,
      words: toWordSet(f.text),
      url: f.url,
      anchor: f.anchor,
    });
  }
}
console.error(`  ${baselinePages} pages, ${oldByHash.size} unique fragments, ${lunrDocs.length} index entries`);

console.error('Building lunr index…');
const idx = lunr(function () {
  this.ref('id');
  this.field('text');
  // Stay close to verbatim wording — stemming inflates scores for unrelated text.
  this.pipeline.remove(lunr.stemmer);
  this.searchPipeline.remove(lunr.stemmer);
  for (const d of lunrDocs) this.add({ id: d.id, text: d.text });
});
const lunrDocById = new Map(lunrDocs.map((d) => [String(d.id), d]));

// ─── Annotate new site ─────────────────────────────────────────────────────
console.error('Annotating new site:', NEW_DIR);
const stats = { pages: 0, reused: 0, modified: 0, new: 0 };
const perPage = []; // [{ url, reused, modified, new }]
for (const file of walkHtml(NEW_DIR)) {
  const $ = cheerio.load(readFileSync(file, 'utf8'));
  const frags = extractFragments($, file, NEW_DIR);
  if (frags.length === 0) continue;
  stats.pages++;
  const pageStat = { url: frags[0].url, reused: 0, modified: 0, new: 0 };
  perPage.push(pageStat);
  for (const f of frags) {
    // Reset any annotations from a previous run so we don't end up with stale
    // values when a fragment's classification changes between runs.
    f.$el.removeAttr('data-origin');
    f.$el.removeAttr('data-source');
    f.$el.removeAttr('data-similarity');
    const matched = oldByHash.get(f.hash);
    if (matched) {
      const link = LIVE_BASE + matched.url + (matched.anchor ? '#' + matched.anchor : '');
      f.$el.attr('data-origin', 'reused');
      f.$el.attr('data-source', link);
      stats.reused++;
      pageStat.reused++;
      continue;
    }
    // lunr doesn't love operator-like chars in queries; strip them.
    const query = f.text.replace(/[+\-:^~*?()\\/]/g, ' ').slice(0, 500);
    let results = [];
    try { results = idx.search(query); } catch { /* ignore */ }
    // lunr's raw score is unbounded; recompute Jaccard against the top few
    // candidates and pick the best — that gives a normalised similarity we
    // can threshold.
    const queryWords = toWordSet(f.text);
    let best = null;
    for (const r of results.slice(0, 5)) {
      const cand = lunrDocById.get(r.ref);
      const sim = jaccard(queryWords, cand.words);
      if (!best || sim > best.sim) best = { cand, sim };
    }
    if (best && best.sim >= SIMILARITY_THRESHOLD) {
      const link = LIVE_BASE + best.cand.url + (best.cand.anchor ? '#' + best.cand.anchor : '');
      f.$el.attr('data-origin', 'modified');
      f.$el.attr('data-source', link);
      f.$el.attr('data-similarity', best.sim.toFixed(2));
      stats.modified++;
      pageStat.modified++;
    } else {
      f.$el.attr('data-origin', 'new');
      stats.new++;
      pageStat.new++;
    }
  }
  if (!basePathLocked) {
    BASE_PATH = inferBasePath($, file, NEW_DIR);
    basePathLocked = true;
  }
  // Always re-inject: if BASE_PATH changed between runs, an old tag with a
  // stale href would otherwise stick around and 404.
  $('link[data-doc-diff], script[data-doc-diff]').remove();
  $('head').append(`<link data-doc-diff rel="stylesheet" href="${BASE_PATH}/_doc-diff/overlay.css">`);
  $('body').append(`<script data-doc-diff src="${BASE_PATH}/_doc-diff/overlay.js" defer></script>`);
  writeFileSync(file, $.html());
}

// ─── Copy overlay assets into the new site ─────────────────────────────────
const overlayDest = path.join(NEW_DIR, '_doc-diff');
mkdirSync(overlayDest, { recursive: true });
copyFileSync(path.join(__dirname, 'overlay', 'overlay.css'), path.join(overlayDest, 'overlay.css'));
copyFileSync(path.join(__dirname, 'overlay', 'overlay.js'), path.join(overlayDest, 'overlay.js'));

const total = stats.reused + stats.modified + stats.new;
const pct = (n, d = total) => d ? ((100 * n) / d).toFixed(1) + '%' : '-';

// ─── Write per-page markdown summary ───────────────────────────────────────
const summaryPath = path.join(__dirname, 'summary.md');
const rows = perPage
  .map((s) => ({ ...s, total: s.reused + s.modified + s.new }))
  .filter((s) => s.total > 0)
  // Pages with the most "new" content first — that's where review attention
  // is most useful. Ties broken by fragment count so big pages outrank tiny ones.
  .sort((a, b) => (b.new / b.total) - (a.new / a.total) || b.total - a.total);

const cell = (n, t) => t ? `${pct(n, t)} (${n})` : '-';
const md = [
  '# Doc-diff summary',
  '',
  `Baseline: \`${process.env.BASELINE_REF || `${process.env.UPSTREAM_REMOTE || 'pg'}/master`}\` (built into ${BASELINE_DIR})`,
  `Live (data-source links): ${LIVE_BASE}`,
  `Preview (summary links):  ${SUMMARY_BASE}`,
  '',
  `**Total:** ${stats.pages} pages, ${total} fragments — ${pct(stats.reused)} reused, ${pct(stats.modified)} modified, ${pct(stats.new)} new.`,
  '',
  '| Page | Fragments | New | Modified | Reused |',
  '| --- | ---: | ---: | ---: | ---: |',
  ...rows.map((r) =>
    `| [\`${r.url}\`](${SUMMARY_BASE}${r.url}) | ${r.total} | ${cell(r.new, r.total)} | ${cell(r.modified, r.total)} | ${cell(r.reused, r.total)} |`
  ),
  '',
].join('\n');
writeFileSync(summaryPath, md);

console.error('Done.');
console.error(`  pages annotated: ${stats.pages}`);
console.error(`  fragments:       ${total}`);
console.error(`    reused:        ${stats.reused} (${pct(stats.reused)})`);
console.error(`    modified:      ${stats.modified} (${pct(stats.modified)})`);
console.error(`    new:           ${stats.new} (${pct(stats.new)})`);
console.error(`  overlay assets:  ${overlayDest}`);
console.error(`  base path:       ${BASE_PATH || '(none)'}`);
console.error(`  summary:         ${summaryPath}`);
