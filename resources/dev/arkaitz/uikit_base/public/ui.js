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
  // A browser with anchor-name but no anchor-scope would apply the stylesheet's area too.
  var open = ":is(.ui-dropdown__menu, .ui-popover, .ui-tooltip, .ui-navbar__panel):popover-open";
  function place(pop) {
    var a = pop.parentElement.firstElementChild.getBoundingClientRect(), p = pop.getBoundingClientRect();
    var tip = pop.classList.contains("ui-tooltip"), gap = 4, rtl = getComputedStyle(pop).direction === "rtl";
    var below = a.bottom + gap, above = a.top - gap - p.height;
    var top = tip ? (above < 0 ? below : above) : (below + p.height > innerHeight && above >= 0 ? above : below);
    var left = tip ? a.left + (a.width - p.width) / 2 : (rtl ? a.right - p.width : a.left);
    left = Math.max(0, Math.min(left, document.documentElement.clientWidth - p.width));
    var props = { "position-area": "none", "position-try-fallbacks": "none", margin: "0" };
    Object.assign(props, { top: top + "px", left: left + "px" });
    Object.keys(props).forEach(function (name) { pop.style.setProperty(name, props[name]); });
  }
  function placeOpen() { if (!nativeAnchor) document.querySelectorAll(open).forEach(place); }
  on("toggle", function (event) { if (event.newState === "open") placeOpen(); }, true);
  ["resize", "scroll"].forEach((name) => addEventListener(name, placeOpen, { capture: true, passive: true }));

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
  // Esc hides an open tooltip, and only that: the dialog around it stays. Where closedby is
  // missing, Esc is refused too while the focus is in a closedby=none modal and no popover in
  // it is open, which Esc must still close.
  on("keydown", function (event) {
    if (event.key !== "Escape") return;
    var tips = [...document.querySelectorAll(".ui-tooltip:popover-open")].map((tip) => tip.hidePopover());
    // The innermost modal holding the focus; when the focus has fallen to body (a click on a
    // modal's text), the modal that held it last, which is the one on top: neither document
    // order nor :modal says which of two open modals is.
    // A modal that opened without the focus ever entering it leaves no mark: then the first open one.
    var modal = [...document.querySelectorAll(":modal:focus-within")].pop() || lastModal;
    modal = modal?.matches(":modal") ? modal : document.querySelector(":modal");
    var held = !nativeClosedBy && !document.querySelector(":popover-open") && modal?.matches("[closedby=none]");
    if (tips.length || held) event.preventDefault();
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

  // Scrollspies: the link whose section is in view is aria-current=location.
  function spy(nav) {
    var links = Array.from(nav.querySelectorAll("a[href^='#']")), seen = new Set();
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { seen[e.isIntersecting ? "add" : "delete"](e.target.id); });
      var here = links.find(function (a) { return seen.has(a.hash.slice(1)); });
      links.forEach(function (a) { if (here) a.toggleAttribute("aria-current", a === here); });
      if (here) here.setAttribute("aria-current", "location");
    }, { rootMargin: "0px 0px -50% 0px" });
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
  init();
})();
