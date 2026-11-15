(ns dev.arkaitz.uikit-base.utilities
  "The kit's utilities and its grid, generated once into `public/ui-utilities.css` and
  committed, like the default colours: `write!` after a change here, and `utilities_test`
  reds until the file and this namespace agree. Part of the engine side: a running host
  serves the committed file and never loads this.

  Every rule sits in `@layer uikit.utilities`, so a utility wins over a component by its
  layer, with no `!important`, and a host's unlayered stylesheet still wins over it.
  Responsive classes name basaku's breakpoints — sm 640, md 768, lg 1024, xl 1280, xxl
  1536 — not Bootstrap's 576/768/992/1200/1400. Spacing steps 0..5 read the kit's spacing
  scale, so a brand's density scales them.

  The grid is a twelve-track CSS grid (`ui-layout`), not Bootstrap's flex row with negative
  margins: a span is `grid-column-end`, an offset `grid-column-start`, so the two hold at
  different breakpoints at once, and a gutter is the gap. Content-sized and shared columns
  (`ui-col`, `ui-col-auto`, `ui-layout-cols-auto`) cannot be a span of twelve tracks: they
  turn their layout into a wrapping flex line, still gapped, and are not mixed with spans."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private breakpoints
  "basaku's breakpoints, by the infix a responsive class carries."
  [["sm" 640] ["md" 768] ["lg" 1024] ["xl" 1280] ["xxl" 1536]])

(def ^:private spacing
  "The spacing steps a class names, and the scale each one reads."
  [["0" "0"] ["1" "var(--ui-spacing-xs)"] ["2" "var(--ui-spacing-sm)"] ["3" "var(--ui-spacing-md)"]
   ["4" "var(--ui-spacing-lg)"] ["5" "var(--ui-spacing-xl)"]])

(defn- named
  "A class name: `ui-` and the prefix, the breakpoint's infix when there is one, then the value."
  [prefix bp value]
  (str "ui-" prefix (when bp (str "-" bp)) (when value (str "-" value))))

(defn- rule [selector & decls] [selector (vec (partition 2 decls))])

(defn- per-value
  "One rule per value: class `prefix[-bp]-value` setting each property to it."
  [prefix bp props values]
  (for [[v x] values] (apply rule (str "." (named prefix bp v)) (mapcat #(vector % x) props))))

(def ^:private track "minmax(0,1fr)")

(defn- grid-base
  "What only the unconditioned tier declares: the containers and the layout itself."
  []
  (concat
    [(rule (str/join "," (map #(str "." %) (cons "ui-container" (cons "ui-container-fluid" (map #(named "container" (first %) nil) breakpoints)))))
           "width" "100%" "padding-inline" "var(--ui-spacing-md)" "margin-inline" "auto")
     (rule ".ui-layout" "display" "grid" "grid-template-columns" (str "repeat(12," track ")")
           "column-gap" "var(--ui-spacing-lg)" "row-gap" "0")
     (rule ".ui-layout>*" "grid-column-end" "span 12" "min-width" "0")]))

(defn- containers
  "From breakpoint `i` up, a container of that tier or a narrower one is no wider than it."
  [i]
  (let [[_ px] (nth breakpoints i)]
    [(rule (str/join "," (map #(str "." %) (cons "ui-container" (for [[bp] (take (inc i) breakpoints)] (named "container" bp nil)))))
           "max-width" (str px "px"))]))

(defn- grid-tier
  "The grid classes of one tier: `bp` nil for the unconditioned one."
  [bp]
  (let [flow (fn [cls] (rule (str ".ui-layout:has(>." cls ")") "display" "flex" "flex-wrap" "wrap"))]
    (concat
      (let [c (named "layout-cols" bp "auto")]
        [(rule (str "." c) "display" "flex" "flex-wrap" "wrap")
         (rule (str "." c ">*") "flex" "0 0 auto")])
      (mapcat (fn [n] [(rule (str "." (named "layout-cols" bp (str n))) "display" "grid" "grid-template-columns" (str "repeat(" n "," track ")"))
                       (rule (str "." (named "layout-cols" bp (str n)) ">*") "grid-column-end" "span 1")])
              (range 1 7))
      [(rule (str "." (named "col" bp nil)) "flex" "1 0 0") (flow (named "col" bp nil))
       (rule (str "." (named "col" bp "auto")) "flex" "0 0 auto") (flow (named "col" bp "auto"))]
      (per-value "col" bp ["grid-column-end"] (for [n (range 1 13)] [(str n) (str "span " n)]))
      (per-value "offset" bp ["grid-column-start"] (for [n (range (if bp 0 1) 12)] [(str n) (if (zero? n) "auto" (str (inc n)))]))
      (per-value "g" bp ["row-gap" "column-gap"] spacing)
      (per-value "gx" bp ["column-gap"] spacing)
      (per-value "gy" bp ["row-gap"] spacing)
      (per-value "order" bp ["order"] (concat [["first" "-1"]] (for [n (range 6)] [(str n) (str n)]) [["last" "6"]])))))

(defn- kv [& pairs] (partition 2 pairs))

(def ^:private ring-halo "0 0 0 var(--ui-focus-outline-width) var(--ui-focus-ring-halo)")

(def ^:private shadows
  "Bootstrap's three shadows on three of basaku's, and none."
  [["shadow" "var(--ui-shadow-md)"] ["shadow-sm" "var(--ui-shadow-sm)"] ["shadow-lg" "var(--ui-shadow-lg)"] ["shadow-none" "none"]])

(def ^:private fills
  "Each fill a `ui-text-bg-*` sets, with the ink basaku audits on it."
  [["primary" "primary" "on-primary"] ["secondary" "secondary" "on-secondary"] ["success" "success" "on-success"]
   ["info" "info" "on-info"] ["warning" "warning" "on-warning"] ["danger" "danger" "on-danger"]
   ["dark" "surface-inverse" "on-surface-inverse"]])

(def ^:private inks
  "Each ink a text utility paints, by the name it takes: only inks basaku audits at AA or
  better on every canvas a background utility paints."
  [["primary" "link"] ["success" "success-ink"] ["info" "info-ink"] ["warning" "warning-ink"] ["danger" "danger-ink"]
   ["muted" "text-muted"] ["body-secondary" "text-muted"] ["body-emphasis" "text-muted-strong"]])

(def ^:private canvases
  "Each canvas a background utility paints, by the name it takes: the neutral surfaces alone."
  [["body" "background"] ["body-secondary" "surface-mid"] ["body-tertiary" "surface-low"] ["surface" "surface"]
   ["surface-alt" "surface-alt"] ["surface-low" "surface-low"] ["surface-mid" "surface-mid"] ["surface-high" "surface-high"]])

(def ^:private edges
  "Each edge colour a border utility draws: the audited non-text edges of each kind."
  [["primary" "primary-border"] ["secondary" "secondary-border"] ["success" "success-border"] ["info" "info-border"]
   ["warning" "warning-border"] ["danger" "danger-border"]])

(def ^:private edge "var(--ui-border-width-default) solid var(--ui-border-strong)")

(defn- helpers
  "Bootstrap's helpers, in its order."
  []
  (concat
    [(rule ".ui-clearfix::after" "display" "block" "clear" "both" "content" "\"\"")]
    (for [[k fill ink] fills] (rule (str ".ui-text-bg-" k) "color" (str "var(--ui-" ink ")") "background-color" (str "var(--ui-" fill ")")))
    (for [[k ink] (take 5 inks)] (rule (str ".ui-link-" k) "color" (str "var(--ui-" ink ")") "text-decoration-color" (str "var(--ui-" ink ")")))
    [(rule ".ui-focus-ring:focus-visible" "outline" "var(--ui-focus-outline-width) solid var(--ui-focus-ring)"
           "outline-offset" "var(--ui-focus-outline-width)" "box-shadow" ring-halo)
     (rule ".ui-icon-link" "display" "inline-flex" "gap" "0.375rem" "align-items" "center" "text-decoration-color" "currentcolor"
           "text-underline-offset" "0.25em" "backface-visibility" "hidden")
     (rule ".ui-icon-link>svg" "flex-shrink" "0" "width" "1em" "height" "1em" "fill" "currentcolor" "transition" "0.2s ease-in-out transform")
     (rule ".ui-icon-link-hover:hover>svg,.ui-icon-link-hover:focus-visible>svg" "transform" "translate3d(0.25em, 0, 0)")
     (rule ".ui-ratio" "position" "relative" "width" "100%")
     (rule ".ui-ratio>*" "position" "absolute" "top" "0" "inset-inline-start" "0" "width" "100%" "height" "100%")]
    (for [[k r] [["1x1" "1/1"] ["4x3" "4/3"] ["16x9" "16/9"] ["21x9" "21/9"]]] (rule (str ".ui-ratio-" k) "aspect-ratio" r))
    [(rule ".ui-fixed-top" "position" "fixed" "top" "0" "inset-inline" "0" "z-index" "var(--ui-z-index-banner)")
     (rule ".ui-fixed-bottom" "position" "fixed" "inset-inline" "0" "bottom" "0" "z-index" "var(--ui-z-index-banner)")]
    [(rule ".ui-hstack" "display" "flex" "flex-direction" "row" "align-items" "center" "align-self" "stretch")
     (rule ".ui-vstack" "display" "flex" "flex" "1 1 auto" "flex-direction" "column" "align-self" "stretch")
     (rule ".ui-visually-hidden,.ui-visually-hidden-focusable:not(:focus):not(:focus-within)"
           "width" "1px" "height" "1px" "padding" "0" "margin" "-1px" "overflow" "hidden" "clip" "rect(0, 0, 0, 0)" "white-space" "nowrap" "border" "0")
     (rule ".ui-visually-hidden:not(caption),.ui-visually-hidden-focusable:not(:focus):not(:focus-within):not(caption)" "position" "absolute")
     (rule ".ui-visually-hidden *,.ui-visually-hidden-focusable:not(:focus):not(:focus-within) *" "overflow" "hidden")
     (rule ".ui-stretched-link::after" "position" "absolute" "top" "0" "inset-inline" "0" "bottom" "0" "z-index" "1" "content" "\"\"")
     (rule ".ui-text-truncate" "overflow" "hidden" "text-overflow" "ellipsis" "white-space" "nowrap")
     (rule ".ui-vr" "display" "inline-block" "align-self" "stretch" "width" "var(--ui-border-width-default)" "min-height" "1em"
           "background-color" "var(--ui-border-strong)")]))

(defn- same [& vs] (for [v vs] [v v]))

(defn- responsive
  "The families Bootstrap repeats at each breakpoint, in its order; `bp` nil for the
  unconditioned tier. Order is the grid's."
  [bp]
  (let [steps   (fn [auto?] (concat spacing (when auto? [["auto" "auto"]])))
        sides   [["" ["margin"]] ["x" ["margin-inline"]] ["y" ["margin-top" "margin-bottom"]] ["t" ["margin-top"]]
                 ["e" ["margin-inline-end"]] ["b" ["margin-bottom"]] ["s" ["margin-inline-start"]]]
        pad     (fn [props] (mapv #(str/replace % "margin" "padding") props))]
    (concat
      (for [[side props] [["top" "top"] ["bottom" "bottom"]]]
        (rule (str "." (named "sticky" bp side)) "position" "sticky" props "0" "z-index" "var(--ui-z-index-sticky)"))
      (per-value "float" bp ["float"] [["start" "inline-start"] ["end" "inline-end"] ["none" "none"]])
      (per-value "object-fit" bp ["object-fit"] (concat (same "contain" "cover" "fill") [["scale" "scale-down"]] (same "none")))
      (per-value "d" bp ["display"] (same "inline" "inline-block" "block" "grid" "inline-grid" "table" "table-row" "table-cell" "flex" "inline-flex" "none"))
      (per-value "flex" bp ["flex"] [["fill" "1 1 auto"]])
      (per-value "flex" bp ["flex-direction"] (same "row" "column" "row-reverse" "column-reverse"))
      (per-value "flex" bp ["flex-grow"] [["grow-0" "0"] ["grow-1" "1"]])
      (per-value "flex" bp ["flex-shrink"] [["shrink-0" "0"] ["shrink-1" "1"]])
      (per-value "flex" bp ["flex-wrap"] (same "wrap" "nowrap" "wrap-reverse"))
      (per-value "justify-content" bp ["justify-content"] [["start" "flex-start"] ["end" "flex-end"] ["center" "center"]
                                                           ["between" "space-between"] ["around" "space-around"] ["evenly" "space-evenly"]])
      (per-value "align-items" bp ["align-items"] [["start" "flex-start"] ["end" "flex-end"] ["center" "center"] ["baseline" "baseline"] ["stretch" "stretch"]])
      (per-value "align-content" bp ["align-content"] [["start" "flex-start"] ["end" "flex-end"] ["center" "center"]
                                                       ["between" "space-between"] ["around" "space-around"] ["stretch" "stretch"]])
      (per-value "align-self" bp ["align-self"] (concat (same "auto") [["start" "flex-start"] ["end" "flex-end"]] (same "center" "baseline" "stretch")))
      (mapcat (fn [[side props]] (per-value (str "m" side) bp props (steps true))) sides)
      (mapcat (fn [[side props]] (per-value (str "p" side) bp (pad props) (steps false))) sides)
      (per-value "gap" bp ["gap"] spacing)
      (per-value "row-gap" bp ["row-gap"] spacing)
      (per-value "column-gap" bp ["column-gap"] spacing)
      (per-value "text" bp ["text-align"] (same "start" "end" "center")))))

(defn- utilities
  "The unconditioned utilities, in Bootstrap's order, the responsive ones among them."
  []
  (let [scroller (fn [prop v] (if (#{"auto" "scroll"} v)
                                ;; A region that scrolls is positioned, so what it hides — the
                                ;; kit's screen-reader text is absolute — is clipped by it.
                                [prop v "position" "relative"]
                                [prop v]))
        corners  {"top" ["border-start-start-radius" "border-start-end-radius"] "end" ["border-start-end-radius" "border-end-end-radius"]
                  "bottom" ["border-end-end-radius" "border-end-start-radius"] "start" ["border-end-start-radius" "border-start-start-radius"]}
        radii    [["" "var(--ui-radius-md)"] ["0" "0"] ["1" "var(--ui-radius-sm)"] ["2" "var(--ui-radius-md)"] ["3" "var(--ui-radius-lg)"]
                  ["4" "var(--ui-radius-xl)"] ["circle" "50%"] ["pill" "var(--ui-radius-full)"]]
        suffix   (fn [k] (when (seq k) (str "-" k)))]
    (concat
      (helpers)
      (per-value "align" nil ["vertical-align"] (same "baseline" "top" "middle" "bottom" "text-bottom" "text-top"))
      (per-value "opacity" nil ["opacity"] [["0" "0"] ["25" "0.25"] ["50" "0.5"] ["75" "0.75"] ["100" "1"]])
      (for [[prefix prop] [["overflow" "overflow"] ["overflow-x" "overflow-x"] ["overflow-y" "overflow-y"]] v ["auto" "hidden" "visible" "scroll"]]
        (apply rule (str "." (named prefix nil v)) (scroller prop v)))
      (for [[k v] shadows] (rule (str ".ui-" k) "box-shadow" v))
      ;; A shadow on a focused control would replace the ring's halo band, which is a
      ;; box-shadow too: each keeps the halo beneath its own.
      (for [[k v] shadows] (rule (str ".ui-" k ":focus-visible") "box-shadow" (if (= "none" v) ring-halo (str ring-halo ", " v))))
      (per-value "position" nil ["position"] (same "static" "relative" "absolute" "fixed" "sticky"))
      (mapcat (fn [[k prop]] (per-value k nil [prop] [["0" "0"] ["50" "50%"] ["100" "100%"]]))
              [["top" "top"] ["bottom" "bottom"] ["start" "inset-inline-start"] ["end" "inset-inline-end"]])
      [(rule ".ui-translate-middle" "transform" "translate(-50%, -50%)")
       (rule ".ui-translate-middle-x" "transform" "translateX(-50%)")
       (rule ".ui-translate-middle-y" "transform" "translateY(-50%)")]
      (mapcat (fn [[k prop]] [(rule (str ".ui-border" (suffix k)) prop edge) (rule (str ".ui-border" (suffix k) "-0") prop "0")])
              [["" "border"] ["top" "border-top"] ["end" "border-inline-end"] ["bottom" "border-bottom"] ["start" "border-inline-start"]])
      (for [[k token] edges] (rule (str ".ui-border-" k) "border-color" (str "var(--ui-" token ")")))
      (per-value "border" nil ["border-width"] [["1" "var(--ui-border-width-default)"] ["2" "var(--ui-border-width-thick)"] ["3" "3px"] ["4" "4px"] ["5" "5px"]])
      (per-value "w" nil ["width"] [["25" "25%"] ["50" "50%"] ["75" "75%"] ["100" "100%"] ["auto" "auto"]])
      [(rule ".ui-mw-100" "max-width" "100%") (rule ".ui-vw-100" "width" "100vw") (rule ".ui-min-vw-100" "min-width" "100vw")]
      (per-value "h" nil ["height"] [["25" "25%"] ["50" "50%"] ["75" "75%"] ["100" "100%"] ["auto" "auto"]])
      [(rule ".ui-mh-100" "max-height" "100%") (rule ".ui-vh-100" "height" "100vh") (rule ".ui-min-vh-100" "min-height" "100vh")]
      (responsive nil)
      [(rule ".ui-font-monospace" "font-family" "var(--ui-font-family-mono)")]
      ;; Six of basaku's sizes, top down; Bootstrap's 1.25rem and 1rem land a step lower,
      ;; basaku having no fourth size above xl. Each grows with its variable at 640px, so
      ;; Bootstrap's fluid sizes and their 1200px caps have no counterpart here.
      (per-value "fs" nil ["font-size"] (map-indexed (fn [i k] [(str (inc i)) (str "var(--ui-font-size-" k ")")]) ["xxxl" "xxl" "xl" "lg" "base" "sm"]))
      (per-value "fst" nil ["font-style"] (same "italic" "normal"))
      (per-value "fw" nil ["font-weight"] [["lighter" "lighter"] ["light" "var(--ui-font-weight-light)"] ["normal" "var(--ui-font-weight-regular)"]
                                           ["medium" "var(--ui-font-weight-medium)"] ["semibold" "var(--ui-font-weight-semibold)"]
                                           ["bold" "var(--ui-font-weight-bold)"] ["bolder" "bolder"]])
      (per-value "lh" nil ["line-height"] [["1" "var(--ui-line-height-none)"] ["sm" "var(--ui-line-height-tight)"]
                                           ["base" "var(--ui-line-height-normal)"] ["lg" "var(--ui-line-height-loose)"]])
      (per-value "text-decoration" nil ["text-decoration"] (same "none" "underline" "line-through"))
      (per-value "text" nil ["text-transform"] (same "lowercase" "uppercase" "capitalize"))
      [(rule ".ui-text-wrap" "white-space" "normal") (rule ".ui-text-nowrap" "white-space" "nowrap")
       (rule ".ui-text-break" "word-wrap" "break-word" "word-break" "break-word")]
      (for [[k ink] inks] (rule (str ".ui-text-" k) "color" (str "var(--ui-" ink ")")))
      (mapcat (fn [[n v]] [(rule (str ".ui-link-offset-" n) "text-underline-offset" v)
                           (rule (str ".ui-link-offset-" n "-hover:hover") "text-underline-offset" v)])
              [["1" "0.125em"] ["2" "0.25em"] ["3" "0.375em"]])
      (for [[k ink] (take 5 inks)] (rule (str ".ui-link-underline-" k) "text-decoration-color" (str "var(--ui-" ink ")")))
      (for [[k canvas] canvases] (rule (str ".ui-bg-" k) "background-color" (str "var(--ui-" canvas ")")))
      (per-value "user-select" nil ["user-select"] (same "all" "auto" "none"))
      (per-value "pe" nil ["pointer-events"] (same "none" "auto"))
      (for [[k v] radii] (rule (str ".ui-rounded" (suffix k)) "border-radius" v))
      (for [side ["top" "end" "bottom" "start"] [k v] radii] (apply rule (str ".ui-rounded-" side (suffix k)) (mapcat #(vector % v) (corners side))))
      [(rule ".ui-visible" "visibility" "visible") (rule ".ui-invisible" "visibility" "hidden")]
      (per-value "z" nil ["z-index"] [["n1" "-1"] ["0" "0"] ["1" "1"] ["2" "2"] ["3" "3"]]))))

(defn- block [rules]
  (str/join (for [[sel decls] rules] (str sel "{" (str/join ";" (for [[k v] decls] (str k ":" v))) "}\n"))))

(def ^:private header
  "/* Generated by uikit-base (dev.arkaitz.uikit-base.utilities/write!). Do not edit: change the generator and regenerate.
 * The kit's utilities and its twelve-column grid, all in @layer uikit.utilities: they win
 * over the kit's components by layer, never by !important, and a host's own unlayered
 * stylesheet still wins over them. Breakpoints are basaku's: 640, 768, 1024, 1280, 1536. */
")

(defn css
  "What `ui-utilities.css` must hold."
  []
  (str header
       "@layer uikit.base, uikit.components, uikit.utilities;\n"
       "@layer uikit.utilities{\n"
       (block (concat (grid-base) (grid-tier nil) (utilities)))
       (str/join (map-indexed (fn [i [bp px]] (str "@media (min-width:" px "px){\n" (block (concat (containers i) (grid-tier bp) (responsive bp))) "}\n"))
                              breakpoints))
       "@media print{\n" (block (per-value "d" "print" ["display"] (same "inline" "inline-block" "block" "grid" "inline-grid" "table" "table-row" "table-cell" "flex" "inline-flex" "none"))) "}\n"
       "@media (prefers-reduced-motion:reduce){\n" (block [(rule ".ui-icon-link>svg" "transition" "none")]) "}\n"
       "}\n"))

(defn write!
  "Writes the utilities file to `path`; answers the path."
  [path]
  (io/make-parents (io/file path))
  (spit (io/file path) (css))
  (str path))
