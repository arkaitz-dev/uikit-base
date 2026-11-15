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

  var inits = [];
  function init() { inits.forEach(function (f) { f(); }); }

  document.addEventListener("htmx:after:swap", init);
  init();
})();
