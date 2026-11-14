(ns dev.arkaitz.uikit-base.audit
  "Every pair basaku's `wcag_audit.zig` holds a generated theme to, as data, and the check
  that answers which ones a palette breaks. In basaku a failure stops the build; here
  `build` refuses to write a colours file that has one. The palette clears them all by
  construction — this is the net under the construction, never part of it."
  (:require [dev.arkaitz.uikit-base.color :as c]))

(def ^:private rules
  "What each threshold demands of a ratio, as wcag_audit.zig:207-238 states it."
  {:aa         {:at-least 4.5}
   :aaa        {:at-least 7.0}
   :ui         {:at-least 3.0}
   :ring       {:at-least 9.0}
   :below-aa   {:below 4.5}
   :muted-band {:at-least 2.5 :at-most 4.5}})

(def pairs
  "`[background foreground rule]`, every pair of default_pairs (wcag_audit.zig:57-144),
  generated_status_pairs (:372-385), generated_boundary_pairs (:267-297) and focus_pairs
  (:392)."
  (vec
   (concat
    (for [[bg fg] [[:background :on-background] [:surface :on-surface] [:surface-alt :on-surface-alt]
                   [:surface-low :on-surface] [:surface-mid :on-surface] [:surface-high :on-surface]
                   [:surface-low :text-muted] [:surface-mid :text-muted] [:surface-high :text-muted]
                   [:surface-low :text-muted-strong] [:surface-mid :text-muted-strong] [:surface-high :text-muted-strong]
                   [:surface-low :link] [:surface-mid :link] [:surface-high :link]
                   [:surface-inverse :on-surface-inverse] [:primary :on-primary] [:secondary :on-secondary]
                   [:tertiary :on-tertiary] [:neutral :on-neutral] [:primary-light :on-primary-light]
                   [:primary-dark :on-primary-dark] [:secondary-light :on-secondary-light]
                   [:secondary-dark :on-secondary-dark] [:success :on-success] [:danger :on-danger]
                   [:warning :on-warning] [:info :on-info] [:selection :on-selection]
                   [:background :link] [:surface :link] [:surface-alt :link] [:surface-inverse :link-inverse]
                   [:background :text-muted] [:surface :text-muted] [:surface-alt :text-muted]
                   [:primary-hover :on-primary] [:primary-active :on-primary] [:secondary-hover :on-secondary]
                   [:secondary-active :on-secondary] [:success-hover :on-success] [:success-active :on-success]
                   [:danger-hover :on-danger] [:danger-active :on-danger] [:warning-hover :on-warning]
                   [:warning-active :on-warning] [:info-hover :on-info] [:info-active :on-info]
                   [:surface-hover :on-surface]]]
      [bg fg :aa])
    (for [bg [:background :surface :surface-alt]] [bg :text-muted-strong :aaa])
    (for [bg [:background :surface :surface-alt]] [bg :text-muted-subtle :muted-band])
    (for [bg [:background :surface :surface-alt] fg [:focus-ring :border-strong]] [bg fg :ui])
    [[:disabled-bg :disabled-fg :below-aa]]
    (for [family ["success" "danger" "warning" "info"]
          state  ["" "-hover" "-active"]]
      [(keyword (str family state)) (keyword (str "on-" family)) :aaa])
    (for [bg [:background :surface :surface-alt :surface-hover :surface-low :surface-mid :surface-high]
          fg (concat (map #(vector % :ui) [:primary-border :secondary-border :success-border :danger-border
                                            :warning-border :info-border])
                     (map #(vector % :aa) [:success-ink :danger-ink :warning-ink :info-ink]))]
      [bg (first fg) (second fg)])
    [[:focus-ring-halo :focus-ring :ring]])))

(defn- holds? [rule ratio]
  (let [{:keys [at-least at-most below]} (rules rule)]
    (and (or (nil? at-least) (>= ratio at-least))
         (or (nil? at-most) (<= ratio at-most))
         (or (nil? below) (< ratio below)))))

(defn failures
  "Every audited pair `tokens` breaks, light and dark, as `{:pair [bg fg] :scheme :rule
  :ratio}`; empty when the palette holds. `tokens` is what `brand/theme` answers:
  keyword → `[\"#light\" \"#dark\"]`. A token missing, or not a `#rrggbb`, is a failure
  of every pair it is in, with no ratio."
  [tokens]
  (vec (for [[bg fg rule] pairs
             [scheme i] [[:light 0] [:dark 1]]
             :let [b (c/parse-hex (get-in tokens [bg i])) f (c/parse-hex (get-in tokens [fg i]))
                   ratio (when (and b f) (c/contrast b f))]
             :when (or (nil? ratio) (not (holds? rule ratio)))]
         {:pair [bg fg] :scheme scheme :rule rule :ratio ratio})))
