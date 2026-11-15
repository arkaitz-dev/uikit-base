// uikit-base: the kit's one script, for what the platform does not yet do in every browser
// of the floor (invoker commands, anchor positioning, a dialog's closedby) and what no
// element does on its own (a toast's timeout, a carousel, a scrollspy). Every listener is
// on document, so nothing is re-bound when htmx swaps <main>; what watches elements is
// rebuilt after each swap. It writes no markup and no text: the server rendered both.
(function () {
  "use strict";
  if (window.uikitBaseLoaded) return;
  window.uikitBaseLoaded = true;

  // Read once, as the script loads, so a page that deletes a feature first runs the fallback.
  var nativeCommand = "commandForElement" in HTMLButtonElement.prototype;
  var nativeAnchor = CSS.supports("anchor-name: --a") && CSS.supports("anchor-scope: all");
  var nativeClosedBy = "closedBy" in HTMLDialogElement.prototype;
  var stillMotion = matchMedia("(prefers-reduced-motion: reduce)");

  // Invoker commands, where the browser has none: a button's commandfor opens or closes its
  // dialog, and the focus goes back to that button when the dialog closes, as it does natively.
  var invokers = new WeakMap();
  if (!nativeCommand) {
    document.addEventListener("click", function (event) {
      var button = event.target.closest("button[commandfor]");
      var dialog = button && document.getElementById(button.getAttribute("commandfor"));
      if (!(dialog instanceof HTMLDialogElement)) return;
      var command = button.getAttribute("command");
      if (command === "show-modal" && !dialog.open) { invokers.set(dialog, button); dialog.showModal(); }
      if ((command === "close" || command === "request-close") && dialog.open) dialog.close();
    });
    document.addEventListener("close", function (event) {
      var button = invokers.get(event.target);
      invokers.delete(event.target);
      if (button && button.isConnected) button.focus();
    }, true);
  }

  // A dialog's closedby, where the browser has none: "none" keeps Esc from closing it, and
  // "any" lets a click on the backdrop, outside the dialog's box, close it.
  if (!nativeClosedBy) {
    document.addEventListener("cancel", function (event) {
      if (event.target instanceof HTMLDialogElement && event.target.getAttribute("closedby") === "none") {
        event.preventDefault();
      }
    }, true);
    document.addEventListener("click", function (event) {
      var dialog = event.target;
      if (!(dialog instanceof HTMLDialogElement) || !dialog.open || dialog.getAttribute("closedby") !== "any") return;
      var box = dialog.getBoundingClientRect();
      var outside = event.clientX < box.left || event.clientX > box.right ||
        event.clientY < box.top || event.clientY > box.bottom;
      if (outside) dialog.close();
    });
  }

  // Placing a popover against the control before it, where CSS anchor positioning is
  // missing: below it and inline-start aligned (a tooltip above it, centred), flipped when
  // the viewport has no room, kept inside it inline.
  var open = ".ui-dropdown__menu:popover-open, .ui-popover:popover-open, .ui-tooltip:popover-open";
  function place(pop) {
    var a = pop.parentElement.firstElementChild.getBoundingClientRect(), p = pop.getBoundingClientRect();
    var tip = pop.classList.contains("ui-tooltip"), gap = 4;
    var below = a.bottom + gap, above = a.top - gap - p.height;
    var top = tip ? (above < 0 ? below : above) : (below + p.height > innerHeight && above >= 0 ? above : below);
    var rtl = getComputedStyle(pop).direction === "rtl";
    var left = tip ? a.left + (a.width - p.width) / 2 : (rtl ? a.right - p.width : a.left);
    pop.style.setProperty("margin", "0");
    pop.style.setProperty("top", top + "px");
    left = Math.max(0, Math.min(left, document.documentElement.clientWidth - p.width));
    pop.style.setProperty("left", left + "px");
  }
  function placeOpen() { if (!nativeAnchor) document.querySelectorAll(open).forEach(place); }
  document.addEventListener("toggle", function (event) { if (event.newState === "open") placeOpen(); }, true);
  addEventListener("resize", placeOpen);
  addEventListener("scroll", placeOpen, { capture: true, passive: true });

  // A dropdown's link or button closes it: htmx may swap <main> and leave the menu open.
  document.addEventListener("click", function (event) {
    var item = event.target.closest(".ui-dropdown__menu a, .ui-dropdown__menu button");
    if (item) item.closest("[popover]").hidePopover();
  });

  // Tooltips: shown while their trigger is hovered or focused, or the pointer rests on the
  // tooltip itself (WCAG 1.4.13); hidden when neither holds, or on Esc.
  function tipOf(node) {
    var wrap = node instanceof Element && node.closest(".ui-tooltip-wrap");
    return wrap ? wrap.querySelector(".ui-tooltip") : null;
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
  document.addEventListener("mouseover", showTip);
  document.addEventListener("focusin", showTip);
  document.addEventListener("mouseout", hideTip);
  document.addEventListener("focusout", hideTip);
  document.addEventListener("keydown", function (event) {
    if (event.key !== "Escape") return;
    document.querySelectorAll(".ui-tooltip:popover-open").forEach(function (t) { t.hidePopover(); });
  });

  var inits = [];
  function init() { inits.forEach(function (f) { f(); }); }

  document.addEventListener("htmx:after:swap", init);
  init();
})();
