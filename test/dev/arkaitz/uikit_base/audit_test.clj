(ns dev.arkaitz.uikit-base.audit-test
  "The audit as basaku defines it: its pairs are basaku's own list, printed by basaku; each
  rule holds at its threshold and refuses one grey step beyond, by a WCAG written again in
  this test; and a tampered palette is named pair, scheme and rule."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base.audit :as audit]
            [dev.arkaitz.uikit-base.brand :as brand]))

(def ^:private basaku-pairs (:pairs (edn/read-string (slurp (io/file "test/resources/basaku-audit-pairs.edn")))))
(def ^:private goldens (:seeds (edn/read-string (slurp (io/file "test/resources/basaku-goldens.edn")))))

(defn- wcag [a b]
  (let [hex (fn [s] (mapv #(Long/parseLong (subs s % (+ % 2)) 16) [1 3 5]))
        lin (fn [ch] (let [x (/ ch 255.0)] (if (<= x 0.03928) (/ x 12.92) (Math/pow (/ (+ x 0.055) 1.055) 2.4))))
        lum (fn [s] (let [[r g b] (hex s)] (+ (* 0.2126 (lin r)) (* 0.7152 (lin g)) (* 0.0722 (lin b)))))
        la (lum a) lb (lum b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(deftest the-audited-pairs-are-basakus-own-145
  (is (= 145 (count basaku-pairs)) "witness: basaku printed 145 pairs")
  (is (= {:aa 77 :aaa 15 :muted-band 3 :ui 48 :below-aa 1 :ring 1} (frequencies (map peek basaku-pairs)))
      "witness: as many of each rule")
  (is (= (frequencies basaku-pairs) (frequencies audit/pairs))
      (str "missing: " (vec (remove (set audit/pairs) basaku-pairs)) " extra: " (vec (remove (set basaku-pairs) audit/pairs)))))

(deftest every-golden-palette-passes-the-audit
  ;; A coarse net only: the goldens clear every threshold by a margin (measured: the
  ;; closest is 4.554 against 4.5), so no fine threshold mutant dies here — the next test
  ;; kills those.
  (is (= 19 (count goldens)) "witness: the nineteen palettes")
  (doseq [[seed tokens] goldens]
    (is (= [] (audit/failures tokens)) seed)))

(deftest each-rule-holds-at-its-threshold-and-not-one-grey-step-beyond
  (let [base (brand/theme {:primary "#2563eb"})
        cases [[:aa         :background :on-background "#ffffff" "#767676" "#777777"]
               [:aaa        :background :text-muted-strong "#ffffff" "#595959" "#5a5a5a"]
               [:ui         :background :border-strong "#ffffff" "#949494" "#959595"]
               [:ring       :focus-ring-halo :focus-ring "#ffffff" "#494949" "#4a4a4a"]
               [:muted-band :background :text-muted-subtle "#ffffff" "#a3a3a3" "#a4a4a4"]]]
    (doseq [[rule bg fg bg-hex pass fail] cases]
      (testing (name rule)
        (let [at   #(-> base (assoc-in [bg 0] bg-hex) (assoc-in [fg 0] %))
              hits (fn [tokens] (set (for [f (audit/failures tokens) :when (and (= (:pair f) [bg fg]) (= :light (:scheme f)))] (:rule f))))]
          (is (> (wcag bg-hex pass) (wcag bg-hex fail)) "witness: the passing grey contrasts more")
          (is (= #{} (hits (at pass))) (str pass " at " (wcag bg-hex pass) " holds"))
          (is (= #{rule} (hits (at fail))) (str fail " at " (wcag bg-hex fail) " is refused")))))
    (testing "the band's ceiling and the below-AA pair"
      (let [hit (fn [tokens pair] (set (for [f (audit/failures tokens) :when (and (= (:pair f) pair) (= :light (:scheme f)))] (:rule f))))]
        (is (= #{:muted-band} (hit (-> base (assoc-in [:background 0] "#ffffff") (assoc-in [:text-muted-subtle 0] "#767676"))
                                   [:background :text-muted-subtle]))
            "#767676 on white is 4.54, over the band's 4.5")
        (is (= #{:below-aa} (hit (-> base (assoc-in [:disabled-bg 0] "#ffffff") (assoc-in [:disabled-fg 0] "#767676"))
                                 [:disabled-bg :disabled-fg]))
            "a disabled pair at 4.54 reads as enabled: refused")
        (is (= #{} (hit (-> base (assoc-in [:disabled-bg 0] "#ffffff") (assoc-in [:disabled-fg 0] "#777777"))
                        [:disabled-bg :disabled-fg]))
            "and at 4.48 it holds")))))

(deftest a-tampered-palette-is-named-pair-scheme-and-rule
  (let [base (brand/theme {:primary "#2563eb"})]
    (is (= [] (audit/failures base)) "control: the palette itself holds")
    (is (= #{[[:primary :on-primary] :light :aa] [[:primary-hover :on-primary] :light :aa] [[:primary-active :on-primary] :light :aa]}
           (set (map (juxt :pair :scheme :rule) (audit/failures (assoc-in base [:on-primary 0] (get-in base [:primary 0]))))))
        "on-primary made primary, in light: the three fills that carry it")
    (is (= [[[:disabled-bg :disabled-fg] :dark :below-aa]]
           (mapv (juxt :pair :scheme :rule) (audit/failures (assoc-in base [:disabled-fg 1] (get-in base [:on-background 1])))))
        "a dark-scheme break is named dark")
    (is (= [[[:primary :on-primary] :light :aa nil]]
           (->> (audit/failures (update base :on-primary assoc 0 nil))
                (filter #(= [:primary :on-primary] (:pair %))) (mapv (juxt :pair :scheme :rule :ratio))))
        "a missing token is a failure with no ratio, never an exception")))
