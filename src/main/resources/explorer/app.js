'use strict';
/* PL/SQL Explorer — everything shown is fetched from the .agentdocs folder at run time (nothing embedded). */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const enc = p => p.split('/').map(encodeURIComponent).join('/');
const getJ = p => fetch('/api/file/' + enc(p), { cache: 'no-store' }).then(r => { if (!r.ok) throw new Error(p + ': ' + r.status); return r.json(); });
const getT = p => fetch('/api/file/' + enc(p), { cache: 'no-store' }).then(r => { if (!r.ok) throw new Error(p + ': ' + r.status); return r.text(); });
const COL = { ROUTINE: '#5aa9ff', PRIVATE: '#b48cff', TABLE: '#4cd694', UNIT_BLOCK: '#f2c14e', BUILTIN_PACKAGE: '#6b778e', EXTERNAL: '#4fd8e8', UNRESOLVED: '#ff6b6b' };
const KIND_LABEL = { ROUTINE: 'routine', TABLE: 'table', UNIT_BLOCK: 'init/trigger', BUILTIN_PACKAGE: 'Oracle built-in', EXTERNAL: 'external (missing)', UNRESOLVED: 'unresolved (missing)' };
const SEV = ['HIGH', 'MEDIUM', 'LOW', 'INFO'];

const S = {
  G: null, IDX: null, C: null, byId: new Map(), out: new Map(), inn: new Map(), conf: new Map(),
  sel: null, mode: 'focus', depth: 2, q: '', risk: new Set(),
  kinds: { ROUTINE: 1, TABLE: 1, UNIT_BLOCK: 1, BUILTIN_PACKAGE: 0, EXTERNAL: 1, UNRESOLVED: 1 },
  card: null, text: new Map(), packOff: new Set(), budget: 32000, closed: new Set(), view: 'explore'
};
let cy = null, pulseTimer = null, dash = 0;

const scoreColor = s => s == null ? '#6b778e' : s >= 90 ? '#4cd694' : s >= 75 ? '#8fd14f' : s >= 60 ? '#f2c14e' : '#ff6b6b';
const shortName = id => { const k = id.indexOf('.'); return k > 0 && !id.startsWith('TABLE:') ? id.slice(k + 1) : id.replace(/^(TABLE|BUILTIN|EXTERNAL|UNRESOLVED|TRIGGER):/, ''); };
const tok = t => Math.ceil(t.length / 4);
const fmt = n => n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n);
function toast(msg) { const t = $('#toast'); t.textContent = msg; t.classList.add('show'); clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2200); }

/* ---------------------------------------------------------------- loading */
async function load() {
  const [G, IDX, C, V, ER] = await Promise.all([getJ('analysis/graph.json'), getJ('INDEX.json'), getJ('analysis/confidence.json'),
    getJ('analysis/verification.json').catch(() => null), getJ('analysis/er.json').catch(() => null)]);
  S.V = V;
  S.ER = ER || { entities: [], relations: [], clusters: [], notes: [] };
  for (const e of S.ER.entities) { e.columns = e.columns || []; e.readBy = e.readBy || []; e.writtenBy = e.writtenBy || []; }
  for (const r of S.ER.relations) { r.childColumns = r.childColumns || []; r.parentColumns = r.parentColumns || []; r.evidence = r.evidence || []; }
  G.fileNodes = G.fileNodes || []; G.fileEdges = G.fileEdges || []; G.fileOrder = G.fileOrder || []; G.fileCycles = G.fileCycles || [];
  for (const f of G.fileNodes) { f.units = f.units || []; f.dependsOn = f.dependsOn || []; f.usedBy = f.usedBy || []; }
  for (const e of G.fileEdges) e.evidence = e.evidence || [];
  C.routines = C.routines || []; C.files = C.files || []; C.missing = C.missing || [];
  for (const s of [...C.routines, ...C.files]) s.findings = s.findings || [];   // empty arrays are omitted from the JSON
  for (const m of C.missing) m.usedBy = m.usedBy || [];
  C.missingByKind = C.missingByKind || {};
  S.G = G; S.IDX = IDX; S.C = C;
  S.byId = new Map(G.nodes.map(n => [n.id, n]));
  S.out = new Map(); S.inn = new Map();
  for (const e of G.edges) {
    (S.out.get(e.from) || S.out.set(e.from, []).get(e.from)).push(e);
    (S.inn.get(e.to) || S.inn.set(e.to, []).get(e.to)).push(e);
  }
  S.conf = new Map(C.routines.map(r => [r.id, r]));
  S.text.clear();
  $('#toolinfo').textContent = G.tool.replace('plsql-atlas ', 'v') + ' · ' + IDX.routines.length + ' routines · ' + G.nodes.filter(n => n.type === 'TABLE').length + ' tables';
  renderChips(); renderTree(); renderLegend(); drawGraph(false);
  if (S.sel && S.byId.has(S.sel)) await showCard(S.sel); else if (S.sel) { S.sel = null; showEmpty(); }
  renderViews();
}

/* ---------------------------------------------------------------- sidebar */
function renderChips() {
  $('#kindchips').innerHTML = Object.keys(S.kinds).map(k => `<span class="chip ${S.kinds[k] ? 'on' : ''}" data-k="${k}" style="color:${COL[k] || COL.ROUTINE}">${KIND_LABEL[k]}</span>`).join('');
  $('#riskchips').innerHTML = '<span class="chip" style="cursor:default">has risk:</span>' + ['HIGH', 'MEDIUM', 'LOW'].map(s => `<span class="chip ${S.risk.has(s) ? 'on' : ''}" data-r="${s}" style="color:${s === 'HIGH' ? 'var(--red)' : s === 'MEDIUM' ? 'var(--amber)' : 'var(--cyan)'}">${s.toLowerCase()}</span>`).join('') +
    `<span class="chip ${S.risk.has('LOWCONF') ? 'on' : ''}" data-r="LOWCONF" style="color:var(--red)">low confidence</span>`;
}
function topRisk(r) { for (const s of ['HIGH', 'MEDIUM', 'LOW']) if ((r.risks || []).some(x => x.startsWith(s + ':'))) return s; return ''; }
function matchRoutine(r) {
  if (S.q) { const h = (r.id + ' ' + (r.purpose || '') + ' ' + (r.signature || '')).toLowerCase(); if (!S.q.split(/\s+/).every(w => h.includes(w))) return false; }
  const rs = [...S.risk].filter(x => x !== 'LOWCONF');
  if (rs.length && !rs.some(s => (r.risks || []).some(x => x.startsWith(s + ':')))) return false;
  if (S.risk.has('LOWCONF') && !((S.conf.get(r.id)?.score ?? 100) < 75)) return false;
  return true;
}
function renderTree() {
  const groups = new Map();
  for (const r of S.IDX.routines) { const g = r.package || (r.id.includes('.') ? r.id.split('.')[0] : '(standalone)'); (groups.get(g) || groups.set(g, []).get(g)).push(r); }
  let html = '';
  for (const [g, rs] of [...groups].sort((a, b) => a[0].localeCompare(b[0]))) {
    const vis = rs.filter(matchRoutine); if (!vis.length) continue;
    const closed = !S.q && S.closed.has(g);
    html += `<div class="grp ${closed ? 'closed' : ''}" data-g="${esc(g)}"><div class="gh"><span>${closed ? '▸' : '▾'} ${esc(g)}</span><span class="cc">${vis.length}</span></div><div class="items">` +
      vis.sort((a, b) => a.line - b.line).map(r => {
        const sc = S.conf.get(r.id)?.score, tr = topRisk(r);
        return `<div class="item ${S.sel === r.id ? 'sel' : ''}" data-id="${esc(r.id)}" title="${esc(r.signature)}"><span class="rk ${tr}"></span><span class="nm">${esc(shortName(r.id))}</span><span class="cc" style="color:${scoreColor(sc)}">${sc ?? ''}</span><span class="cc">cc${r.cyclomatic}</span></div>`;
      }).join('') + '</div></div>';
  }
  const tables = Object.keys(S.IDX.tables || {}).filter(t => !S.q || t.toLowerCase().includes(S.q) || S.q.split(/\s+/).every(w => t.toLowerCase().includes(w)));
  if (tables.length && !S.risk.size) {
    const closed = !S.q && S.closed.has('(tables)');
    html += `<div class="grp ${closed ? 'closed' : ''}" data-g="(tables)"><div class="gh"><span>${closed ? '▸' : '▾'} tables</span><span class="cc">${tables.length}</span></div><div class="items">` +
      tables.map(t => `<div class="item ${S.sel === 'TABLE:' + t ? 'sel' : ''}" data-id="TABLE:${esc(t)}"><span class="rk"></span><span class="nm" style="color:var(--green)">${esc(t)}</span></div>`).join('') + '</div></div>';
  }
  $('#tree').innerHTML = html || '<div style="color:var(--dim);padding:8px">No match.</div>';
}

/* ---------------------------------------------------------------- graph */
function initCy() {
  for (const ext of ['cytoscapeFcose', 'cytoscapeDagre']) { try { if (window[ext]) cytoscape.use(window[ext]); } catch (e) { /* already registered */ } }
  cy = cytoscape({
    container: $('#cy'), wheelSensitivity: 0.25, minZoom: 0.08, maxZoom: 3, boxSelectionEnabled: false,
    style: [
      { selector: 'node', style: { label: 'data(label)', color: '#c8d2e6', 'font-size': 9, 'text-valign': 'bottom', 'text-margin-y': 3, 'text-outline-color': '#0b0f17', 'text-outline-width': 2, 'underlay-opacity': 0.28, 'underlay-padding': 5, 'underlay-shape': 'ellipse', 'border-width': 0, 'min-zoomed-font-size': 6 } },
      { selector: 'node[size]', style: { 'background-color': 'data(color)', width: 'data(size)', height: 'data(size)' } },
      { selector: 'node[ring]', style: { 'underlay-color': 'data(ring)' } },
      { selector: 'node[type="TABLE"]', style: { shape: 'round-rectangle' } },
      { selector: 'node[type="UNIT_BLOCK"]', style: { shape: 'diamond' } },
      { selector: 'node[type="BUILTIN_PACKAGE"]', style: { shape: 'round-tag' } },
      { selector: 'node[?ghost]', style: { 'background-opacity': 0.06, 'border-width': 1.5, 'border-style': 'dashed', 'border-color': 'data(color)', color: 'data(color)' } },
      { selector: 'node[type="EXTERNAL"], node[type="UNRESOLVED"]', style: { shape: 'round-rectangle' } },
      { selector: 'node.pkg', style: { shape: 'round-rectangle', 'background-opacity': 0.05, 'background-color': '#5aa9ff', 'border-width': 1, 'border-color': '#243049', label: 'data(label)', 'text-valign': 'top', 'text-halign': 'center', color: '#5aa9ff', 'font-size': 11, 'underlay-opacity': 0, 'text-margin-y': -4, padding: 14 } },
      { selector: 'node[?risk]', style: { 'border-width': 2, 'border-color': '#ff6b6b' } },
      { selector: 'edge', style: { width: 1, 'line-color': '#3b4c6e', 'target-arrow-color': '#3b4c6e', 'target-arrow-shape': 'triangle', 'arrow-scale': 0.7, 'curve-style': 'bezier', opacity: 0.55 } },
      { selector: 'edge[type="READS"]', style: { 'line-color': '#2f8f68', 'target-arrow-color': '#2f8f68', 'line-style': 'dotted' } },
      { selector: 'edge[type="WRITES"]', style: { 'line-color': '#e0a93a', 'target-arrow-color': '#e0a93a', width: 2 } },
      { selector: 'edge[?ambiguous]', style: { 'line-style': 'dashed', 'line-color': '#b48cff' } },
      { selector: 'edge.hot', style: { opacity: 1, 'line-style': 'dashed', 'line-dash-pattern': [6, 4], width: 2.2 } },
      { selector: '.faded', style: { opacity: 0.1 } },
      { selector: 'node.sel', style: { 'underlay-opacity': 0.6, 'underlay-padding': 10, 'border-width': 2, 'border-color': '#fff', color: '#fff', 'font-size': 11, 'font-weight': 'bold', 'z-index': 99 } }
    ]
  });
  cy.on('tap', 'node', e => { if (!e.target.hasClass('pkg')) select(e.target.id(), true); });
  cy.on('dbltap', 'node', e => { if (!e.target.hasClass('pkg')) { setMode('focus'); select(e.target.id(), true); } });
  cy.on('mouseover', 'node', e => { if (e.target.hasClass('pkg')) return; const n = e.target.closedNeighborhood(); cy.elements().not(n).not('.pkg').addClass('faded'); });
  cy.on('mouseout', 'node', () => cy.elements().removeClass('faded'));
  setInterval(() => { dash = (dash - 1) % 20; if (S.mode === 'focus') cy.edges('.hot').style('line-dash-offset', dash); }, 60);
}
function nodeEl(n, extra) {
  const ghost = n.type === 'EXTERNAL' || n.type === 'UNRESOLVED';
  const col = n.type === 'ROUTINE' ? (['PRIVATE', 'LOCAL'].includes(n.visibility) ? COL.PRIVATE : COL.ROUTINE) : COL[n.type] || COL.ROUTINE;
  const size = n.type === 'ROUTINE' ? 14 + Math.min(n.cyclomatic || 1, 30) * 0.9 : n.type === 'TABLE' ? 22 : 14;
  const hi = (n.risks || []).some(r => r.startsWith('HIGH:'));
  const ring = n.type === 'ROUTINE' && n.confidence != null && n.confidence < 75 ? scoreColor(n.confidence) : col;
  return { group: 'nodes', data: { id: n.id, label: n.type === 'ROUTINE' ? shortName(n.id) : n.label, type: n.type, color: col, ring, size, ghost, risk: hi, ...extra } };
}
function focusSet(id, depth) {
  const keep = new Set([id]), n = S.byId.get(id); if (!n) return keep;
  const walk = (adj, pick) => { let fr = [id]; for (let i = 0; i < depth; i++) { const nx = []; for (const x of fr) for (const e of (adj.get(x) || [])) if (e.type === 'CALLS' && !keep.has(pick(e))) { keep.add(pick(e)); nx.push(pick(e)); } fr = nx; } };
  if (n.type === 'TABLE') {
    for (const e of (S.inn.get(id) || [])) keep.add(e.from);
    const writers = (S.inn.get(id) || []).filter(e => e.type === 'WRITES').map(e => e.from); let fr = writers;
    for (let i = 1; i < depth; i++) { const nx = []; for (const w of fr) for (const e of (S.inn.get(w) || [])) if (e.type === 'CALLS' && !keep.has(e.from)) { keep.add(e.from); nx.push(e.from); } fr = nx; }
  } else {
    walk(S.out, e => e.to); walk(S.inn, e => e.from);
    for (const x of [id, ...(S.out.get(id) || []).filter(e => e.type === 'CALLS').map(e => e.to)]) for (const e of (S.out.get(x) || [])) if (e.type !== 'CALLS') keep.add(e.to);
  }
  return keep;
}
function drawGraph(animate = true) {
  if (!cy) initCy();
  const global = S.mode === 'global' || !S.sel || !S.byId.has(S.sel);
  let ids;
  if (global) ids = new Set(S.G.nodes.map(n => n.id)); else ids = focusSet(S.sel, S.depth);
  const nodes = S.G.nodes.filter(n => ids.has(n.id) && (S.kinds[n.type] || n.id === S.sel));
  const keep = new Set(nodes.map(n => n.id)), els = [];
  const pkgs = new Set();
  for (const n of nodes) {
    let parent;
    if (global && n.type === 'ROUTINE' && n.owner) { parent = 'pkg:' + n.owner; pkgs.add(n.owner); }
    els.push(nodeEl(n, parent ? { parent } : {}));
  }
  for (const p of pkgs) els.push({ group: 'nodes', classes: 'pkg', data: { id: 'pkg:' + p, label: p } });
  for (const e of S.G.edges) if (keep.has(e.from) && keep.has(e.to) && (global || ids.has(e.from) && ids.has(e.to)))
    els.push({ group: 'edges', data: { id: e.from + '|' + e.to + '|' + e.type, source: e.from, target: e.to, type: e.type, ambiguous: !!e.ambiguous } });
  cy.batch(() => { cy.elements().remove(); cy.add(els); });
  const layout = global
    ? { name: 'fcose', animate, animationDuration: 1100, randomize: true, quality: 'default', nodeRepulsion: () => 9000, idealEdgeLength: () => 85, packComponents: true, nodeDimensionsIncludeLabels: true, fit: true, padding: 40 }
    : { name: 'dagre', rankDir: 'LR', nodeSep: 16, rankSep: 80, animate, animationDuration: 600, fit: true, padding: 40 };
  cy.resize();   // the explore view may have been hidden while this was called
  refit(cy, layout);
  markSelection();
  $('#graphstat').textContent = `${nodes.length} nodes · ${cy.edges().length} edges · ${global ? 'global' : 'focus depth ' + S.depth}`;
}
/** Run a layout, then re-measure the canvas and fit once it settles (a hidden or just-shown canvas reports stale sizes). */
function refit(graph, options) {
  if (document.hidden) options = { ...options, animate: false };   // hidden pages get no animation frames: place nodes at once
  const lay = graph.layout(options);
  lay.one('layoutstop', () => { graph.resize(); graph.fit(undefined, 40); graph.forceRender(); setTimeout(() => graph.forceRender(), 250); });
  lay.run();
}
function markSelection() {
  cy.nodes().removeClass('sel'); cy.edges().removeClass('hot'); clearInterval(pulseTimer);
  const n = S.sel && cy.getElementById(S.sel); if (!n || n.empty()) return;
  n.addClass('sel'); n.connectedEdges().addClass('hot');
  const pulse = () => n.animate({ style: { 'underlay-padding': 18 } }, { duration: 700 }).animate({ style: { 'underlay-padding': 8 } }, { duration: 700 });
  pulse(); pulseTimer = setInterval(pulse, 1400);
}
function renderLegend() {
  $('#legend').innerHTML = `<span><i style="background:${COL.ROUTINE}"></i>public routine</span><span><i style="background:${COL.PRIVATE}"></i>private</span><span><i style="background:${COL.TABLE}"></i>table</span><span><i style="background:${COL.UNIT_BLOCK}"></i>init/trigger</span>` +
    `<span><i style="border:1.5px dashed ${COL.EXTERNAL};background:none"></i>external — missing</span><span><i style="border:1.5px dashed ${COL.UNRESOLVED};background:none"></i>unresolved — missing</span><span><i style="background:#ff6b6b33;border:2px solid #ff6b6b"></i>HIGH risk · red/amber glow = low confidence</span>` +
    `<span>— calls · <span style="color:#e0a93a">— writes</span> · <span style="color:#2f8f68">··· reads</span> · <span style="color:#b48cff">- - ambiguous</span></span>`;
}
function setMode(m) { S.mode = m; $$('#modeseg button').forEach(b => b.classList.toggle('on', b.dataset.mode === m)); }

/* ---------------------------------------------------------------- selection + card */
async function select(id, fromGraph) {
  S.sel = id; S.packOff.clear();
  $$('.item.sel').forEach(x => x.classList.remove('sel'));
  const it = $(`.item[data-id="${CSS.escape(id)}"]`); if (it) { it.classList.add('sel'); it.scrollIntoView({ block: 'nearest' }); }
  if (S.view !== 'explore') switchView('explore');
  if (S.mode === 'focus') drawGraph(true);
  else { markSelection(); const n = cy.getElementById(id); if (!n.empty() && !fromGraph) cy.animate({ center: { eles: n }, zoom: Math.max(cy.zoom(), 1.1) }, { duration: 600 }); }
  await showCard(id);
}
function showEmpty() { $('#empty').hidden = false; $('#card').hidden = true; }
async function showCard(id) {
  const n = S.byId.get(id); if (!n) return showEmpty();
  $('#empty').hidden = true; $('#card').hidden = false;
  S.card = null;
  if (n.type === 'ROUTINE' && n.cardJson) {
    try { S.card = await getJ(n.cardJson); } catch (e) { S.card = null; }
  }
  renderHead(n); renderLogic(n); await renderDetails(n); renderPack(n);
}
function badge(t, cls = '', title = '') { return `<span class="badge ${cls}" title="${esc(title)}">${esc(t)}</span>`; }
function renderHead(n) {
  const c = S.card, sc = S.conf.get(n.id);
  let h = `<h2>${esc(n.id.replace(/^TABLE:/, ''))}</h2>`;
  if (c) {
    h += `<div class="sig">${esc(c.signature)}</div>${c.purpose ? `<div class="purpose">${esc(c.purpose)}</div>` : ''}`;
    h += badge(c.visibility) + badge(c.kind) + badge('batch ' + c.batch) + badge('cc ' + (c.metrics?.cyclomatic ?? '?')) + badge((c.metrics?.linesOfCode ?? '?') + ' loc') + badge(c.file + ':' + c.line);
    for (const a of c.attributes || []) h += badge(a);
    if (sc) h += `<span class="badge" style="color:${scoreColor(sc.score)};border-color:${scoreColor(sc.score)}" title="parse confidence">confidence ${sc.score} ${sc.grade}</span>`;
    for (const r of [...new Set((c.risks || []).map(r => r.severity + ':' + r.code))]) h += badge(r.split(':')[1], r.split(':')[0]);
    if (c.alternateVariantOnly) h += badge('$IF alternate only', 'LOW');
  } else {
    h += `<div class="sig">${esc(KIND_LABEL[n.type] || n.type)}${n.file ? ' · ' + esc(n.file) + ':' + n.line : ''}</div>`;
    if (n.type === 'EXTERNAL' || n.type === 'UNRESOLVED') h += `<div class="purpose" style="color:var(--red)">Missing artifact: this is used by the scanned code but not defined in it.</div>`;
  }
  $('#cardhead').innerHTML = h;
}

/* ---- logic outline <-> source */
let stepIndex = [];
function renderLogic(n) {
  const c = S.card; stepIndex = [];
  if (!c) { $('#outline').innerHTML = `<div style="color:var(--dim)">${n.type === 'TABLE' ? 'See Details for readers and writers.' : 'No body in the scanned code.'}</div>`; $('#source').innerHTML = ''; return; }
  const steps = (list, pre) => (list || []).map((s, i) => {
    const no = pre ? pre + '.' + (i + 1) : String(i + 1), idx = stepIndex.push(s) - 1;
    return `<div class="step" data-i="${idx}" title="${esc(s.text)}"><span class="ln">${no}</span> <span class="k">${esc(s.kind)}</span> ${esc((s.text || '').slice(0, 120))}${(s.refs || []).length ? `<span class="rf">${esc(s.refs.slice(0, 3).join(', '))}</span>` : ''}<span class="ln">L${s.line}${s.endLine > s.line ? '–' + s.endLine : ''}</span></div>` +
      ((s.children || []).length ? `<div class="kids">${steps(s.children, no)}</div>` : '');
  }).join('');
  $('#outline').innerHTML = steps(c.outline, '') || '<div style="color:var(--dim)">Empty body.</div>';
  const lines = (c.source || '').split('\n'); if (lines.length && lines[lines.length - 1] === '') lines.pop();
  $('#source').innerHTML = lines.map((l, i) => `<div class="sl" data-n="${c.line + i}"><span class="n">${c.line + i}</span><span>${esc(l)}</span></div>`).join('');
}
function activateStep(i, scrollOutline) {
  const s = stepIndex[i]; if (!s) return;
  $$('#outline .step.act').forEach(x => x.classList.remove('act'));
  const el = $(`#outline .step[data-i="${i}"]`); el.classList.add('act'); if (scrollOutline) el.scrollIntoView({ block: 'nearest' });
  let first = null;
  $$('#source .sl').forEach(x => { const n = +x.dataset.n, on = n >= s.line && n <= (s.endLine || s.line); x.classList.toggle('hl', on); if (on && !first) first = x; });
  if (first) first.scrollIntoView({ block: 'center', behavior: 'smooth' });
}
function stepAtLine(n) {
  let best = -1, span = 1e9;
  stepIndex.forEach((s, i) => { const e = s.endLine || s.line; if (n >= s.line && n <= e && e - s.line <= span) { span = e - s.line; best = i; } });
  return best;
}

/* ---- details */
const kv = o => !o ? '' : '<ul class="plain">' + Object.entries(o).filter(([, v]) => !(v == null || v === false || v === '' || (Array.isArray(v) && !v.length) || (typeof v === 'object' && !Array.isArray(v) && !Object.keys(v).length)))
  .map(([k, v]) => `<li><b>${esc(k)}</b>: ${esc(Array.isArray(v) ? v.map(x => typeof x === 'object' ? JSON.stringify(x) : x).join(', ') : typeof v === 'object' ? JSON.stringify(v) : v)}</li>`).join('') + '</ul>';
const idLink = id => S.byId.has(id) ? `<a class="go" data-go="${esc(id)}">${esc(id)}</a>` : esc(id);
async function renderDetails(n) {
  const box = $('#tab-details'), c = S.card, sc = S.conf.get(n.id); let h = '';
  if (sc && sc.findings.length) h += `<h3>Why confidence is ${sc.score}/100</h3><table><tr><th>−</th><th>Finding</th><th>Where</th></tr>${sc.findings.map(f => `<tr><td>${f.penalty}</td><td><b>${esc(f.code)}</b> ${esc(f.subject)}<br><span style="color:var(--dim)">${esc(f.message)}</span></td><td>${f.line ? 'L' + f.line : ''}</td></tr>`).join('')}</table>`;
  if (n.type === 'TABLE') {
    const imp = S.G.tableImpact[n.label] || {};
    h += `<h3>Writers (${(imp.writers || []).length})</h3><ul class="plain">${(imp.writers || []).map(x => `<li>${idLink(x)}</li>`).join('')}</ul><h3>Readers (${(imp.readers || []).length})</h3><ul class="plain">${(imp.readers || []).map(x => `<li>${idLink(x)}</li>`).join('')}</ul><h3>Reach a writer through calls (${(imp.transitiveWriters || []).length})</h3><ul class="plain">${(imp.transitiveWriters || []).map(x => `<li>${idLink(x)}</li>`).join('')}</ul>`;
    const miss = S.C.missing.find(m => m.kind === 'TABLE_DDL_MISSING' && m.name === n.label);
    if (miss) h += `<h3>Missing artifact</h3><div style="color:var(--amber)">${esc(miss.detail)}</div>`;
  } else if (!c) {
    h += `<h3>Called by</h3><ul class="plain">${(S.inn.get(n.id) || []).map(e => `<li>${idLink(e.from)} <span class="cc">×${e.count || 1}</span></li>`).join('') || '<li>—</li>'}</ul>`;
  } else {
    if ((c.params || []).length) h += `<h3>Parameters</h3><table><tr>${Object.keys(c.params[0]).map(k => `<th>${esc(k)}</th>`).join('')}</tr>${c.params.map(p => `<tr>${Object.values(p).map(v => `<td>${esc(v)}</td>`).join('')}</tr>`).join('')}</table>`;
    h += `<h3>Calls (${(c.calls || []).length})</h3><table><tr><th>Callee</th><th>Line</th><th>Resolution</th></tr>${(c.calls || []).map(x => `<tr><td>${x.targetId ? idLink(x.targetId) : esc(x.callee)}</td><td>${x.line}</td><td style="color:${/UNRESOLVED|EXTERNAL/.test(x.resolution || '') ? 'var(--red)' : 'var(--dim)'}">${esc(x.resolution)}${(x.candidates || []).length ? ' (' + x.candidates.length + ' candidates)' : ''}</td></tr>`).join('')}</table>`;
    h += `<h3>Called by (${(c.calledBy || []).length})</h3><ul class="plain">${(c.calledBy || []).map(x => `<li>${idLink(x)}</li>`).join('') || '<li>—</li>'}</ul>`;
    h += `<h3>SQL (${(c.sql || []).length})</h3>${(c.sql || []).map(s => `<div><b>${esc(s.kind)}</b> L${s.line}${s.inLoop ? ' <span style="color:var(--amber)">in loop</span>' : ''}${s.dynamic ? ' <span style="color:var(--red)">dynamic ' + esc(s.dynamicConfidence) + '</span>' : ''} ${(s.tables || []).map(t => `<span class="badge">${esc(t.table)} ${esc((t.access || []).join('/'))}</span>`).join('')}<pre class="sql">${esc(s.text)}</pre></div>`).join('')}`;
    h += `<h3>Tables</h3>read: ${(c.tablesRead || []).map(t => idLink('TABLE:' + t)).join(', ') || '—'}<br>written: ${(c.tablesWritten || []).map(t => idLink('TABLE:' + t)).join(', ') || '—'}`;
    h += `<h3>Exception contract</h3>${kv(c.exceptionContract)}<h3>Side effects</h3>${kv(c.sideEffects)}`;
    if ((c.oracleNotes || []).length) h += `<h3>Oracle semantics notes</h3><ul class="plain">${c.oracleNotes.map(x => `<li>${esc(x)}</li>`).join('')}</ul>`;
    if ((c.risks || []).length) h += `<h3>Risks</h3>${c.risks.map(r => `<div>${badge(r.severity, r.severity)} <b>${esc(r.code)}</b> L${r.line} — ${esc(r.message)}</div>`).join('')}`;
  }
  box.innerHTML = h || '<div style="color:var(--dim)">Nothing to show.</div>';
}

/* ---- prompt pack */
function packItems(n) {
  const items = [], seen = new Set();
  const add = (label, path, kind) => { if (path && !seen.has(path)) { seen.add(path); items.push({ label, path, kind }); } };
  add('AGENTS.md (how to read the cards)', 'AGENTS.md', 'guide');
  if (n.card) add(n.id + ' — card', n.card, 'card');
  if (n.type === 'ROUTINE') {
    const ids = [...focusSet(n.id, S.depth)].filter(i => i !== n.id);
    const down = new Set(); let fr = [n.id];
    for (let d = 0; d < Math.max(S.depth, 1); d++) { const nx = []; for (const x of fr) for (const e of (S.out.get(x) || [])) if (e.type === 'CALLS' && !down.has(e.to)) { down.add(e.to); nx.push(e.to); } fr = nx; }
    for (const i of down) { const m = S.byId.get(i); if (m && m.type === 'ROUTINE') add(i + ' — callee card', m.card, 'callee'); }
    const tbl = new Set();
    for (const x of [n.id, ...down]) for (const e of (S.out.get(x) || [])) if (e.type !== 'CALLS') tbl.add(e.to);
    for (const t of tbl) { const m = S.byId.get(t); if (m) add(m.label + ' — table', m.card, 'table'); }
    void ids;
  } else if (n.type === 'TABLE') {
    for (const e of (S.inn.get(n.id) || [])) { const m = S.byId.get(e.from); if (m && m.card) add(e.from + ' — ' + e.type.toLowerCase() + ' it', m.card, 'callee'); }
  }
  add('Parse confidence & missing artifacts', 'analysis/confidence.md', 'guide');
  return items;
}
async function text(path) { if (!S.text.has(path)) S.text.set(path, await getT(path).catch(() => '')); return S.text.get(path); }
async function renderPack(n) {
  const box = $('#tab-pack'), items = packItems(n);
  for (const it of items) it.text = await text(it.path);
  if (S.sel !== n.id) return;
  const on = items.filter(i => !S.packOff.has(i.path));
  const pre = 'You are helping understand and refactor Oracle PL/SQL. The files below are machine-generated cards (static analysis, no LLM). Treat the original source embedded in each card as the truth; do not guess anything marked UNRESOLVED, EXTERNAL or UNKNOWN — ask instead.\n\n';
  const body = pre + on.map(i => `<file path="${i.path}">\n${i.text}\n</file>`).join('\n\n');
  const total = tok(body), pct = Math.min(100, total / S.budget * 100);
  box.innerHTML = `<h3>Prompt pack for ${esc(n.id.replace(/^TABLE:/, ''))}</h3>
    <div style="display:flex;gap:8px;align-items:center"><button class="big" id="copypack">Copy prompt pack</button><b id="packtok">~${fmt(total)} tokens</b>
    <label style="margin-left:auto">budget <select id="budget">${[16000, 32000, 64000, 128000, 200000].map(b => `<option value="${b}" ${b === S.budget ? 'selected' : ''}>${fmt(b)}</option>`).join('')}</select></label></div>
    <div class="meter"><i style="width:${pct}%"></i></div>${total > S.budget ? `<div style="color:var(--red)">Over budget by ~${fmt(total - S.budget)} tokens — untick items or lower the depth.</div>` : ''}
    ${items.map((i, k) => `<label class="packrow"><input type="checkbox" data-p="${esc(i.path)}" ${S.packOff.has(i.path) ? '' : 'checked'}> ${esc(i.label)}<span class="tok">${fmt(tok(i.text))}</span></label>`).join('')}
    <h3>Preview</h3><textarea id="packtext" readonly></textarea>`;
  $('#packtext').value = body;
  $('#copypack').onclick = async () => { try { await navigator.clipboard.writeText(body); } catch (e) { const t = $('#packtext'); t.select(); document.execCommand('copy'); } toast(`Copied ${fmt(total)} tokens (${on.length} files)`); };
  $('#budget').onchange = e => { S.budget = +e.target.value; renderPack(n); };
  $$('#tab-pack input[type=checkbox]').forEach(cb => cb.onchange = () => { cb.checked ? S.packOff.delete(cb.dataset.p) : S.packOff.add(cb.dataset.p); renderPack(n); });
}

/* ---------------------------------------------------------------- other views */
function renderViews() { renderVerdict(); renderTables(); renderOrder(); renderCoverage(); renderConfidence(); if (S.view === 'files') renderFiles(); if (S.view === 'er') renderER(); }

/* ---------------------------------------------------------------- verdict */
const VERDICT_TEXT = { VERIFIED: 'Verified', VERIFIED_WITH_WARNINGS: 'Verified with warnings', NOT_VERIFIED: 'Not verified' };
function renderVerdict() {
  const V = S.V, box = $('#verdict');
  if (!V) { box.innerHTML = '<div style="color:var(--dim)">No verification report in this folder yet — run a build.</div>'; return; }
  const counts = { routines: V.routines, files: V.files, cards: S.IDX.routines.length, tables: Object.keys(S.IDX.tables || {}).length, risks: S.IDX.routines.reduce((a, r) => a + (r.risks || []).length, 0) };
  box.innerHTML = `<div class="banner ${V.verdict}"><div><h1>${VERDICT_TEXT[V.verdict] || V.verdict}</h1><div style="max-width:620px;margin-top:4px">${esc(V.summary)}</div></div>
    <div class="cards" style="margin:0 0 0 auto"><div class="stat"><b style="color:${scoreColor(V.confidence)}">${V.confidence}</b><span>parse confidence · grade ${V.grade}</span></div>
    <div class="stat"><b>${counts.files}</b><span>files</span></div><div class="stat"><b>${counts.routines}</b><span>routines</span></div><div class="stat"><b>${counts.tables}</b><span>tables</span></div></div></div>
    <h3>Cross-checks</h3><div style="color:var(--dim);margin-bottom:6px">Each check re-reads the folder this tool wrote and compares it with your input files.</div>
    ${V.checks.map(c => `<div class="chk"><span class="st ${c.status}">${c.status}</span><div><b>${esc(c.title)}</b><div style="color:var(--dim)">${esc(c.evidence)}</div></div>${(c.details || []).length && c.status !== 'PASS' ? `<ul>${c.details.slice(0, 12).map(d => `<li>${esc(d)}</li>`).join('')}${c.details.length > 12 ? `<li>… ${c.details.length - 12} more</li>` : ''}</ul>` : ''}</div>`).join('')}
    <h3>What was written</h3><div id="written" style="color:var(--dim)">…</div>
    <h3>Hand this to your coding agent</h3><div><a class="go" href="/api/file/AGENTS.md" target="_blank">AGENTS.md</a> — reading order and rules · <a class="go" href="/api/file/analysis/verification.md" target="_blank">verification.md</a> · <a class="go" href="/api/file/analysis/confidence.md" target="_blank">confidence.md</a> · <a class="go" href="/api/file/analysis/file-dependencies.md" target="_blank">file-dependencies.md</a> · <a class="go" href="/api/file/analysis/er-diagram.md" target="_blank">er-diagram.md</a></div>`;
  fetch('/api/files', { cache: 'no-store' }).then(r => r.json()).then(fs => {
    const by = {}; for (const f of fs) { const k = f.includes('/') ? f.split('/')[0] : '(root)'; by[k] = (by[k] || 0) + 1; }
    const el = $('#written'); if (el) el.textContent = fs.length + ' files: ' + Object.entries(by).map(([k, v]) => `${k} ${v}`).join(' · ');
  }).catch(() => { });
}

/* ---------------------------------------------------------------- file map */
let fcy = null;
function renderFiles() {
  const G = S.G; if (!G) return;
  if (!fcy) fcy = cytoscape({
    container: $('#filecy'), wheelSensitivity: 0.25, minZoom: 0.2, maxZoom: 3,
    style: [
      { selector: 'node', style: { shape: 'round-rectangle', 'background-color': '#16233a', 'border-width': 1.5, 'border-color': '#5aa9ff', label: 'data(label)', color: '#d5dcea', 'font-size': 11, 'text-wrap': 'wrap', 'text-valign': 'center', 'text-halign': 'center', width: 'label', height: 'label', padding: 12, 'underlay-color': '#5aa9ff', 'underlay-opacity': 0.12, 'underlay-padding': 6 } },
      { selector: 'node.sel', style: { 'border-color': '#f2c14e', 'underlay-color': '#f2c14e', 'underlay-opacity': 0.4, 'border-width': 3 } },
      { selector: 'edge', style: { width: 'mapData(w, 1, 20, 1.5, 6)', 'curve-style': 'bezier', 'line-color': '#5aa9ff', 'target-arrow-color': '#5aa9ff', 'target-arrow-shape': 'triangle', label: 'data(label)', color: '#8794ad', 'font-size': 9, 'text-background-color': '#0b0f17', 'text-background-opacity': 0.8, 'text-background-padding': 2 } },
      { selector: 'edge[type="SPEC_BODY"]', style: { 'line-style': 'dashed', 'line-color': '#4fd8e8', 'target-arrow-color': '#4fd8e8' } },
      { selector: 'edge[type="NEEDS_DDL"]', style: { 'line-color': '#4cd694', 'target-arrow-color': '#4cd694' } },
      { selector: 'edge[type="SHARED_TABLE"]', style: { 'line-style': 'dotted', 'line-color': '#f2c14e', 'target-arrow-shape': 'none', 'source-arrow-shape': 'none' } }
    ]
  });
  const els = [];
  for (const f of G.fileNodes) els.push({ group: 'nodes', data: { id: f.file, label: f.file.split('/').pop() + '\n' + f.routines + ' routines · ' + f.lines + ' lines' } });
  G.fileEdges.forEach((e, i) => els.push({ group: 'edges', data: { id: 'e' + i, source: e.from, target: e.to, type: e.type, w: e.count, label: e.type === 'CALLS' ? e.count + ' call(s)' : e.type === 'SPEC_BODY' ? 'spec' : e.type === 'NEEDS_DDL' ? 'tables' : e.count + ' table(s)' } }));
  fcy.batch(() => { fcy.elements().remove(); fcy.add(els); });
  fcy.resize();   // the canvas was hidden behind another view
  refit(fcy, { name: 'dagre', rankDir: 'LR', nodeSep: 30, rankSep: 110, animate: true, animationDuration: 600, fit: true, padding: 40 });
  fcy.off('tap', 'node'); fcy.on('tap', 'node', e => showFile(e.target.id()));
  $('#filelegend').innerHTML = '<span style="color:#5aa9ff">→ calls</span><span style="color:#4fd8e8">- - spec ↔ body</span><span style="color:#4cd694">→ uses tables created in</span><span style="color:#f2c14e">··· shares a table</span>';
  showFile(null);
}
function showFile(id) {
  const G = S.G; if (fcy) { fcy.nodes().removeClass('sel'); if (id) fcy.getElementById(id).addClass('sel'); }
  let h = `<h3>Reading order</h3><div style="color:var(--dim);margin-bottom:4px">Dependencies first.</div><ol style="padding-left:20px;margin:0">${G.fileOrder.map(f => `<li><a class="go" data-file="${esc(f)}">${esc(f)}</a></li>`).join('')}</ol>`;
  if (G.fileCycles.length) h += `<div style="color:var(--amber);margin-top:6px">Mutually dependent: ${G.fileCycles.map(c => c.map(esc).join(' ↔ ')).join('; ')}</div>`;
  if (id) {
    const f = G.fileNodes.find(x => x.file === id) || {};
    h = `<h3>${esc(id)}</h3><div style="color:var(--dim)">${esc((f.units || []).join(', '))}</div>
      <h3>Depends on</h3>${(f.dependsOn || []).map(x => `<div><a class="go" data-file="${esc(x)}">${esc(x)}</a></div>`).join('') || '—'}
      <h3>Used by</h3>${(f.usedBy || []).map(x => `<div><a class="go" data-file="${esc(x)}">${esc(x)}</a></div>`).join('') || '—'}
      <h3>Relations</h3>${G.fileEdges.filter(e => e.from === id || e.to === id).map(e => `<div><b>${e.type === 'CALLS' ? 'calls' : e.type === 'SPEC_BODY' ? 'spec/body' : e.type === 'NEEDS_DDL' ? 'uses tables created in' : 'shared table'}</b> ${esc(e.from === id ? '→ ' + e.to : '← ' + e.from)}<div class="cc">${e.evidence.slice(0, 4).map(esc).join('<br>')}</div></div>`).join('') || '—'}
      <h3>Routines in this file</h3>${S.IDX.routines.filter(r => r.file === id).map(r => `<div class="item" data-id="${esc(r.id)}" style="padding-left:0"><span class="nm">${esc(r.id)}</span><span class="cc">cc${r.cyclomatic}</span></div>`).join('') || '—'}
      <div style="margin-top:8px"><a class="go" data-file="">← reading order</a></div>`;
  }
  $('#filepanel').innerHTML = h;
}

/* ---------------------------------------------------------------- ER diagram */
let ecy = null, erState = { declared: true, inferred: true, columns: true, cluster: 0 };
function erLabel(e) {
  if (!erState.columns) return e.key;
  const rows = e.columns.slice(0, 14).map(c => (c.pk ? 'PK ' : c.fk ? 'FK ' : c.unique ? 'UK ' : '   ') + c.name + (c.type ? '  ' + c.type : ''));
  if (e.columns.length > 14) rows.push('   … +' + (e.columns.length - 14) + ' more');
  return e.key + '\n' + '─'.repeat(Math.max(12, e.key.length)) + '\n' + (rows.join('\n') || '(no columns known)');
}
function renderER() {
  const ER = S.ER; if (!ER) return;
  const sel = $('#ercluster'), multi = ER.clusters.map((c, i) => ({ c, i })).filter(x => x.c.length > 1);
  sel.innerHTML = `<option value="-1">All tables (${ER.entities.length})</option>` + ER.clusters.map((c, i) => `<option value="${i}">${c.length > 1 ? 'Group: ' : 'Alone: '}${esc(c.slice(0, 3).join(', '))}${c.length > 3 ? ' +' + (c.length - 3) : ''}</option>`).join('');
  sel.value = String(erState.cluster);
  if (!ecy) ecy = cytoscape({
    container: $('#ercy'), wheelSensitivity: 0.25, minZoom: 0.1, maxZoom: 2.5,
    style: [
      { selector: 'node', style: { shape: 'round-rectangle', 'background-color': '#111826', 'border-width': 2, 'border-color': '#4cd694', label: 'data(label)', color: '#d5dcea', 'font-family': 'Consolas, monospace', 'font-size': 10, 'text-wrap': 'wrap', 'text-valign': 'center', 'text-halign': 'center', 'text-justification': 'left', width: 'label', height: 'label', padding: 12, 'underlay-color': '#4cd694', 'underlay-opacity': 0.1, 'underlay-padding': 6 } },
      { selector: 'node[!ddl]', style: { 'border-style': 'dashed', 'border-color': '#f2c14e', 'underlay-color': '#f2c14e' } },
      { selector: 'node.sel', style: { 'underlay-opacity': 0.45, 'border-width': 3 } },
      { selector: 'edge', style: { width: 2, 'curve-style': 'bezier', 'line-color': '#7fb8ff', 'source-arrow-shape': 'circle', 'source-arrow-color': '#7fb8ff', 'target-arrow-shape': 'tee', 'target-arrow-color': '#7fb8ff', label: 'data(label)', color: '#8794ad', 'font-size': 9, 'text-background-color': '#0b0f17', 'text-background-opacity': 0.85, 'text-background-padding': 2, 'text-rotation': 'autorotate' } },
      { selector: 'edge[origin="INFERRED"]', style: { 'line-style': 'dashed', 'line-color': '#b48cff', 'source-arrow-color': '#b48cff', 'target-arrow-color': '#b48cff', color: '#b48cff' } },
      { selector: 'edge[card="UNKNOWN"]', style: { 'target-arrow-shape': 'circle', 'target-arrow-color': '#b48cff' } }
    ]
  });
  const keep = erState.cluster >= 0 ? new Set(ER.clusters[erState.cluster] || []) : null;
  const ents = ER.entities.filter(e => !keep || keep.has(e.key)), ks = new Set(ents.map(e => e.key));
  const rels = ER.relations.filter(r => ks.has(r.child) && ks.has(r.parent) && (erState[r.origin.toLowerCase()]));
  const els = ents.map(e => ({ group: 'nodes', data: { id: e.key, label: erLabel(e), ddl: e.ddl } }))
    .concat(rels.map((r, i) => ({ group: 'edges', data: { id: 'r' + i, source: r.child, target: r.parent, origin: r.origin, card: r.cardinality, label: (r.origin === 'INFERRED' ? 'inferred · ' : '') + r.name } })));
  ecy.batch(() => { ecy.elements().remove(); ecy.add(els); });
  ecy.resize();
  refit(ecy, { name: 'fcose', animate: true, animationDuration: 900, randomize: true, quality: 'default', nodeRepulsion: () => 22000, idealEdgeLength: () => 190, packComponents: true, nodeDimensionsIncludeLabels: true, fit: true, padding: 40 });
  ecy.off('tap', 'node'); ecy.on('tap', 'node', e => showEntity(e.target.id()));
  $('#erstat').textContent = `${ents.length} tables · ${rels.length} relationships`;
  $('#erlegend').innerHTML = '<span style="color:#4cd694">▭ solid green: DDL found</span><span style="color:#f2c14e">▭ dashed amber: no DDL (columns only as used by the code)</span><span style="color:#7fb8ff">○─┤ declared FK (many → one)</span><span style="color:#b48cff">○- -┤ inferred from join</span>';
  showEntity(null);
}
function showEntity(key) {
  const ER = S.ER; if (ecy) { ecy.nodes().removeClass('sel'); if (key) ecy.getElementById(key).addClass('sel'); }
  let h = ER.notes.length ? ER.notes.map(n => `<div style="color:var(--dim);margin-bottom:6px">${esc(n)}</div>`).join('') : '';
  h += `<h3>Relationships (${ER.relations.length})</h3>${ER.relations.slice(0, 80).map(r => `<div style="margin:3px 0"><a class="go" data-ent="${esc(r.child)}">${esc(r.child)}</a> → <a class="go" data-ent="${esc(r.parent)}">${esc(r.parent)}</a> <span class="badge ${r.origin === 'DECLARED' ? 'LOW' : ''}">${r.origin.toLowerCase()}</span><div class="cc">${esc(r.childColumns.join(','))} → ${esc(r.parentColumns.join(','))} · ${esc(r.cardinality.replace(/_/g, ' ').toLowerCase())}</div></div>`).join('') || '<div style="color:var(--dim)">None found.</div>'}`;
  if (key) {
    const e = ER.entities.find(x => x.key === key); if (!e) return;
    h = `<h3>${esc(e.key)}</h3><div style="color:var(--dim)">${e.ddl ? 'DDL: ' + esc(e.file) + ':' + e.line : 'No CREATE TABLE in the scanned files — columns below are only those the code uses.'}</div>
      <table><tr><th></th><th>Column</th><th>Type</th><th>Used</th></tr>${e.columns.map(c => `<tr><td>${c.pk ? 'PK' : c.fk ? 'FK' : c.unique ? 'UK' : ''}</td><td>${esc(c.name)}${c.notNull ? ' <span class="cc">not null</span>' : ''}</td><td class="cc">${esc(c.type || '?')}</td><td class="cc">${c.read ? 'R' : ''}${c.written ? 'W' : ''}${c.inDdl ? '' : ' ·not in DDL'}</td></tr>`).join('')}</table>
      <h3>Written by</h3>${e.writtenBy.map(x => `<div><a class="go" data-go="${esc(x)}">${esc(x)}</a></div>`).join('') || '—'}
      <h3>Read by</h3>${e.readBy.map(x => `<div><a class="go" data-go="${esc(x)}">${esc(x)}</a></div>`).join('') || '—'}
      <h3>Relationships</h3>${ER.relations.filter(r => r.child === key || r.parent === key).map(r => `<div><b>${esc(r.name)}</b> <span class="badge">${r.origin.toLowerCase()}</span><div class="cc">${esc(r.child)}(${esc(r.childColumns.join(','))}) → ${esc(r.parent)}(${esc(r.parentColumns.join(','))})</div><div class="cc">${r.evidence.slice(0, 3).map(esc).join('<br>')}</div></div>`).join('') || '—'}
      <div style="margin-top:8px"><a class="go" data-ent="">← overview</a></div>`;
  }
  $('#erpanel').innerHTML = h;
}
function renderTables() {
  const imp = S.G.tableImpact || {}, miss = new Set(S.C.missing.filter(m => m.kind === 'TABLE_DDL_MISSING').map(m => m.name));
  $('#tables').innerHTML = `<h3>Table impact</h3><table><tr><th>Table</th><th>Writers</th><th>Readers</th><th>Reach a writer via calls</th><th>DDL in scanned files?</th></tr>` +
    Object.entries(imp).map(([t, i]) => `<tr><td><a class="go" data-go="TABLE:${esc(t)}">${esc(t)}</a></td><td>${i.writers.map(idLink).join(', ') || '—'}</td><td>${i.readers.length}</td><td>${i.transitiveWriters.length}</td><td style="color:${miss.has(t) ? 'var(--amber)' : 'var(--green)'}">${miss.has(t) ? 'missing' : 'yes'}</td></tr>`).join('') + '</table>';
}
function renderOrder() {
  const cyc = S.G.cycles || [];
  $('#order').innerHTML = `<h3>Understanding order — callees before callers</h3><div style="color:var(--dim);margin-bottom:8px">Batch 0 calls nothing else in the scanned code. Work left to right.</div>` +
    (cyc.length ? `<div style="margin-bottom:10px;color:var(--amber)">Mutually recursive groups: ${cyc.map(c => c.map(idLink).join(' ↔ ')).join(' · ')}</div>` : '') +
    `<div class="board">${(S.G.batches || []).map((b, i) => `<div class="col"><h4>Batch ${i} <span class="cc">${b.length}</span></h4>${b.map(id => { const n = S.byId.get(id), sc = S.conf.get(id)?.score; return `<div class="rcard" data-go="${esc(id)}"><span class="rk ${n ? topRisk({ risks: n.risks }) : ''}"></span><span class="nm" style="flex:1;overflow:hidden;text-overflow:ellipsis">${esc(id)}</span><span class="cc" style="color:${scoreColor(sc)}">${sc ?? ''}</span><span class="cc">cc${n?.cyclomatic ?? ''}</span></div>`; }).join('')}</div>`).join('')}</div>`;
}
function renderCoverage() {
  const cov = S.G.coverage || [], un = cov.reduce((a, c) => a + (c.unaccounted || []).reduce((s, r) => s + r[1] - r[0] + 1, 0), 0), code = cov.reduce((a, c) => a + c.codeLines, 0);
  $('#coverage').innerHTML = `<div class="cards"><div class="stat"><b>${cov.length}</b><span>files</span></div><div class="stat"><b>${code}</b><span>code lines</span></div><div class="stat"><b style="color:${un ? 'var(--red)' : 'var(--green)'}">${un}</b><span>unaccounted lines</span></div><div class="stat"><b>${S.C.files.filter(f => f.findings.some(x => x.code === 'SYNTAX_ERRORS')).length}</b><span>files with syntax errors</span></div></div>
    <table><tr><th>File</th><th>Code lines</th><th>In units</th><th>Listed, not decomposed</th><th>Unaccounted</th><th>Coverage</th></tr>${cov.map(c => {
    const u = (c.unaccounted || []).reduce((s, r) => s + r[1] - r[0] + 1, 0), pct = c.codeLines ? 100 * (c.codeLines - u) / c.codeLines : 100;
    return `<tr><td>${esc(c.file)}</td><td>${c.codeLines}</td><td>${c.inUnits}</td><td>${c.inSkipped}</td><td style="color:${u ? 'var(--red)' : 'var(--dim)'}">${u ? (c.unaccounted || []).map(r => r[0] === r[1] ? r[0] : r[0] + '-' + r[1]).join(', ') : '0'}</td><td><div class="bar ${u ? 'bad' : ''}"><i style="width:${pct}%"></i></div> ${pct.toFixed(1)}%</td></tr>`;
  }).join('')}</table>`;
}
let confFilter = new Set();
function renderConfidence() {
  const C = S.C, R = 54, circ = 2 * Math.PI * R, col = scoreColor(C.overall);
  const kinds = Object.entries(C.missingByKind || {});
  const miss = C.missing.filter(m => !confFilter.size || confFilter.has(m.kind));
  $('#confidence').innerHTML = `<div style="display:flex;gap:24px;align-items:center;flex-wrap:wrap;margin-bottom:16px">
    <svg width="140" height="140" viewBox="0 0 140 140"><circle cx="70" cy="70" r="${R}" fill="none" stroke="#1b2540" stroke-width="12"/><circle id="ring" cx="70" cy="70" r="${R}" fill="none" stroke="${col}" stroke-width="12" stroke-linecap="round" stroke-dasharray="${circ}" stroke-dashoffset="${circ}" transform="rotate(-90 70 70)" style="transition:stroke-dashoffset 1.2s ease;filter:drop-shadow(0 0 6px ${col})"/><text x="70" y="68" text-anchor="middle" fill="${col}" font-size="34" font-weight="700">${C.overall}</text><text x="70" y="90" text-anchor="middle" fill="#8794ad" font-size="13">grade ${C.grade}</text></svg>
    <div><h3 style="margin-top:0">Parse confidence</h3><div style="max-width:560px;color:var(--dim)">How far the analysis can be trusted — not how good the code is. Routines ${C.routineAverage} · files ${C.fileAverage}. Every lost point is listed with its reason; missing artifacts are things the code needs that were not in the scanned files.</div></div>
    <div class="cards" style="margin:0">${SEV.slice(0, 3).map(s => `<div class="stat"><b style="color:${s === 'HIGH' ? 'var(--red)' : s === 'MEDIUM' ? 'var(--amber)' : 'var(--cyan)'}">${C.missing.filter(m => m.severity === s).length}</b><span>${s.toLowerCase()} missing</span></div>`).join('')}</div></div>
    <h3>Missing artifacts (${miss.length})</h3><div class="chips" style="margin-bottom:8px">${kinds.map(([k, v]) => `<span class="chip ${confFilter.has(k) ? 'on' : ''}" data-ck="${k}">${k} ${v}</span>`).join('')}</div>
    <table><tr><th>Severity</th><th>Kind</th><th>Name</th><th>What it means</th><th>Used by</th></tr>${miss.map(m => `<tr><td><span class="badge ${m.severity}">${m.severity}</span></td><td>${esc(m.kind)}</td><td><b>${esc(m.name)}</b>${m.file ? `<br><span class="cc">${esc(m.file)}${m.line ? ':' + m.line : ''}</span>` : ''}</td><td>${esc(m.detail)}</td><td>${(m.usedBy || []).slice(0, 5).map(idLink).join(', ')}${(m.usedBy || []).length > 5 ? ' …' : ''}</td></tr>`).join('') || '<tr><td colspan="5">Nothing missing.</td></tr>'}</table>
    <h3>Least certain routines</h3><table><tr><th>Routine</th><th>Score</th><th>Why</th></tr>${C.routines.filter(r => r.score < 100).slice(0, 40).map(r => `<tr><td>${idLink(r.id)}</td><td style="color:${scoreColor(r.score)}"><b>${r.score}</b> ${r.grade}</td><td>${r.findings.slice(0, 5).map(f => `<span class="badge" title="${esc(f.message)}">${esc(f.code)}${f.line ? ' L' + f.line : ''} −${f.penalty}</span>`).join('')}</td></tr>`).join('') || '<tr><td colspan="3">Every routine scored 100.</td></tr>'}</table>
    <h3>Files</h3><table><tr><th>File</th><th>Score</th><th>Why</th></tr>${C.files.map(f => `<tr><td>${esc(f.id)}</td><td style="color:${scoreColor(f.score)}"><b>${f.score}</b> ${f.grade}</td><td>${f.findings.map(x => `<span class="badge" title="${esc(x.message)}">${esc(x.code)} −${x.penalty}</span>`).join('') || '—'}</td></tr>`).join('')}</table>`;
  requestAnimationFrame(() => requestAnimationFrame(() => { const r = $('#ring'); if (r) r.style.strokeDashoffset = circ * (1 - C.overall / 100); }));
}

/* ---------------------------------------------------------------- wiring */
function switchView(v) {
  S.view = v;
  $$('#views button').forEach(b => b.classList.toggle('on', b.dataset.view === v));
  $$('.view').forEach(x => x.classList.toggle('on', x.id === v));
  if (v === 'explore' && cy) setTimeout(() => { cy.resize(); cy.fit(undefined, 40); }, 30);
  if (v === 'confidence') renderConfidence();
  if (v === 'verdict') renderVerdict();
  if (v === 'files' && S.G) setTimeout(renderFiles, 30);
  if (v === 'er' && S.ER) setTimeout(renderER, 30);
}
document.addEventListener('click', e => {
  const t = e.target;
  let el;
  if ((el = t.closest('#views button'))) return switchView(el.dataset.view);
  if ((el = t.closest('[data-go]'))) return select(el.dataset.go);
  if ((el = t.closest('[data-file]'))) return showFile(el.dataset.file || null);
  if ((el = t.closest('[data-ent]'))) return showEntity(el.dataset.ent || null);
  if ((el = t.closest('#erbar .chip[data-er]'))) {
    const k = el.dataset.er === 'COLUMNS' ? 'columns' : el.dataset.er.toLowerCase();
    erState[k] = !erState[k]; el.classList.toggle('on', erState[k]); return renderER();
  }
  if (t.closest('#ercopy')) return copyMermaid();
  if ((el = t.closest('#kindchips .chip'))) { S.kinds[el.dataset.k] = S.kinds[el.dataset.k] ? 0 : 1; renderChips(); return drawGraph(true); }
  if ((el = t.closest('#riskchips .chip[data-r]'))) { const r = el.dataset.r; S.risk.has(r) ? S.risk.delete(r) : S.risk.add(r); renderChips(); return renderTree(); }
  if ((el = t.closest('[data-ck]'))) { const k = el.dataset.ck; confFilter.has(k) ? confFilter.delete(k) : confFilter.add(k); return renderConfidence(); }
  if ((el = t.closest('.gh'))) { const g = el.parentElement.dataset.g; S.closed.has(g) ? S.closed.delete(g) : S.closed.add(g); return renderTree(); }
  if ((el = t.closest('.item[data-id]'))) return select(el.dataset.id);
  if ((el = t.closest('#modeseg button'))) { setMode(el.dataset.mode); return drawGraph(true); }
  if ((el = t.closest('#cardtabs button'))) { $$('#cardtabs button').forEach(b => b.classList.toggle('on', b === el)); $$('.tab').forEach(x => x.classList.toggle('on', x.id === 'tab-' + el.dataset.tab)); return; }
  if ((el = t.closest('#outline .step'))) return activateStep(+el.dataset.i, false);
  if ((el = t.closest('#source .sl'))) { const i = stepAtLine(+el.dataset.n); if (i >= 0) activateStep(i, true); return; }
  if (t.closest('#fit')) return cy.animate({ fit: { padding: 40 } }, { duration: 500 });
  if (t.closest('#expand')) { $('#explore').classList.toggle('wide'); $('#expand').textContent = $('#explore').classList.contains('wide') ? '⛶ restore' : '⛶ expand'; setTimeout(() => { cy.resize(); cy.animate({ fit: { padding: 40 } }, { duration: 500 }); }, 30); }
});
$('#ercluster').addEventListener('change', e => { erState.cluster = +e.target.value; renderER(); });
async function copyMermaid() {
  try {
    const md = await getT('analysis/er-diagram.md');
    const blocks = [...md.matchAll(/```mermaid\n([\s\S]*?)```/g)].map(m => m[1]);
    const pick = erState.cluster >= 0 && S.ER.clusters.length > 1 ? blocks.find(b => (S.ER.clusters[erState.cluster] || []).some(k => b.includes(S.ER.entities.find(e => e.key === k)?.mermaid + ' {'))) || blocks.join('\n') : blocks.join('\n');
    try { await navigator.clipboard.writeText(pick); } catch (e) { const ta = document.createElement('textarea'); ta.value = pick; document.body.appendChild(ta); ta.select(); document.execCommand('copy'); ta.remove(); }
    toast('Mermaid ER source copied');
  } catch (e) { toast('Could not read er-diagram.md'); }
}
$('#search').addEventListener('input', e => { S.q = e.target.value.trim().toLowerCase(); renderTree(); });
$('#depth').addEventListener('change', e => { S.depth = +e.target.value; drawGraph(true); if (S.sel) renderPack(S.byId.get(S.sel)); });

/* ---------------------------------------------------------------- live build feed (what the tool is doing right now) */
const L = { state: 'idle', stages: [], files: [], total: 0, done: 0, log: [], verdict: null, message: null, t0: 0, showResult: false, finishedAt: 0 };
const STAGES = [['discover', 'Find files'], ['parse', 'Parse'], ['analyse', 'Link & analyse'], ['write', 'Write docs'], ['verify', 'Cross-check']];
let clock = null;
function hydrate(s) {
  Object.assign(L, { state: s.state, stages: s.stages || [], files: s.files || [], total: s.total || 0, done: s.done || 0, log: s.log || [], verdict: s.verdict, message: s.message, t0: Date.now() - (s.elapsedMs || 0) });
}
function renderLive() {
  const o = $('#live-overlay'), show = L.state === 'building' || L.showResult;
  o.hidden = !show;
  if (!show) { clearInterval(clock); clock = null; return; }
  if (!clock) clock = setInterval(() => { if (L.state === 'building') $('#liveclock').textContent = ((Date.now() - L.t0) / 1000).toFixed(1) + ' s'; }, 200);
  $('#livetitle').textContent = L.state === 'building' ? 'Reading your PL/SQL…' : L.state === 'failed' ? 'Build failed' : (VERDICT_TEXT[L.verdict] || 'Done');
  $('#livestages').innerHTML = STAGES.map(([id, name]) => { const st = L.stages.find(x => x.id === id); return `<span class="stg ${st ? st.status : ''}">${st && st.status === 'done' ? '✓ ' : ''}${name}</span>`; }).join('');
  const idx = Math.max(0, L.stages.length - 1), pct = L.state === 'done' ? 100 : Math.min(98, idx * 20 + (L.stages.length && L.stages[L.stages.length - 1].id === 'parse' && L.total ? 20 * L.done / L.total : 0));
  $('#livefill').style.width = pct + '%';
  $('#livefiles').innerHTML = L.files.map(f => `<span class="lf ${f.errors ? 'bad' : ''}">${f.errors ? '✗' : '✓'} ${esc(f.file)}</span>`).join('');
  const lg = $('#livelog'); lg.textContent = L.log.slice(-80).join('\n'); lg.scrollTop = lg.scrollHeight;
  const r = $('#liveresult'); r.hidden = !L.showResult;
  if (L.showResult) r.innerHTML = L.state === 'failed'
    ? `<div class="banner NOT_VERIFIED"><div><h1>Build failed</h1><div>${esc(L.message || '')}</div></div><button id="liveclose" class="big" style="margin-left:auto">Close</button></div>`
    : `<div class="banner ${L.verdict}"><div><h1>${VERDICT_TEXT[L.verdict] || L.verdict}</h1><div>${esc(L.message || '')}</div></div><button id="liveclose" class="big" style="margin-left:auto">Open verdict</button></div>`;
}
function onProgress(ev) {
  switch (ev.type) {
    case 'snapshot': hydrate(ev.snapshot); break;
    case 'start': Object.assign(L, { state: 'building', stages: [], files: [], total: 0, done: 0, log: [], verdict: null, message: null, t0: Date.now(), showResult: false }); break;
    case 'stage': { const last = L.stages[L.stages.length - 1]; if (last) last.status = 'done'; L.stages.push({ id: ev.id, detail: ev.detail, status: 'running' }); L.log.push(`[${ev.id}] ${ev.detail}`); break; }
    case 'file': L.total = ev.total; L.done = Math.max(L.done, ev.done); L.files.push({ file: ev.file, errors: ev.errors }); break;
    case 'log': L.log.push(ev.line); break;
    case 'finished': {
      const last = L.stages[L.stages.length - 1]; if (last) last.status = ev.state === 'failed' ? 'failed' : 'done';
      Object.assign(L, { state: ev.state, verdict: ev.verdict, message: ev.message, showResult: true, finishedAt: Date.now() });
      renderLive(); afterFinish(); return;
    }
  }
  renderLive();
}
async function afterFinish() {
  try { await load(); } catch (e) { /* the failure is already shown in the overlay */ }
  if (L.state === 'done') { switchView('verdict'); setTimeout(() => { if (L.showResult) { L.showResult = false; renderLive(); } }, 4000); }
}
document.addEventListener('click', e => { if (e.target.closest('#liveclose')) { L.showResult = false; renderLive(); if (L.state === 'done') switchView('verdict'); } });

function connect() {
  const live = $('#live'), txt = $('#livetext'), es = new EventSource('/api/events');
  es.addEventListener('hello', () => { live.className = 'ok'; txt.textContent = 'live — watching folder'; });
  es.addEventListener('changed', async () => {
    if (L.state === 'building' || Date.now() - L.finishedAt < 5000) return;   // the build feed reloads when it finishes
    live.classList.add('flash'); txt.textContent = 'rebuild detected — refreshing…';
    try { await load(); toast('Folder changed — Explorer refreshed'); } catch (e) { toast('Refresh failed: ' + e.message); }
    live.classList.remove('flash'); txt.textContent = 'live — watching folder';
  });
  es.onerror = () => { live.className = ''; txt.textContent = 'disconnected — retrying…'; };
  const ps = new EventSource('/api/progress');
  ps.onmessage = m => { try { onProgress(JSON.parse(m.data)); } catch (e) { /* ignore malformed event */ } };
}
async function boot() {
  try { hydrate(await (await fetch('/api/status', { cache: 'no-store' })).json()); } catch (e) { /* no status endpoint: plain explore */ }
  connect();
  if (L.state === 'building') { renderLive(); return; }   // data arrives when the build finishes
  try { await load(); if (S.V) switchView('verdict'); } catch (e) { $('#empty').textContent = 'Nothing to show yet: ' + e.message + ' — run a build in this folder.'; $('#livetext').textContent = 'waiting for a build'; }
}
boot();
