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
            [dev.arkaitz.uikit-base.brand :as brand]
            [dev.arkaitz.uikit-base.web :as web]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
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

(defn- chain
  "The selector, then without its trailing pseudo-class or attribute, then without its
  modifier: where an effective colour or background is inherited from."
  [sel]
  (let [base (str/replace sel #"(:[a-z-]+(\([^)]*\))?|\[[^\]]*\])+$" "")
        root (str/replace base #"--[a-z]+$" "")]
    (distinct [sel base root])))

(deftest every-text-the-kit-paints-is-an-audited-pair-and-reads-at-4-5
  (let [rules  (leaf-rules)
        by-sel (fn [s] (some (fn [[sels decls]] (when (some #{s} sels) decls)) (reverse rules)))
        eff    (fn [sel prop] (some #(palette-var (get (by-sel %) prop)) (chain sel)))
        pairs  (distinct (for [[sels decls] rules s sels
                               :when (or (contains? decls "color") (contains? decls "background"))
                               :let [fg (eff s "color") bg (eff s "background")]
                               :when fg
                               bg (if bg [bg] [:background :surface :surface-alt])]
                           [s bg fg]))
        disabled? (fn [[s]] (or (str/includes? s ":disabled") (str/includes? s "aria-disabled")))]
    (is (= 39 (count pairs)) (str "witness: every painted pair found, a count measured once: " (count pairs)))
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
            (is (>= r 4.5) (str seed " " scheme " " [bg fg] " " r))))))
    (testing "colour and background come only from the palette"
      (is (= [] (for [[sels decls] rules [k v] decls :when (#{"color" "background"} k) :when (not (palette-var v))] [sels k v]))))))

(deftest every-control-edge-is-visible
  (let [edges (for [[sels decls] (leaf-rules) s sels
                    :when (re-find #"^\.ui-(btn|input|notice|check)" s)
                    :when (not (or (str/includes? s ":disabled") (str/includes? s "aria-disabled")))
                    [k v] decls :when (re-find #"^(border|outline|accent-color)" k)
                    :let [t (palette-var v)] :when t
                    bg [:background :surface :surface-alt]]
                [s bg t])]
    (is (< 10 (count edges)) "witness: the edges were found")
    (doseq [[s bg t] (distinct edges)]
      (testing (str s " " t)
        (is (= :ui (audited [bg t])) "an audited non-text pair")
        (doseq [[seed tokens] goldens [scheme i] [[:light 0] [:dark 1]]]
          (is (>= (wcag (get-in tokens [bg i]) (get-in tokens [t i])) 3.0) (str seed " " scheme)))))))

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
