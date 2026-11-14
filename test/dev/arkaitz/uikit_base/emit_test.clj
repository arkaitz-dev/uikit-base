(ns dev.arkaitz.uikit-base.emit-test
  "The colours file read back from its own text, by a parser of this test: every token in
  each scheme, the audit holding over what was written, light light and dark dark, the
  media block the .dark one, and the bridge unlayered onto web-base's own colour
  properties as wb.css declares them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base.audit :as audit]
            [dev.arkaitz.uikit-base.brand :as brand]
            [dev.arkaitz.uikit-base.color :as c]
            [dev.arkaitz.uikit-base.emit :as emit]))

(def ^:private tokens (brand/theme {:primary "#2563eb"}))
(def ^:private text (emit/css tokens "x"))

(defn- decls
  "A block's declarations as a map, refusing a property declared twice: a map would keep
  the last and hide the first."
  [body]
  (let [pairs (map #(vec (str/split % #":" 2)) (remove str/blank? (str/split body #";")))
        m     (into {} pairs)]
    (when (= (count pairs) (count m)) m)))

(defn- block [re] (some->> (re-find re text) second decls))

(def ^:private light (block #"(?m)^:root\{(color-scheme:light;[^}]*)\}$"))
(def ^:private dark (block #"(?m)^:root\.dark\{([^}]*)\}$"))
(def ^:private media (block #"(?m)^@media \(prefers-color-scheme:dark\)\{:root:not\(\.light\)\{([^}]*)\}\}$"))
(def ^:private bridge (block #"(?m)^:root\{(--wb-[^}]*)\}$"))

(defn- scheme-tokens [d] (into {} (for [[k v] d :when (str/starts-with? k "--ui-")] [(keyword (subs k 5)) v])))

(deftest every-token-is-written-in-each-scheme-and-the-audit-holds-over-what-was-written
  (is (every? some? [light dark media bridge]) (str "witness: the four blocks parsed, none with a property twice, from\n" text))
  (is (= [1 1 1 1] (mapv #(count (re-seq % text)) [#"(?m)^:root\{color-scheme:light;" #"(?m)^:root\.dark\{"
                                                  #"(?m)^@media \(prefers-color-scheme:dark\)" #"(?m)^:root\{--wb-"]))
      "each block once")
  (is (= 5 (count (str/split-lines text))) "the comment and the four blocks, nothing else")
  (doseq [[label d] [["light" light] ["dark" dark] ["media" media]]]
    (is (= (conj (set (map #(str "--ui-" (name %)) (keys tokens))) "color-scheme") (set (keys d)))
        (str label ": the 72 tokens and color-scheme, and no other property")))
  (doseq [[label d i] [["light" light 0] ["dark" dark 1] ["media" media 1]]]
    (testing label
      (is (= (into {} (for [[k pair] tokens] [k (pair i)])) (scheme-tokens d)) "the 72 tokens, each its own value")))
  (is (= [] (audit/failures (into {} (for [k (keys tokens)] [k [((scheme-tokens light) k) ((scheme-tokens dark) k)]]))))
      "the 145 pairs re-audited on the hex read back")
  (is (= ["light" "dark" "dark"] (mapv #(% "color-scheme") [light dark media])) "each scheme declares itself")
  (is (= dark media) "the media block is the .dark block"))

(deftest light-is-light-and-dark-is-dark
  (is (> (c/luminance (c/parse-hex ((scheme-tokens light) :background))) 0.5) "the light background is light")
  (is (< (c/luminance (c/parse-hex ((scheme-tokens dark) :background))) 0.2) "the dark background is dark"))

(deftest the-bridge-maps-web-bases-colours-unlayered
  (let [wb      (slurp (io/resource "dev/arkaitz/web_base/public/wb.css"))
        wb-vars (set (map second (re-seq #"(--wb-[a-z-]+)\s*:" wb)))
        colour  (disj wb-vars "--wb-font" "--wb-measure" "--wb-gap")]
    (is (= 9 (count wb-vars)) (str "witness: wb.css declares nine properties: " wb-vars))
    (is (= colour (set (keys bridge))) "every colour property of wb.css is bridged, and nothing else")
    (is (= {"--wb-bg" "var(--ui-background)" "--wb-text" "var(--ui-on-background)" "--wb-muted" "var(--ui-text-muted)"
            "--wb-border" "var(--ui-border)" "--wb-accent" "var(--ui-link)" "--wb-danger" "var(--ui-danger-ink)"}
           bridge)
        "onto these tokens (a decision: the page, its text, muted text, lines, links, an error's text)")
    (is (not (str/includes? text "@layer")) "nothing in a layer: wb.css is unlayered, and would win")
    (is (not (str/includes? text "!important")) "and nothing forced")))
