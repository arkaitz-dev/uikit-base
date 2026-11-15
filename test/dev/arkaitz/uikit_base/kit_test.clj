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

(defn- leaf-rules
  "Every `selector { declarations }` with no block inside, as [selectors decls index]."
  []
  (vec (map-indexed (fn [i [_ sel body]]
                      [(mapv str/trim (str/split sel #",")) (into {} (for [d (str/split body #";") :let [[k v] (str/split d #":" 2)] :when v] [(str/trim k) (str/trim v)])) i])
                    (re-seq #"([^{}]+)\{([^{}]*)\}" (subs css (inc (str/index-of css "{")))))))

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
  pseudo-class are one class each (`:not(x)` counts its argument), a type or a
  pseudo-element one element."
  [sel]
  (let [s (str/replace sel #":not\(" "(")]
    [(count (re-seq #"#[\w-]+" s))
     (count (re-seq #"\.[\w-]+|\[[^\]]*\]|(?<!:):[a-z-]+" s))
     (+ (count (re-seq #"(?:^|[\s>+~(])[a-z][a-z0-9]*" s)) (count (re-seq #"::[a-z-]+" s)))]))

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
  implications, the combinators kept."
  [sel]
  (let [parts (re-seq #"[^\s>+~]+|\s*[>+~]\s*|\s+" (str/trim sel))]
    (map str/join (reduce (fn [acc part] (for [a acc x (if (re-matches compound-re part) (implied part) [part])] (conj a x))) [[]] parts))))

(defn- cascaded
  "The value the cascade gives `sel` for the first of `props` any rule sets: among the
  kit's rules that apply to it, the one of highest specificity, then the last — they share
  a layer. Nil when none sets it."
  [rules sel props]
  (let [applies (set (selectors-implied sel))]
    (some-> (last (sort-by (juxt (comp specificity first) second)
                           (for [[sels decls i] rules s sels :when (applies s) p props :when (contains? decls p)] [s i (get decls p)])))
            (nth 2))))

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
  (let [rules  (leaf-rules)
        pairs  (distinct (for [[sels decls] rules s sels
                               :when (some #(contains? decls %) (cons "color" backgrounds))
                               :let [fg (resolved rules s ["color"]) bg (resolved rules s backgrounds)]
                               :when fg
                               bg (if bg [bg] [:background :surface :surface-alt])]
                           [s bg fg]))
        disabled? (fn [[s]] (or (str/includes? s ":disabled") (str/includes? s "aria-disabled")))]
    (is (= [0 2 1] (specificity ".ui-pager a:focus-visible")) "witness: the test's specificity")
    (is (= #{".ui-btn--primary:hover" ".ui-btn--primary" ".ui-btn:hover" ".ui-btn"} (set (selectors-implied ".ui-btn--primary:hover")))
        "witness: a variant's hover is also the base's hover")
    (is (= 61 (count pairs)) (str "witness: every painted pair found, a count measured once: " (count pairs)))
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
                             (#{"solid" "dashed" "dotted" "none" "inset" "inherit" "currentcolor"} t)
                             (when-let [[_ n] (re-matches #"var\(--ui-([a-z0-9-]+)\)" t)] (or (palette (keyword n)) (scales (keyword n))))))
        tokens   (fn [v] (map first (re-seq #"[^\s(]+(\([^)]*\))?" v)))
        bearing  (filter (fn [[_ k]] (re-find colour-bearing k)) decls)]
    (is (= 107 (count bearing)) (str "witness: the colour-bearing declarations, a count measured once: " (count bearing)))
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
  #{[".ui-table th" :border] [".ui-table td" :border] [".ui-row" :border] [".ui-card" :border]})

(deftest every-control-edge-is-visible
  (let [found (distinct (for [[sels decls] (leaf-rules) s sels
                              :when (str/starts-with? s ".ui-")
                              :when (not (or (str/includes? s ":disabled") (str/includes? s "aria-disabled")))
                              [k v] decls :when (re-find #"^(border|outline|accent-color)" k)
                              :let [t (palette-var v)] :when t]
                          [s t]))
        edges (remove dividers found)]
    (is (= dividers (set (filter dividers found))) "every divider named is still drawn")
    (is (= 24 (count edges)) (str "witness: the edges, a count measured once: " (count edges)))
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
  (let [rules (leaf-rules)
        live  (remove #(str/includes? % ":disabled") (filter #(str/includes? % "[aria-invalid=\"true\"]") (input-states)))]
    (is (= 8 (count live)) (str "witness: the refused states, a count measured once: " (vec live)))
    (is (= [] (for [s live :let [t (resolved rules s ["border-color" "border"])] :when (not= :danger-border t)] [s t]))
        "hovered, read-only or not, a refused control is edged in danger")))

(deftest a-disabled-input-looks-disabled-in-every-state
  (let [rules (leaf-rules)
        look  (fn [s] (mapv #(resolved rules s %) [["color"] backgrounds ["border-color" "border"]]))
        dead  (filter #(str/includes? % ":disabled") (input-states))]
    (is (= [:disabled-fg :disabled-bg :disabled-bg] (look ".ui-input:disabled")) "witness: the disabled look")
    (is (= 16 (count dead)) (str "witness: the disabled states, a count measured once: " (count dead)))
    (is (= [] (for [s dead :let [l (look s)] :when (not= l (look ".ui-input:disabled"))] [s l]))
        "no hover, refusal or read-only shows through a disabled control")))

(deftest an-input-and-a-button-share-one-box
  ;; Only the declarations are compared: a browser alone measures pixels.
  (let [rules (leaf-rules)
        box   (fn [s] [(cascaded rules s ["font-size"]) (cascaded rules s ["line-height"])
                       (some-> (cascaded rules s ["padding-block" "padding"]) (str/split #"\s+") first)
                       (some-> (cascaded rules s ["border-width" "border"]) (str/split #"\s+") first)
                       (cascaded rules s ["height" "min-height"])])]
    (is (= ["var(--ui-font-base)" "1.25" "var(--ui-space-sm)" "1px" nil] (box ".ui-btn")) "witness: the button's box")
    (is (= (box ".ui-btn") (box ".ui-input")) "an inline input sits level with the button beside it")))

(deftest screen-reader-text-is-hidden-from-the-eye-and-not-from-the-reader
  (let [d (some (fn [[sels decls]] (when (= [".ui-sr-only"] sels) decls)) (leaf-rules))]
    (is (= {"position" "absolute" "width" "1px" "height" "1px" "overflow" "hidden" "clip" "rect(0, 0, 0, 0)"}
           (select-keys d ["position" "width" "height" "overflow" "clip"]))
        "out of the flow, one pixel (a zero-sized box some readers skip), clipped")
    (is (= {} (select-keys d ["display" "visibility" "content-visibility" "opacity"]))
        "never what hides it from a screen reader as well")
    (is (= [] (for [[sels decls] (leaf-rules) s sels :when (str/includes? s "ui-sr-only") :when (not= [".ui-sr-only"] sels)] [sels decls]))
        "and no other rule reaches it")))

(def ^:private req
  {::security/csrf-stored true :anti-forgery-token "T" ::security/csrf-used (volatile! false)
   :wb/tr (fn [k args] (str k args))})

(def ^:private fixtures
  "Every component, drawn with everything it can make focusable."
  {'button    [(ui/button "x") (ui/button {:variant :primary} "x")]
   'link-button [(ui/link-button "/a" {:variant :primary} "x")]
   'action    [(ui/action req "/a" {:label "x" :hidden {:a 1}})]
   'confirm   [(ui/confirm req "/a" {:label "x" :confirm-label "y"})]
   'empty     [(ui/empty "x")]
   'pairs     [(ui/pairs [["a" "b"]])]
   'time      [(ui/time (java.time.LocalDate/of 2026 1 15) "x")]
   'page-head [(ui/page-head {:title "t" :subtitle "s" :actions [(ui/link-button "/a" "x")]})]
   'post-form [(ui/post-form req "/a" (ui/button "x"))]
   'field     (for [t [nil "select" "textarea" "file" "checkbox" "radio"]] (ui/field req {:id "f" :label "F" :type t :options [["a" "A"]] :error :e :hint "h" :required? true}))
   'notice    [(ui/notice :ok "x")]
   'table     [(ui/table {:caption "c" :head ["h"] :rows [["a"]] :row-header? true})]
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
    (is (= 16 (count found)) (str "witness: the focusables, a count measured once: " (vec (map (juxt first #(nth % 2)) found))))
    (is (= [] bare) "each wears the ring, through a class of its own or its kit ancestor's")
    (is (= [] (for [[sels decls] (leaf-rules) :let [v (get decls "outline")] :when v :when (not= :focus-ring (palette-var v))] [sels v]))
        "no rule takes the ring away: an outline is the ring")))

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
