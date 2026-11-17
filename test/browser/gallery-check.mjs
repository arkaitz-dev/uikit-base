// The gallery's behaviour, checked in a real engine: what no JVM test can see. Run against a
// gallery served by `clojure -M:gallery [port]`:
//
//   PLAYWRIGHT_CORE=<path to playwright-core/index.mjs> node test/browser/gallery-check.mjs <engine> <url>
//
// <engine> is `webkit` or `chromium` (CHROMIUM=<executable> to use one Playwright did not
// install). Each engine runs natively and with the gaps of an older browser of the floor
// forced: invoker commands cancelled (a `command` listener that prevents the native action,
// counted), `closedBy` deleted, anchor positioning faked away together with the stylesheet's
// anchor properties, which a browser without them would ignore. A dialog's `closedby` cannot be
// switched off from script, so the shim's decision is read from the Escape keydown's
// `defaultPrevented`. Prints `{engine version modes {mode {check {pass … seen …}}}}` as JSON.
const { webkit, chromium } = await import(process.env.PLAYWRIGHT_CORE || 'playwright-core');
const { inflateSync } = await import('node:zlib');

// The pixel rows of a PNG screenshot, RGB, so a check can see what no computed style says
// (a user agent's own image on a control's part).
function pixels(png) {
  let i = 8, w = 0, h = 0, bpp = 4; const idat = [];
  while (i < png.length) {
    const n = png.readUInt32BE(i), t = png.toString('latin1', i + 4, i + 8), c = png.subarray(i + 8, i + 8 + n); i += 12 + n;
    if (t === 'IHDR') { w = c.readUInt32BE(0); h = c.readUInt32BE(4); bpp = c[9] === 6 ? 4 : 3; }
    if (t === 'IDAT') idat.push(c);
  }
  const raw = inflateSync(Buffer.concat(idat)), st = w * bpp, rows = []; let prev = Buffer.alloc(st), o = 0;
  for (let y = 0; y < h; y++) {
    const f = raw[o++], line = Buffer.from(raw.subarray(o, o + st)); o += st;
    for (let x = 0; x < st; x++) {
      const a = x >= bpp ? line[x - bpp] : 0, b = prev[x], c = x >= bpp ? prev[x - bpp] : 0;
      const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      line[x] = (line[x] + [0, a, b, (a + b) >> 1, pa <= pb && pa <= pc ? a : pb <= pc ? b : c][f]) & 255;
    }
    rows.push(line); prev = line;
  }
  return { w, h, at: (x, y) => [...rows[y].subarray(x * bpp, x * bpp + 3)].join(',') };
}

const [engine, url = 'http://localhost:3010/'] = process.argv.slice(2);
const base = `window.__err=[];addEventListener("error",e=>window.__err.push(String(e.message)));window.__csp=[];document.addEventListener("securitypolicyviolation",e=>window.__csp.push(e.violatedDirective));
  window.__esc=[];window.addEventListener('keydown',e=>{if(e.key==='Escape')window.__esc.push(e.defaultPrevented);});`;
const noCommand = `window.__cmd=0;document.addEventListener('command',e=>{e.preventDefault();window.__cmd++;},true);
  delete HTMLButtonElement.prototype.commandForElement; delete HTMLButtonElement.prototype.command; delete HTMLDialogElement.prototype.closedBy;`;
const noAnchor = `{const s=CSS.supports.bind(CSS); CSS.supports=(a,b)=>/anchor/.test(String(a))?false:s(a,b);}
  document.addEventListener('DOMContentLoaded',()=>{const sh=new CSSStyleSheet();
    sh.replaceSync('.ui-dropdown__menu,.ui-popover,.ui-tooltip{position-area:none;position-anchor:auto;position-try-fallbacks:none}');
    document.adoptedStyleSheets=[...document.adoptedStyleSheets,sh];});`;
const noScope = `{const s=CSS.supports.bind(CSS); CSS.supports=(a,b)=>/anchor-scope/.test(String(a))?false:s(a,b);}`;
const modes = { native: '', 'older-floor': noCommand + noAnchor, ...(engine === 'chromium' ? { 'anchor-without-scope': noScope } : {}) };

const type = { webkit, chromium }[engine];
if (!type) { console.error('engine: webkit or chromium'); process.exit(2); }
const browser = await type.launch(process.env.CHROMIUM && engine === 'chromium' ? { executablePath: process.env.CHROMIUM } : {});
const result = { engine, version: browser.version(), modes: {} };

for (const [mode, init] of Object.entries(modes)) {
  const ctx = await browser.newContext({ viewport: { width: 1024, height: 768 } });
  await ctx.addInitScript(base + init);
  const p = await ctx.newPage();
  const errors = []; p.on('pageerror', e => errors.push(String(e)));
  await p.goto(url);
  const r = {};
  const check = async (name, f, pass) => {
    try { const seen = await f(); r[name] = { pass: pass(seen), seen }; }
    catch (e) { r[name] = { pass: false, seen: 'ERROR ' + String(e).split('\n')[0] }; }
  };
  const isOpen = id => p.evaluate(id => document.getElementById(id).open, id);
  const popOpen = id => p.evaluate(id => document.getElementById(id).matches(':popover-open'), id);
  const opener = p.locator('button[commandfor=g-dialog][command=show-modal]').first();

  await check('dialog: opens by keyboard, Esc closes, focus returns to its button', async () => {
    await opener.focus(); await p.keyboard.press('Enter');
    const open = await isOpen('g-dialog'), inside = await p.evaluate(() => document.getElementById('g-dialog').contains(document.activeElement));
    await p.keyboard.press('Escape');
    return { open, inside, closed: !(await isOpen('g-dialog')), focus: await p.evaluate(() => document.activeElement?.matches('button[command=show-modal][commandfor=g-dialog]')) };
  }, s => s.open && s.inside && s.closed && s.focus);
  await check('dialog: centred in the viewport, inside a ui-stack as a page puts it', async () => {
    // Measured in Chrome: `.ui-stack > *` zeroed the user agent's auto margins and the modal
    // sat at the top of the window; no check looked at where it sat.
    await opener.click();
    const r = await p.locator('#g-dialog').boundingBox(), vw = 1024, vh = 768;
    const inStack = await p.evaluate(() => !!document.getElementById('g-dialog').closest('.ui-stack'));
    await p.keyboard.press('Escape');
    return { inStack, dy: Math.round(r.y - (vh - r.height) / 2), dx: Math.round(r.x - (vw - r.width) / 2) };
  }, s => s.inStack && Math.abs(s.dy) <= 1 && Math.abs(s.dx) <= 1);
  await check('dialog: a press on the backdrop closes, a drag out of it does not, the x closes', async () => {
    await opener.click(); await p.mouse.click(5, 5);
    const press = !(await isOpen('g-dialog'));
    await opener.click();
    const inp = await p.locator('#g-dialog input:not([type=hidden])').first().boundingBox();
    await p.mouse.move(inp.x + 5, inp.y + inp.height / 2); await p.mouse.down(); await p.mouse.move(5, 5, { steps: 5 }); await p.mouse.up();
    const drag = await isOpen('g-dialog');
    await p.locator('#g-dialog .ui-close').click();
    return { press, drag, x: !(await isOpen('g-dialog')) };
  }, s => s.press && s.drag && s.x);
  await check('static dialog: two Esc and a backdrop press leave it open, the x closes it', async () => {
    await p.locator('button[commandfor=g-static][command=show-modal]').click();
    await p.evaluate(() => { window.__esc = []; });
    await p.keyboard.press('Escape'); await p.waitForTimeout(50); await p.keyboard.press('Escape'); await p.mouse.click(5, 5);
    const holds = await isOpen('g-static'), esc = await p.evaluate(() => window.__esc);
    await p.locator('#g-static .ui-close').click();
    return { holds, esc, x: !(await isOpen('g-static')) };
  }, s => s.holds && s.x);
  await check('static dialog: a popover inside closes on Esc, a modal stacked above closes on Esc', async () => {
    await p.evaluate(() => { const st = document.getElementById('g-static'); st.append(document.querySelector('.ui-popover-wrap'));
      const b = document.createElement('button'); b.id = 'stack'; b.setAttribute('commandfor', 'g-dialog'); b.setAttribute('command', 'show-modal'); st.append(b); });
    await p.locator('button[commandfor=g-static][command=show-modal]').click();
    await p.locator('#g-static button[popovertarget=g-pop]').click(); await p.waitForTimeout(80);
    await p.keyboard.press('Escape'); await p.waitForTimeout(50);
    const pop = !(await popOpen('g-pop')), held = await isOpen('g-static');
    await p.locator('#stack').click(); await p.waitForTimeout(50); await p.keyboard.press('Escape'); await p.waitForTimeout(50);
    const stacked = !(await isOpen('g-dialog')), still = await isOpen('g-static');
    await p.locator('#g-static .ui-close').click();
    return { pop, held, stacked, still };
  }, s => s.pop && s.held && s.stacked && s.still);
  await check('forms: fields apart by the kit\'s spacing without ui-form, a form of only a button left inline', async () => {
    // Measured in the user's Chrome: a post-form without ui-form drew its fields touching (0px).
    return await p.evaluate(() => {
      const f = document.querySelector('#forms form'), kids = [...f.children].filter(e => e.type !== 'hidden');
      const md = (() => { const d = document.createElement('div'); d.style.setProperty('inline-size', 'var(--ui-spacing-md)');
        document.body.append(d); const w = d.getBoundingClientRect().width; d.remove(); return w; })();
      const gaps = kids.slice(1).map((e, i) => Math.round(e.getBoundingClientRect().top - kids[i].getBoundingClientRect().bottom));
      // A form of only a button, an action in a row, is never made a stack of its own.
      const actions = [...document.querySelectorAll('form.ui-action')].map(a => getComputedStyle(a).display);
      return { uiForm: f.classList.contains('ui-form'), md: Math.round(md), gaps: [...new Set(gaps)], actions: [...new Set(actions)] };
    });
  }, s => !s.uiForm && s.md > 0 && s.gaps.length === 1 && s.gaps[0] === s.md && s.actions.length > 0 && !s.actions.includes('grid'));
  await check('button-group: an open confirm leaves its neighbours their own height', async () => {
    // Measured in academy: a confirm opened in a group stretched the Edit beside it.
    const g = p.locator('#g-group-confirm');
    await g.scrollIntoViewIfNeeded();
    const edit = g.locator('a.ui-btn').first(), before = (await edit.boundingBox()).height;
    await g.locator('summary').click(); await p.waitForTimeout(100);
    const open = await g.locator('details').evaluate(d => d.open), after = (await edit.boundingBox()).height;
    await g.locator('summary').click();
    return { open, before: Math.round(before), after: Math.round(after) };
  }, s => s.open && s.before > 0 && s.before === s.after);
  await check('meter: its fill and its track are the palette\'s, the fill the track\'s whole height', async () => {
    // Measured in the user's Chrome: the user agent's grey and yellow gradients showed under
    // the kit's colours, and Blink centred a fill half the track's height.
    // On a page of its own: Playwright's element screenshot injects a <style> in WebKit,
    // whatever its options (measured), which the CSP refuses; the gallery's page stays clean.
    const q = await ctx.newPage(); await q.goto(url);
    const m = q.locator('meter').first();
    await m.scrollIntoViewIfNeeded();
    const want = await q.evaluate(() => Object.fromEntries(['--ui-warning-border', '--ui-surface-alt'].map(v => {
      const d = document.createElement('div'); d.style.setProperty('background-color', `var(${v})`); document.body.append(d);
      const c = getComputedStyle(d).backgroundColor.match(/\d+/g).slice(0, 3).join(','); d.remove(); return [v, c]; })));
    const img = pixels(await m.screenshot()), mid = [2, Math.floor(img.h / 2), img.h - 3];
    await q.close();
    const fill = mid.map(y => img.at(Math.floor(img.w * 0.3), y)), track = mid.map(y => img.at(Math.floor(img.w * 0.9), y));
    return { fill, track, want };
  }, s => s.fill.every(c => c === s.want['--ui-warning-border']) && s.track.every(c => c === s.want['--ui-surface-alt']));
  await check('offcanvas: the end sheet at the inline end, full height', async () => {
    await p.locator('button[commandfor=g-end][command=show-modal]').click();
    const b = await p.locator('#g-end').boundingBox(); await p.keyboard.press('Escape');
    return { right: Math.round(b.x + b.width), height: Math.round(b.height), closed: !(await isOpen('g-end')) };
  }, s => s.right === 1024 && s.height === 768 && s.closed);
  await check('dropdown: below its button at the inline start, closed by Esc', async () => {
    const dd = p.locator('button[popovertarget=g-menu]');
    await dd.scrollIntoViewIfNeeded(); await dd.click(); await p.waitForTimeout(150);
    const b = await dd.boundingBox(), m = await p.locator('#g-menu').boundingBox();
    const open = await popOpen('g-menu'); await p.keyboard.press('Escape');
    return { open, below: Math.round(m.y - (b.y + b.height)), dx: Math.round(m.x - b.x), closed: !(await popOpen('g-menu')) };
  }, s => s.open && s.below >= 0 && s.below <= 8 && s.dx === 0 && s.closed);
  await check('tooltip: shown on hover and focus, above, reachable by the pointer, hidden on leave and Esc', async () => {
    const trig = p.locator('.ui-tooltip-wrap > :first-child');
    await trig.scrollIntoViewIfNeeded();
    const tb = await trig.boundingBox();
    await p.mouse.move(tb.x + tb.width / 2, tb.y + tb.height / 2); await p.waitForTimeout(150);
    const hover = await popOpen('g-tip'), t = await p.locator('#g-tip').boundingBox();
    await p.mouse.move(t.x + t.width / 2, t.y + t.height / 2, { steps: 4 }); await p.waitForTimeout(400);
    const reachable = await popOpen('g-tip');
    await p.mouse.move(5, 760); await p.waitForTimeout(400);
    const leaves = !(await popOpen('g-tip'));
    await trig.focus(); await p.waitForTimeout(100);
    const focus = await popOpen('g-tip'); await p.keyboard.press('Escape');
    return { hover, above: Math.round(tb.y - (t.y + t.height)), reachable, leaves, focus, esc: !(await popOpen('g-tip')) };
  }, s => s.hover && s.above >= 0 && s.above <= 8 && s.reachable && s.leaves && s.focus && s.esc);
  await check('carousel: no scrollbar of its own once its buttons drive it, one without them', async () => {
    // Measured in the user's Chrome: the viewport's overlay scrollbar flashed at every slide.
    // Without the script the row is scrolled by hand, and the bar says it scrolls.
    const bar = (label) => p.locator(`section.ui-carousel[aria-label=${label}] .ui-carousel__viewport`).evaluate(v => getComputedStyle(v).scrollbarWidth);
    const driven = await bar('Featured');
    await p.evaluate(() => { document.querySelector('section.ui-carousel[aria-label=Still] .ui-carousel__controls').hidden = true; });
    const bare = await bar('Still');
    await p.evaluate(() => { document.querySelector('section.ui-carousel[aria-label=Still] .ui-carousel__controls').hidden = false; });
    return { driven, bare };
  }, s => s.driven === 'none' && s.bare !== 'none');
  await check('carousel: rotating until the focus enters it, then two quick Next presses move two slides', async () => {
    const c = p.locator('section.ui-carousel[aria-label=Featured]');
    await c.scrollIntoViewIfNeeded(); await p.mouse.move(5, 5);
    const shown = await c.locator('.ui-carousel__controls').isVisible();
    const rotating = () => c.evaluate(el => el.hasAttribute('data-ui-rotating'));
    const before = await rotating();
    // The focus in its viewport stops the rotation for good, so no tick of its own races
    // the presses below (measured: one did, between reading `from` and the first press).
    await c.locator('.ui-carousel__viewport').focus(); await p.waitForTimeout(1300);
    const stopped = !(await rotating());
    const at = () => c.locator('.ui-carousel__viewport').evaluate(v => [Math.round(Math.abs(v.scrollLeft) / v.clientWidth), v.children.length]);
    const [from, n] = await at();
    await c.locator('.ui-carousel__next').click(); await c.locator('.ui-carousel__next').click(); await p.waitForTimeout(1300);
    return { shown, before, stopped, from, n, to: (await at())[0] };
  }, s => s.shown && s.before && s.stopped && s.to === (s.from + 2) % s.n);
  await check('toasts: the timed one goes after its time, the x removes the other', async () => {
    await p.mouse.move(5, 5); await p.waitForTimeout(8500);
    const timed = await p.locator('.ui-toast[data-timeout-ms]').count();
    await p.locator('.ui-toast .ui-toast__close').first().click();
    return { timed, left: await p.locator('.ui-toast').count() };
  }, s => s.timed === 0 && s.left === 0);
  await check('scrollspy: the section in view is aria-current=location', async () => {
    await p.evaluate(() => document.getElementById('cards').scrollIntoView({ block: 'start' })); await p.waitForTimeout(500);
    return await p.evaluate(() => [...document.querySelectorAll('.ui-scrollspy [aria-current]')].map(a => a.getAttribute('href') + '=' + a.getAttribute('aria-current')));
  }, s => s.length === 1 && s[0] === '#cards=location');
  const cmd = await p.evaluate(() => window.__cmd ?? null);
  r['forced: native invoker commands were cancelled, so the script acted'] = { pass: mode !== 'older-floor' || cmd > 0, seen: cmd };
  const csp = await p.evaluate(() => window.__csp);
  r['no CSP violation, no script error'] = { pass: csp.length === 0 && errors.length === 0, seen: { csp, errors } };
  // The navbar, on pages of its own so the gallery's page above stays where its checks left it.
  // fit() runs a frame after a resize, and a headless frame can stall: wait for frames, not for a fixed time.
  const frames = (q) => q.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(() => requestAnimationFrame(r)))));
  const pageAt = async (width, path) => {
    const q = await ctx.newPage(); await q.setViewportSize({ width, height: 740 });
    const errs = []; q.on('pageerror', e => errs.push(String(e)));
    await q.goto(new URL(path, url).href); return [q, errs];
  };
  const navState = (q) => q.evaluate(() => {
    const panel = document.getElementById('g-nav'), toggle = document.querySelector('.ui-navbar__toggle'), bar = document.querySelector('.ui-navbar');
    const pr = panel.getBoundingClientRect(), br = bar.getBoundingClientRect();
    return { open: panel.matches(':popover-open'), shown: getComputedStyle(panel).display !== 'none' && pr.height > 0,
      toggleShown: getComputedStyle(toggle).display !== 'none', expanded: toggle.getAttribute('aria-expanded'),
      // Under the bar, edge to edge with it (a dropdown's own link to the page may be current too).
      under: pr.top - br.bottom, left: pr.left, width: pr.width, barLeft: br.left, barWidth: br.width, vw: document.documentElement.clientWidth,
      current: [...document.querySelectorAll('.ui-navbar__link[aria-current="page"]')].map(a => a.getAttribute('href')), path: location.pathname };
  });
  await check('navbar: from its breakpoint up, its panel inline in the bar, its toggle gone, no popover open', async () => {
    const [q, errs] = await pageAt(1024, '/'); const s = await navState(q);
    const sameRow = await q.evaluate(() => { const l = document.querySelector('.ui-navbar__link').getBoundingClientRect(), b = document.querySelector('.ui-navbar').getBoundingClientRect(); return l.top >= b.top && l.bottom <= b.bottom; });
    const csp = await q.evaluate(() => window.__csp); await q.close();
    return { ...s, sameRow, csp, errs };
  }, s => s.shown && !s.open && !s.toggleShown && s.sameRow && s.current.length === 1 && s.current[0] === '/' && s.csp.length === 0 && s.errs.length === 0);
  await check('navbar: a link swaps <main> and the current mark moves with it, no reload', async () => {
    const [q, errs] = await pageAt(1024, '/'); await q.evaluate(() => { window.__mark = 1; });
    await q.locator('.ui-navbar__link[href="/second"]').click(); await q.waitForFunction(() => location.pathname === '/second'); await q.waitForTimeout(200);
    const s = await navState(q), same = await q.evaluate(() => window.__mark === 1);
    const csp = await q.evaluate(() => window.__csp); await q.close();
    return { current: s.current, path: s.path, same, csp, errs };
  }, s => s.path === '/second' && s.current.length === 1 && s.current[0] === '/second' && s.same && s.csp.length === 0 && s.errs.length === 0);
  await check('navbar: narrow, the panel hidden until its toggle opens it, under the bar, both as wide as the page; Esc and a press outside close it', async () => {
    const [q, errs] = await pageAt(360, '/');
    const before = await navState(q);
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150); const opened = await navState(q);
    await q.keyboard.press('Escape'); await q.waitForTimeout(100); const escaped = await navState(q);
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    await q.mouse.click(180, 700); await q.waitForTimeout(150); const outside = await navState(q);
    const csp = await q.evaluate(() => window.__csp); await q.close();
    return { before, opened, escaped, outside, csp, errs };
  }, s => !s.before.shown && s.before.toggleShown && s.opened.open && s.opened.shown && Math.abs(s.opened.under) <= 0.5
        && s.opened.barLeft === 0 && s.opened.barWidth === s.opened.vw && s.opened.left === 0 && s.opened.width === s.opened.vw && !s.escaped.open && !s.outside.open && s.csp.length === 0 && s.errs.length === 0);
  await check('navbar: narrow, its dropdown opens inside the open panel, and a link chosen closes the panel and swaps the page', async () => {
    const [q, errs] = await pageAt(360, '/');
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    await q.locator('[popovertarget="g-nav-more"]').click(); await q.waitForTimeout(150);
    const both = await q.evaluate(() => [document.getElementById('g-nav').matches(':popover-open'), document.getElementById('g-nav-more').matches(':popover-open')]);
    await q.keyboard.press('Escape'); await q.waitForTimeout(100);
    await q.locator('.ui-navbar__link[href="/second"]').click(); await q.waitForFunction(() => location.pathname === '/second'); await q.waitForTimeout(200);
    const after = await navState(q); const csp = await q.evaluate(() => window.__csp); await q.close();
    return { both, after, csp, errs };
  }, s => s.both[0] && s.both[1] && !s.after.open && s.after.current[0] === '/second' && s.csp.length === 0 && s.errs.length === 0);
  await check('navbar: a link of its dropdown, inside the open panel, closes the panel too', async () => {
    const [q, errs] = await pageAt(360, '/');
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    await q.locator('[popovertarget="g-nav-more"]').click(); await q.waitForTimeout(150);
    await q.locator('#g-nav-more a[href="/second"]').click(); await q.waitForFunction(() => location.pathname === '/second'); await q.waitForTimeout(200);
    const open = await q.evaluate(() => ['g-nav', 'g-nav-more'].filter(id => document.getElementById(id).matches(':popover-open')));
    await q.close(); return { open, errs };
  }, s => s.open.length === 0 && s.errs.length === 0);
  await check('navbar: opened narrow, then the window widens past its breakpoint: closed, its links inline in the bar', async () => {
    const [q, errs] = await pageAt(360, '/');
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    await q.setViewportSize({ width: 1024, height: 740 }); await q.waitForTimeout(250);
    const s = await q.evaluate(() => {
      const l = document.querySelector('.ui-navbar__link').getBoundingClientRect(), b = document.querySelector('.ui-navbar').getBoundingClientRect();
      return { open: document.getElementById('g-nav').matches(':popover-open'), inBar: l.top >= b.top && l.bottom <= b.bottom };
    });
    await q.close(); return { ...s, errs };
  }, s => !s.open && s.inBar && s.errs.length === 0);
  await check('navbar: on a short screen with many links, the open panel ends at the screen\'s bottom and its last link is reached by scrolling it', async () => {
    const q = await ctx.newPage(); await q.setViewportSize({ width: 320, height: 256 }); await q.goto(url);
    await q.evaluate(() => { const ul = document.querySelector('.ui-navbar__items'), li = ul.querySelector('li');
      for (let i = 0; i < 10; i++) { const c = li.cloneNode(true); c.querySelector('a').textContent = 'Extra ' + i; c.querySelector('a').removeAttribute('aria-current'); ul.append(c); } });
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    const s = await q.evaluate(() => {
      const panel = document.getElementById('g-nav'), r = panel.getBoundingClientRect(), overflows = panel.scrollHeight > panel.clientHeight;
      panel.scrollTop = 1e6; const links = panel.querySelectorAll('.ui-navbar__items > li'), last = links[links.length - 1].getBoundingClientRect();
      const bar = document.querySelector('.ui-navbar').getBoundingClientRect().bottom;
      return { open: panel.matches(':popover-open'), bottom: r.bottom, vh: innerHeight, overflows, lastTop: last.top, lastBottom: last.bottom, top: r.top, under: r.top - bar };
    });
    await q.close(); return s;
  }, s => s.open && Math.abs(s.under) <= 0.5 && s.bottom <= s.vh + 0.5 && s.overflows && s.lastTop >= s.top && s.lastBottom <= s.vh + 0.5);
  await check('navbar: narrow, its links, its dropdown\'s button, its end and its brand start their text at one inset', async () => {
    const [q, errs] = await pageAt(360, '/');
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    const s = await q.evaluate(() => {
      // Where the text itself starts (a Range over its first text node), so a box at the inset with its text centred is caught.
      const start = (el) => { const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, { acceptNode: n => n.data.trim() ? 1 : 3 });
        const r = document.createRange(); r.selectNodeContents(w.nextNode()); return Math.round(r.getBoundingClientRect().left * 2) / 2; };
      const endKid = document.querySelector('.ui-navbar__end').firstElementChild;
      return { brand: start(document.querySelector('.ui-navbar__brand')), link: start(document.querySelector('.ui-navbar__link')),
        raw: start(document.getElementById('g-nav-raw')), more: start(document.querySelector('[popovertarget="g-nav-more"]')),
        end: Math.round(endKid.getBoundingClientRect().left * 2) / 2, endKid: Math.round(endKid.getBoundingClientRect().left * 2) / 2 };
    });
    await q.close(); return { ...s, errs };
  }, s => s.brand === s.link && s.raw === s.link && s.more === s.link && s.end === s.link && s.endKid === s.link);
  await check('navbar: a host\'s plain link is painted as its links are, and a section mark looks like the page mark', async () => {
    const [q, errs] = await pageAt(1024, '/');
    await q.keyboard.press('Shift');
    const s = await q.evaluate(() => {
      const link = document.querySelector('.ui-navbar__link[href="/second"]'), raw = document.getElementById('g-nav-raw');
      const look = (el) => { const cs = getComputedStyle(el); return { color: cs.color, weight: cs.fontWeight, line: cs.textDecorationLine }; };
      const ring = (el) => { el.focus(); const cs = getComputedStyle(el); const o = { style: cs.outlineStyle, color: cs.outlineColor, width: cs.outlineWidth, fv: el.matches(':focus-visible') }; el.blur(); return o; };
      const page = look(document.querySelector('.ui-navbar__link[aria-current="page"]'));
      const plain = look(link), rawLook = look(raw), linkRing = ring(link), rawRing = ring(raw);
      link.setAttribute('aria-current', 'true'); const section = look(link);
      link.setAttribute('aria-current', 'location'); const location = look(link);
      return { page, plain, rawLook, section, location, linkRing, rawRing };
    });
    await q.close(); return { ...s, errs };
  }, s => s.rawLook.color === s.plain.color && s.rawLook.weight === s.plain.weight
        && s.linkRing.fv && s.rawRing.fv && s.linkRing.style !== 'none' && JSON.stringify(s.rawRing) === JSON.stringify(s.linkRing)
        && s.page.weight === '600' && s.page.line.includes('underline') && s.plain.weight !== '600'
        && JSON.stringify(s.section) === JSON.stringify(s.page) && JSON.stringify(s.location) === JSON.stringify(s.page));
  await check('navbar: sticky, its header stays at the top as the page scrolls, and a jump to a section lands below it', async () => {
    const [q, errs] = await pageAt(1024, '/');
    const s = await q.evaluate(async () => {
      const header = document.querySelector('.wb-header'), frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
      scrollTo(0, 2000); await frame(); const scrolledTop = header.getBoundingClientRect().top, scrolled = scrollY;
      // A section from the middle, so the page can scroll it to the top: one near the end would sit low whatever the padding.
      const all = [...document.querySelectorAll('main section[id]')], target = all[Math.floor(all.length / 2)];
      scrollTo(0, 0); await frame(); location.hash = target.id; await frame();
      return { scrolled, scrolledTop, targetTop: target.getBoundingClientRect().top, headerBottom: header.getBoundingClientRect().bottom, y: scrollY };
    });
    await q.close(); return { ...s, errs };
  }, s => s.scrolled > 0 && Math.abs(s.scrolledTop) <= 0.5 && s.y > 0 && s.targetTop >= s.headerBottom - 0.5 && s.errs.length === 0);
  await check('navbar: opened narrow, a resize that stays below its breakpoint leaves it open', async () => {
    const [q, errs] = await pageAt(360, '/');
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    await q.setViewportSize({ width: 420, height: 700 }); await q.waitForTimeout(250);
    const open = await q.evaluate(() => document.getElementById('g-nav').matches(':popover-open')); await q.close(); return { open, errs };
  }, s => s.open && s.errs.length === 0);
  await check('navbar: its toggle a compact button, centred in the bar', async () => {
    const [q, errs] = await pageAt(360, '/');
    const s = await q.evaluate(() => { const t = document.querySelector('.ui-navbar__toggle').getBoundingClientRect(), b = document.querySelector('.ui-navbar').getBoundingClientRect();
      return { h: t.height, bar: b.height, above: t.top - b.top, below: b.bottom - t.bottom }; });
    await q.close(); return { ...s, errs };
  }, s => s.h < s.bar - 4 && Math.abs(s.above - s.below) <= 1.5);
  await check('navbar: past its breakpoint it collapses to its toggle while its items do not fit, inline once they do, and stays one row', async () => {
    const [q, errs] = await pageAt(700, '/second');
    const state = () => q.evaluate(() => { const bar = document.querySelector('.ui-navbar'), t = document.querySelector('.ui-navbar__toggle'), l = document.querySelector('.ui-navbar__link').getBoundingClientRect(), b = bar.getBoundingClientRect();
      return { collapsed: bar.classList.contains('ui-navbar--collapsed'), toggle: getComputedStyle(t).display !== 'none', linkInBar: l.height > 0 && l.top >= b.top && l.bottom <= b.bottom, h: b.height }; });
    const at = async (w) => { await q.setViewportSize({ width: w, height: 740 }); await q.waitForTimeout(100); await frames(q); return state(); };
    const s700 = await state(), s690 = await at(690), s600 = await at(600), s320 = await at(320), back = await at(700), wide = await at(1024);
    await q.close(); return { s700, s690, s600, s320, back, wide, errs };
  }, s => !s.s700.collapsed && !s.s700.toggle && s.s700.linkInBar && s.s690.collapsed && s.s690.toggle && !s.s690.linkInBar
        && s.s600.toggle && !s.s600.linkInBar && s.s320.toggle && !s.back.collapsed && !s.back.toggle && s.back.linkInBar && s.wide.linkInBar
        && [s.s690, s.s600, s.s320, s.back, s.wide].every(x => Math.abs(x.h - s.s700.h) <= 0.5) && s.errs.length === 0);
  await check('navbar: with a banner above its header and many links, the open panel starts at the bar and ends within the screen', async () => {
    const q = await ctx.newPage(); await q.setViewportSize({ width: 320, height: 400 }); await q.goto(url);
    await q.evaluate(() => { const ban = document.createElement('div'); ban.style.blockSize = '120px'; document.body.prepend(ban);
      const ul = document.querySelector('.ui-navbar__items'), li = ul.querySelector('li');
      for (let i = 0; i < 10; i++) { const c = li.cloneNode(true); c.querySelector('a').removeAttribute('aria-current'); ul.append(c); } });
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    const s = await q.evaluate(() => { const p = document.getElementById('g-nav'), r = p.getBoundingClientRect(), b = document.querySelector('.ui-navbar').getBoundingClientRect();
      return { open: p.matches(':popover-open'), under: r.top - b.bottom, barTop: b.top, bottom: r.bottom, vh: innerHeight, overflows: p.scrollHeight > p.clientHeight }; });
    await q.close(); return s;
  }, s => s.open && s.barTop >= 119 && Math.abs(s.under) <= 0.5 && s.bottom <= s.vh + 0.5 && s.overflows);
  // A ResizeObserver loop is reported as a window error event (Chromium), not always as a page error: both are read.
  const growShrink = async (q, w) => { await q.setViewportSize({ width: w, height: 740 }); await q.waitForTimeout(100); await frames(q);
    for (let i = 0; i < 10; i++) {
      await q.evaluate(() => { document.querySelector('.ui-navbar__link[href="/second"]').textContent = 'Second page with a much longer name'; }); await q.waitForTimeout(60);
      await q.evaluate(() => { document.querySelector('.ui-navbar__link[href="/second"]').textContent = 'Second page'; }); await q.waitForTimeout(60);
    } };
  await check('navbar: its items growing and shrinking around the width they need raise no error', async () => {
    const [q, errs] = await pageAt(720, '/second');
    for (const w of [690, 698, 720]) await growShrink(q, w);
    const err = await q.evaluate(() => window.__err); await q.close(); return { err, errs };
  }, s => s.err.length === 0 && s.errs.length === 0);
  await check('navbar: wide, an item grown past the row collapses it, and shrunk back while collapsed it is inline again', async () => {
    const [q, errs] = await pageAt(1024, '/second');
    const set = async (t) => { await q.evaluate(t => { document.querySelector('.ui-navbar__link[href="/second"]').textContent = t; }, t); await q.waitForTimeout(100); await frames(q);
      return q.evaluate(() => ({ collapsed: document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'), toggle: document.querySelector('.ui-navbar__toggle').checkVisibility() })); };
    const grown = await set('A second page whose name is far too long to share one row with all the others'), shrunk = await set('X');
    const err = await q.evaluate(() => window.__err); await q.close(); return { grown, shrunk, err, errs };
  }, s => s.grown.collapsed && s.grown.toggle && !s.shrunk.collapsed && !s.shrunk.toggle && s.err.length === 0 && s.errs.length === 0);
  await check('navbar: opened narrow, then widened, its inline panel keeps none of the placement written for the popover', async () => {
    const [q, errs] = await pageAt(360, '/');
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
    await q.setViewportSize({ width: 1024, height: 740 }); await q.waitForTimeout(300);
    const s = await q.evaluate(() => { const p = document.getElementById('g-nav'), cs = getComputedStyle(p);
      return { inline: p.style.length, max: cs.maxBlockSize, position: cs.position, open: p.matches(':popover-open') }; });
    await q.close(); return { ...s, errs };
  }, s => s.inline === 0 && s.max === 'none' && s.position === 'static' && !s.open && s.errs.length === 0);
  await check('navbar: when it collapses while a link of its row has the focus, the focus goes to its toggle', async () => {
    const [q, errs] = await pageAt(720, '/second');
    await q.keyboard.press('Shift'); await q.evaluate(() => document.querySelector('.ui-navbar__link[href="/"]').focus());
    const before = await q.evaluate(() => document.activeElement.getAttribute('href'));
    await q.setViewportSize({ width: 690, height: 740 }); await q.waitForTimeout(100); await frames(q);
    const after = await q.evaluate(() => document.activeElement.className); await q.close(); return { before, after, errs };
  }, s => s.before === '/' && /ui-navbar__toggle/.test(s.after) && s.errs.length === 0);
  await check('navbar: collapsed past its breakpoint, its toggle opens the panel and it stays open, through a change of its content too', async () => {
    const [q, errs] = await pageAt(690, '/second'); await frames(q);
    const collapsed = await q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(1000); await frames(q);
    const opened = await q.evaluate(() => document.getElementById('g-nav').matches(':popover-open'));
    await q.evaluate(() => document.querySelector('.ui-navbar__end').append(document.createElement('span'))); await q.waitForTimeout(300); await frames(q);
    const afterEdit = await q.evaluate(() => document.getElementById('g-nav').matches(':popover-open'));
    const err = await q.evaluate(() => window.__err); await q.close(); return { collapsed, opened, afterEdit, err, errs };
  }, s => s.collapsed && s.opened && s.afterEdit && s.err.length === 0 && s.errs.length === 0);
  await check('navbar: a stylesheet that widens its items collapses it, with no error', async () => {
    const [q, errs] = await pageAt(1100, '/'); await frames(q);
    const before = await q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    await q.evaluate(() => { const sheet = new CSSStyleSheet(); sheet.replaceSync('.ui-navbar__items { font-size: 28px; }'); document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet]; });
    await q.waitForTimeout(300); await frames(q);
    const after = await q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    const err = await q.evaluate(() => window.__err); await q.close(); return { before, after, err, errs };
  }, s => !s.before && s.after && s.err.length === 0 && s.errs.length === 0);
  await check('navbar: a node added to it and removed in one task raises no error', async () => {
    const [q, errs] = await pageAt(1024, '/'); await frames(q);
    await q.evaluate(() => { const span = document.createElement('span'); document.querySelector('.ui-navbar__end').append(span); span.append(document.createElement('b')); span.remove(); });
    await q.waitForTimeout(300); await frames(q);
    const err = await q.evaluate(() => window.__err); await q.close(); return { err, errs };
  }, s => s.err.length === 0 && s.errs.length === 0);
  await check('navbar: a link\'s text edited in place collapses it when too long and lets it expand when short again', async () => {
    const [q, errs] = await pageAt(1024, '/second'); await frames(q);
    const set = async (t) => { await q.evaluate(t => { document.querySelector('.ui-navbar__link[href="/second"]').firstChild.data = t; }, t); await q.waitForTimeout(150); await frames(q);
      return q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed')); };
    const grown = await set('A second page whose name is far too long to share one row with all the others'), shrunk = await set('X');
    const err = await q.evaluate(() => window.__err); await q.close(); return { grown, shrunk, err, errs };
  }, s => s.grown && !s.shrunk && s.err.length === 0 && s.errs.length === 0);
  await check('navbar: a host\'s own inline style on its panel survives the bar fitting itself', async () => {
    const [q, errs] = await pageAt(1024, '/'); await frames(q);
    await q.evaluate(() => document.getElementById('g-nav').style.setProperty('outline-offset', '3px'));
    await q.setViewportSize({ width: 1000, height: 740 }); await q.waitForTimeout(100); await frames(q);
    await q.evaluate(() => document.querySelector('.ui-navbar__end').append(document.createElement('span'))); await q.waitForTimeout(100); await frames(q);
    const kept = await q.evaluate(() => document.getElementById('g-nav').style.getPropertyValue('outline-offset')); await q.close(); return { kept, errs };
  }, s => s.kept === '3px' && s.errs.length === 0);
  result.modes[mode] = r;
  await ctx.close();
}
await browser.close();
console.log(JSON.stringify(result, null, 1));
