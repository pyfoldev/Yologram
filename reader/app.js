// Читалка: аниме (AniLibria), книги и комиксы (Gutendex, Wikisource, Open Library, Google Books, Internet Archive)
const $ = s => document.querySelector(s);
const grid = $('#grid'), statusEl = $('#status'), reader = $('#reader'), rbody = $('#rbody');
const store = {
  get: (k, d) => { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } },
  set: (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
};
const j = async (url, opts) => {
  const r = await fetch(url, { signal: AbortSignal.timeout(15000), ...opts }).catch(e => { throw new Error(url.split('/')[2] + (e.name === 'TimeoutError' ? ': таймаут' : ': нет связи')); });
  if (!r.ok) throw new Error(url.split('/')[2] + ': ' + r.status);
  return r.json();
};
const qs = o => new URLSearchParams(o).toString();
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// ---------- Источники. Каждый возвращает [{id,title,sub,cover,open()}] ----------
const AL = 'https://anilibria.top';
const sources = {
  anilibria: {
    name: 'AniLibria',
    async list(q) {
      const d = q ? await j(`${AL}/api/v1/app/search/releases?${qs({ query: q })}`)
                  : (await j(`${AL}/api/v1/anime/catalog/releases?${qs({ page: 1, limit: 30 })}`)).data;
      return d.map(r => ({
        key: 'al' + r.id, title: r.name.main, sub: `${r.year} · ${r.type?.description ?? ''}`,
        cover: AL + (r.poster?.optimized?.preview || r.poster?.preview || r.poster?.src || ''),
        open: () => openAnime(r.id, r.name.main),
      }));
    },
  },
  gutendex: {
    name: 'Gutenberg',
    async list(q) {
      const d = await j(`https://gutendex.com/books?${qs({ languages: 'ru', ...(q && { search: q }) })}`);
      return d.results.map(b => {
        const html = b.formats['text/html'] || b.formats['text/html; charset=utf-8'];
        return {
          key: 'gt' + b.id, title: b.title, sub: b.authors.map(a => a.name).join(', '),
          cover: b.formats['image/jpeg'],
          open: () => html ? openFrame(b.title, html) : openLink(b.title, `https://www.gutenberg.org/ebooks/${b.id}`),
        };
      });
    },
  },
  wikisource: {
    name: 'Викитека',
    async list(q) {
      const d = await j(`https://ru.wikisource.org/w/api.php?${qs({ action: 'query', list: 'search', srsearch: q || 'Пушкин', srlimit: 30, format: 'json', origin: '*' })}`);
      return d.query.search.map(s => ({
        key: 'ws' + s.pageid, title: s.title, sub: s.snippet.replace(/<[^>]+>/g, '').slice(0, 80),
        open: () => openWiki(s.title),
      }));
    },
  },
  openlibrary: {
    name: 'Open Library',
    async list(q) {
      const d = await j(`https://openlibrary.org/search.json?${qs({ q: q || 'классика', language: 'rus', limit: 30, fields: 'key,title,author_name,cover_i,ia,first_publish_year' })}`);
      return d.docs.map(b => ({
        key: 'ol' + b.key, title: b.title, sub: (b.author_name || []).join(', '),
        cover: b.cover_i ? `https://covers.openlibrary.org/b/id/${b.cover_i}-M.jpg` : '',
        open: () => b.ia?.length ? openFrame(b.title, `https://archive.org/embed/${b.ia[0]}`) : openLink(b.title, 'https://openlibrary.org' + b.key),
      }));
    },
  },
  google: {
    name: 'Google Books',
    async list(q) {
      const key = store.get('gkey', '');
      const d = await j(`https://www.googleapis.com/books/v1/volumes?${qs({ q: q || 'классика', langRestrict: 'ru', maxResults: 30, filter: 'partial', ...(key && { key }) })}`);
      return (d.items || []).map(b => ({
        key: 'gb' + b.id, title: b.volumeInfo.title, sub: (b.volumeInfo.authors || []).join(', '),
        cover: b.volumeInfo.imageLinks?.thumbnail?.replace('http:', 'https:'),
        open: () => b.accessInfo?.embeddable ? openFrame(b.volumeInfo.title, `https://books.google.com/books?id=${b.id}&output=embed`) : openLink(b.volumeInfo.title, b.volumeInfo.infoLink),
      }));
    },
  },
  archive: {
    name: 'Internet Archive',
    async list(q, tab) {
      const subj = tab === 'comics' ? ' AND (subject:comics OR subject:"комиксы" OR subject:manga)' : '';
      const lang = tab === 'comics' ? '' : ' AND language:(rus OR russian)';
      const query = `${q ? `(${q})` : '*'}${lang}${subj} AND mediatype:texts`;
      const d = await j(`https://archive.org/advancedsearch.php?${new URLSearchParams([['q', query], ['fl[]', 'identifier'], ['fl[]', 'title'], ['fl[]', 'creator'], ['rows', 30], ['sort[]', 'downloads desc'], ['output', 'json']])}`);
      return d.response.docs.map(b => ({
        key: 'ia' + b.identifier, title: b.title, sub: [].concat(b.creator || []).join(', '),
        cover: `https://archive.org/services/img/${b.identifier}`,
        open: () => openFrame(b.title, `https://archive.org/embed/${b.identifier}`),
      }));
    },
  },
};
const tabs = {
  anime: ['anilibria'],
  books: ['gutendex', 'wikisource', 'openlibrary', 'google', 'archive'],
  comics: ['archive'],
};

// ---------- Состояние и выдача ----------
let tab = 'anime', src = 'anilibria', seq = 0;
const bm = () => store.get('saved', []);

function renderCards(items) {
  const saved = new Set(bm().map(b => b.key));
  grid.replaceChildren(...items.map(it => {
    const c = document.createElement('div');
    c.className = 'card';
    c.innerHTML = (it.cover ? `<img loading="lazy" referrerpolicy="no-referrer" src="${esc(it.cover)}" alt="">` : '<div class="ph"></div>') +
      `<div>${saved.has(it.key) ? '★ ' : ''}${esc(it.title)}<small>${esc(it.sub)}</small></div>`;
    c.onclick = it.open;
    c.oncontextmenu = e => { e.preventDefault(); toggleSave(it); };
    let t; c.ontouchstart = () => t = setTimeout(() => toggleSave(it), 600); c.ontouchend = c.ontouchmove = () => clearTimeout(t);
    return c;
  }));
}
function toggleSave(it) {
  const list = bm(), i = list.findIndex(b => b.key === it.key);
  if (i >= 0) list.splice(i, 1); else list.push({ key: it.key, title: it.title, sub: it.sub, cover: it.cover, src, tab });
  store.set('saved', list);
  statusEl.textContent = i >= 0 ? 'Убрано из закладок' : 'Добавлено в закладки (★)';
  if (tab === 'saved') showSaved();
}
async function load() {
  const my = ++seq, q = $('#q').value.trim();
  statusEl.textContent = 'Загрузка…'; grid.replaceChildren();
  try {
    const items = await sources[src].list(q, tab);
    if (my !== seq) return;
    renderCards(items);
    statusEl.textContent = items.length ? '' : 'Ничего не найдено';
  } catch (e) {
    if (my === seq) statusEl.textContent = 'Ошибка: ' + e.message + '. Попробуйте другой источник.';
  }
}
function showSaved() {
  $('#sources').hidden = true;
  const list = bm();
  statusEl.textContent = list.length ? 'Долгое нажатие / правый клик — убрать' : 'Закладок пока нет: долгое нажатие / правый клик по карточке';
  renderCards(list.map(b => ({ ...b, open: async () => {
    // восстанавливаем открытие через источник по заголовку
    tab = b.tab; src = b.src; $('#q').value = b.title; setTab(b.tab, b.src); await load();
  } })));
}
function setTab(t, s) {
  tab = t; document.querySelectorAll('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === t));
  if (t === 'saved') return showSaved();
  src = s && tabs[t].includes(s) ? s : tabs[t][0];
  const box = $('#sources'); box.hidden = tabs[t].length < 2;
  box.replaceChildren(...tabs[t].map(k => {
    const b = document.createElement('button'); b.textContent = sources[k].name; b.classList.toggle('on', k === src);
    b.onclick = () => { src = k; [...box.children].forEach(x => x.classList.toggle('on', x === b)); load(); };
    return b;
  }));
  if (!s) load();
}
document.querySelectorAll('#tabs button').forEach(b => b.onclick = () => { $('#q').value = ''; setTab(b.dataset.tab); });
$('#search').onsubmit = e => { e.preventDefault(); if (tab !== 'saved') load(); };

// ---------- Читалка ----------
function openReader(title) { $('#rtitle').textContent = title; $('#rtools').replaceChildren(); reader.hidden = false; rbody.replaceChildren(); history.pushState({ r: 1 }, ''); }
function closeReader() { reader.hidden = true; rbody.replaceChildren(); }
$('#close').onclick = () => history.state?.r ? history.back() : closeReader();
addEventListener('popstate', closeReader);

function openFrame(title, url) {
  openReader(title);
  const f = document.createElement('iframe'); f.src = url; f.allowFullscreen = true; rbody.append(f);
  const a = document.createElement('a'); a.href = url; a.target = '_blank'; a.rel = 'noopener'; a.textContent = 'открыть отдельно'; $('#rtools').append(a);
}
function openLink(title, url) {
  openReader(title);
  rbody.innerHTML = `<div class="text"><p>Для этого издания нет встроенного просмотра.</p><p><a href="${esc(url)}" target="_blank" rel="noopener">Открыть на сайте источника</a></p></div>`;
}
async function openWiki(title) {
  openReader(title); rbody.innerHTML = '<div class="text">Загрузка…</div>';
  try {
    const d = await j(`https://ru.wikisource.org/w/api.php?${qs({ action: 'parse', page: title, prop: 'text', format: 'json', origin: '*', disableeditsection: 1, redirects: 1 })}`);
    const doc = new DOMParser().parseFromString(d.parse.text['*'], 'text/html');
    doc.querySelectorAll('script,style,iframe,object,link,.mw-editsection,.navbox').forEach(n => n.remove());
    doc.querySelectorAll('*').forEach(n => [...n.attributes].forEach(a => { if (/^on/i.test(a.name) || /^\s*javascript:/i.test(a.value)) n.removeAttribute(a.name); }));
    doc.querySelectorAll('a[href^="/"]').forEach(a => { a.href = 'https://ru.wikisource.org' + a.getAttribute('href'); a.target = '_blank'; });
    doc.querySelectorAll('img[src^="//"]').forEach(i => i.src = 'https:' + i.getAttribute('src'));
    const box = document.createElement('div'); box.className = 'text'; box.append(...doc.body.childNodes);
    rbody.replaceChildren(box);
    const size = store.get('fs', 18); box.style.fontSize = size + 'px';
    for (const [lbl, dlt] of [['A−', -2], ['A+', 2]]) {
      const b = document.createElement('button'); b.textContent = lbl;
      b.onclick = () => { const s = Math.min(32, Math.max(12, (store.get('fs', 18)) + dlt)); store.set('fs', s); box.style.fontSize = s + 'px'; };
      $('#rtools').append(b);
    }
  } catch (e) { rbody.innerHTML = `<div class="text">Ошибка: ${esc(e.message)}</div>`; }
}
let hls;
async function openAnime(id, title) {
  openReader(title); rbody.innerHTML = '<div class="text">Загрузка…</div>';
  try {
    const r = await j(`${AL}/api/v1/anime/releases/${id}`);
    const eps = (r.episodes || []).slice().sort((a, b) => a.ordinal - b.ordinal);
    const box = document.createElement('div'); box.className = 'player';
    const vid = document.createElement('video'); vid.controls = true; vid.playsInline = true;
    const list = document.createElement('div'); list.className = 'eps';
    const desc = document.createElement('p'); desc.textContent = r.description || '';
    box.append(vid, list, desc); rbody.replaceChildren(box);
    if (!eps.length) { list.textContent = 'Серий нет (возможно, релиз заблокирован по региону/правообладателю).'; return; }
    const last = store.get('ep' + id, eps[0].ordinal);
    const play = ep => {
      const url = ep.hls_1080 || ep.hls_720 || ep.hls_480; if (!url) return;
      store.set('ep' + id, ep.ordinal);
      [...list.children].forEach(b => b.classList.toggle('on', +b.dataset.o === ep.ordinal));
      hls?.destroy();
      if (window.Hls?.isSupported()) { hls = new Hls(); hls.loadSource(url); hls.attachMedia(vid); }
      else vid.src = url; // Safari: нативный HLS
      vid.play().catch(() => {});
    };
    for (const ep of eps) {
      const b = document.createElement('button'); b.textContent = ep.ordinal; b.dataset.o = ep.ordinal; b.onclick = () => play(ep); list.append(b);
    }
    vid.onended = () => { const n = eps.find(e => e.ordinal > store.get('ep' + id, 0)); if (n) play(n); };
    play(eps.find(e => e.ordinal === last) || eps[0]);
    vid.pause();
  } catch (e) { rbody.innerHTML = `<div class="text">Ошибка: ${esc(e.message)}</div>`; }
}

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
setTab('anime');
