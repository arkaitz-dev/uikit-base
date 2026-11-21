// uikit-base: the kit's one script, for what some browser of the floor lacks and no element does
// alone. Its listeners are on document, so a swap of <main> re-binds nothing; it writes no text.
// web-base loads it once per document: the shell lists each script once, in the head htmx keeps.
(function () {
  "use strict";

  // Read once, as the script loads, so a page that deletes a feature first runs the fallback.
  var nativeCommand = "commandForElement" in HTMLButtonElement.prototype;
  var nativeAnchor = CSS.supports("anchor-name: --a") && CSS.supports("anchor-scope: all");
  var nativeClosedBy = "closedBy" in HTMLDialogElement.prototype;
  var stillMotion = matchMedia("(prefers-reduced-motion: reduce)");
  var inits = [], spies = [], lastModal, pressedOutside = false;
  function on(type, listener, capture) { document.addEventListener(type, listener, capture); }
  function closedBy(node) { return node instanceof HTMLDialogElement && node.open && node.getAttribute("closedby"); }

  // Invoker commands where missing: open or close a dialog, and give the focus back on close.
  var invokers = new WeakMap();
  if (!nativeCommand) {
    on("click", function (event) {
      var button = event.target.closest("button[commandfor]");
      var dialog = button && document.getElementById(button.getAttribute("commandfor"));
      if (!(dialog instanceof HTMLDialogElement)) return;
      var command = button.getAttribute("command");
      if (command === "show-modal" && !dialog.open) { invokers.set(dialog, button); dialog.showModal(); }
      // request-close closes a closedby=none dialog too, as the native command does (measured):
      // closedby governs the person's own close requests, never the page's.
      if ((command === "close" || command === "request-close") && dialog.open) dialog.close();
    });
    on("close", function (event) {
      var button = invokers.get(event.target);
      invokers.delete(event.target);
      if (button && button.isConnected) button.focus();
    }, true);
  }

  // A dialog's closedby where missing: "none" refuses Esc — its keydown too, since a browser
  // that lacks closedby may not let a second cancel be refused — and "any" closes on a press
  // that both starts and ends on the backdrop, as light dismiss does, never on a drag out.
  function outside(event) {
    var box = event.target.getBoundingClientRect(), x = event.clientX, y = event.clientY;
    return closedBy(event.target) === "any" && (x < box.left || x > box.right || y < box.top || y > box.bottom);
  }
  if (!nativeClosedBy) {
    on("cancel", function (event) { if (closedBy(event.target) === "none") event.preventDefault(); }, true);
    on("pointerdown", function (event) { pressedOutside = outside(event); });
    on("click", function (event) { if (pressedOutside && outside(event)) event.target.close(); });
  }

  // Where anchor positioning is missing: below the control (a tooltip above), flipped to fit.
  // A browser with anchor-name but no anchor-scope would apply the stylesheet's area too. A
  // navbar's panel is placed against its bar, at its very edge, never flipped (no room above),
  // and bounded by the screen's bottom from the top measured here, as the stylesheet's 100% is.
  // A dropdown opened up prefers above, one lined with the button's end starts from its end, and
  // one beside it stands at its top, at its inline start or end (+1 is the right), flipped to fit.
  var open = ":is(.ui-dropdown__menu, .ui-popover, .ui-tooltip, .ui-navbar__panel):popover-open";
  function place(pop) {
    var bar = pop.matches(".ui-navbar__panel") && pop.closest(".ui-navbar"), p = pop.getBoundingClientRect();
    var a = (bar || pop.parentElement.firstElementChild).getBoundingClientRect(), wrap = pop.parentElement.classList;
    var tip = pop.classList.contains("ui-tooltip"), gap = bar ? 0 : 4, rtl = getComputedStyle(pop).direction === "rtl";
    var side = (wrap.contains("ui-dropdown--end") - wrap.contains("ui-dropdown--start")) * (rtl ? -1 : 1);
    var below = a.bottom + gap, above = bar ? -1 : a.top - gap - p.height, vw = document.documentElement.clientWidth;
    var top = side ? a.top : tip || wrap.contains("ui-dropdown--up") ? (above < 0 ? below : above)
      : (below + p.height > innerHeight && above >= 0 ? above : below);
    var right = a.right + gap, before = a.left - gap - p.width, end = rtl !== wrap.contains("ui-dropdown--align-end");
    var left = side > 0 ? (right + p.width > vw && before >= 0 ? before : right) : side ? (before < 0 ? right : before)
      : tip ? a.left + (a.width - p.width) / 2 : (end ? a.right - p.width : a.left);
    left = Math.max(0, Math.min(left, vw - p.width));
    var props = { "position-area": "none", "position-try-fallbacks": "none", margin: "0" };
    Object.assign(props, { top: top + "px", left: left + "px", "max-block-size": bar ? innerHeight - top + "px" : "" });
    Object.keys(props).forEach(function (name) { pop.style.setProperty(name, props[name]); });
  }
  function placeOpen() { if (!nativeAnchor) document.querySelectorAll(open).forEach(place); }
  on("toggle", function (event) { if (event.newState === "open") placeOpen(); }, true);
  ["resize", "scroll"].forEach((name) => addEventListener(name, placeOpen, { capture: true, passive: true }));
  // Navbars are one row. Each fit drops the collapsed class, measures the bar inline and sets it
  // again only if the brand and the panel overflow: all in one task, which never paints, so no
  // cached width is needed and a shrink anywhere expands it. An open panel sits in the top layer,
  // out of the bar's flow, so it is never measured open: a panel open under a shown toggle is
  // left as it is, and one whose toggle went is closed — and loses what place() wrote, and only
  // that — before the bar is measured. A resize fits after a frame, never inside the observer's
  // delivery, whose observed sizes the class changes (a loop error, measured on a stylesheet
  // change); each bar's own MutationObserver fits it once per batch of node or text changes. When
  // the bar collapses under the focus, the focus goes to the toggle rather than to the body.
  var fits = new ResizeObserver((entries) => requestAnimationFrame(() => entries.forEach((e) => fit(e.target))));
  var placed = ["position-area", "position-try-fallbacks", "margin", "top", "left", "max-block-size"];
  function fit(node) {
    var bar = node.closest(".ui-navbar"), panel = bar?.querySelector(".ui-navbar__panel");
    var toggle = panel?.previousElementSibling, held = panel?.contains(document.activeElement);
    if (!toggle || (panel.matches(":popover-open") && toggle.checkVisibility())) return;
    bar.classList.remove("ui-navbar--collapsed");
    var inline = !toggle.checkVisibility(), brand = bar.querySelector(".ui-navbar__brand") || bar;
    if (inline) { panel.hidePopover(); placed.forEach((name) => panel.style.removeProperty(name)); }
    // Without a brand the bar's own overflow counts twice, which changes no answer: over or not.
    var over = inline && bar.scrollWidth + brand.scrollWidth - brand.clientWidth > bar.clientWidth;
    if (bar.classList.toggle("ui-navbar--collapsed", over) && held) toggle.focus();
  }
  inits.push(() => document.querySelectorAll(".ui-navbar, .ui-navbar__panel").forEach((e) => fits.observe(e)));
  // Fitted at once as well, in this task and never in an observer's delivery, so a page loaded
  // where its items do not fit paints no overflowing frame; before the stylesheets apply (WebKit)
  // the toggle still shows and this fit is a no-op, and the init on load fits it for real.
  inits.push(() => document.querySelectorAll(".ui-navbar").forEach((b) => {
    (b.uiMo ||= new MutationObserver(() => fit(b))).observe(b, { childList: true, subtree: true, characterData: true });
    fit(b);
  }));

  // Tooltips: shown while the trigger or the tooltip is hovered or focused (WCAG 1.4.13).
  // 150 ms is a declared policy: a pointer crosses the few pixels to the tooltip in a frame or
  // two, so a tooltip hides only once neither has been hovered or focused for that long.
  // One hide timer per tip, cleared when it is entered again, so a stale one never hides it.
  var tipOf = (node) => node?.closest?.(".ui-tooltip-wrap")?.querySelector(".ui-tooltip");
  function showTip(event) {
    var tip = tipOf(event.target);
    clearTimeout(tip?.uiHide);
    if (tip && !tip.matches(":popover-open")) { tip.showPopover(); placeOpen(); }
  }
  function hideTip(event) {
    var tip = tipOf(event.target);
    clearTimeout(tip?.uiHide);
    if (tip) tip.uiHide = setTimeout(function () {
      // The tip is in the top layer, whose :hover its wrap does not share (measured), so ask both.
      var held = tip.matches(":hover") || tip.parentElement?.matches(":hover, :focus-within");
      if (!held && tip.matches(":popover-open")) tip.hidePopover();
    }, 150);
  }
  ["mouseover", "focusin"].forEach(function (type) { on(type, showTip); });
  ["mouseout", "focusout"].forEach(function (type) { on(type, hideTip); });
  // Esc hides every open tooltip; one in the top modal keeps the Esc, so that modal stays, and
  // one outside it (still in its hide delay) does not, so the modal closes. Where closedby is
  // missing, Esc is refused too while the focus is in a closedby=none modal and no popover in
  // it is open, which Esc must still close.
  on("keydown", function (event) {
    if (event.key !== "Escape") return;
    // The innermost modal holding the focus; when the focus has fallen to body (a click on a
    // modal's text), the modal that held it last, which is the one on top: neither document
    // order nor :modal says which of two open modals is.
    // A modal that opened without the focus ever entering it leaves no mark: then the first open one.
    var modal = [...document.querySelectorAll(":modal:focus-within")].pop() || lastModal;
    modal = modal?.matches(":modal") ? modal : document.querySelector(":modal");
    var tips = [...document.querySelectorAll(".ui-tooltip:popover-open")].map((tip) => (tip.hidePopover(), tip));
    var held = !nativeClosedBy && !document.querySelector(":popover-open") && modal?.matches("[closedby=none]");
    if (tips.some((tip) => !modal || modal.contains(tip)) || held) event.preventDefault();
  }, true);

  // Toasts: one with a timeout goes once shown that long, the time stopped while hovered or focused.
  inits.push(function () {
    document.querySelectorAll(".ui-toast[data-timeout-ms]").forEach(function (toast) {
      var left = Number(toast.dataset.timeoutMs);
      toast.uiTimer = toast.uiTimer || setInterval(function () {
        if (!toast.matches(":hover, :focus-within")) left -= 250;
        if (left <= 0 || !toast.isConnected) { clearInterval(toast.uiTimer); toast.remove(); }
      }, 250);
    });
  });
  // A closed toast whose button had the focus hands it to the next toast's, or the one before.
  function dismiss(close) {
    var toast = close.closest(".ui-toast"), focused = document.activeElement === close;
    var next = toast.nextElementSibling, near = next?.matches(".ui-toast") ? next : toast.previousElementSibling;
    toast.remove();
    if (focused && near?.matches(".ui-toast")) near.querySelector(".ui-toast__close")?.focus();
  }

  // Carousels: a button scrolls a slide; an interval advances them, never under reduced motion.
  // Mid-scroll the position is between slides, so a press counts from the slide last aimed at.
  function go(carousel, step) {
    var view = carousel.querySelector(".ui-carousel__viewport"), n = view ? view.children.length : 0;
    if (!n || !view.clientWidth) return;
    // While a scroll this started is under way, scrollLeft has not caught up (it may not have
    // moved at all yet): count from the slide aimed at. 1000 ms bounds a smooth scroll, declared.
    var busy = performance.now() < (carousel.uiUntil || 0);
    var from = busy ? carousel.uiAt : Math.round(Math.abs(view.scrollLeft) / view.clientWidth);
    carousel.uiAt = (from + step + n) % n;
    carousel.uiUntil = performance.now() + 1000;
    var sign = getComputedStyle(view).direction === "rtl" ? -1 : 1;
    view.scrollTo({ left: sign * carousel.uiAt * view.clientWidth, behavior: stillMotion.matches ? "auto" : "smooth" });
  }
  function rotate(carousel, rotating) {
    carousel.toggleAttribute("data-ui-rotating", rotating);
    carousel.querySelector(".ui-carousel__viewport")?.setAttribute("aria-live", rotating ? "off" : "polite");
  }
  inits.push(function () {
    document.querySelectorAll(".ui-carousel").forEach(function (carousel) {
      var controls = carousel.querySelector(".ui-carousel__controls"), ms = Number(carousel.dataset.intervalMs);
      if (!controls || !controls.hidden || !carousel.querySelector(".ui-carousel__viewport")) return;
      controls.hidden = false;
      if (!ms || stillMotion.matches) return;
      rotate(carousel, true);
      var timer = setInterval(function () {
        if (!carousel.isConnected) return clearInterval(timer);
        if (carousel.matches("[data-ui-rotating]:not(:hover)") && !stillMotion.matches) go(carousel, 1);
      }, ms);
    });
  });
  on("focusin", function (event) {
    lastModal = event.target.closest(":modal") || lastModal;
    var carousel = event.target.closest(".ui-carousel");
    if (carousel && !event.target.closest(".ui-carousel__rotate")) rotate(carousel, false);
  });

  // A dropdown's item closes it, and any link in an open navbar panel the panel with what it nests
  // (htmx may keep them); a toast's close removes it; a carousel turns.
  on("click", function (event) {
    var item = event.target.closest(".ui-dropdown__menu a, .ui-dropdown__menu button, .ui-navbar :popover-open a");
    var close = event.target.closest(".ui-toast__close"), carousel = event.target.closest(".ui-carousel");
    var button = carousel && event.target.closest(".ui-carousel__controls button");
    if (item) (item.closest(".ui-navbar__panel:popover-open") || item.closest("[popover]")).hidePopover();
    if (close) dismiss(close);
    if (!button) return;
    if (button.classList.contains("ui-carousel__rotate")) rotate(carousel, !carousel.hasAttribute("data-ui-rotating"));
    else go(carousel, button.classList.contains("ui-carousel__next") ? 1 : -1);
  });

  // Scrollspies: the link whose section is in view is aria-current=location. A band the root's
  // scroll padding keeps clear — under a sticky bar — is no view: read as each spy is built.
  function spy(nav) {
    var links = Array.from(nav.querySelectorAll("a[href^='#']")), root = getComputedStyle(document.documentElement);
    var seen = new Set(), observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { seen[e.isIntersecting ? "add" : "delete"](e.target.id); });
      var here = links.find(function (a) { return seen.has(a.hash.slice(1)); });
      links.forEach(function (a) { if (here) a.toggleAttribute("aria-current", a === here); });
      if (here) here.setAttribute("aria-current", "location");
    }, { rootMargin: -(parseFloat(root.scrollPaddingTop) || 0) + "px 0px -50% 0px" });
    var sections = links.map(function (a) { return document.getElementById(a.hash.slice(1)); });
    sections.forEach(function (section) { if (section) observer.observe(section); });
    return observer;
  }
  inits.push(function () {
    spies.forEach(function (observer) { observer.disconnect(); });
    spies = Array.from(document.querySelectorAll(".ui-scrollspy"), spy);
  });

  function init() { inits.forEach(function (f) { f(); }); }
  on("htmx:after:swap", init);
  // Again on load, when the stylesheets have applied (WebKit may run a deferred script before
  // them, and a scrollspy built then reads no scroll padding): every init is idempotent.
  addEventListener("load", init);
  init();
})();
