(ns dev.arkaitz.uikit-base.brand-test
  "The palette against basaku's own answers: for every seed of the golden table, every
  token, light and dark, the same hex — exactly, no tolerance (CLAUDE.md, rule 2). The
  table is basaku's output, never this port's, so a mismatch is the port's to explain."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base.audit :as audit]
            [dev.arkaitz.uikit-base.brand :as brand]
            [dev.arkaitz.uikit-base.color :as c]))

(def ^:private goldens (edn/read-string (slurp (io/file "test/resources/basaku-goldens.edn"))))

(deftest every-token-of-every-seed-is-basakus-own-hex--light-and-dark
  (is (= 19 (count (:seeds goldens))) "witness: basaku's eleven adversarial seeds and the eight chosen for coverage")
  (is (= #{72} (set (map count (vals (:seeds goldens))))) "witness: 72 tokens each")
  (doseq [[seed expected] (:seeds goldens)]
    (testing seed
      (let [actual (brand/theme {:primary seed})
            wrong  (into (sorted-map)
                         (keep (fn [[token pair]] (when (not= pair (get actual token)) [token {:basaku pair :port (get actual token)}])))
                         expected)]
        (is (= (set (keys expected)) (set (keys actual))) "the same 72 token names, no more, no fewer")
        (is (= {} wrong) (str (count wrong) " of 72 tokens differ"))))))

(def ^:private option-goldens (edn/read-string (slurp (io/file "test/resources/basaku-option-goldens.edn"))))

(deftest every-colour-option-is-basakus-own-hex--and-every-palette-passes-the-audit
  (is (= 201 (count (:cases option-goldens))) "witness: 19 seeds by three presences by three elevations, and thirty seed cases")
  (is (= #{72} (set (map (comp count :tokens) (:cases option-goldens)))) "witness: 72 tokens each")
  (is (= #{:calm :balanced :vivid} (set (map (comp :presence :opts) (:cases option-goldens)))) "witness: every presence")
  (is (= 30 (count (filter (comp #(or (:secondary %) (:tertiary %)) :opts) (:cases option-goldens)))) "witness: the seed cases")
  (doseq [{:keys [opts tokens]} (:cases option-goldens)]
    (testing (pr-str opts)
      (let [actual (brand/theme opts)
            wrong  (into (sorted-map) (keep (fn [[t pair]] (when (not= pair (get actual t)) [t {:basaku pair :port (get actual t)}]))) tokens)]
        (is (= {} wrong) (str (count wrong) " of 72 tokens differ"))
        (is (= [] (audit/failures actual)) "and the palette passes every audited pair, as basaku's build requires")))))

(deftest the-colour-vision-simulation-reproduces-basakus-measured-table
  ;; cvd.zig:144-146, measured there: OKLab distance of two sample pairs under each vision,
  ;; crossed so a projection wired to the wrong cone fails. Four decimals, as tabled.
  (let [simulate #'brand/simulate distance #'brand/distance
        d (fn [a b v] (distance (simulate a v) (simulate b v)))
        green [0x22 0xaa 0x22] orange [0xdd 0x88 0x00] cyan [0x00 0xc8 0xc8]
        row (fn [a b] (mapv #(/ (Math/round (* 10000.0 (d a b %))) 10000.0)
                            [:normal :deuteranopia :protanopia :tritanopia]))]
    (is (= [0.2302 0.0717 0.0387 0.3628] (row green orange)) "green | orange, as tabled")
    ;; The table's cyan | green row (0.1876 …) is not what its own formula gives: the
    ;; normal-vision distance is plain OKLab, 0.19434 computed independently. So that row
    ;; is held to basaku's tests instead, which are what cvd.zig actually asserts.
    (let [ratio #(/ (d cyan green %) (d cyan green :normal))]
      (is (< (Math/abs (- 0.19434 (d cyan green :normal))) 1e-5) "cyan | green, normal vision: plain OKLab distance")
      (is (and (< (ratio :tritanopia) 0.5) (> (ratio :deuteranopia) 0.8) (> (ratio :protanopia) 0.8))
          "tritanopia collapses it, the red-green deficiencies keep it"))
    (is (= [0.1716 0.0506 0.132 0.3328] (row [0x3d 0x7a 0xb8] [0x8a 0x3d 0xb8]))
        "a blue | violet pair, which the green and orange samples never reach through the blue cone (an independent implementation of the same published matrices, in Python)")))

(deftest the-status-fills-stay-a-ramp-step-apart-under-every-vision
  (is (true? (brand/cvd-ok?)) "basaku's gate: 24 pairs per scheme, every one at least 0.10 apart"))

(deftest the-hover-and-active-ladder-moves-the-way-basakus-own-tests-say
  ;; state_colors.zig's four fixtures, ported: properties, not hex, so the direction is
  ;; what is held — one the goldens leave unseen, since no seed's chip flips there.
  (let [pair #'brand/state-pair
        L    #(first (c/srgb->oklab %))
        slate {:light [0x64 0x74 0x8b] :dark [0x94 0xa3 0xb8]} on-slate {:light [255 255 255] :dark [0x0f 0x17 0x2a]}
        amber {:light [0xf5 0x9e 0x0b] :dark [0xfb 0xbf 0x24]} on-amber {:light [0x42 0x20 0x06] :dark [0x42 0x20 0x06]}
        pale  {:light [0xca 0x8a 0x04] :dark [0xfe 0xfc 0xe8]} on-pale {:light [255 255 255] :dark [0x1c 0x14 0x10]}
        states (fn [base on] [(pair base on 1.0 4.5) (pair base on 2.0 4.5)])]
    (let [[hover active] (states slate on-slate)]
      (is (and (< (L (:light hover)) (L (:light slate))) (< (L (:light active)) (L (:light hover))))
          "light scheme, white text: each state one step deeper")
      (is (and (> (L (:dark hover)) (L (:dark slate))) (> (L (:dark active)) (L (:dark hover))))
          "dark scheme, ink text: each state one step lighter"))
    (let [[hover active] (states amber on-amber)]
      (is (and (> (L (:light hover)) (L (:light amber))) (> (L (:light active)) (L (:light hover))))
          "a dark-text chip in the light scheme brightens: deepening would lose its contrast")
      (is (every? #(>= (c/contrast (:light %) (:light on-amber)) 4.5) [hover active]) "and keeps 4.5"))
    (let [[hover active] (states pale on-pale)]
      (is (> (+ (L (:dark pale)) (* 2 0.07)) 0.95) "witness: two steps up would leave the tone range")
      (is (and (< (L (:dark hover)) (L (:dark pale))) (< (L (:dark active)) (L (:dark hover))))
          "so a near-white dark chip deepens instead"))
    (let [grey {:light [176 176 176] :dark [176 176 176]} on {:light [0 0 0] :dark [0 0 0]}
          fits #'brand/direction-fits? lab (c/srgb->oklab [176 176 176])
          hover (pair grey on 1.0 4.5)]
      (is (and (fits lab [1.0 0.0] 0.0 [0 0 0] 1.0 4.5) (fits lab [1.0 0.0] 0.0 [0 0 0] -1.0 4.5))
          "witness: on black text both directions keep 4.5 for this grey, so the preference decides")
      (is (and (< (L (:light hover)) (L [176 176 176])) (> (L (:dark hover)) (L [176 176 176])))
          "and the preference is: light deepens, dark brightens"))))
