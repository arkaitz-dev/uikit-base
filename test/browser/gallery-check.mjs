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

const [engine, url = 'http://localhost:3010/'] = process.argv.slice(2);
const base = `window.__csp=[];document.addEventListener("securitypolicyviolation",e=>window.__csp.push(e.violatedDirective));
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
  result.modes[mode] = r;
  await ctx.close();
}
await browser.close();
console.log(JSON.stringify(result, null, 1));
