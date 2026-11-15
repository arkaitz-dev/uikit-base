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

  var inits = [];
  function init() { inits.forEach(function (f) { f(); }); }

  document.addEventListener("htmx:after:swap", init);
  init();
})();
