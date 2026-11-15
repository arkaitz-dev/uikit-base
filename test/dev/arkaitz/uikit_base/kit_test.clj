(ns dev.arkaitz.uikit-base.kit-test
  "The kit's stylesheet as a host meets it: every rule inside the one layer, so a host's
  own stylesheet always wins; every variable it reads defined; every text it paints
  readable — an audited pair of basaku's, and 4.5 by a WCAG written in this test, in each
  of the nineteen palettes, light and dark — and every control's edge visible. Then the
  plugin, through a real web-base handler: linked in order, served byte for byte."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base :as ui]
            [dev.arkaitz.uikit-base.brand :as brand]
            [dev.arkaitz.uikit-base.web :as web]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.shell :as shell]
            [ring.mock.request :as mock])
  (:import [java.util Arrays]))

(def ^:private kit (slurp (io/resource "dev/arkaitz/uikit_base/public/kit.css")))
(def ^:private css (str/replace kit #"(?s)/\*.*?\*/" ""))
(def ^:private goldens (:seeds (edn/read-string (slurp (io/file "test/resources/basaku-goldens.edn")))))
(def ^:private audited (into {} (map (fn [[bg fg rule]] [[bg fg] rule]))
                             (:pairs (edn/read-string (slurp (io/file "test/resources/basaku-audit-pairs.edn"))))))
(def ^:private palette (set (keys (brand/theme {:primary "#2563eb"}))))

(defn- top-level
  "The top-level items of a stylesheet: each block's prelude, each statement's text."
  [text]
  (loop [i 0 depth 0 start 0 items []]
    (if (= i (count text))
      {:items items :depth depth :rest (subs text start)}
      (let [ch (.charAt ^String text i)]
        (cond (and (= ch \{) (zero? depth)) (recur (inc i) 1 start (conj items (str/trim (subs text start i))))
              (= ch \{) (recur (inc i) (inc depth) start items)
              (and (= ch \}) (= depth 1)) (recur (inc i) 0 (inc i) items)
              (= ch \}) (recur (inc i) (dec depth) start items)
              (and (= ch \;) (zero? depth)) (recur (inc i) 0 (inc i) (conj items (str/trim (subs text start i))))
              :else (recur (inc i) depth start items))))))

(defn- parse-css
  "The stylesheet as its rules, in order: each `{:selectors :decls :index :layer :conditions}`,
  `:layer` the path of named layers it sits in (`[\"uikit\" \"components\"]`), `:conditions`
  the preludes of the `@media` and `@starting-style` blocks around it, and the order the
  layers were named in, per parent, as `:layer-order`. A rule nested in a rule is refused:
  the regex this replaces read one as a selector."
  [text]
  (let [order (atom {}) out (atom [])
        note! (fn [parent nm] (swap! order update parent #(if (some #{nm} %) % (conj (or % []) nm))))]
    (letfn [(block-end [t from]
              (loop [i from depth 1]
                (let [c (.charAt ^String t i)]
                  (cond (= c \{) (recur (inc i) (inc depth))
                        (= c \}) (if (= 1 depth) i (recur (inc i) (dec depth)))
                        :else (recur (inc i) depth)))))
            (walk [t layer conds]
              (loop [t (str/trim t)]
                (when (seq t)
                  (let [brace (str/index-of t "{") semi (str/index-of t ";")]
                    (if (and semi (or (nil? brace) (< semi brace)))
                      (let [stmt (str/trim (subs t 0 semi))]
                        (when-let [[_ names] (re-matches #"@layer\s+(.+)" stmt)]
                          (doseq [n (str/split names #"\s*,\s*")] (note! layer n)))
                        (recur (str/trim (subs t (inc semi)))))
                      (let [prelude (str/trim (subs t 0 brace)) end (block-end t (inc brace))
                            body (subs t (inc brace) end) rest (str/trim (subs t (inc end)))]
                        (cond
                          (str/starts-with? prelude "@layer")
                          (let [nm (str/trim (subs prelude 6))] (note! layer nm) (walk body (conj layer nm) conds))
                          (or (str/starts-with? prelude "@media") (str/starts-with? prelude "@starting-style"))
                          (walk body layer (conj conds prelude))
                          (str/includes? body "{")
                          (throw (ex-info "kit.css: a rule nested in a rule" {:selector prelude}))
                          :else
                          (swap! out conj {:selectors (mapv str/trim (str/split prelude #","))
                                           :decls (into {} (for [d (str/split body #";") :let [[k v] (str/split d #":" 2)] :when v] [(str/trim k) (str/trim v)]))
                                           :layer layer :conditions conds}))
                        (recur rest)))))))]
      (walk text [] [])
      {:rules (vec (map-indexed #(assoc %2 :index %1) @out)) :layer-order @order})))

(def ^:private parsed (parse-css css))

(defn- rank-key
  "Where a rule's layer stands in the cascade, compared element by element: a later-named
  sub-layer wins over an earlier one, and a rule directly in a layer (its missing depths
  read as the highest rank) wins over every one of that layer's sub-layers."
  [order layer]
  (let [r (vec (for [i (range 1 (inc (count layer)))]
                 (.indexOf ^java.util.List (get order (subvec layer 0 (dec i)) []) (nth layer (dec i)))))]
    (vec (concat r (repeat (- 4 (count r)) Integer/MAX_VALUE)))))

(defn- leaf-rules
  "Every rule as [selectors decls index layer conditions]."
  []
  (mapv (juxt :selectors :decls :index :layer :conditions) (:rules parsed)))

(def ^:private environments
  "Where the kit is read: six widths, both schemes, either motion preference."
  (for [w [360 640 768 1024 1280 1536] scheme [:light :dark] motion [:no-preference :reduce]]
    {:width w :scheme scheme :motion motion}))

(defn- holds?
  "Whether one media condition holds in `env`; an unknown one is refused, so a new query is
  modelled before it is trusted."
  [prelude env]
  (cond
    (str/starts-with? prelude "@starting-style") false
    :else
    (let [q (str/trim (str/replace prelude #"^@media" ""))]
      (every? (fn [part]
                (let [part (str/trim part)]
                  (if-let [[_ f n u] (re-matches #"\((min|max)-width:\s*([\d.]+)(px|rem)\)" part)]
                    (let [px (* (Double/parseDouble n) (if (= u "rem") 16 1))]
                      (if (= f "min") (>= (:width env) px) (<= (:width env) px)))
                    (if-let [[_ f v] (re-matches #"\((prefers-color-scheme|prefers-reduced-motion):\s*([a-z-]+)\)" part)]
                      (= v (name (if (= f "prefers-color-scheme") (:scheme env) (:motion env))))
                      (throw (ex-info "kit_test: a media condition the model does not know" {:condition part}))))))
              (str/split q #"\s+and\s+")))))

(defn- in-env
  "The rules that apply in `env`."
  [rules env]
  (filterv (fn [[_ _ _ _ conds]] (every? #(holds? % env) conds)) rules))

(defn- palette-var
  "The palette token a value reads, skipping the kit's own scales (`outline: var(--ui-ring)
  solid var(--ui-focus-ring)` reads two)."
  [v]
  (some #(palette (keyword (second %))) (re-seq #"var\(--ui-([a-z-]+)\)" (str v))))

(defn- wcag [a b]
  (let [hex (fn [s] (mapv #(Long/parseLong (subs s % (+ % 2)) 16) [1 3 5]))
        lin (fn [ch] (let [x (/ ch 255.0)] (if (<= x 0.03928) (/ x 12.92) (Math/pow (/ (+ x 0.055) 1.055) 2.4))))
        lum (fn [s] (let [[r g b] (hex s)] (+ (* 0.2126 (lin r)) (* 0.7152 (lin g)) (* 0.0722 (lin b)))))
        la (lum a) lb (lum b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(declare cascaded specificity)

(deftest the-tests-model-of-the-cascade-knows-layers-media-and-specificity
  ;; The model every other test here stands on, checked against stylesheets whose cascade
  ;; is known: if it mis-ranked layers or ignored a query, those tests would audit the
  ;; wrong winner and stay green.
  (let [p (parse-css "@layer k { @layer base, components, utilities; @layer utilities { .x { color: u; } } @layer components { .w .x { color: c; } } .d .e { color: d; } @layer base { .d .e { color: b; } } .m { color: narrow; } @media (min-width: 48rem) and (prefers-color-scheme: dark) { .m { color: wide; } } }")
        rules (mapv (juxt :selectors :decls :index :layer :conditions) (:rules p))
        win (fn [env sel] (cascaded (:layer-order p) (in-env rules env) sel ["color"]))]
    (is (= {["k"] ["base" "components" "utilities"]} (dissoc (:layer-order p) []))
        "witness: the sub-layers in the order they were named")
    (is (= "u" (win {:width 360 :scheme :light :motion :reduce} ".w .x"))
        "a later sub-layer wins over a more specific rule of an earlier one")
    (is (= "d" (win {:width 360 :scheme :light :motion :reduce} ".d .e"))
        "a rule directly in a layer wins over that layer's sub-layers")
    (is (= ["wide" "narrow" "narrow"] (mapv #(win % ".m") [{:width 768 :scheme :dark :motion :reduce} {:width 767 :scheme :dark :motion :reduce} {:width 1024 :scheme :light :motion :reduce}]))
        "a query applies where it holds and nowhere else, its conditions all at once"))
  (is (= [[0 0 0] [0 1 0] [0 2 0] [0 1 1]] (mapv specificity [":where(.a, .b)" ":is(.a, a)" ".x:has(.a)" "a:not(.b)"]))
      "where counts nothing; is, has and not their most specific argument")
  (is (= "kit.css: a rule nested in a rule" (try (parse-css ".a { color: x; .b { color: y; } }") nil (catch clojure.lang.ExceptionInfo e (ex-message e)))))
  (is (= "kit_test: a media condition the model does not know" (try (holds? "@media (hover: hover)" (first environments)) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))))
  (is (= 24 (count environments)) "witness: six widths, two schemes, two motions"))

(deftest every-rule-sits-in-the-one-layer-and-nothing-is-important
  (let [{:keys [items depth rest]} (top-level css)]
    (is (= ["@layer uikit"] items) "one top-level item: the layer")
    (is (and (zero? depth) (str/blank? rest)) "balanced, nothing after it")
    (is (not (str/includes? css "!important")) "nothing forced: a layered !important would beat the host's")
    (is (< 40 (count (leaf-rules))) (str "witness: the rules were parsed (" (count (leaf-rules)) ")"))))

(deftest every-variable-the-kit-reads-exists
  (let [read     (set (map (comp keyword second) (re-seq #"var\(--ui-([a-z0-9-]+)" css)))
        declared (set (map (comp keyword second) (re-seq #"--ui-([a-z0-9-]+)\s*:" css)))]
    (is (= 72 (count palette)) "witness: the palette's 72 tokens")
    (is (< 30 (count read)) "witness: the references were found")
    (is (= [] (remove (some-fn palette declared) read)) "every variable read is the palette's or a scale the kit declares")
    (is (= [] (filter palette declared)) "the kit redeclares no palette token, which would be dead under the unlayered colours")))

(def ^:private compound-re #"[^\s>+~]+")
(def ^:private state-re #"(::?[a-z-]+(\([^)]*\))?|\[[^\]]*\])")

(defn- specificity
  "A selector's specificity, [ids classes elements]: a class, an attribute and a
  pseudo-class are one class each, a type or a pseudo-element one element; `:where(…)`
  counts nothing, and `:is(…)`, `:not(…)` and `:has(…)` count their most specific
  argument."
  [sel]
  (let [m (re-matcher #":(is|where|not|has)\(" sel)]
    (if (.find m)
      (let [start (.start m) open (dec (.end m))
            close (loop [i (inc open) d 1] (let [c (.charAt ^String sel i)] (cond (= c \() (recur (inc i) (inc d)) (= c \)) (if (= d 1) i (recur (inc i) (dec d))) :else (recur (inc i) d))))
            inner (subs sel (inc open) close)
            outer (str (subs sel 0 start) (subs sel (inc close)))
            arg (if (= "where" (.group m 1)) [0 0 0] (last (sort (map #(specificity (str/trim %)) (str/split inner #",")))))]
        (mapv + (specificity outer) arg))
      [(count (re-seq #"#[\w-]+" sel))
       (count (re-seq #"\.[\w-]+|\[[^\]]*\]|(?<!:):[a-z-]+" sel))
       (+ (count (re-seq #"(?:^|[\s>+~(])[a-z][a-z0-9]*" sel)) (count (re-seq #"::[a-z-]+" sel)))])))

(defn- implied
  "Every compound an element matching `compound` also matches among the kit's rules: its
  modifier implies its base (`.ui-btn--primary` is a `.ui-btn`), and each subset of its
  states (`:hover`, `[aria-invalid]`) applies with or without the others. A
  pseudo-element stays: it is another box."
  [compound]
  (let [pe     (re-find #"::[a-z-]+$" compound)
        c      (if pe (subs compound 0 (- (count compound) (count pe))) compound)
        core   (str/replace c #"(:[a-z-]+(\([^)]*\))?|\[[^\]]*\])+$" "")
        states (map first (re-seq state-re (subs c (count core))))
        subsets (reduce (fn [acc st] (concat acc (map #(conj % st) acc))) [[]] states)]
    (distinct (for [k (distinct [core (str/replace core #"--[a-z]+$" "")]) st subsets] (str k (apply str st) pe)))))

(defn- selectors-implied
  "Every selector an element matching `sel` also matches: the product of each compound's
  implications, the combinators kept, and the same with any leading ancestors dropped —
  what matches `.w .x` matches `.x` too, which is how a utility meets a component's rule."
  [sel]
  (let [parts  (vec (re-seq #"[^\s>+~]+|\s*[>+~]\s*|\s+" (str/trim sel)))
        starts (cons 0 (keep-indexed (fn [i p] (when (re-matches #"\s*[>+~]?\s*" p) (inc i))) parts))]
    (distinct
      (for [st starts
            implied-sel (map str/join (reduce (fn [acc part] (for [a acc x (if (re-matches compound-re part) (implied part) [part])] (conj a x)))
                                              [[]] (subvec parts st)))]
        implied-sel))))

(defn- cascaded
  "The value the cascade gives `sel` for the first of `props` any rule sets: among the
  rules that apply to it, the one of the highest layer, then of highest specificity, then
  the last. Nil when none sets it. `order` is the layers' naming order, the kit's unless
  given."
  ([rules sel props] (cascaded (:layer-order parsed) rules sel props))
  ([order rules sel props]
  (let [applies (set (selectors-implied sel))]
    (some-> (last (sort-by (fn [[s i _ layer]] [(rank-key order layer) (specificity s) i])
                           (for [[sels decls i layer] rules s sels :when (applies s) p props :when (contains? decls p)] [s i (get decls p) layer])))
            (nth 2)))))

(defn- resolved
  "The palette token the cascade gives `sel` for one of `props`. With none, a
  pseudo-element takes its element's, and a descendant its ancestor's: the colour it
  inherits, the background that shows through."
  [rules sel props]
  (let [winner (cascaded rules sel props)]
    (cond winner (palette-var winner)
          (re-find #"::[a-z-]+$" sel) (resolved rules (str/replace sel #"::[a-z-]+$" "") props)
          (re-find #"[\s>+~]" (str/trim sel)) (resolved rules (str/trim (str/replace (str/trim sel) #"\s*[>+~]?\s*[^\s>+~]+$" "")) props))))

(def ^:private backgrounds ["background" "background-color"])

(deftest every-text-the-kit-paints-is-an-audited-pair-and-reads-at-4-5
  (let [pairs  (distinct (for [env environments :let [rules (in-env (leaf-rules) env)] [sels decls] rules s sels
                               :when (some #(contains? decls %) (cons "color" backgrounds))
                               :let [fg (resolved rules s ["color"]) bg (resolved rules s backgrounds)]
                               :when fg
                               bg (if bg [bg] [:background :surface :surface-alt])]
                           [s bg fg]))
        disabled? (fn [[s]] (or (str/includes? s ":disabled") (str/includes? s "aria-disabled")))]
    (is (= [0 2 1] (specificity ".ui-pager a:focus-visible")) "witness: the test's specificity")
    (is (= #{".ui-btn--primary:hover" ".ui-btn--primary" ".ui-btn:hover" ".ui-btn"} (set (selectors-implied ".ui-btn--primary:hover")))
        "witness: a variant's hover is also the base's hover")
    (is (= 80 (count pairs)) (str "witness: every painted pair found, a count measured once: " (count pairs)))
    (is (= 21.0 (wcag "#000000" "#ffffff")) "witness: the test's WCAG")
    (doseq [[s bg fg :as p] pairs]
      (testing s
        (if (disabled? p)
          (is (= :below-aa (audited [bg fg])) "disabled is the audited below-AA pair")
          (is (#{:aa :aaa} (audited [bg fg])) (str [bg fg] " is a pair basaku audits for text")))
        (doseq [[seed tokens] goldens [scheme i] [[:light 0] [:dark 1]]
                :let [r (wcag (get-in tokens [bg i]) (get-in tokens [fg i]))]]
          (if (disabled? p)
            (is (< r 4.5) (str seed " " scheme " " r))
            (is (>= r 4.5) (str seed " " scheme " " [bg fg] " " r))))))))

(def ^:private colour-bearing
  #"^(color|background(-color)?|border(-(top|right|bottom|left|block|inline)(-(start|end))?)?(-color)?|outline(-color)?|accent-color|caret-color|fill|stroke|box-shadow|text-decoration-color)$")

(def ^:private shadows
  "The kit's own properties that hold a colour literal: a shadow's, which no palette token
  is, read only by `box-shadow`."
  #{"--ui-shadow-sm"})

(deftest every-colour-comes-from-the-palette
  (let [rules    (leaf-rules)
        decls    (for [[sels ds] rules [k v] ds] [sels k v])
        scales   (set (map (comp keyword second) (re-seq #"--ui-([a-z0-9-]+)\s*:" css)))
        token-ok (fn [t] (or (re-matches #"-?[\d.]+(px|rem|em|%)?" t)
                             (#{"solid" "dashed" "dotted" "none" "inset" "inherit" "currentcolor" ","} t)
                             (when-let [[_ n] (re-matches #"var\(--ui-([a-z0-9-]+)\)" t)] (or (palette (keyword n)) (scales (keyword n))))))
        tokens   (fn [v] (map first (re-seq #"[^\s(]+(\([^)]*\))?" v)))
        bearing  (filter (fn [[_ k]] (re-find colour-bearing k)) decls)]
    (is (= 119 (count bearing)) (str "witness: the colour-bearing declarations, a count measured once: " (count bearing)))
    (is (= [] (for [[sels k v] bearing t (tokens v) :when (not (token-ok t))] [sels k t]))
        "a colour is a palette token: no literal, no keyword, nothing else")
    (is (= [] (for [[sels k v] bearing :when (#{"color" "background" "background-color"} k) :when (not (palette-var v))] [sels k v]))
        "a colour and a background read one")
    (is (= [] (for [[sels k v] decls :when (not (str/starts-with? k "--")) :when (re-find #"#[0-9a-fA-F]{3,8}\b|\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color|color-mix)\(" v)] [sels k v]))
        "no other property holds a colour literal either")
    (is (= shadows (set (for [[_ k v] decls :when (str/starts-with? k "--") :when (re-find #"#[0-9a-fA-F]{3,8}\b|\(" v)] k)))
        "the kit's own properties holding a colour are the shadows, and only those")
    (is (= #{"box-shadow"} (set (for [[_ k v] decls :when (some #(str/includes? v (str "var(" % ")")) shadows)] k)))
        "and only a shadow reads them")))

(def ^:private dividers
  "Lines that separate, not edges that bound a control: drawn in `border`, which is no
  audited non-text pair, and named here one by one so a new one is decided, not inherited."
  #{[".ui-table th" :border] [".ui-table td" :border] [".ui-row" :border] [".ui-card" :border] [".ui-tabs ul" :border] [".ui-card__foot" :border]})

(deftest every-control-edge-is-visible
  (let [found (distinct (for [[sels decls] (leaf-rules) s sels
                              :when (str/starts-with? s ".ui-")
                              :when (not (or (str/includes? s ":disabled") (str/includes? s "aria-disabled")))
                              [k v] decls :when (re-find #"^(border|outline|accent-color)" k)
                              :let [t (palette-var v)] :when t]
                          [s t]))
        edges (remove dividers found)]
    (is (= dividers (set (filter dividers found))) "every divider named is still drawn")
    (is (= 27 (count edges)) (str "witness: the edges, a count measured once: " (count edges)))
    (doseq [[s t] edges bg [:background :surface :surface-alt]]
      (testing (str s " " t)
        (is (= :ui (audited [bg t])) "an audited non-text pair")
        (doseq [[seed tokens] goldens [scheme i] [[:light 0] [:dark 1]]]
          (is (>= (wcag (get-in tokens [bg i]) (get-in tokens [t i])) 3.0) (str seed " " scheme)))))))

(deftest nothing-is-pinned-to-a-physical-side
  ;; A page in a right-to-left language mirrors every inline start and end; a left or a
  ;; right stays put, on the wrong side. Top and bottom are not this rule's: no host
  ;; writes vertically.
  (let [decls (for [[sels ds] (leaf-rules) [k v] ds] [sels k v])
        four  #"^(margin|padding|inset|border-width|border-style|border-color)$"]
    (is (< 80 (count decls)) "witness: the declarations were read")
    (is (= [] (for [[sels k v] decls
                    :when (or (re-find #"(^|-)(left|right)(-|$)" k)
                              (some #{"left" "right"} (str/split v #"\s+"))
                              (when (re-find four k) (let [t (str/split (str/trim v) #"\s+")] (and (= 4 (count t)) (not= (t 1) (t 3))))))]
                [sels k v]))
        "every side is a logical one: inline start and end")))

(defn- input-states
  "Every combination of the states the kit styles on `.ui-input`, as one compound each."
  []
  (let [states (distinct (for [[sels] (leaf-rules) s sels :let [[_ st] (re-matches #"\.ui-input((?::[a-z-]+|\[[^\]]*\])+)" s)] :when st
                               x (map first (re-seq state-re st))] x))]
    (map #(str ".ui-input" (apply str %)) (reduce (fn [acc st] (concat acc (map #(conj % st) acc))) [[]] states))))

(deftest a-refused-control-keeps-its-danger-edge-in-every-state
  (doseq [env environments :let [rules (in-env (leaf-rules) env)]]
  (let [
        live  (remove #(str/includes? % ":disabled") (filter #(str/includes? % "[aria-invalid=\"true\"]") (input-states)))]
    (is (= 8 (count live)) (str "witness: the refused states, a count measured once: " (vec live)))
    (is (= [] (for [s live :let [t (resolved rules s ["border-color" "border"])] :when (not= :danger-border t)] [s t]))
        (str "hovered, read-only or not, a refused control is edged in danger " env)))))

(deftest a-disabled-input-looks-disabled-in-every-state
  (doseq [env environments :let [rules (in-env (leaf-rules) env)]]
  (let [
        look  (fn [s] (mapv #(resolved rules s %) [["color"] backgrounds ["border-color" "border"]]))
        dead  (filter #(str/includes? % ":disabled") (input-states))]
    (is (= [:disabled-fg :disabled-bg :disabled-bg] (look ".ui-input:disabled")) "witness: the disabled look")
    (is (= 16 (count dead)) (str "witness: the disabled states, a count measured once: " (count dead)))
    (is (= [] (for [s dead :let [l (look s)] :when (not= l (look ".ui-input:disabled"))] [s l]))
        (str "no hover, refusal or read-only shows through a disabled control " env)))))

(deftest an-input-and-a-button-share-one-box
  ;; Only the declarations are compared: a browser alone measures pixels.
  (doseq [env environments :let [rules (in-env (leaf-rules) env)]]
  (let [box   (fn [s] [(cascaded rules s ["font-size"]) (cascaded rules s ["line-height"])
                       (some-> (cascaded rules s ["padding-block" "padding"]) (str/split #"\s+") first)
                       (some-> (cascaded rules s ["border-width" "border"]) (str/split #"\s+") first)
                       (cascaded rules s ["height" "min-height"])])]
    (is (= ["var(--ui-font-base)" "1.25" "var(--ui-space-sm)" "1px" nil] (box ".ui-btn")) "witness: the button's box")
    (is (= (box ".ui-btn") (box ".ui-input")) (str "an inline input sits level with the button beside it " env)))))

(deftest screen-reader-text-is-hidden-from-the-eye-and-not-from-the-reader
  (let [d (some (fn [[sels decls]] (when (= [".ui-sr-only"] sels) decls)) (leaf-rules))]
    (is (= {"position" "absolute" "width" "1px" "height" "1px" "overflow" "hidden" "clip" "rect(0, 0, 0, 0)"}
           (select-keys d ["position" "width" "height" "overflow" "clip"]))
        "out of the flow, one pixel (a zero-sized box some readers skip), clipped")
    (is (= {} (select-keys d ["display" "visibility" "content-visibility" "opacity"]))
        "never what hides it from a screen reader as well")
    (is (= [] (for [[sels decls] (leaf-rules) s sels :when (str/includes? s "ui-sr-only") :when (not= [".ui-sr-only"] sels)] [sels decls]))
        "and no other rule reaches it")))

(deftest a-region-that-scrolls-contains-what-it-hides
  ;; Text hidden for the eye is `position: absolute`; with no positioned ancestor its box
  ;; is placed against the page, escapes the region's clip, and widens the page (measured:
  ;; 366px on a 360px phone, a confirm's subject inside a scrolling table).
  (let [scrolling (for [[sels decls] (leaf-rules) s sels
                        :when (some #(re-find #"\b(auto|scroll)\b" (str (get decls %))) ["overflow" "overflow-x" "overflow-y"])]
                    [s (get decls "position")])]
    (is (seq scrolling) "witness: a rule that scrolls was found")
    (is (= [] (remove (fn [[_ p]] (#{"relative" "absolute" "fixed" "sticky"} p)) scrolling))
        "each is positioned, so what it holds is clipped by it")))

(def ^:private req
  {::security/csrf-stored true :anti-forgery-token "T" ::security/csrf-used (volatile! false)
   :wb/tr (fn [k args] (str k args))})

(def ^:private fixtures
  "Every component, drawn with everything it can make focusable."
  {'button    [(ui/button "x") (ui/button {:variant :primary} "x")]
   'link-button [(ui/link-button "/a" {:variant :primary} "x") (ui/link-button "/f.csv" {:download? true} "x")]
   'action    [(ui/action req "/a" {:label "x" :hidden {:a 1}})]
   'confirm   [(ui/confirm req "/a" {:label "x" :confirm-label "y"})]
   'empty     [(ui/empty "x")]
   'pairs     [(ui/pairs [["a" "b"]])]
   'time      [(ui/time (java.time.LocalDate/of 2026 1 15) "x")]
   'page-head [(ui/page-head {:title "t" :subtitle "s" :actions [(ui/link-button "/a" "x")]})]
   'crumbs    [(ui/crumbs req {:items [["/a" "A"] [nil "B"]]})]
   'tabs-nav  [(ui/tabs-nav req {:label "S" :items [["/a" "A"] ["/b" "B"]] :current "/a"})]
   'secret    [(ui/secret req {:id "s" :label "S" :value "v"})]
   'sentence  [(ui/sentence {:wb/tr (wb/translator {:default-locale :en :dict {:en {:k {:d "Due %1"}}}} [:en])} :k/d (ui/time 0 "x"))]
   'thread    [(ui/thread (ui/message {:meta "m"} "x"))]
   'message   [(ui/message {:meta "m"} "x")]
   'post-form [(ui/post-form req "/a" (ui/button "x"))]
   'field     (for [t [nil "select" "textarea" "file" "checkbox" "radio"]] (ui/field req {:id "f" :label "F" :type t :options [["a" "A"]] :error :e :hint "h" :required? true}))
   'notice    [(ui/notice :ok "x")]
   'table     [(ui/table {:caption "c" :head ["h"] :rows [["a"]] :row-header? true :scroll-label "s"})]
   'rows      [(ui/rows (ui/row "x"))]
   'row       [(ui/row "x")]
   'pager     [(ui/pager req {:previous "/a" :next "/b" :where "w"})]
   'badge     [(ui/badge :info "x")]
   'card      [(ui/card "x")]})

(defn- elements
  "Every element of a Hiccup tree as [tag attrs classes ancestors' classes]."
  [node ancestors]
  (cond (and (vector? node) (keyword? (first node)))
        (let [[t & more] node
              [_ tag cls] (re-matches #"([a-z0-9]+)(?:#[^.]*)?((?:\.[^.#]+)*)" (name t))
              attrs   (if (map? (first more)) (first more) {})
              classes (into (set (remove str/blank? (str/split (subs (or cls "") (min 1 (count (or cls "")))) #"\."))) (str/split (str (:class attrs "")) #" "))
              classes (set (filter #(str/starts-with? % "ui-") classes))]
          (cons [tag attrs classes ancestors]
                (mapcat #(elements % (concat classes ancestors)) (if (map? (first more)) (rest more) more))))
        (sequential? node) (mapcat #(elements % ancestors) node)
        :else nil))

(defn- focusable? [[tag attrs]]
  (or (and (= "a" tag) (:href attrs)) (#{"button" "select" "textarea" "summary"} tag)
      (and (= "input" tag) (not= "hidden" (:type attrs))) (contains? attrs :tabindex)))

(deftest everything-focusable-the-kit-emits-wears-the-focus-ring
  (let [ringed (set (for [[sels decls] (leaf-rules) s sels
                          :when (str/ends-with? s ":focus-visible")
                          :when (= :focus-ring (palette-var (get decls "outline")))]
                      (str/replace s #":focus-visible$" "")))
        found  (filter focusable? (mapcat #(elements % ()) (vals fixtures)))
        bare   (for [[tag _ classes ancestors :as e] found
                     :when (not (or (some #(ringed (str "." %)) classes)
                                    (some-> (first ancestors) (#(ringed (str "." % " " tag))))))]
                 [tag classes (first ancestors)])]
    (is (= (set (keys fixtures)) (set (remove '#{words cell} (keys (ns-publics 'dev.arkaitz.uikit-base)))))
        "every public component is drawn here, so a new one needs its fixture")
    (is (= 22 (count found)) (str "witness: the focusables, a count measured once: " (vec (map (juxt first #(nth % 2)) found))))
    (is (= [] bare) "each wears the ring, through a class of its own or its kit ancestor's")
    (is (= [] (for [[sels decls] (leaf-rules) :let [v (get decls "outline")] :when v :when (not= :focus-ring (palette-var v))] [sels v]))
        "no rule takes the ring away: an outline is the ring")
    (let [rules  (leaf-rules)
          shaded (distinct (for [[sels decls] rules s sels :when (contains? decls "box-shadow")
                                 :when (not (str/ends-with? s ":focus-visible"))
                                 :when (some #(str/starts-with? s %) ringed)] s))]
      (is (seq shaded) "witness: a focusable that casts a shadow of its own was found")
      (is (= [] (for [s shaded :let [v (cascaded rules (str s ":focus-visible") ["box-shadow"])]
                      :when (not (str/includes? (str v) "var(--ui-focus-ring-halo)"))] [s v]))
          "and its shadow never takes the ring's halo away: the ring is two bands"))))

(deftest the-disabled-look-wins-over-every-variant-by-coming-last
  (let [rules (leaf-rules)
        paints? (fn [decls] (some #(contains? decls %) ["color" "background" "border-color"]))
        idx   (fn [pred] (keep (fn [[sels decls i]] (when (and (paints? decls) (some pred sels)) i)) rules))]
    (is (< (apply max (idx #(and (str/starts-with? % ".ui-btn") (not (str/includes? % "disabled")))))
           (apply min (idx #(str/includes? % ".ui-btn:disabled"))))
        "every button rule that paints comes before the disabled one: equal specificity, so order decides")))

(deftest the-plugin-links-and-serves-the-kit
  (let [app   (wb/handler {:routes [["/" {:get #(response/ok (shell/page {:request % :title "t" :content [:p "x"]}))}]]
                           :session {:key (byte-array 16 (byte 7))} :plugins [(web/plugin)] :i18n {:default-locale :en}
                           :stylesheets ["/ui-colors.css" "/app.css"]})
        page  (app (mock/request :get "/"))
        bytes (fn [in] (with-open [i (io/input-stream in)] (.readAllBytes i)))]
    (is (= 200 (:status page)) "witness: the page answered")
    (is (= ["/wb/wb.css" "/ui/ui-colors.css" "/ui/kit.css" "/ui-colors.css" "/app.css"]
           (map second (re-seq #"<link href=\"([^\"]+)\" rel=\"stylesheet\">" (:body page))))
        "web-base's, the kit's colours and the kit, then the host's colours and its own sheet")
    (doseq [f ["kit.css" "ui-colors.css"]]
      (let [r (app (mock/request :get (str "/ui/" f))) want (bytes (io/resource (str "dev/arkaitz/uikit_base/public/" f)))]
        (is (= 200 (:status r)) f)
        (is (pos? (alength ^bytes want)) "witness: a file with bytes")
        (is (Arrays/equals ^bytes want ^bytes (bytes (:body r))) (str f " served byte for byte"))))
    (is (= 404 (:status (app (mock/request :get "/ui/nope.css")))) "and nothing else under /ui/")))
