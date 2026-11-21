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
const launch = () => type.launch(process.env.CHROMIUM && engine === 'chromium' ? { executablePath: process.env.CHROMIUM } : {});
// Headless Chromium hides scrollbars; one launched without that flag lays out a styled 15px one
// every time, so a page beside a scrollbar is measured there. Headless WebKit lays one out on
// the gallery's tall page in some runs and not others (measured: 1455 and 1470 for the same
// page), so it measures none; web-base's own check holds its side of it in WebKit.
const scrolling = engine === 'chromium'
  ? await type.launch({ ignoreDefaultArgs: ['--hide-scrollbars'], ...(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {}) }) : null;
const result = { engine, modes: {} };

// Each mode in a browser of its own: headless WebKit, given every page of one mode and then of
// the next in one process, stops loading pages part way through the second (measured: 60 of 60
// with that mode alone, 20 of 60 after the native one, every page.goto timing out from one
// check on), which no reader's browser is ever asked to do. Within one mode too: one headless
// WebKit process loads no page after about sixty, closed or not (measured: the 63rd about:blank
// never loads, while the server still answers), so the pages each check opens fresh come from a
// browser of their own, replaced every 40 — a margin under the 62 measured.
for (const [mode, init] of Object.entries(modes)) {
  const browser = await launch();
  result.version = browser.version();
  const ctx = await browser.newContext({ viewport: { width: 1024, height: 768 } });
  await ctx.addInitScript(base + init);
  let spare = null, spareCtx = null, served = 0;
  const context = async (b) => { const c = await b.newContext({ viewport: { width: 1024, height: 768 } }); await c.addInitScript(base + init); return c; };
  // `own` gives the page a context of its own: page.clock is the context's, so a clock paused on
  // one page freezes every page the context opens after it (measured: their setTimeout never fired).
  const fresh = async (own = false) => {
    if (served === 40) { await spare.close(); spare = null; }
    if (!spare) { spare = await launch(); spareCtx = await context(spare); served = 0; }
    served++; return (own ? await context(spare) : spareCtx).newPage();
  };
  const p = await ctx.newPage();
  const errors = []; p.on('pageerror', e => errors.push(String(e)));
  await p.goto(url);
  const r = {};
  // A hang guard, never a pass criterion: Playwright's evaluate has no deadline of its own, and
  // one WebKit run stood on a single call for seventeen hours. The stderr line names the check.
  const check = async (name, f, pass) => {
    const began = Date.now(); let timer;
    const hang = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('HANG: no answer in 180 s')), 180000); });
    try { const seen = await Promise.race([f(), hang]); r[name] = { pass: pass(seen), seen }; }
    catch (e) { r[name] = { pass: false, seen: 'ERROR ' + String(e).split('\n')[0] }; }
    finally { clearTimeout(timer); console.error(mode, Date.now() - began, 'ms', name.slice(0, 60)); }
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
    const q = await fresh(); await q.goto(url);
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
  await check('offcanvas: the top and bottom sheets span the width at their edge, as tall as their content', async () => {
    const sheet = async (id) => {
      await p.locator(`button[commandfor=${id}][command=show-modal]`).click();
      const s = await p.evaluate((id) => { const d = document.getElementById(id), r = d.getBoundingClientRect();
        const content = [...d.children].reduce((h, c) => h + c.getBoundingClientRect().height, 0);
        return { open: d.matches(':modal'), left: r.left, width: r.width, top: r.top, bottom: r.bottom, height: r.height, content: Math.round(content) }; }, id);
      await p.keyboard.press('Escape'); return { ...s, closed: !(await isOpen(id)) };
    };
    return { top: await sheet('g-top'), bottom: await sheet('g-bottom') };
  }, s => [s.top, s.bottom].every(x => x.open && x.closed && x.left === 0 && x.width === 1024 && x.height < 400 && Math.abs(Math.round(x.height) - x.content) <= 1)
       && s.top.top === 0 && s.bottom.bottom === 768);
  await check('offcanvas: responsive from md, closed, its body in the page with no head, its link reachable, its button hidden', async () => {
    return p.evaluate(() => {
      const d = document.getElementById('g-side'), r = d.getBoundingClientRect(), link = d.querySelector('a');
      const before = d.previousElementSibling.previousElementSibling.getBoundingClientRect(); link.focus();
      return { open: d.open, display: getComputedStyle(d).display, position: getComputedStyle(d).position, inFlow: Math.round(r.top) >= Math.round(before.bottom),
        head: getComputedStyle(d.querySelector('.ui-dialog__head')).display, linkShown: link.getBoundingClientRect().height > 0, focused: document.activeElement === link,
        button: getComputedStyle(document.querySelector('button[commandfor=g-side][command=show-modal]')).display, border: getComputedStyle(d).borderTopWidth };
    });
  }, s => !s.open && s.display === 'block' && s.position === 'static' && s.inFlow && s.head === 'none' && s.linkShown && s.focused && s.button === 'none' && s.border === '0px');
  await check('offcanvas: the end sheet at the inline end, full height', async () => {
    await p.locator('button[commandfor=g-end][command=show-modal]').click();
    const b = await p.locator('#g-end').boundingBox(); await p.keyboard.press('Escape');
    return { right: Math.round(b.x + b.width), height: Math.round(b.height), closed: !(await isOpen('g-end')) };
  }, s => s.right === 1024 && s.height === 768 && s.closed);
  await check('tabs-nav: vertical, its items a column on one line at the inline end, the current one\'s rail on it; pills draw neither', async () => {
    const look = (label) => p.evaluate((label) => {
      const nav = document.querySelector(`nav[aria-label="${label}"]`); nav.scrollIntoView();
      const ul = nav.querySelector('ul'), ur = ul.getBoundingClientRect(), us = getComputedStyle(ul);
      const items = [...ul.querySelectorAll('a')].map(a => a.getBoundingClientRect());
      const cur = ul.querySelector('a[aria-current="page"]'), cr = cur.getBoundingClientRect(), after = getComputedStyle(cur, '::after');
      const probe = document.createElement('div'); probe.style.color = 'var(--ui-primary-border)'; document.body.append(probe);
      const primary = getComputedStyle(probe).color; probe.remove();
      return { stacked: items.every((r, i) => i === 0 || (Math.round(r.top) >= Math.round(items[i - 1].bottom) && Math.round(r.left) === Math.round(items[0].left))),
        lineEnd: us.borderRightWidth, lineBottom: us.borderBottomWidth, lineColour: us.borderRightColor,
        rail: after.content !== 'none' ? { width: after.borderRightWidth, colour: after.borderRightColor === primary,
          // The rail's own box, in the link's padding box: its right edge on the line's inner side.
          right: Math.round(cr.left + parseFloat(after.left) + parseFloat(after.width) + parseFloat(after.borderRightWidth)) === Math.round(ur.right - parseFloat(us.borderRightWidth)) && Math.round(cr.right) === Math.round(ur.right - parseFloat(us.borderRightWidth)), height: Math.round(parseFloat(after.height)) === Math.round(cr.height) } : null,
        shadow: getComputedStyle(cur).boxShadow, fill: getComputedStyle(cur).backgroundColor };
    }, label);
    return { tabs: await look('Vertical views'), pills: await look('Vertical pills') };
  }, s => s.tabs.stacked && s.tabs.lineEnd === '1px' && s.tabs.lineBottom === '0px' && s.tabs.rail?.width === '2px' && s.tabs.rail.colour
       && s.tabs.rail.right && s.tabs.rail.height && s.tabs.shadow === 'none'
       && s.pills.stacked && s.pills.lineEnd === '0px' && s.pills.lineBottom === '0px' && s.pills.rail === null && s.pills.fill !== 'rgba(0, 0, 0, 0)');
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
  const pageAt = async (width, path, own = false) => {
    const q = await fresh(own); await q.setViewportSize({ width, height: 740 });
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
  await check('offcanvas: responsive from md, below it a sheet its button opens; widened while open it stays a sheet until closed, then sits in the page', async () => {
    const [q, errs] = await pageAt(600, '/');
    const state = () => q.evaluate(() => { const d = document.getElementById('g-side'), r = d.getBoundingClientRect();
      const before = d.previousElementSibling.previousElementSibling.getBoundingClientRect();
      return { open: d.open, modal: d.matches(':modal'), display: getComputedStyle(d).display, head: getComputedStyle(d.querySelector('.ui-dialog__head')).display,
        position: getComputedStyle(d).position, inFlow: Math.round(r.top) >= Math.round(before.bottom),
        left: Math.round(r.left), height: Math.round(r.height), vh: innerHeight }; });
    const closed = await state();
    const button = await q.evaluate(() => getComputedStyle(document.querySelector('button[commandfor=g-side][command=show-modal]')).display);
    await q.locator('button[commandfor=g-side][command=show-modal]').click(); const opened = await state();
    await q.setViewportSize({ width: 1024, height: 740 }); await frames(q); const widened = await state();
    await q.keyboard.press('Escape'); await frames(q); const after = await state();
    await q.close(); return { closed, button, opened, widened, after, errs };
  }, s => !s.closed.open && s.closed.display === 'none' && s.button !== 'none'
       && s.opened.modal && s.opened.head !== 'none' && s.opened.left === 0 && s.opened.height === s.opened.vh
       && s.widened.modal && s.widened.head !== 'none' && !s.after.open && s.after.display === 'block' && s.after.head === 'none' && s.after.position === 'static' && s.after.inFlow && s.errs.length === 0);
  // A dropdown's placement, measured on a page of its own with its wrapper pinned where the
  // check needs it (CSSOM, which the page's CSP allows), so no other check moves with it.
  const dropAt = async (id, pin, rtl) => {
    const [q, errs] = await pageAt(1024, '/');
    const s = await q.evaluate(async ({ id, pin, rtl }) => {
      if (rtl) document.documentElement.dir = 'rtl';
      const menu = document.getElementById(id), wrap = menu.parentElement, btn = wrap.firstElementChild;
      Object.entries({ position: 'fixed', ...pin }).forEach(([k, v]) => wrap.style.setProperty(k, v));
      // The caret turns by a transition: read it once every running one has finished.
      const settled = async () => { await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))); await Promise.race([Promise.all(document.getAnimations().filter(a => a instanceof CSSTransition).map(a => a.finished)),
        new Promise(r => setTimeout(r, 2000))]); };
      const caret = () => getComputedStyle(btn, '::before').rotate;
      // The caret's offset, [x, y] in px: the side its V is moved to so the V sits centred.
      const shift = () => { const [x, y = '0'] = getComputedStyle(btn, '::before').translate.split(' '); return [parseFloat(x) || 0, parseFloat(y) || 0]; };
      await settled(); const closed = caret(), shiftClosed = shift(); btn.click(); await settled();
      const b = btn.getBoundingClientRect(), m = menu.getBoundingClientRect(), opened = caret(), shiftOpened = shift();
      const before = getComputedStyle(btn, '::before'), order = before.order;
      const items = [...menu.querySelectorAll('li')].map(li => ({ cls: li.className, role: li.getAttribute('role'), border: getComputedStyle(li).borderTopWidth }));
      const off = menu.querySelector('[aria-disabled="true"]'); let offFocus = null;
      if (off) { off.focus(); offFocus = { href: off.hasAttribute('href'), focused: document.activeElement === off }; }
      return { open: menu.matches(':popover-open'), closed, opened, shiftClosed, shiftOpened, order, items, offFocus,
        above: Math.round(b.top - m.bottom), below: Math.round(m.top - b.bottom), toStart: Math.round(b.left - m.right), toEnd: Math.round(m.left - b.right),
        dTop: Math.round(m.top - b.top), dLeft: Math.round(m.left - b.left), dRight: Math.round(m.right - b.right) };
    }, { id, pin, rtl });
    await q.close(); return { ...s, errs };
  };
  // The gap is the kit's --ui-spacing-xs, 0.25rem: 4px at the default font size, and the 4
  // place() writes where anchor positioning is missing.
  const near = (n) => n === 4;
  const clean = (s) => Object.values(s).every(x => x.errs.length === 0);
  const sign = Math.sign;
  await check('dropdown: up above its button, lined with its start or its end, flipped below at the top, and down lined with its end; its caret points up, down while open; a header, a line and a disabled item that takes no focus', async () => {
    return { up: await dropAt('g-up', { top: '400px', left: '300px' }), flipped: await dropAt('g-up', { top: '0px', left: '300px' }),
      upStart: await dropAt('g-up-start', { top: '400px', left: '300px' }), downEnd: await dropAt('g-down-end', { top: '300px', left: '300px' }) };
  }, s => s.upStart.open && near(s.upStart.above) && s.upStart.dLeft === 0 && s.downEnd.open && near(s.downEnd.below) && s.downEnd.dRight === 0
       && s.up.open && near(s.up.above) && s.up.dRight === 0 && s.up.closed === '225deg' && s.up.opened === '45deg'
       && s.up.items[0].cls === 'ui-dropdown__header' && s.up.items.some(i => i.role === 'separator' && i.border === '1px')
       && s.up.offFocus && !s.up.offFocus.href && !s.up.offFocus.focused
       && s.flipped.open && near(s.flipped.below) && s.flipped.dRight === 0
       && sign(s.up.shiftClosed[1]) === 1 && sign(s.up.shiftOpened[1]) === -1 && s.up.shiftClosed[0] === 0 && clean(s));
  await check('dropdown: beside its button at its inline start or end, at its top, flipped to the other side at the edge; its caret before the label pointing there, or after it', async () => {
    return { start: await dropAt('g-beside-start', { top: '300px', left: '400px' }), startFlipped: await dropAt('g-beside-start', { top: '300px', left: '0px' }),
      end: await dropAt('g-beside-end', { top: '300px', left: '300px' }), endFlipped: await dropAt('g-beside-end', { top: '300px', right: '0px' }) };
  }, s => s.start.open && near(s.start.toStart) && s.start.dTop === 0 && s.start.closed === '135deg' && s.start.opened === '135deg' && s.start.order === '-1'
       && s.startFlipped.open && near(s.startFlipped.toEnd) && s.startFlipped.dTop === 0
       && s.end.open && near(s.end.toEnd) && s.end.dTop === 0 && s.end.closed === '-45deg' && s.end.opened === '-45deg' && s.end.order === '1'
       && s.endFlipped.open && near(s.endFlipped.toStart) && s.endFlipped.dTop === 0
       && sign(s.start.shiftClosed[0]) === 1 && s.start.shiftClosed[1] === 0 && sign(s.end.shiftClosed[0]) === -1 && s.end.shiftClosed[1] === 0 && clean(s));
  await check('dropdown: right to left, the inline start is the right and the end the left, its carets turned to match', async () => {
    return { start: await dropAt('g-beside-start', { top: '300px', left: '400px' }, true), end: await dropAt('g-beside-end', { top: '300px', left: '400px' }, true),
      up: await dropAt('g-up', { top: '400px', left: '300px' }, true), upStart: await dropAt('g-up-start', { top: '400px', left: '300px' }, true),
      downEnd: await dropAt('g-down-end', { top: '300px', left: '300px' }, true),
      startFlipped: await dropAt('g-beside-start', { top: '300px', right: '0px' }, true) };
  }, s => s.upStart.open && near(s.upStart.above) && s.upStart.dRight === 0 && s.downEnd.open && near(s.downEnd.below) && s.downEnd.dLeft === 0
       && s.start.open && near(s.start.toEnd) && s.start.dTop === 0 && s.start.closed === '-135deg' && s.start.opened === '-135deg'
       && s.end.open && near(s.end.toStart) && s.end.closed === '45deg' && s.end.opened === '45deg'
       && sign(s.start.shiftClosed[0]) === -1 && sign(s.end.shiftClosed[0]) === 1
       && s.startFlipped.open && near(s.startFlipped.toStart) && s.startFlipped.dTop === 0 && clean(s)
       && s.up.open && near(s.up.above) && s.up.dLeft === 0 && s.up.closed === '-225deg' && s.up.opened === '-45deg');
  // Measured: a tooltip outside a modal, still inside its 150 ms hide, took the modal's Esc. The
  // clock is paused so that hide never fires, and the page records, around ui.js's own listener
  // (on document, capturing), whether the tip was open, whether it is after, and the decision.
  // A paused clock stops animation frames too, so the pointer and the focus move raw, never
  // through a locator's actionability wait.
  const escAt = async (arrange) => {
    const [q, errs] = await pageAt(1024, '/', true);
    try { await q.clock.install(); await q.clock.pauseAt(Date.now() + 1000);
    await q.evaluate(() => { const tip = () => document.getElementById('g-tip').matches(':popover-open'); window.__tipEsc = [];
      addEventListener('keydown', e => { if (e.key === 'Escape') window.__tipEsc.push({ at: tip() }); }, true);
      addEventListener('keydown', e => { if (e.key === 'Escape') Object.assign(window.__tipEsc.at(-1), { after: tip(), prevented: e.defaultPrevented }); }); });
    await arrange(q);
    const open = () => q.evaluate(() => ({ end: document.getElementById('g-end').open, dialog: document.getElementById('g-dialog').open }));
    const before = await open(); await q.keyboard.press('Escape');
    const once = await open(); await q.keyboard.press('Escape');
    const twice = await open(), esc = await q.evaluate(() => window.__tipEsc);
    return { before, once, twice, esc, errs }; } finally { await q.context().close(); }
  };
  const centre = (q, sel) => q.evaluate((sel) => { const e = document.querySelector(sel); e.scrollIntoView({ block: 'center' });
    const r = e.getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2]; }, sel);
  const endButton = 'button[commandfor=g-end][command=show-modal]', focusTip = (q, sel) => q.evaluate((sel) => document.querySelector(sel).focus(), sel);
  const openEnd = async (q) => { const [x, y] = await centre(q, endButton); await q.mouse.click(x, y); };
  await check('tooltip: Esc hides it; the modal it is in stays, a modal it is not in closes', async () => ({
    // Hovered, then left for the end sheet's button: its hide is pending when Esc comes.
    outside: await escAt(async (q) => { const [x, y] = await centre(q, '.ui-tooltip-wrap > :first-child'); await q.mouse.move(x, y); await openEnd(q); }),
    // Inside the end sheet, shown by the focus.
    inside: await escAt(async (q) => { await q.evaluate(() => document.querySelector('#g-end .ui-dialog__body').append(document.querySelector('.ui-tooltip-wrap')));
      await openEnd(q); await focusTip(q, '#g-end .ui-tooltip-wrap > :first-child'); }),
    // Inside the second of two open modals, later in the document than the first.
    stacked: await escAt(async (q) => { await q.evaluate(() => document.querySelector('#g-dialog .ui-dialog__body').append(document.querySelector('.ui-tooltip-wrap')));
      await openEnd(q); await q.evaluate(() => document.getElementById('g-dialog').showModal()); await focusTip(q, '#g-dialog .ui-tooltip-wrap > :first-child'); }),
  }), s => Object.values(s).every(x => x.errs.length === 0 && x.esc.length === 2 && x.esc[0].at && !x.esc[0].after && !x.esc[1].at)
       && s.outside.before.end && !s.outside.esc[0].prevented && !s.outside.once.end
       && s.inside.before.end && s.inside.esc[0].prevented && s.inside.once.end && !s.inside.twice.end
       && s.stacked.before.end && s.stacked.before.dialog && s.stacked.esc[0].prevented && s.stacked.once.dialog && s.stacked.once.end
       && !s.stacked.twice.dialog && s.stacked.twice.end);
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
    const q = await fresh(); await q.setViewportSize({ width: 320, height: 256 }); await q.goto(url);
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
    // The inks transition; with motion reduced the kit has none, so what is read is the resting look.
    await q.emulateMedia({ reducedMotion: 'reduce' });
    await q.keyboard.press('Shift');
    const s = await q.evaluate(() => {
      const link = document.querySelector('.ui-navbar__link[href="/second"]'), raw = document.getElementById('g-nav-raw');
      const look = (el) => { const cs = getComputedStyle(el), rail = getComputedStyle(el, '::after'); return { color: cs.color, weight: cs.fontWeight, rail: rail.opacity, railColor: rail.borderBottomColor }; };
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
        && s.page.weight === '600' && s.page.rail === '1' && s.plain.weight !== '600' && s.plain.rail === '0' && s.page.color !== s.plain.color
        && JSON.stringify(s.section) === JSON.stringify(s.page) && JSON.stringify(s.location) === JSON.stringify(s.page));
  // The shell's :header-width (web-base 0.27.0). The column is 48rem = 768px and --wb-gap 16px,
  // so at 1470 it is 351..1119 and its text 367..1103; at 360 every inset is the gap.
  const widthAt = async (width, path) => {
    const [q, errs] = await pageAt(width, path);
    const s = await q.evaluate(() => {
      const R = (sel) => { const e = document.querySelector(sel); if (!e) return null; const r = e.getBoundingClientRect(); return [r.left, r.right]; };
      return { cw: document.documentElement.clientWidth, sw: document.documentElement.scrollWidth, header: R('header.wb-header'), bar: R('.ui-navbar'),
        brand: R('.ui-navbar__brand'), end: R('.ui-navbar__end'), text: R('#width-text') };
    });
    await q.close(); return { ...s, errs };
  };
  const eq = (a, b) => a !== null && a[0] === b[0] && a[1] === b[1];
  await check('navbar: under :full it spans the window, under :aligned and :measure the column, and under :aligned its content stands over main\'s text', async () =>
    ({ full: await widthAt(1470, '/'), aligned: await widthAt(1470, '/aligned'), measure: await widthAt(1470, '/measure'), narrow: await widthAt(360, '/aligned') }),
  ({ full, aligned, measure, narrow }) => [full, aligned, measure].every(s => s.cw === 1470 && s.errs.length === 0) && narrow.cw === 360 && narrow.errs.length === 0
    && [aligned, narrow].every(s => s.sw === s.cw)
    && eq(full.header, [0, 1470]) && eq(full.bar, [0, 1470])
    && eq(aligned.header, [0, 1470]) && eq(aligned.bar, [351, 1119]) && eq(aligned.text, [367, 1103]) && aligned.brand[0] === 367 && aligned.end[1] === 1103
    && eq(measure.header, [351, 1119]) && eq(measure.bar, [351, 1119])
    && eq(narrow.bar, [0, 360]) && eq(narrow.text, [16, 344]) && narrow.brand[0] === 16);
  await check('navbar: under :aligned its ground and its line reach the window\'s edges and nowhere else, and under :measure they keep to the column', async () => {
    // A page of its own: Playwright's screenshot injects a <style> in WebKit, which the CSP refuses.
    const shot = async (path) => {
      const q = await fresh(); await q.setViewportSize({ width: 1470, height: 740 }); await q.goto(new URL(path, url).href);
      const s = await q.evaluate(() => {
        const probe = (v) => { const d = document.createElement('div'); d.style.setProperty('background-color', `var(${v})`); document.body.append(d);
          const c = getComputedStyle(d).backgroundColor.match(/\d+/g).slice(0, 3).join(','); d.remove(); return c; };
        const r = document.querySelector('.ui-navbar').getBoundingClientRect();
        return { surface: probe('--ui-surface'), border: probe('--ui-border'), page: probe('--ui-background'), mid: Math.floor((r.top + r.bottom) / 2), line: Math.ceil(r.bottom) - 1 };
      });
      const img = pixels(await q.screenshot({ caret: 'initial' })); await q.close();
      // Device pixels: the context's scale is 1, so a CSS pixel is one. x 2, 200, 1300 and 1467
      // are outside the column (351..1119), 360 inside it and before the brand's ink (367 or
      // 369.4); below the line, beside main's text.
      return { ...s, ground: [2, 200, 360, 1300, 1467].map(x => img.at(x, s.mid)), rule: [2, 200, 360, 1300, 1467].map(x => img.at(x, s.line)),
        below: [2, 200, 1467].map(x => img.at(x, s.line + 3)) };
    };
    return { aligned: await shot('/aligned'), measure: await shot('/measure') };
  }, ({ aligned: a, measure: m }) => a.surface !== a.border && a.surface !== a.page
    && a.ground.every(c => c === a.surface) && a.rule.every(c => c === a.border) && a.below.every(c => c === a.page)
    && [0, 1, 3, 4].every(i => m.ground[i] === m.page && m.rule[i] === m.page) && m.ground[2] === m.surface && m.rule[2] === m.border);
  await check('navbar: under :aligned its brand, its links and its end start their text where main\'s does, and under :measure where its brand does — narrow, collapsed wide, under a host\'s own gap and measure, and (Chromium) beside a scrollbar — its panel inset alike on both sides', async () => {
    const starts = (q) => q.evaluate(() => {
      const start = (el) => { const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, { acceptNode: n => n.data.trim() ? 1 : 3 });
        const r = document.createRange(); r.selectNodeContents(w.nextNode()); return Math.round(r.getBoundingClientRect().left * 2) / 2; };
      return { cw: document.documentElement.clientWidth, brand: start(document.querySelector('.ui-navbar__brand')), link: start(document.querySelector('.ui-navbar__link')),
        more: start(document.querySelector('[popovertarget="g-nav-more"]')), end: Math.round(document.querySelector('.ui-navbar__end').firstElementChild.getBoundingClientRect().left * 2) / 2,
        text: start(document.getElementById('width-text')),
        sides: (cs => [cs.paddingInlineStart, cs.paddingInlineEnd, cs.paddingLeft, cs.paddingRight])(getComputedStyle(document.querySelector('.ui-navbar__panel'))) };
    });
    // Collapsed by type three times the bar's width (as the widening check derives it), then opened.
    const collapsed = async (q) => {
      await frames(q);
      await q.evaluate(() => { const bar = document.querySelector('.ui-navbar'), items = bar.querySelector('.ui-navbar__items');
        const px = parseFloat(getComputedStyle(items).fontSize) * 3 * bar.clientWidth / items.getBoundingClientRect().width;
        const sheet = new CSSStyleSheet(); sheet.replaceSync(`.ui-navbar__items { font-size: ${px}px; }`); document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet]; });
      await q.waitForFunction(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'), null, { timeout: 3000 });
      await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(150);
      return starts(q);
    };
    const errs = [], at = async (width, path) => { const [q, e] = await pageAt(width, path); errs.push(...e); return q; };
    const n = await at(360, '/aligned'); await n.locator('.ui-navbar__toggle').click(); await n.waitForTimeout(150);
    const narrow = await starts(n); await n.close();
    const w = await at(1100, '/aligned'); const wide = await collapsed(w); await w.close();
    const t = await at(1100, '/aligned/themed'); const themed = await collapsed(t); await t.close();
    const m = await at(1100, '/measure'); const measure = await collapsed(m); await m.close();
    const mt = await at(1100, '/measure/themed'); const measureThemed = await collapsed(mt); await mt.close();
    const m8 = await at(800, '/measure'); const measure800 = await collapsed(m8); await m8.close();
    const mn = await at(360, '/measure'); await mn.locator('.ui-navbar__toggle').click(); await mn.waitForTimeout(150);
    const measureNarrow = await starts(mn); await mn.close();
    // Beside a 15px scrollbar, in Chromium only (see `scrolling`): a context of its own, native.
    const beside = async (path) => { const c = await scrolling.newContext({ viewport: { width: 1470, height: 740 } }); const b = await c.newPage();
      b.on('pageerror', e => errs.push(String(e))); await b.goto(new URL(path, url).href); const o = await collapsed(b); await c.close(); return o; };
    const scrollbar = scrolling ? await beside('/aligned/tall') : null, measureScrollbar = scrolling ? await beside('/measure/tall') : null;
    return { engine, narrow, wide, themed, scrollbar, measure, measureThemed, measure800, measureNarrow, measureScrollbar, errs };
  }, s => {
    const aligned = [s.narrow, s.wide, s.themed, ...(s.scrollbar ? [s.scrollbar] : [])], measure = [s.measure, s.measureThemed, s.measure800, s.measureNarrow, ...(s.measureScrollbar ? [s.measureScrollbar] : [])];
    const one = (o, at) => o.brand === at && o.link === at && o.more === at && o.end === at;
    return s.errs.length === 0 && [...aligned, ...measure].every(o => o.sides[0] === o.sides[1] && o.sides[2] === o.sides[3])
      // :aligned, main's text: (1100 - 768) / 2 + 16; a host's (1100 - 640) / 2 + 24; beside the scrollbar (1455 - 768) / 2 + 16.
      && aligned.every(o => one(o, o.text)) && s.narrow.text === 16 && s.wide.text === 182 && s.themed.text === 254
      // :measure, the brand: the column's edge plus the bar's padding (--ui-spacing-md: 1.15rem from 1024, 1rem from 640,
      // 0.85rem below), to the half pixel: 166 + 18.4; (1100 - 640) / 2 + 18.4; 16 + 16; 0 + 13.6; (1455 - 768) / 2 + 18.4.
      && one(s.measure, 184.5) && one(s.measureThemed, 248.5) && one(s.measure800, 32) && one(s.measureNarrow, 13.5)
      && (s.engine === 'chromium' ? s.scrollbar.cw === 1455 && s.scrollbar.text === 359.5 && s.measureScrollbar.cw === 1455 && one(s.measureScrollbar, 362)
                                  : s.scrollbar === null && s.measureScrollbar === null);
  });
  await check('navbar: under :aligned, items that outgrow the column by a little collapse it', async () => {
    const [q, errs] = await pageAt(1470, '/aligned'); await frames(q);
    // Type that makes the items about 60px wider than the room the column leaves them.
    const room = await q.evaluate(() => {
      const bar = document.querySelector('.ui-navbar'), items = bar.querySelector('.ui-navbar__items'), cs = getComputedStyle(bar);
      const content = bar.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
      const used = [...bar.children].filter(c => c.checkVisibility()).reduce((a, c) => a + c.getBoundingClientRect().width, 0);
      const fs = parseFloat(getComputedStyle(items).fontSize), iw = items.getBoundingClientRect().width, px = fs * (iw + content - used + 60) / iw;
      const before = bar.classList.contains('ui-navbar--collapsed');
      const sheet = new CSSStyleSheet(); sheet.replaceSync(`.ui-navbar__items { font-size: ${px}px; }`); document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
      return { content, used, px, before };
    });
    const after = await q.waitForFunction(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'), null, { timeout: 3000 }).then(() => true, () => false);
    await q.close(); return { ...room, after, errs };
  }, s => !s.before && s.content === 736 && s.used > 0 && s.after && s.errs.length === 0);
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
  await check('navbar: its toggle a compact button, a target of 24px at least, clear of the bar\'s edges and centred in it', async () => {
    // 24px is WCAG 2.5.8's target. Clear is declared: the scale's smallest step, spacing-xxs, on
    // each side within the bar's row, which is the bar less its one-pixel bottom edge.
    const [q, errs] = await pageAt(360, '/');
    const s = await q.evaluate(() => { const t = document.querySelector('.ui-navbar__toggle').getBoundingClientRect(), b = document.querySelector('.ui-navbar').getBoundingClientRect();
      const d = document.createElement('div'); d.style.setProperty('inline-size', 'var(--ui-spacing-xxs)'); document.body.append(d); const step = d.getBoundingClientRect().width; d.remove();
      return { h: t.height, bar: b.height, step, above: t.top - b.top, below: b.bottom - t.bottom }; });
    await q.close(); return { ...s, errs };
  }, s => s.step > 0 && s.h >= 24 && s.h <= s.bar - 1 - 2 * s.step && Math.abs(s.above - s.below) <= 1.5);
  // /second's need is wherever its items stop fitting; it is found, not assumed: the narrowest
  // width from its sm breakpoint to the gallery's 768px container at which the bar is inline,
  // swept upward 2px at a time, once per mode. The checks that work around it read it: under
  // it by 10px the bar is collapsed, past it by 20px it is inline. A need under 650px would put
  // that collapse below the sm breakpoint, collapsed by it rather than by the items: those reds.
  let needFound;
  const secondNeed = async () => {
    if (needFound !== undefined) return needFound;
    const [q] = await pageAt(640, '/second'); needFound = null;
    for (let w = 640; w <= 768 && needFound === null; w += 2) {
      await q.setViewportSize({ width: w, height: 740 }); await q.waitForTimeout(100); await frames(q);
      if (await q.evaluate(() => { const l = document.querySelector('.ui-navbar__link').getBoundingClientRect(), b = document.querySelector('.ui-navbar').getBoundingClientRect();
        return l.height > 0 && l.top >= b.top && l.bottom <= b.bottom; })) needFound = w;
    }
    await q.close(); return needFound;
  };
  await check('navbar: past its breakpoint it collapses to its toggle while its items do not fit, inline once they do, and stays one row', async () => {
    const [q, errs] = await pageAt(640, '/second');
    const state = () => q.evaluate(() => { const bar = document.querySelector('.ui-navbar'), t = document.querySelector('.ui-navbar__toggle'), l = document.querySelector('.ui-navbar__link').getBoundingClientRect(), b = bar.getBoundingClientRect();
      return { collapsed: bar.classList.contains('ui-navbar--collapsed'), toggle: getComputedStyle(t).display !== 'none', linkInBar: l.height > 0 && l.top >= b.top && l.bottom <= b.bottom, h: b.height,
        row: parseFloat(getComputedStyle(document.documentElement).scrollPaddingTop) }; });
    // One row is the bar's own row at that width — the brand's line, its padding and the edge,
    // which the sticky bar's scroll padding repeats — and the brand's size is the scale's, which
    // grows past 640px, so the bar is held to the row of each width, never to one height.
    const at = async (w) => { await q.setViewportSize({ width: w, height: 740 }); await q.waitForTimeout(100); await frames(q); return state(); };
    const need = await secondNeed(); if (need !== null) await at(need);
    if (need === null) { await q.close(); return { need, errs }; }
    const fits = await state(), under = await at(need - 6), s600 = await at(600), s320 = await at(320), back = await at(need), wide = await at(1024);
    await q.close(); return { need, fits, under, s600, s320, back, wide, errs };
  }, s => s.need >= 646 && !s.fits.collapsed && !s.fits.toggle && s.under.collapsed && s.under.toggle && !s.under.linkInBar
        && s.s600.toggle && !s.s600.linkInBar && s.s320.toggle && !s.back.collapsed && !s.back.toggle && s.back.linkInBar && s.wide.linkInBar
        && [s.fits, s.under, s.s600, s.s320, s.back, s.wide].every(x => x.row > 0 && Math.abs(x.h - x.row) <= 0.5) && s.errs.length === 0);
  await check('navbar: with a banner above its header and many links, the open panel starts at the bar and ends within the screen', async () => {
    const q = await fresh(); await q.setViewportSize({ width: 320, height: 400 }); await q.goto(url);
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
    const need = await secondNeed(); if (need === null) return { need };
    const [q, errs] = await pageAt(need + 20, '/second');
    for (const w of [need - 10, need - 2, need + 20]) await growShrink(q, w);
    const err = await q.evaluate(() => window.__err); await q.close(); return { need, err, errs };
  }, s => s.need >= 650 && s.err.length === 0 && s.errs.length === 0);
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
    const need = await secondNeed(); if (need === null) return { need };
    const [q, errs] = await pageAt(need + 20, '/second');
    await q.keyboard.press('Shift'); await q.evaluate(() => document.querySelector('.ui-navbar__link[href="/"]').focus());
    const before = await q.evaluate(() => document.activeElement.getAttribute('href'));
    await q.setViewportSize({ width: need - 10, height: 740 }); await q.waitForTimeout(100); await frames(q);
    const after = await q.evaluate(() => document.activeElement.className); await q.close(); return { need, before, after, errs };
  }, s => s.need >= 650 && s.before === '/' && /ui-navbar__toggle/.test(s.after) && s.errs.length === 0);
  await check('navbar: collapsed past its breakpoint, its toggle opens the panel and it stays open, through a change of its content too', async () => {
    const need = await secondNeed(); if (need === null) return { need };
    const [q, errs] = await pageAt(need - 10, '/second'); await frames(q);
    const collapsed = await q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    await q.locator('.ui-navbar__toggle').click(); await q.waitForTimeout(1000); await frames(q);
    const opened = await q.evaluate(() => document.getElementById('g-nav').matches(':popover-open'));
    await q.evaluate(() => document.querySelector('.ui-navbar__end').append(document.createElement('span'))); await q.waitForTimeout(300); await frames(q);
    const afterEdit = await q.evaluate(() => document.getElementById('g-nav').matches(':popover-open'));
    const err = await q.evaluate(() => window.__err); await q.close(); return { need, collapsed, opened, afterEdit, err, errs };
  }, s => s.need >= 650 && s.collapsed && s.opened && s.afterEdit && s.err.length === 0 && s.errs.length === 0);
  // E1: the breakpoints are in em, a media query's em being the browser's default font size.
  // Chromium takes that size through CDP (Page.setFontSizes); WebKit has no such setting, so
  // there the check holds the 16px half only. `/` draws its bar from :md, 48em: 768px at 16,
  // 960px at 20. Below it the toggle shows by the query alone — ui.js's fit adds
  // ui-navbar--collapsed only to a bar its query already made inline — so a shown toggle with
  // no class is the breakpoint's doing. Each width is read on a page of its own, the setting
  // made before it loads.
  await check('navbar: its breakpoint follows the reader\'s default font size — below 48em its toggle shows by the query alone, never by fit (Chromium at a 20px default; WebKit has no setting and holds the 16px half only)', async () => {
    const at = async (width, size) => {
      const q = await fresh(); await q.setViewportSize({ width, height: 740 });
      const errs = []; q.on('pageerror', e => errs.push(String(e)));
      if (size) await (await q.context().newCDPSession(q)).send('Page.setFontSizes', { fontSizes: { standard: size } });
      await q.goto(url); await frames(q);
      const s = await q.evaluate(() => {
        const bar = document.querySelector('.ui-navbar'), items = bar.querySelector('.ui-navbar__items'), end = bar.querySelector('.ui-navbar__end');
        const span = document.createElement('span'); span.style.fontSize = 'medium'; document.documentElement.append(span);
        const medium = getComputedStyle(span).fontSize; span.remove();
        return { medium, mq: matchMedia('(min-width: 48em)').matches, toggleShown: getComputedStyle(bar.querySelector('.ui-navbar__toggle')).display !== 'none',
          collapsed: bar.classList.contains('ui-navbar--collapsed'), linkInBar: items.checkVisibility() && items.getBoundingClientRect().height > 0 && !bar.querySelector('.ui-navbar__panel').matches(':popover-open'),
          free: end.firstElementChild.getBoundingClientRect().left - items.getBoundingClientRect().right };
      });
      await q.close(); return { ...s, errs };
    };
    const knob = engine === 'chromium';
    const out = { engine, s760: await at(760), s780: await at(780) };
    if (knob) { out.s950 = await at(950, 20); out.s970 = await at(970, 20);
      // A page after them, with no setting of its own: the size does not leak to the next check.
      const after = await at(780); out.leak = after.medium; }
    return out;
  }, o => {
    const below = (s, size) => s.medium === size && !s.mq && s.toggleShown && !s.collapsed && !s.linkInBar && s.errs.length === 0;
    // 20px between the two widths of each pair, over which no token of the bar changes: room
    // of 20 at the wider one is room at the narrower one, so the query alone made the toggle.
    const above = (s, size) => s.medium === size && s.mq && !s.toggleShown && !s.collapsed && s.linkInBar && s.free >= 20 && s.errs.length === 0;
    return below(o.s760, '16px') && above(o.s780, '16px')
      && (o.engine === 'chromium' ? below(o.s950, '20px') && above(o.s970, '20px') && o.leak === '16px' : o.s950 === undefined);
  });
  await check('navbar: a stylesheet that widens its items collapses it, with no error', async () => {
    const [q, errs] = await pageAt(1100, '/'); await frames(q);
    const before = await q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    // The bar spans the window, so the widening is derived from it: type that makes the items'
    // text three times the bar's width, whatever that width is.
    const scale = await q.evaluate(() => {
      const bar = document.querySelector('.ui-navbar'), items = bar.querySelector('.ui-navbar__items');
      const fs = parseFloat(getComputedStyle(items).fontSize), w = bar.clientWidth, iw = items.getBoundingClientRect().width;
      const px = fs * 3 * w / iw; const sheet = new CSSStyleSheet(); sheet.replaceSync(`.ui-navbar__items { font-size: ${px}px; }`);
      document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet]; return { fs, w, iw, px };
    });
    await q.waitForTimeout(300); await frames(q);
    const after = await q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    const err = await q.evaluate(() => window.__err); await q.close(); return { before, scale, after, err, errs };
  }, s => !s.before && s.scale.iw > 0 && s.scale.w > 0 && s.after && s.err.length === 0 && s.errs.length === 0);
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
  await check('navbar: without a brand it still collapses when its items do not fit and expands when they do, with no error', async () => {
    const [q, errs] = await pageAt(1024, '/second'); await frames(q);
    await q.evaluate(() => document.querySelector('.ui-navbar__brand').remove()); await q.waitForTimeout(100); await frames(q);
    const set = async (t) => { await q.evaluate(t => { document.querySelector('.ui-navbar__link[href="/second"]').textContent = t; }, t); await q.waitForTimeout(100); await frames(q);
      return q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed')); };
    const grown = await set('A second page whose name is far too long to share one row with all the others'), shrunk = await set('X');
    const err = await q.evaluate(() => window.__err); await q.close(); return { grown, shrunk, err, errs };
  }, s => s.grown && !s.shrunk && s.err.length === 0 && s.errs.length === 0);
  await check('navbar: a collapse leaves alone a focus that was not in its row', async () => {
    const need = await secondNeed(); if (need === null) return { need };
    const [q, errs] = await pageAt(need + 20, '/second'); await frames(q);
    const inline = await q.evaluate(() => !document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    await q.evaluate(() => { const i = document.createElement('input'); i.id = 'outside'; document.querySelector('main').prepend(i); i.focus(); });
    await q.setViewportSize({ width: need - 10, height: 740 }); await q.waitForTimeout(100); await frames(q);
    const s = await q.evaluate(() => ({ collapsed: document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'), focus: document.activeElement.id }));
    await q.close(); return { need, inline, ...s, errs };
  }, s => s.need >= 650 && s.inline && s.collapsed && s.focus === 'outside' && s.errs.length === 0);
  await check('navbar: swaps of the page leave one content observer on its bar', async () => {
    const q = await fresh(); await q.setViewportSize({ width: 1024, height: 740 });
    await q.addInitScript(() => { const observe = MutationObserver.prototype.observe; window.__barObservers = new Set();
      MutationObserver.prototype.observe = function (target, options) { if (target.matches && target.matches('.ui-navbar')) window.__barObservers.add(this); return observe.call(this, target, options); }; });
    await q.goto(url); await frames(q);
    for (const path of ['/second', '/', '/second']) { await q.locator(`.ui-navbar__link[href="${path}"]`).click(); await q.waitForFunction(p => location.pathname === p, path); await frames(q); }
    const n = await q.evaluate(() => window.__barObservers.size); await q.close(); return { n };
  }, s => s.n === 1);
  // What each frame paints: a ResizeObserver on a 1px element that every frame's first rAF resizes is
  // delivered after every rAF callback (the kit's deferred fit too) and after layout, just before
  // paint. A frame counts when the bar is drawn inline, overflows or cuts its brand, and lacks the
  // class, once ui.js has started (bar.uiMo): a frame painted before a deferred script runs is no
  // script's to prevent.
  await check('navbar: a page loaded at a width where its items do not fit paints no overflowing frame once ui.js runs', async () => {
    const sampler = `window.__paint=[];(function(){let d,k=0;const ro=new ResizeObserver(()=>{const bar=document.querySelector('.ui-navbar');if(!bar)return;
      const t=bar.querySelector('.ui-navbar__toggle'),b=bar.querySelector('.ui-navbar__brand');
      const over=!t.checkVisibility()&&(bar.scrollWidth>bar.clientWidth||(b&&b.scrollWidth>b.clientWidth))&&!bar.classList.contains('ui-navbar--collapsed');
      window.__paint.push([over?1:0,bar.uiMo?1:0]);});
      (function tick(){if(!d&&document.body){d=document.createElement('div');d.style.setProperty('position','absolute');d.style.setProperty('height','1px');document.body.append(d);ro.observe(d);}
        if(d)d.style.setProperty('width',(1+(k++%2))+'px');requestAnimationFrame(tick);})();})();`;
    const loads = [];
    for (let i = 0; i < 4; i++) {
      const c = await browser.newContext({ viewport: { width: 650, height: 700 } }); await c.addInitScript(base + init); await c.addInitScript(sampler);
      if (process.env.MUT_PATH) { const fs = await import('node:fs'); const body = fs.readFileSync(process.env.MUT_FILE, 'utf8'); await c.route('**' + process.env.MUT_PATH, rt => rt.fulfill({ status: 200, contentType: 'text/javascript', body })); }
      const q = await c.newPage(); await q.goto(new URL('/second', url).href);
      await q.waitForFunction(() => window.__paint.length > 40, null, { timeout: 10000 });
      loads.push(await q.evaluate(() => ({ frames: window.__paint.length, overAfterUi: window.__paint.filter(x => x[0] && x[1]).length,
        collapsed: document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'), err: window.__err.length })));
      await c.close();
    }
    return loads;
  }, s => s.every(l => l.frames > 40 && l.overAfterUi === 0 && l.collapsed && l.err === 0));
  await check('navbar: wide, the current item\'s rail lies on the bar\'s bottom edge, and the caret of its dropdown stands apart from its label', async () => {
    const [q, errs] = await pageAt(1024, '/'); await frames(q);
    const s = await q.evaluate(() => {
      const a = document.querySelector('.ui-navbar__link[aria-current="page"]'), cs = getComputedStyle(a, '::after');
      const more = document.querySelector('[popovertarget="g-nav-more"]'), range = document.createRange(); range.selectNodeContents(more.firstChild);
      const label = range.getBoundingClientRect().right, caret = more.getBoundingClientRect().right - parseFloat(getComputedStyle(more).paddingRight);
      return { railBottom: a.getBoundingClientRect().bottom - parseFloat(cs.insetBlockEnd), barBottom: document.querySelector('.ui-navbar').getBoundingClientRect().bottom,
        railShown: cs.opacity, labelRight: label, moreRight: caret };
    });
    await q.close(); return { ...s, errs };
  }, s => Math.abs(s.railBottom - s.barBottom) <= 0.5 && s.railShown === '1' && s.moreRight - s.labelRight >= 8 && s.errs.length === 0);
  await check('navbar: wide, under forced colours, a hovered item is underlined and the current one keeps its rail', async () => {
    // Forced colours drop the hover ground, and a hover has no rail: the underline is its mark there.
    const [q, errs] = await pageAt(1024, '/'); await q.emulateMedia({ forcedColors: 'active', reducedMotion: 'reduce' }); await frames(q);
    const look = (sel) => q.evaluate((sel) => { const el = document.querySelector(sel), r = getComputedStyle(el, '::after');
      return { forced: matchMedia('(forced-colors: active)').matches, hovered: el.matches(':hover'), line: getComputedStyle(el).textDecorationLine, rail: r.opacity, railStyle: r.borderBottomStyle }; }, sel);
    const link = '.ui-navbar__link[href="/second"]', more = '[popovertarget="g-nav-more"]';
    await q.mouse.move(0, 400); await frames(q); const resting = await look(link), current = await look('.ui-navbar__link[aria-current="page"]');
    await q.hover(link); await frames(q); const hovered = await look(link);
    await q.hover(more); await frames(q); const moreHovered = await look(more);
    await q.close(); return { resting, current, hovered, moreHovered, errs };
  }, s => s.resting.forced && s.resting.line === 'none' && s.hovered.hovered && s.hovered.line === 'underline'
        && s.moreHovered.hovered && s.moreHovered.line === 'underline' && s.current.rail === '1' && s.current.railStyle === 'solid' && s.errs.length === 0);
  await check('navbar: wide, a kit button of the default size at its end keeps the bar to its row', async () => {
    const [q, errs] = await pageAt(1024, '/'); await frames(q);
    // A host's identity is a sign-out form, so the button is put inside one, as auth-base's is.
    const s = await q.evaluate(() => new Promise(done => { const bar = document.querySelector('.ui-navbar'), btn = bar.querySelector('.ui-navbar__end .ui-btn');
      const row = () => parseFloat(getComputedStyle(document.documentElement).scrollPaddingTop);
      const small = btn.classList.contains('ui-btn--sm'); btn.classList.remove('ui-btn--sm');
      const form = document.createElement('form'); btn.before(form); form.append(btn);
      requestAnimationFrame(() => requestAnimationFrame(() => done({ small, sized: !btn.classList.contains('ui-btn--sm'), nested: btn.parentElement.tagName === 'FORM',
        h: bar.getBoundingClientRect().height, row: row(), inline: !bar.classList.contains('ui-navbar--collapsed') }))); }));
    await q.close(); return { ...s, errs };
  }, s => s.small && s.sized && s.nested && s.inline && s.row > 0 && Math.abs(s.h - s.row) <= 0.5 && s.errs.length === 0);
  await check('navbar: wide, the current item and a hovered or focused one lie on the hover ground, and only the current one shows a rail', async () => {
    const [q, errs] = await pageAt(1024, '/'); await frames(q);
    // The grounds and the rail transition; with motion reduced the kit has none, so what is read is the settled look.
    await q.emulateMedia({ reducedMotion: 'reduce' });
    const probe = await q.evaluate(() => Object.fromEntries(['--ui-surface-hover', '--ui-surface', '--ui-surface-high', '--ui-primary-border', '--ui-focus-ring'].map((v) => {
      const d = document.createElement('div'); d.style.setProperty('background-color', `var(${v})`); document.body.append(d);
      const c = getComputedStyle(d).backgroundColor; d.remove(); return [v, c]; })));
    const look = (sel) => q.evaluate((sel) => { const el = document.querySelector(sel), cs = getComputedStyle(el), r = getComputedStyle(el, '::after');
      return { ground: cs.backgroundColor, rail: r.opacity, railColor: r.borderBottomColor, after: r.content, hovered: el.matches(':hover'), focused: el.matches(':focus-visible'),
        ring: [cs.outlineStyle, cs.outlineColor] }; }, sel);
    const cur = '.ui-navbar__link[aria-current="page"]', link = '.ui-navbar__link[href="/second"]', more = '[popovertarget="g-nav-more"]';
    await q.mouse.move(0, 400); await frames(q);
    const collapsed = await q.evaluate(() => document.querySelector('.ui-navbar').classList.contains('ui-navbar--collapsed'));
    const current = await look(cur), resting = await look(link);
    await q.hover(link); await frames(q); const hovered = await look(link);
    await q.hover(more); await frames(q); const moreHovered = await look(more);
    await q.mouse.move(0, 400); await q.keyboard.press('Shift'); await q.focus(link); await frames(q); const focused = await look(link);
    await q.close(); return { probe, collapsed, current, resting, hovered, moreHovered, focused, errs };
  }, s => { const g = s.probe['--ui-surface-hover'];
    return g !== s.probe['--ui-surface'] && g !== s.probe['--ui-surface-high'] && !s.collapsed
      && s.current.ground === g && s.current.rail === '1' && s.current.railColor === s.probe['--ui-primary-border']
      && s.resting.ground !== g && s.resting.rail === '0'
      && s.hovered.hovered && s.hovered.ground === g && s.hovered.rail === '0'
      && s.moreHovered.hovered && s.moreHovered.ground === g && s.moreHovered.after === 'none'
      && s.focused.focused && !s.focused.hovered && s.focused.ground === g && s.focused.rail === '0'
      && s.focused.ring[0] === 'solid' && s.focused.ring[1] === s.probe['--ui-focus-ring']
      && s.errs.length === 0; });
  result.modes[mode] = r;
  await ctx.close();
  if (spare) await spare.close();
  await browser.close();
}
if (scrolling) await scrolling.close();
console.log(JSON.stringify(result, null, 1));
