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
  var inits = [];
  function on(type, listener, capture) { document.addEventListener(type, listener, capture); }

  // Invoker commands where missing: open or close a dialog, and give the focus back on close.
  var invokers = new WeakMap();
  if (!nativeCommand) {
    on("click", function (event) {
      var button = event.target.closest("button[commandfor]");
      var dialog = button && document.getElementById(button.getAttribute("commandfor"));
      if (!(dialog instanceof HTMLDialogElement)) return;
      var command = button.getAttribute("command");
      if (command === "show-modal" && !dialog.open) { invokers.set(dialog, button); dialog.showModal(); }
      if ((command === "close" || command === "request-close") && dialog.open) dialog.close();
    });
    on("close", function (event) {
      var button = invokers.get(event.target);
      invokers.delete(event.target);
      if (button && button.isConnected) button.focus();
    }, true);
  }

  // A dialog's closedby where missing: "none" refuses Esc, "any" closes on a backdrop click.
  function closedBy(node) { return node instanceof HTMLDialogElement && node.open && node.getAttribute("closedby"); }
  if (!nativeClosedBy) {
    on("cancel", function (event) { if (closedBy(event.target) === "none") event.preventDefault(); }, true);
    on("click", function (event) {
      var box = event.target.getBoundingClientRect(), x = event.clientX, y = event.clientY;
      var outside = x < box.left || x > box.right || y < box.top || y > box.bottom;
      if (closedBy(event.target) === "any" && outside) event.target.close();
    });
  }

  // Where anchor positioning is missing: below the control (a tooltip above), flipped to fit.
  var open = ".ui-dropdown__menu:popover-open, .ui-popover:popover-open, .ui-tooltip:popover-open";
  function place(pop) {
    var a = pop.parentElement.firstElementChild.getBoundingClientRect(), p = pop.getBoundingClientRect();
    var tip = pop.classList.contains("ui-tooltip"), gap = 4, rtl = getComputedStyle(pop).direction === "rtl";
    var below = a.bottom + gap, above = a.top - gap - p.height;
    var top = tip ? (above < 0 ? below : above) : (below + p.height > innerHeight && above >= 0 ? above : below);
    var left = tip ? a.left + (a.width - p.width) / 2 : (rtl ? a.right - p.width : a.left);
    pop.style.setProperty("margin", "0");
    pop.style.setProperty("top", top + "px");
    pop.style.setProperty("left", Math.max(0, Math.min(left, document.documentElement.clientWidth - p.width)) + "px");
  }
  function placeOpen() { if (!nativeAnchor) document.querySelectorAll(open).forEach(place); }
  on("toggle", function (event) { if (event.newState === "open") placeOpen(); }, true);
  addEventListener("resize", placeOpen);
  addEventListener("scroll", placeOpen, { capture: true, passive: true });

  // Tooltips: shown while the trigger or the tooltip is hovered or focused (WCAG 1.4.13).
  function tipOf(node) {
    return node instanceof Element ? node.closest(".ui-tooltip-wrap")?.querySelector(".ui-tooltip") : null;
  }
  function showTip(event) {
    var tip = tipOf(event.target);
    if (tip && !tip.matches(":popover-open")) { tip.showPopover(); placeOpen(); }
  }
  function hideTip(event) {
    var tip = tipOf(event.target), wrap = tip && tip.parentElement;
    if (!tip || tipOf(event.relatedTarget) === tip) return;
    var held = event.type === "mouseout" ? wrap.contains(document.activeElement) : wrap.matches(":hover");
    if (!held) tip.hidePopover();
  }
  ["mouseover", "focusin"].forEach(function (type) { on(type, showTip); });
  ["mouseout", "focusout"].forEach(function (type) { on(type, hideTip); });
  on("keydown", function (event) {
    if (event.key !== "Escape") return;
    document.querySelectorAll(".ui-tooltip:popover-open").forEach(function (tip) { tip.hidePopover(); });
  });

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

  // Carousels: a button scrolls a slide; an interval advances them, never under reduced motion.
  function go(carousel, step) {
    var view = carousel.querySelector(".ui-carousel__viewport"), n = view.children.length;
    var at = (Math.round(Math.abs(view.scrollLeft) / view.clientWidth) + step + n) % n;
    var sign = getComputedStyle(view).direction === "rtl" ? -1 : 1;
    view.scrollTo({ left: sign * at * view.clientWidth, behavior: stillMotion.matches ? "auto" : "smooth" });
  }
  function rotate(carousel, rotating) {
    carousel.toggleAttribute("data-ui-rotating", rotating);
    carousel.querySelector(".ui-carousel__viewport").setAttribute("aria-live", rotating ? "off" : "polite");
  }
  inits.push(function () {
    document.querySelectorAll(".ui-carousel").forEach(function (carousel) {
      var controls = carousel.querySelector(".ui-carousel__controls"), ms = Number(carousel.dataset.intervalMs);
      if (!controls.hidden) return;
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
    var carousel = event.target.closest(".ui-carousel");
    if (carousel && !event.target.closest(".ui-carousel__rotate")) rotate(carousel, false);
  });

  // A dropdown's item closes it, as htmx may keep it; a toast's close removes it; a carousel turns.
  on("click", function (event) {
    var item = event.target.closest(".ui-dropdown__menu a, .ui-dropdown__menu button");
    var close = event.target.closest(".ui-toast__close"), carousel = event.target.closest(".ui-carousel");
    var button = carousel && event.target.closest(".ui-carousel__controls button");
    if (item) item.closest("[popover]").hidePopover();
    if (close) close.closest(".ui-toast").remove();
    if (!button) return;
    if (button.classList.contains("ui-carousel__rotate")) rotate(carousel, !carousel.hasAttribute("data-ui-rotating"));
    else go(carousel, button.classList.contains("ui-carousel__next") ? 1 : -1);
  });

  // Scrollspies: the link whose section is in view is aria-current=location.
  var spies = [];
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
