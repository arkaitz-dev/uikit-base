(ns dev.arkaitz.uikit-base.scales
  "The kit's scales — spacing, radius, type, shadows, line heights and the rest — as basaku
  writes them (`themes/default.zig`), shaped by the four personality axes of
  `personality.zig`: `:density` (`:compact` `:cozy` `:comfortable`), `:roundness` (`:sharp`
  `:soft` `:round`), `:typography` (`:sans` `:serif` `:mono`) and `:elevation` (`:flat`
  `:normal` `:raised`). Spacing, type and shadows grow at basaku's breakpoints, 40em and
  64em (640px and 1024px at the default font size); shadows differ by scheme. Arithmetic
  is basaku's own, in 32-bit floats, and the numbers are printed as it prints them, so the
  declarations are its declarations under the kit's `--ui-` prefix. Part of the engine: a
  running host reads them from its generated file, never from here."
  (:require [clojure.string :as str]))

(def ^:private densities {:compact (float 0.8) :cozy (float 1.0) :comfortable (float 1.25)})

(def ^:private radii
  {:sharp {:sm 0.0 :md 0.0 :lg 0.0 :xl 0.0}
   :soft  {:sm 0.125 :md 0.375 :lg 0.5 :xl 0.75}
   :round {:sm 0.375 :md 0.75 :lg 1.0 :xl 1.5}})

(def ^:private line-heights {:sans 1.5 :serif 1.65 :mono 1.45})

(def ^:private stacks
  {:sans  "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
   :serif "Georgia, Cambria, 'Times New Roman', Times, serif"
   :mono  "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace"})

(def ^:private rungs
  "The layout spacing rungs: their value below 40em, from 40em and from 64em."
  [[:md 0.85 1.0 1.15] [:lg 1.25 1.5 1.75] [:xl 2.0 2.5 3.0] [:xxl 3.0 4.0 5.0] [:xxxl 4.5 6.0 7.5]])

(defn- sh [y blur spread a] {:y (float y) :blur (float blur) :spread (some-> spread float) :a a})

(def ^:private default-shadows
  {:sm {:light (sh 1 3 nil "0.1") :dark (sh 1 3 nil "0.35")}
   :md {:light {:default (sh 3 5 -1 "0.1") :sm (sh 4 6 -1 "0.1") :lg (sh 5 8 -1 "0.1")}
        :dark  {:default (sh 3 5 -1 "0.4") :sm (sh 4 6 -1 "0.4") :lg (sh 5 8 -1 "0.4")}}
   :lg {:light {:default (sh 8 12 -2 "0.1") :sm (sh 10 15 -3 "0.1") :lg (sh 12 18 -4 "0.1")}
        :dark  {:default (sh 8 12 -2 "0.4") :sm (sh 10 15 -3 "0.4") :lg (sh 12 18 -4 "0.4")}}
   :xl {:light {:default (sh 15 20 -4 "0.1") :sm (sh 20 25 -5 "0.1") :lg (sh 24 30 -6 "0.1")}
        :dark  {:default (sh 15 20 -4 "0.4") :sm (sh 20 25 -5 "0.4") :lg (sh 24 30 -6 "0.4")}}})

(defn- scale-shadow [s f]
  (-> s (update :y #(float (* % f))) (update :blur #(float (* % f))) (update :spread #(some-> % (* f) float))))

(defn- shadows
  "personality.zig:305-330: normal is the default; flat has none; raised steps each scale
  one rung up, the top one ×1.5."
  [elevation]
  (let [d default-shadows f (float 1.5)]
    (case elevation
      :normal d
      :flat   {:sm {:light :none :dark :none}
               :md {:light {:default :none :sm :none :lg :none} :dark {:default :none :sm :none :lg :none}}
               :lg {:light {:default :none :sm :none :lg :none} :dark {:default :none :sm :none :lg :none}}
               :xl {:light {:default :none :sm :none :lg :none} :dark {:default :none :sm :none :lg :none}}}
      :raised {:sm {:light (get-in d [:md :light :default]) :dark (get-in d [:md :dark :default])}
               :md (:lg d) :lg (:xl d)
               :xl (into {} (for [side [:light :dark]] [side (into {} (for [[k s] (get-in d [:xl side])] [k (scale-shadow s f)]))]))})))

(defn- number
  "A 32-bit float as basaku's `formatFloat` prints it (builder.zig:1059-1093): a whole
  number as one, else truncated — not rounded — to three decimals, trailing zeros trimmed."
  [x]
  (let [v (float x)]
    (cond (zero? v) "0"
          (== (float (int v)) v) (str (int v))
          :else (let [scaled (int (float (* v (float 1000))))
                      whole (quot scaled 1000)
                      frac  (Math/abs (int (mod scaled 1000)))
                      digits (format "%03d" frac)
                      digits (str/replace digits #"(?<=.)0+$" "")]
                  (str whole "." digits)))))

(defn- rem-of [x] (str (number x) "rem"))
(defn- px [x] (str (number x) "px"))

(defn- shadow-css [s]
  (if (= :none s)
    "none"
    (str "0 " (px (:y s)) " " (px (:blur s)) (when (:spread s) (str " " (px (:spread s)))) " rgba(0,0,0," (:a s) ")")))

(def ^:private constants
  [["--ui-font-weight-light" "300"] ["--ui-font-weight-regular" "400"] ["--ui-font-weight-medium" "500"]
   ["--ui-font-weight-semibold" "600"] ["--ui-font-weight-bold" "700"]
   ["--ui-border-width-default" "1px"] ["--ui-border-width-thick" "2px"]
   ["--ui-duration-fast" "75ms"] ["--ui-duration-normal" "150ms"] ["--ui-duration-slow" "300ms"]
   ["--ui-easing-linear" "linear"] ["--ui-easing-ease-in" "cubic-bezier(0.4, 0, 1, 1)"]
   ["--ui-easing-ease-out" "cubic-bezier(0, 0, 0.2, 1)"] ["--ui-easing-ease-in-out" "cubic-bezier(0.4, 0, 0.2, 1)"]
   ["--ui-z-index-base" "0"] ["--ui-z-index-raised" "10"] ["--ui-z-index-dropdown" "1000"] ["--ui-z-index-sticky" "1100"]
   ["--ui-z-index-banner" "1200"] ["--ui-z-index-overlay" "1300"] ["--ui-z-index-modal" "1400"] ["--ui-z-index-popover" "1500"]
   ["--ui-z-index-toast" "1600"] ["--ui-z-index-tooltip" "1700"]
   ["--ui-opacity-scrim-light" "0.2"] ["--ui-opacity-scrim-medium" "0.4"] ["--ui-opacity-scrim-dark" "0.6"]
   ["--ui-opacity-disabled" "0.5"] ["--ui-opacity-muted" "0.6"] ["--ui-opacity-active" "0.85"] ["--ui-opacity-hover" "0.9"]
   ["--ui-focus-outline-offset" "2px"] ["--ui-focus-outline-width" "2px"]])

(def axes
  "Each personality axis, its levels and the one that is basaku's default."
  {:density    {:levels #{:compact :cozy :comfortable} :default :cozy}
   :roundness  {:levels #{:sharp :soft :round} :default :soft}
   :typography {:levels #{:sans :serif :mono} :default :sans}
   :elevation  {:levels #{:flat :normal :raised} :default :normal}})

(defn declarations
  "The scales for a brand's personality, `{:density … :roundness … :typography … :elevation …}`
  (each its default when absent), as an ordered seq of `[block [[property value] …]]`,
  `block` one of `:root` `:dark` `:sm` `:sm-dark` `:lg` `:lg-dark` — the light scheme and
  the dark at every width, from 40em, from 64em."
  [opts]
  (let [lvl  (fn [k] (get opts k (get-in axes [k :default])))
        f    (densities (lvl :density))
        r    (radii (lvl :roundness))
        typo (lvl :typography)
        shs  (shadows (lvl :elevation))
        resp (fn [k side at] (shadow-css (get-in shs [k side at])))]
    [[:root (concat
              [["--ui-spacing-xxs" "0.125rem"] ["--ui-spacing-xs" "0.25rem"] ["--ui-spacing-sm" "0.5rem"]]
              (for [[k d] rungs] [(str "--ui-spacing-" (name k)) (rem-of (* (float d) f))])
              [["--ui-radius-none" "0px"]]
              (for [k [:sm :md :lg :xl]] [(str "--ui-radius-" (name k)) (rem-of (get r k))])
              [["--ui-radius-full" "9999px"]
               ["--ui-font-size-xs" "0.75rem"] ["--ui-font-size-sm" "0.875rem"] ["--ui-font-size-base" "1rem"]
               ["--ui-font-size-lg" "1.2rem"] ["--ui-font-size-xl" "1.25rem"] ["--ui-font-size-xxl" "1.4rem"]
               ["--ui-font-size-xxxl" "1.6rem"]
               ["--ui-shadow-sm" (shadow-css (get-in shs [:sm :light]))]]
              (for [k [:md :lg :xl]] [(str "--ui-shadow-" (name k)) (resp k :light :default)])
              (for [k [:sans :serif :mono]] [(str "--ui-font-family-" (name k)) (stacks k)])
              (for [k [:heading :body :label]] [(str "--ui-font-family-" (name k)) (str "var(--ui-font-family-" (name typo) ")")])
              [["--ui-font-family-code" "var(--ui-font-family-mono)"]
               ["--ui-line-height-none" "1"] ["--ui-line-height-tight" "1.25"]
               ["--ui-line-height-normal" (number (line-heights typo))]
               ["--ui-line-height-relaxed" "1.625"] ["--ui-line-height-loose" "2"]]
              constants)]
     [:dark (cons ["--ui-shadow-sm" (shadow-css (get-in shs [:sm :dark]))]
                  (for [k [:md :lg :xl]] [(str "--ui-shadow-" (name k)) (resp k :dark :default)]))]
     [:sm (concat (for [[k _ s] rungs] [(str "--ui-spacing-" (name k)) (rem-of (* (float s) f))])
                  [["--ui-font-size-xl" "1.44rem"] ["--ui-font-size-xxl" "1.728rem"] ["--ui-font-size-xxxl" "2.074rem"]]
                  (for [k [:md :lg :xl]] [(str "--ui-shadow-" (name k)) (resp k :light :sm)]))]
     [:sm-dark (for [k [:md :lg :xl]] [(str "--ui-shadow-" (name k)) (resp k :dark :sm)])]
     [:lg (concat (for [[k _ _ l] rungs] [(str "--ui-spacing-" (name k)) (rem-of (* (float l) f))])
                  (for [k [:md :lg :xl]] [(str "--ui-shadow-" (name k)) (resp k :light :lg)]))]
     [:lg-dark (for [k [:md :lg :xl]] [(str "--ui-shadow-" (name k)) (resp k :dark :lg)])]]))

(defn css
  "The scales as an unlayered stylesheet: the light scheme on `:root`, the dark on
  `:root.dark` and under `prefers-color-scheme: dark` for a page that says neither, each
  again from 40em and from 64em (640px and 1024px at the default font size)."
  [opts]
  (let [block (fn [sel decls] (str sel "{" (apply str (for [[k v] decls] (str k ":" v ";"))) "}"))
        d     (into {} (declarations opts))
        dark  (fn [decls] (str (block ":root.dark" decls) "\n@media (prefers-color-scheme:dark){" (block ":root:not(.light)" decls) "}"))]
    (str (block ":root" (:root d)) "\n" (dark (:dark d)) "\n"
         "@media (min-width:40em){" (block ":root" (:sm d)) "}\n"
         "@media (min-width:40em){" (block ":root.dark" (:sm-dark d)) "}\n"
         "@media (prefers-color-scheme:dark) and (min-width:40em){" (block ":root:not(.light)" (:sm-dark d)) "}\n"
         "@media (min-width:64em){" (block ":root" (:lg d)) "}\n"
         "@media (min-width:64em){" (block ":root.dark" (:lg-dark d)) "}\n"
         "@media (prefers-color-scheme:dark) and (min-width:64em){" (block ":root:not(.light)" (:lg-dark d)) "}\n")))
