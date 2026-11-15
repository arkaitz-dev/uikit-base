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
(def ^:private utilities-file (slurp (io/resource "dev/arkaitz/uikit_base/public/ui-utilities.css")))
(def ^:private utilities-css (str/replace utilities-file #"(?s)/\*.*?\*/" ""))
(def ^:private both
  "The two stylesheets in the order the plugin links them: the kit, then its utilities."
  (str css "\n" utilities-css))
(def ^:private goldens (:seeds (edn/read-string (slurp (io/file "test/resources/basaku-goldens.edn")))))
(def ^:private audited (into {} (map (fn [[bg fg rule]] [[bg fg] rule]))
                             (:pairs (edn/read-string (slurp (io/file "test/resources/basaku-audit-pairs.edn"))))))
(def ^:private palette (set (keys (brand/theme {:primary "#2563eb"}))))
(def ^:private scale-names
  "Every scale the generated file publishes — spacing, radius, type, shadows, motion — by
  its name without `--ui-`, as the kit's own default colours file holds them."
  (set (remove palette (map (comp keyword second) (re-seq #"--ui-([a-z0-9-]+)\s*:" (slurp (io/resource "dev/arkaitz/uikit_base/public/ui-colors.css")))))))

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
        note1 (fn [parent nm] (swap! order update parent #(if (some #{nm} %) % (conj (or % []) nm))))
        ;; A dotted name, `uikit.utilities`, is the sub-layer `utilities` of `uikit`: each
        ;; of its parts is named under the ones before it.
        parts (fn [nm] (str/split (str/trim nm) #"\."))
        note! (fn [parent nm] (reduce (fn [p part] (note1 p part) (conj p part)) parent (parts nm)))]
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
                          (let [nm (str/trim (subs prelude 6))] (walk body (note! layer nm) conds))
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

(def ^:private parsed (parse-css both))
(def ^:private kit-parsed (parse-css css))
(def ^:private utilities-parsed (parse-css utilities-css))

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
    ;; Every environment here is a screen: what is only for paper never holds in one.
    (= "@media print" (str/trim prelude)) false
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

(deftest every-rule-sits-in-a-sub-layer-the-kit-names
  ;; A rule directly in `uikit` would beat base, components and utilities alike, so a
  ;; utility would lose to it whatever the layers say.
  (is (= ["base" "components" "utilities"] (get (:layer-order parsed) ["uikit"])) "the order the sub-layers are named in")
  (is (= ["base" "components" "utilities"] (get (:layer-order utilities-parsed) ["uikit"]))
      "the utilities file names the same order itself, so it cannot reorder them if linked alone")
  (is (< 100 (count (:rules kit-parsed))) "witness: the kit's rules were read")
  (is (< 100 (count (:rules utilities-parsed))) "witness: the utilities' rules were read")
  (is (= (+ (count (:rules kit-parsed)) (count (:rules utilities-parsed))) (count (:rules parsed))) "witness: the two read as one")
  (is (= [] (for [{:keys [layer selectors]} (:rules parsed) :when (not= 2 (count layer))] [layer selectors]))
      "each in exactly one of them")
  (is (= #{["uikit" "components"]} (set (map :layer (:rules kit-parsed))))
      "what the kit draws in components; base holds nothing yet, the scales living with the colours")
  (is (= #{["uikit" "utilities"]} (set (map :layer (:rules utilities-parsed))))
      "and every utility in utilities"))

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
    (is (= ["k" "base"] (:layer (first (:rules (parse-css "@layer k.base { .a { color: x; } }")))))
        "a dotted layer name is the sub-layer it names")
    (is (= {[] ["k"] ["k"] ["base" "components"]} (:layer-order (parse-css "@layer k.base, k.components;")))
        "and a dotted statement names each part under the ones before it")
    (is (= ["wide" "narrow" "narrow"] (mapv #(win % ".m") [{:width 768 :scheme :dark :motion :reduce} {:width 767 :scheme :dark :motion :reduce} {:width 1024 :scheme :light :motion :reduce}]))
        "a query applies where it holds and nowhere else, its conditions all at once"))
  (is (= [[0 0 0] [0 1 0] [0 2 0] [0 1 1]] (mapv specificity [":where(.a, .b)" ":is(.a, a)" ".x:has(.a)" "a:not(.b)"]))
      "where counts nothing; is, has and not their most specific argument")
  (is (= "kit.css: a rule nested in a rule" (try (parse-css ".a { color: x; .b { color: y; } }") nil (catch clojure.lang.ExceptionInfo e (ex-message e)))))
  (is (= "kit_test: a media condition the model does not know" (try (holds? "@media (hover: hover)" (first environments)) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))))
  (is (= [] (filter #(holds? "@media print" %) environments)) "print holds in no screen")
  (is (= [false true true] (mapv #(holds? "@media (min-width:1280px)" {:width %}) [1024 1280 1536])) "a breakpoint written without a space")
  (is (= 24 (count environments)) "witness: six widths, two schemes, two motions"))

(deftest every-rule-sits-in-the-one-layer-and-nothing-is-important
  (let [{:keys [items depth rest]} (top-level css)]
    (is (= ["@layer uikit"] items) "one top-level item: the layer")
    (is (and (zero? depth) (str/blank? rest)) "balanced, nothing after it")
    (is (not (str/includes? css "!important")) "nothing forced: a layered !important would beat the host's")
    (is (< 40 (count (leaf-rules))) (str "witness: the rules were parsed (" (count (leaf-rules)) ")"))))

(deftest every-utility-sits-in-its-layer-and-nothing-is-important
  (let [{:keys [items depth rest]} (top-level utilities-css)]
    (is (= ["@layer uikit.base, uikit.components, uikit.utilities" "@layer uikit.utilities"] items)
        "the layers' order, then one block: the utilities' layer")
    (is (and (zero? depth) (str/blank? rest)) "balanced, nothing after it")
    (is (not (str/includes? utilities-css "!important")) "nothing forced: a utility wins by its layer")))

(deftest every-variable-the-kit-reads-exists
  (let [read     (set (map (comp keyword second) (re-seq #"var\(--ui-([a-z0-9-]+)" both)))
        declared scale-names]
    (is (= 72 (count palette)) "witness: the palette's 72 tokens")
    (is (< 30 (count read)) "witness: the references were found")
    (is (= 70 (count declared)) "witness: the seventy scales basaku writes, a count measured once")
    (is (= [] (remove (some-fn palette declared) read)) "every variable read is the palette's or a scale the generated file publishes")
    (is (= [] (filter palette declared)) "no scale shadows a palette token")))

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

(defn- on-element
  "The value the cascade gives `prop` on one element, among `rules` (one environment's):
  of the rules that reach it by one of the three shapes the utilities write — `.c` for a
  class of its own, `.p>*` for a class of its parent's, `.c:has(>.d)` for its own class
  over a child's — the one of the highest layer, then specificity, then the last. Nil when
  none sets it."
  [rules {:keys [classes parent children]} prop]
  (let [reaches? (fn [s] (or (when-let [[_ c] (re-matches #"\.([\w-]+)" s)] (contains? classes c))
                             (when-let [[_ p] (re-matches #"\.([\w-]+)>\*" s)] (contains? (set parent) p))
                             (when-let [[_ c d] (re-matches #"\.([\w-]+):has\(>\.([\w-]+)\)" s)] (and (contains? classes c) (contains? (set children) d)))))]
    (some-> (last (sort-by (fn [[s i _ layer]] [(rank-key (:layer-order parsed) layer) (specificity s) i])
                           (for [[sels decls i layer] rules s sels :when (reaches? s) :when (contains? decls prop)] [s i (get decls prop) layer])))
            (nth 2))))

(def ^:private breakpoint-px
  "basaku's breakpoints, by the user's decision for 0.6.0 — a named deviation from
  Bootstrap's 576/768/992/1200/1400."
  [["sm" 640] ["md" 768] ["lg" 1024] ["xl" 1280] ["xxl" 1536]])

(def ^:private tiers "The unconditioned tier, then one per breakpoint." (cons [nil 0] breakpoint-px))

(def ^:private spacing-steps
  "A step's scale: 0..5 read the kit's spacing, so a brand's density scales them."
  {0 "0" 1 "var(--ui-spacing-xs)" 2 "var(--ui-spacing-sm)" 3 "var(--ui-spacing-md)" 4 "var(--ui-spacing-lg)" 5 "var(--ui-spacing-xl)"})

(defn- cls [prefix bp v] (str "ui-" prefix (when bp (str "-" bp)) (when (some? v) (str "-" v))))

(def ^:private env-rules (delay (into {} (for [env environments] [env (in-env (leaf-rules) env)]))))

(defn- misses
  "Every case `[env tier value]` whose observed value is not the expected one, and how many
  cases were checked: `f` answers `[expected observed]` for one."
  [values f]
  (let [cases (for [env environments [bp px] tiers v values] [env bp px v])]
    {:checked (count cases)
     :misses  (vec (for [[env bp px v] cases :let [[want got] (f (@env-rules env) env bp px v)] :when (not= want got)]
                     [(:width env) bp v :want want :got got]))}))

(deftest the-breakpoints-are-basakus-own
  (let [widths (fn [text] (set (map #(Long/parseLong (second %)) (re-seq #"min-width:\s*(\d+)px" text))))]
    (is (= #{640 768 1024 1280 1536} (widths utilities-css)) "every query of the utilities is one of the five")
    (is (= #{640 1024} (widths (slurp (io/resource "dev/arkaitz/uikit_base/public/ui-colors.css"))))
        "witness: the scales grow at two of them, the same numbers")
    (is (= (set (map :width environments)) (conj (set (map second breakpoint-px)) 360))
        "and the model reads the page at each, and on a phone below them")))

(deftest a-span-holds-from-its-breakpoint-up-and-a-full-row-below
  (let [{:keys [checked misses]} (misses (range 1 13) (fn [rules env bp px n]
                                                         [(if (>= (:width env) px) (str "span " n) "span 12")
                                                          (on-element rules {:classes #{(cls "col" bp n)} :parent ["ui-layout"]} "grid-column-end")]))]
    (is (= 1728 checked) "witness: 24 environments, six tiers, twelve spans")
    (is (= [] misses) "ui-col-[bp-]n spans n tracks of twelve from its breakpoint, the whole row below it")))

(deftest an-offset-holds-from-its-breakpoint-up-and-keeps-the-span-beside-it
  (let [{:keys [checked misses]} (misses (range 0 12) (fn [rules env bp px n]
                                                         (if (and (nil? bp) (zero? n))
                                                           [nil (on-element rules {:classes #{"ui-offset-0"} :parent ["ui-layout"]} "grid-column-start")]
                                                           [(when (>= (:width env) px) (if (zero? n) "auto" (str (inc n))))
                                                            (on-element rules {:classes #{(cls "offset" bp n)} :parent ["ui-layout"]} "grid-column-start")])))]
    (is (= 1728 checked) "witness: 24 environments, six tiers, twelve offsets")
    (is (= [] misses) "ui-offset-[bp-]n starts after n tracks from its breakpoint; there is no unconditioned offset-0, as in Bootstrap"))
  (doseq [[env rules] @env-rules :let [el {:classes #{"ui-col-md-6" "ui-offset-2"} :parent ["ui-layout"]}]]
    (is (= [(if (>= (:width env) 768) "span 6" "span 12") "3"] [(on-element rules el "grid-column-end") (on-element rules el "grid-column-start")])
        (str "a span from md and an offset from below it hold together " (:width env)))))

(deftest a-container-is-as-wide-as-its-tier-allows
  (let [want (fn [w tier] (some (fn [[i [_ px]]] (when (and (>= i tier) (>= w px)) (str px "px"))) (reverse (map-indexed vector breakpoint-px))))
        {:keys [checked misses]} (misses [nil] (fn [rules env bp _ _]
                                                 (let [c (if bp (cls "container" bp nil) "ui-container")
                                                       tier (if bp (.indexOf ^java.util.List (mapv first breakpoint-px) bp) 0)]
                                                   [[(want (:width env) tier) "100%"]
                                                    [(on-element rules {:classes #{c}} "max-width") (on-element rules {:classes #{c}} "width")]])))]
    (is (= 144 checked) "witness: 24 environments, the plain container and five tiers")
    (is (= "1536px" (want 1536 0)) "witness: the test's own reading of a tier")
    (is (= [] misses) "full width below its tier, then no wider than the largest breakpoint reached")
    (doseq [[env rules] @env-rules]
      (is (= [nil "100%"] [(on-element rules {:classes #{"ui-container-fluid"}} "max-width") (on-element rules {:classes #{"ui-container-fluid"}} "width")])
          (str "the fluid one is never bounded " (:width env))))))

(deftest layout-columns-divide-the-layout-from-their-breakpoint-up
  (let [twelve "repeat(12,minmax(0,1fr))"
        {:keys [checked misses]} (misses (range 1 7) (fn [rules env bp px n]
                                                        (let [layout ["ui-layout" (cls "layout-cols" bp n)] on? (>= (:width env) px)]
                                                          [[(if on? (str "repeat(" n ",minmax(0,1fr))") twelve) (if on? "span 1" "span 12")]
                                                           [(on-element rules {:classes (set layout)} "grid-template-columns")
                                                            (on-element rules {:classes #{"x"} :parent layout} "grid-column-end")]])))]
    (is (= 864 checked) "witness: 24 environments, six tiers, six counts")
    (is (= [] misses) "n equal tracks, a child in each, from the breakpoint; twelve and a full row below"))
  (let [{:keys [misses]} (misses ["auto"] (fn [rules env bp px _]
                                            (let [layout ["ui-layout" (cls "layout-cols" bp "auto")] on? (>= (:width env) px)]
                                              [[(if on? "flex" "grid") (when on? "0 0 auto")]
                                               [(on-element rules {:classes (set layout)} "display") (on-element rules {:classes #{"x"} :parent layout} "flex")]])))]
    (is (= [] misses) "auto: a wrapping line of content-sized children, from the breakpoint"))
  (doseq [[env rules] @env-rules]
    (is (= (if (>= (:width env) 768) "grid" "flex") (on-element rules {:classes #{"ui-layout" "ui-layout-cols-auto" "ui-layout-cols-md-3"}} "display"))
        (str "a count from md makes a grid again of a layout auto below it " (:width env)))))

(deftest shared-and-content-sized-columns-turn-their-layout-into-a-line
  (doseq [[kind flex] [[nil "1 0 0"] ["auto" "0 0 auto"]]]
    (let [{:keys [misses]} (misses [kind] (fn [rules env bp px k]
                                            (let [c (cls "col" bp k) on? (>= (:width env) px)]
                                              [[(if on? "flex" "grid") (if on? "wrap" nil) (when on? flex)]
                                               [(on-element rules {:classes #{"ui-layout"} :children #{c}} "display")
                                                (on-element rules {:classes #{"ui-layout"} :children #{c}} "flex-wrap")
                                                (on-element rules {:classes #{c} :parent ["ui-layout"]} "flex")]])))]
      (is (= [] misses) (str "ui-col" (when kind "-auto") ": from its breakpoint the layout wraps a line, the column " flex))))
  (doseq [[env rules] @env-rules]
    (is (= "grid" (on-element rules {:classes #{"ui-layout"} :children #{"ui-col-6"}} "display")) "witness: a span leaves the layout a grid")))

(deftest a-gutter-is-the-layouts-gap-on-the-axes-it-names
  (let [default ["var(--ui-spacing-lg)" "0"]]
    (doseq [[prefix axes] [["g" [true true]] ["gx" [true false]] ["gy" [false true]]]]
      (let [{:keys [checked misses]} (misses (range 6) (fn [rules env bp px n]
                                                          (let [on? (>= (:width env) px) el {:classes #{"ui-layout" (cls prefix bp n)}}]
                                                            [(mapv (fn [axis d] (if (and on? axis) (spacing-steps n) d)) axes default)
                                                             [(on-element rules el "column-gap") (on-element rules el "row-gap")]])))]
        (is (= 864 checked) (str "witness: " prefix " across 24 environments, six tiers, six steps"))
        (is (= [] misses) (str "ui-" prefix "-[bp-]n: the kit's spacing step on " ({[true true] "both axes" [true false] "columns" [false true] "rows"} axes)
                               ", Bootstrap's 1.5rem columns and no rows otherwise"))))))

(deftest order-places-an-item-from-its-breakpoint-up
  (let [values (into {"first" "-1" "last" "6"} (for [n (range 6)] [(str n) (str n)]))
        {:keys [checked misses]} (misses (keys values) (fn [rules env bp px v]
                                                          [(when (>= (:width env) px) (values v)) (on-element rules {:classes #{(cls "order" bp v)}} "order")]))]
    (is (= 1152 checked) "witness: 24 environments, six tiers, eight places")
    (is (= [] misses) "first, 0..5 and last, from the breakpoint")))

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
    (is (= 126 (count pairs)) (str "witness: every painted pair found — the kit's 80, then the utilities' 46: seven fills, five link and eight text inks on three canvases each — a count measured once: " (count pairs)))
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
  "The scales that hold a colour literal: the shadows', which no palette token is, read only
  by `box-shadow`."
  #{:shadow-sm :shadow-md :shadow-lg :shadow-xl})

(deftest every-colour-comes-from-the-palette
  (let [rules    (leaf-rules)
        decls    (for [[sels ds] rules [k v] ds] [sels k v])
        scales   scale-names
        token-ok (fn [t] (or (re-matches #"-?[\d.]+(px|rem|em|%)?" t)
                             (#{"solid" "dashed" "dotted" "none" "inset" "inherit" "currentcolor" ","} t)
                             (when-let [[_ n] (re-matches #"var\(--ui-([a-z0-9-]+)\)" t)] (or (palette (keyword n)) (scales (keyword n))))))
        tokens   (fn [v] (map first (re-seq #"[^\s(]+(\([^)]*\))?" v)))
        bearing  (filter (fn [[_ k]] (re-find colour-bearing k)) decls)]
    (is (= 191 (count bearing)) (str "witness: the colour-bearing declarations, the kit's 119 and the utilities' 75, a count measured once: " (count bearing)))
    (is (= [] (for [[sels k v] bearing t (tokens v) :when (not (token-ok t))] [sels k t]))
        "a colour is a palette token: no literal, no keyword, nothing else")
    (is (= [] (for [[sels k v] bearing :when (#{"color" "background" "background-color"} k) :when (not (palette-var v))] [sels k v]))
        "a colour and a background read one")
    (is (= [] (for [[sels k v] decls :when (not (str/starts-with? k "--")) :when (re-find #"#[0-9a-fA-F]{3,8}\b|\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color|color-mix)\(" v)] [sels k v]))
        "no other property holds a colour literal either")
    (is (= [] (for [[sels k] decls :when (str/starts-with? k "--")] [sels k]))
        "the kit declares no property of its own: a colour smuggled through one would escape this test")
    (let [colour-scales (set (for [[_ n v] (re-seq #"--ui-([a-z0-9-]+)\s*:([^;]*);" (slurp (io/resource "dev/arkaitz/uikit_base/public/ui-colors.css")))
                                   :when (not (palette (keyword n))) :when (re-find #"#[0-9a-fA-F]{3,8}\b|rgba?\(" v)] (keyword n)))]
      (is (= shadows colour-scales) "the scales holding a colour are the shadows, and only those")
      (is (= #{"box-shadow"} (set (for [[_ k v] decls :when (some #(str/includes? v (str "var(--ui-" (name %) ")")) shadows)] k)))
          "and only a shadow reads them"))))

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
    (is (= 39 (count edges)) (str "witness: the edges — the kit's 27, then the utilities' five borders, six edge colours and the ring — a count measured once: " (count edges)))
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
    (is (= ["var(--ui-font-size-base)" "1.25" "var(--ui-spacing-sm)" "1px" nil] (box ".ui-btn")) "witness: the button's box")
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

(def ^:private bootstrap
  "Bootstrap 5.3.8's utilities and grid (MIT; test/resources/bootstrap-5.3.8, downloaded with
  the user's permission), read by this test's own parser."
  (delay (parse-css (str/replace (str (slurp (io/file "test/resources/bootstrap-5.3.8/bootstrap-utilities.css")) "\n"
                                      (slurp (io/file "test/resources/bootstrap-5.3.8/bootstrap-grid.css")))
                                 #"(?s)/\*.*?\*/" ""))))

(def ^:private bootstrap-widths
  "Bootstrap's breakpoints on basaku's, by the user's decision."
  {"576px" "640px" "768px" "768px" "992px" "1024px" "1200px" "1280px" "1400px" "1536px"})

(def ^:private logical-properties
  "Bootstrap's physical sides on the logical ones: start and end are inline, top and bottom stay."
  {"margin-left" "margin-inline-start" "margin-right" "margin-inline-end" "padding-left" "padding-inline-start"
   "padding-right" "padding-inline-end" "left" "inset-inline-start" "right" "inset-inline-end"
   "border-left" "border-inline-start" "border-right" "border-inline-end"
   "border-top-left-radius" "border-start-start-radius" "border-top-right-radius" "border-start-end-radius"
   "border-bottom-right-radius" "border-end-end-radius" "border-bottom-left-radius" "border-end-start-radius"})

(def ^:private logical-values {"float" {"left" "inline-start" "right" "inline-end"} "text-align" {"left" "start" "right" "end"}})

(def ^:private kit-values
  "Bootstrap's values on the kit's, by the properties each table applies to. A value no table
  names is compared as it is."
  (let [spacing #{"margin" "margin-top" "margin-bottom" "margin-inline" "margin-inline-start" "margin-inline-end"
                  "padding" "padding-top" "padding-bottom" "padding-inline" "padding-inline-start" "padding-inline-end"
                  "gap" "row-gap" "column-gap"}
        radius  #{"border-radius" "border-start-start-radius" "border-start-end-radius" "border-end-end-radius" "border-end-start-radius"}
        rgba    (fn [kind suffix] (str "rgba(var(--bs-" kind "-rgb), var(--bs-" suffix "))"))]
    [[spacing {"0.25rem" "var(--ui-spacing-xs)" "0.5rem" "var(--ui-spacing-sm)" "1rem" "var(--ui-spacing-md)"
               "1.5rem" "var(--ui-spacing-lg)" "3rem" "var(--ui-spacing-xl)"}]
     ;; Six of basaku's sizes top down; Bootstrap's fs-5 and fs-6 land a step lower (base, sm).
     [#{"font-size"} {"calc(1.375rem + 1.5vw)" "var(--ui-font-size-xxxl)" "calc(1.325rem + 0.9vw)" "var(--ui-font-size-xxl)"
                      "calc(1.3rem + 0.6vw)" "var(--ui-font-size-xl)" "calc(1.275rem + 0.3vw)" "var(--ui-font-size-lg)"
                      "1.25rem" "var(--ui-font-size-base)" "1rem" "var(--ui-font-size-sm)"}]
     [#{"font-weight"} {"300" "var(--ui-font-weight-light)" "400" "var(--ui-font-weight-regular)" "500" "var(--ui-font-weight-medium)"
                        "600" "var(--ui-font-weight-semibold)" "700" "var(--ui-font-weight-bold)"}]
     [#{"line-height"} {"1" "var(--ui-line-height-none)" "1.25" "var(--ui-line-height-tight)" "1.5" "var(--ui-line-height-normal)" "2" "var(--ui-line-height-loose)"}]
     [radius {"var(--bs-border-radius)" "var(--ui-radius-md)" "var(--bs-border-radius-sm)" "var(--ui-radius-sm)"
              "var(--bs-border-radius-lg)" "var(--ui-radius-lg)" "var(--bs-border-radius-xl)" "var(--ui-radius-xl)"
              "var(--bs-border-radius-pill)" "var(--ui-radius-full)"}]
     [#{"box-shadow"} {"var(--bs-box-shadow)" "var(--ui-shadow-md)" "var(--bs-box-shadow-sm)" "var(--ui-shadow-sm)" "var(--bs-box-shadow-lg)" "var(--ui-shadow-lg)"}]
     [#{"border-width"} {"1px" "var(--ui-border-width-default)" "2px" "var(--ui-border-width-thick)"}]
     [#{"border" "border-top" "border-bottom" "border-inline-start" "border-inline-end"}
      {"var(--bs-border-width) var(--bs-border-style) var(--bs-border-color)" "var(--ui-border-width-default) solid var(--ui-border-strong)"}]
     ;; A fixed bar is the kit's banner, a sticky one its sticky.
     [#{"z-index"} {"1030" "var(--ui-z-index-banner)" "1020" "var(--ui-z-index-sticky)"}]
     [#{"font-family"} {"var(--bs-font-monospace)" "var(--ui-font-family-mono)"}]
     [#{"transform"} {"var(--bs-icon-link-transform, translate3d(0.25em, 0, 0))" "translate3d(0.25em, 0, 0)"}]
     ;; The colours: each to the audited token the restricted utilities paint.
     [#{"color"} (into {"var(--bs-secondary-color)" "var(--ui-text-muted)" "var(--bs-emphasis-color)" "var(--ui-text-muted-strong)"}
                       (for [[k ink] [["primary" "link"] ["success" "success-ink"] ["info" "info-ink"] ["warning" "warning-ink"] ["danger" "danger-ink"]]]
                         [(rgba k "text-opacity") (str "var(--ui-" ink ")")]))]
     [#{"text-decoration-color"} (into {"rgba(var(--bs-link-color-rgb), var(--bs-link-opacity, 0.5))" "currentcolor"}
                                       (for [[k ink] [["primary" "link"] ["success" "success-ink"] ["info" "info-ink"] ["warning" "warning-ink"] ["danger" "danger-ink"]]]
                                         [(rgba k "link-underline-opacity") (str "var(--ui-" ink ")")]))]
     [#{"background-color"} {(rgba "body-bg" "bg-opacity") "var(--ui-background)" (rgba "secondary-bg" "bg-opacity") "var(--ui-surface-alt)"
                             (rgba "tertiary-bg" "bg-opacity") "var(--ui-surface)"}]
     [#{"border-color"} (into {} (for [k ["primary" "secondary" "success" "info" "warning" "danger"]] [(rgba k "border-opacity") (str "var(--ui-" k "-border)")]))]]))

(defn- canonical-condition [c] (str/replace (str/trim c) #":\s+" ":"))
(defn- canonical-selector [s] (-> (str/trim s) (str/replace #"\s*>\s*" ">") (str/replace #"\s+" " ")))

(defn- normalised
  "One Bootstrap declaration block as the kit writes it: `!important` dropped (a utility wins
  by its layer); vendor prefixes and Bootstrap's own custom properties dropped (the floor
  browsers need none, and the kit declares none); physical sides made logical, a start and
  an end of one value folded into the `-inline` shorthand; then each value through
  `kit-values`."
  [decls]
  (let [d (into {} (for [[k v] decls :when (not (str/starts-with? k "-"))]
                     [(logical-properties k k) (str/trim (str/replace v #"\s*!important$" ""))]))
        d (into {} (for [[k v] d] [k (get-in logical-values [k v] v)]))
        d (reduce (fn [d base] (let [s (str base "-inline-start") e (str base "-inline-end")]
                                 (if (and (contains? d s) (= (d s) (d e))) (-> d (dissoc s e) (assoc (str base "-inline") (d s))) d)))
                  d ["margin" "padding" "inset"])]
    (into {} (for [[k v] d] [k (or (some (fn [[props table]] (when (props k) (table v))) kit-values) v)]))))

(defn- first-class [sel] (second (re-find #"^\.(-?[a-zA-Z][\w-]*)" (str/trim sel))))

(defn- by-class
  "Each class that opens a selector, with the set of `[conditions selector declarations]` of
  every rule it opens."
  [rows]
  (reduce (fn [m [c row]] (update m c (fnil conj #{}) row)) {} rows))

(defn- bootstrap-view []
  (by-class (for [{:keys [selectors decls conditions]} (:rules @bootstrap) s selectors :let [c (first-class s)] :when c
                  :let [conds (mapv #(canonical-condition (str/replace % #"(min-width:\s*)(\d+px)" (fn [[_ p w]] (str p (bootstrap-widths w w))))) conditions)]
                  ;; RFS's fluid caps: basaku's sizes grow by their own variables.
                  :when (not (and (= ["@media (min-width:1280px)"] conds) (= #{"font-size"} (set (keys decls)))))]
              [c [conds (canonical-selector (-> s (str/replace #"\.bi(?![\w-])" "svg") (str/replace #"\.(-?[a-zA-Z][\w-]*)" ".ui-$1")))
                  (normalised decls)]])))

(defn- kit-view []
  (by-class (for [{:keys [selectors decls conditions]} (:rules utilities-parsed) s selectors :let [c (first-class s)] :when c]
              [c [(mapv canonical-condition conditions) (canonical-selector s) decls]])))

(def ^:private infixes [nil "sm" "md" "lg" "xl" "xxl"])
(defn- bs [prefix bp v] (str prefix (when bp (str "-" bp)) (when (some? v) (str "-" v))))

(def ^:private deviations
  "Every Bootstrap class the kit does not generate as `ui-<name>` with Bootstrap's
  declarations under the stated normalisation: `[:absent why]`, not generated; `[:reshaped
  why]`, generated differently; `[:renamed to why]`, generated under another name."
  (let [k      (fn [kind why classes] (for [c classes] [c [kind why]]))
        colours ["primary" "secondary" "success" "info" "warning" "danger" "light" "dark"]]
    (into {}
      (concat
        (k :absent "no ink of that colour is audited at AA on every canvas a background utility paints"
           (concat (map #(str "text-" %) ["secondary" "light" "dark" "black" "white" "body" "black-50" "white-50" "body-tertiary"])
                   (map #(str "text-" % "-emphasis") colours)))
        (k :absent "inherit is no palette token, and every colour the kit writes is one" ["text-reset"])
        (k :absent "an opacity of a colour: the palette has no alpha tokens, and the kit declares no custom property"
           (concat (map #(str "text-opacity-" %) [25 50 75 100])
                   (map #(str "bg-opacity-" %) [10 25 50 75 100])
                   (map #(str "border-opacity-" %) [10 25 50 75 100])
                   (for [v [10 25 50 75 100] h ["" "-hover"]] (str "link-opacity-" v h))
                   (for [v [0 10 25 50 75 100] h ["" "-hover"]] (str "link-underline-opacity-" v h))
                   ["link-underline"]))
        (k :absent "a background utility paints a neutral canvas alone; a fill comes with its audited ink through ui-text-bg-*"
           (concat (map #(str "bg-" %) (concat colours ["black" "white"])) (map #(str "bg-" % "-subtle") colours)))
        (k :absent "transparent is no palette token" ["bg-transparent"])
        (k :absent "a gradient is no palette token" ["bg-gradient"])
        (k :absent "no audited edge of that colour" (concat (map #(str "border-" %) ["light" "dark" "black" "white"]) (map #(str "border-" % "-subtle") colours)))
        (k :absent "no link ink of that colour is audited on every canvas"
           (concat (map #(str "link-" %) ["secondary" "light" "dark" "body-emphasis"]) (map #(str "link-underline-" %) ["secondary" "light" "dark"])))
        (k :absent "the kit has one focus ring, in the audited focus colour" (map #(str "focus-ring-" %) colours))
        (k :absent "a light fill is a canvas: ui-bg-* paints it, and ui-text-* inks it" ["text-bg-light"])
        (k :absent "basaku's radii stop at xl and full" (cons "rounded-5" (map #(str "rounded-" % "-5") ["top" "end" "bottom" "start"])))
        (k :reshaped "the fill's own audited ink, not Bootstrap's fixed black or white" (map #(str "text-bg-" %) ["primary" "secondary" "success" "info" "warning" "danger" "dark"]))
        (k :reshaped "no hover ink in the palette: the link keeps its audited ink when hovered" (map #(str "link-" %) ["primary" "success" "info" "warning" "danger"]))
        (k :reshaped "the kit's one ring, both bands, on :focus-visible" ["focus-ring"])
        (k :reshaped "the platform's aspect-ratio, not a padding through a custom property" ["ratio" "ratio-1x1" "ratio-4x3" "ratio-16x9" "ratio-21x9"])
        (k :reshaped "an audited edge colour at full opacity, not currentcolor at a quarter" ["vr"])
        (k :reshaped "a region that scrolls is positioned, so what it hides is clipped by it" (for [p ["overflow" "overflow-x" "overflow-y"] v ["auto" "scroll"]] (str p "-" v)))
        (k :reshaped "a shadow keeps the focus ring's halo beneath it when its element is focused" ["shadow" "shadow-sm" "shadow-lg" "shadow-none"])
        (k :reshaped "a container is bounded at basaku's breakpoints, as wide as the one it reached, its gutters the kit's spacing"
           (concat ["container" "container-fluid"] (map #(str "container-" %) (rest infixes))))
        (k :reshaped "a twelve-track CSS grid, not a flex row with negative margins: a span is grid-column-end, an offset grid-column-start, a gutter the gap"
           (concat (for [bp infixes v (cons "auto" (range 1 13))] (bs "col" bp v))
                   (for [bp infixes n (range (if bp 0 1) 12)] (bs "offset" bp n))
                   (for [p ["g" "gx" "gy"] bp infixes n (range 6)] (bs p bp n))))
        [["row" [:renamed "ui-layout" "ui-row is the kit's rows component"]]]
        (for [bp infixes v (cons "auto" (range 1 7))] [(bs "row-cols" bp v) [:renamed (str "ui-" (bs "layout-cols" bp v)) "ui-row is the kit's rows component"]])))))

(def ^:private extras
  "Every class the utilities generate that Bootstrap has not, with no Bootstrap name to take:
  the neutral canvases past Bootstrap's three, so a background utility reaches each."
  #{"ui-bg-surface" "ui-bg-surface-alt"})

(deftest every-bootstrap-utility-is-the-kits-or-a-named-deviation
  (let [bv (bootstrap-view) kv (kit-view)
        unmatched (set (for [[c rows] bv :when (not= rows (get kv (str "ui-" c)))] c))
        renamed (set (for [[_ [kind to]] deviations :when (= :renamed kind)] to))]
    (is (= 1705 (count bv)) (str "witness: the classes that open a Bootstrap selector, a count measured once: " (count bv)))
    (is (= 444 (count deviations)) "witness: the deviations, counted")
    (is (= #{} (set (remove (set (keys bv)) (keys deviations)))) "every deviation names a class Bootstrap has")
    (is (= (set (keys deviations)) unmatched)
        (str "every class Bootstrap has is the kit's, equal after normalisation, or a named deviation. Unnamed: "
             (vec (sort (remove (set (keys deviations)) unmatched))) " — named but equal: " (vec (sort (remove unmatched (keys deviations))))))
    (doseq [[c [kind to]] deviations]
      (case kind
        :absent   (is (nil? (get kv (str "ui-" c))) (str c " is absent, as its deviation says"))
        :reshaped (is (seq (get kv (str "ui-" c))) (str c " is generated, reshaped, as its deviation says"))
        :renamed  (is (and (nil? (get kv (str "ui-" c))) (seq (get kv to))) (str c " is generated as " to ", and only so"))))
    (is (= extras (set (remove (into renamed (map #(str "ui-" %) (keys bv))) (keys kv))))
        "and the utilities generate nothing else but the named extras")))

(def ^:private option-palettes
  "basaku's 201 option cases: presence by elevation for the nineteen seeds, and thirty with
  explicit seeds."
  (map :tokens (:cases (edn/read-string (slurp (io/file "test/resources/basaku-option-goldens.edn"))))))

(deftest every-colour-utility-combination-is-an-audited-pair-in-every-palette
  ;; A host may put any text utility on any background utility, on one element: each such
  ;; pair, and each fill with its own ink, must be one basaku audits for text and must read
  ;; at 4.5 in every palette it was measured in, light and dark.
  (let [single  (for [{:keys [selectors decls conditions]} (:rules utilities-parsed) s selectors
                      :when (empty? conditions) :when (re-matches #"\.ui-[\w-]+" s)] [s decls])
        token   (fn [d k] (palette-var (get d k)))
        inks    (vec (for [[s d] single :when (token d "color") :when (not (token d "background-color"))] [s (token d "color")]))
        grounds (vec (for [[s d] single :when (token d "background-color") :when (not (token d "color"))] [s (token d "background-color")]))
        fills   (vec (for [[s d] single :when (token d "color") :when (token d "background-color")] [s [(token d "background-color") (token d "color")]]))
        canvases (remove (fn [[s]] (= ".ui-vr" s)) grounds)
        pairs   (distinct (concat (for [[_ ink] inks [_ bg] canvases] [bg ink]) (map second fills)))
        palettes (concat (vals goldens) option-palettes)]
    (is (= [13 6 7] (mapv count [inks grounds fills])) "witness: thirteen text utilities, six backgrounds, seven fills")
    (is (= [".ui-vr"] (remove #(str/starts-with? % ".ui-bg-") (map first grounds)))
        "the one background that is no canvas: a rule, which paints no text")
    (is (= #{:background :surface :surface-alt} (set (map second canvases)))
        "the canvases are the three neutral surfaces every edge is audited on, and only those")
    (is (= 65 (* (count inks) (count canvases))) "witness: the class combinations a host can write")
    (is (= 28 (count pairs)) "witness: seven inks on three canvases, and seven fills")
    (is (= 220 (count palettes)) "witness: the nineteen seeds and the 201 option cases")
    (is (= [] (remove #(#{:aa :aaa} (audited %)) pairs)) "each an audited text pair")
    (is (= [] (for [[bg fg] pairs t palettes [scheme i] [[:light 0] [:dark 1]]
                    :let [r (wcag (get-in t [bg i]) (get-in t [fg i]))] :when (< r 4.5)]
                [bg fg scheme r]))
        "and each reads at 4.5 in every palette, light and dark")))

(deftest every-border-utility-is-a-visible-edge-on-every-background-utility
  ;; A border a host writes beside a background a host writes, on one element: the edge
  ;; must read at 3:1 there, or the box it draws is not seen.
  (let [single (for [{:keys [selectors decls conditions]} (:rules utilities-parsed) s selectors
                     :when (empty? conditions) :when (re-matches #"\.ui-[\w-]+" s)] [s decls])
        edge   (fn [d] (some #(palette-var (get d %)) ["border-color" "border" "border-top" "border-inline-start" "border-bottom" "border-inline-end"]))
        borders (distinct (for [[s d] single :when (str/starts-with? s ".ui-border") :let [t (edge d)] :when t] t))
        grounds (distinct (for [[s d] single :when (str/starts-with? s ".ui-bg-") :let [t (palette-var (get d "background-color"))] :when t] t))
        palettes (concat (vals goldens) option-palettes)]
    (is (seq borders) "witness: border colours were found")
    (is (= #{:background :surface :surface-alt} (set grounds)) "witness: the backgrounds")
    (is (= [] (for [b borders g grounds :when (not= :ui (audited [g b]))] [g b])) "each an audited edge pair")
    (is (= [] (for [b borders g grounds t palettes [scheme i] [[:light 0] [:dark 1]]
                    :let [r (wcag (get-in t [g i]) (get-in t [b i]))] :when (< r 3.0)] [g b scheme r]))
        "and each at 3:1 in every palette, light and dark")))

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
                                 :when (or (some #(str/starts-with? s %) ringed)
                                           ;; A utility may sit on anything focusable.
                                           (and (re-matches #"\.ui-[\w-]+" s) (some #{s} (mapcat :selectors (:rules utilities-parsed)))))] s))]
      (is (seq shaded) "witness: a focusable that casts a shadow of its own was found")
      (is (= #{".ui-shadow" ".ui-shadow-sm" ".ui-shadow-lg" ".ui-shadow-none"} (set (filter #(str/starts-with? % ".ui-shadow") shaded)))
          "witness: and every shadow utility, which a host may put on a focusable")
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
    (is (= ["/wb/wb.css" "/ui/ui-colors.css" "/ui/kit.css" "/ui/ui-utilities.css" "/ui-colors.css" "/app.css"]
           (map second (re-seq #"<link href=\"([^\"]+)\" rel=\"stylesheet\">" (:body page))))
        "web-base's, the kit's colours, the kit and its utilities, then the host's colours and its own sheet")
    (doseq [f ["kit.css" "ui-colors.css" "ui-utilities.css"]]
      (let [r (app (mock/request :get (str "/ui/" f))) want (bytes (io/resource (str "dev/arkaitz/uikit_base/public/" f)))]
        (is (= 200 (:status r)) f)
        (is (pos? (alength ^bytes want)) "witness: a file with bytes")
        (is (Arrays/equals ^bytes want ^bytes (bytes (:body r))) (str f " served byte for byte"))))
    (is (= 404 (:status (app (mock/request :get "/ui/nope.css")))) "and nothing else under /ui/")))
