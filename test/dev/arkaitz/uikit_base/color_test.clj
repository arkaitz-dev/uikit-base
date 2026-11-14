(ns dev.arkaitz.uikit-base.color-test
  "The colour space against oracles that do not run it: the published WCAG formula, the
  published OKLab values of the sRGB primaries, and a grey ladder computed here from first
  principles. The goldens against basaku itself are brand_test's; these hold the pieces
  apart, so a red names the piece. Every literal was measured once by an independent
  computation (Python, the published formulas) and typed here."
  (:require [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base.color :as c]))

;; --- local oracles, never the code under test ------------------------------------------

(defn- wcag
  "The published WCAG 2 contrast ratio, written here a second time."
  [a b]
  (let [lin (fn [ch] (let [x (/ ch 255.0)] (if (<= x 0.03928) (/ x 12.92) (Math/pow (/ (+ x 0.055) 1.055) 2.4))))
        lum (fn [[r g b]] (+ (* 0.2126 (lin r)) (* 0.7152 (lin g)) (* 0.0722 (lin b))))
        la (lum a) lb (lum b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn- ladder-grey
  "The sRGB grey of OKLab lightness `i/256`: a grey's OKLab L is the cube root of its
  linear light, so Y = L³, encoded and rounded half up."
  [i]
  (let [y (Math/pow (/ i 256.0) 3)
        g (if (<= y 0.0031308) (* 12.92 y) (- (* 1.055 (Math/pow y (/ 1 2.4))) 0.055))
        v (long (Math/floor (+ (* (min 1.0 (max 0.0 g)) 255.0) 0.5)))]
    [v v v]))

(def ^:private white [255 255 255])
(def ^:private black [0 0 0])
(def ^:private grey-hue [1.0 0.0])

(defn- close? [tol a b] (every? true? (map #(<= (Math/abs (- (double %1) (double %2))) tol) a b)))

;; --- contrast -------------------------------------------------------------------------

(deftest contrast-is-the-wcag-ratio
  (is (= [21.0 21.0 1.0] [(c/contrast black white) (c/contrast white black) (c/contrast white white)])
      "black and white are 21, either way round; a colour against itself is 1")
  (testing "the primaries against white, by the formula (1.05 / (Y + 0.05))"
    (is (close? 1e-12 [3.9984767707539985 1.3721902770517513 8.592471358428805]
                [(c/contrast white [255 0 0]) (c/contrast white [0 255 0]) (c/contrast white [0 0 255])])
        "each luminance coefficient in its place"))
  (is (close? 1e-9 [5.168555560 3.677901154]
              [(c/contrast white [37 99 235]) (c/contrast white [59 130 246])])
      "#2563eb and #3b82f6 on white, as basaku's default theme annotates them (5.17, 3.68)")
  (is (close? 1e-12 [(wcag white [37 99 235])] [(c/contrast white [37 99 235])])
      "and as the formula written a second time in this test answers"))

;; --- OKLab ----------------------------------------------------------------------------

(deftest oklab-of-the-srgb-primaries-is-ottossons
  ;; Published to six decimals (≤ 5e-7), matrices truncated to ten digits (≤ ~2e-9):
  ;; 1e-6 holds every component, and a coefficient off by 1e-5 breaks it.
  (doseq [[rgb lab] [[[255 255 255] [1.0 0.0 0.0]]
                     [[255 0 0] [0.627955 0.224863 0.125846]]
                     [[0 255 0] [0.866440 -0.233888 0.179498]]
                     [[0 0 255] [0.452014 -0.032457 -0.311528]]]]
    (testing (c/hex rgb)
      (is (close? 1e-6 lab (c/srgb->oklab rgb)) (str "Ottosson's value: " (c/srgb->oklab rgb)))))
  (is (= 0.0030959752321981426 (c/gamma->linear 0.04))
      "0.04 is on the linear side of OKLab's gamma threshold, 0.04045: 0.04 / 12.92"))

(deftest rgb-at-clamps-to-the-gamut-cusp-and-keeps-a-grey-grey
  (testing "at a primary's own lightness and hue the most chroma sRGB holds is that primary"
    (doseq [[rgb lab] [[[255 0 0] [0.627955 0.224863 0.125846]]
                       [[0 255 0] [0.866440 -0.233888 0.179498]]
                       [[0 0 255] [0.452014 -0.032457 -0.311528]]]
            req [0.4 1.0]]
      (let [hue (c/hue-of lab)]
        (is (= rgb (c/rgb-at (first lab) req hue)) (str (c/hex rgb) " at chroma " req)))))
  (testing "chroma 0 is the grey of that lightness, all 257 of the ladder"
    (is (= (mapv ladder-grey (range 257)) (mapv #(c/rgb-at (/ % 256.0) 0.0 grey-hue) (range 257)))))
  (is (= [189 0 0] (c/rgb-at 0.5 0.5 (c/hue-of [0.627955 0.224863 0.125846])))
      "off the cusp: red's hue at L 0.5, chroma lowered into the gamut (measured once on this port)"))

;; --- solvers --------------------------------------------------------------------------

(deftest tone-for-contrast-scans-both-ends-of-the-grid
  (is (= white (c/tone-for-contrast grey-hue 0.0 black 21.0 :nearest 0.3))
      "against black only L = 1 reaches 21: the scan includes its last sample")
  (is (= black (c/tone-for-contrast grey-hue 0.0 white 21.0 :lightest 0.3))
      "against white only L = 0 does: and its first"))

(deftest the-three-pick-modes-answer-three-different-tones
  (let [pick #(c/tone-for-contrast grey-hue 0.0 white 4.5 % 0.3)]
    (is (= [black [118 118 118] [46 46 46]] [(pick :darkest) (pick :lightest) (pick :nearest)])
        "darkest, the lightest passing grey (#767676), the passing grey nearest L 0.3 (#2e2e2e)")
    (is (and (>= (wcag white [118 118 118]) 4.5) (< (wcag white [119 119 119]) 4.5))
        "witness: #767676 passes 4.5 on white and the next grey up does not"))
  (is (= [99 99 99] (c/tone-for-contrast grey-hue 0.0 white 1.0 :nearest (+ 0.5 (/ 1.0 512))))
      "a tie between two samples keeps the first found")
  (is (= [118 118 118] (c/tone-for-contrast grey-hue 0.0 white 4.5 :nearest 0.9))
      "nearest from outside the passing set is its edge"))

(deftest when-nothing-passes-the-tone-at-the-start-lightness-comes-back
  (is (= [99 99 99] (c/tone-for-contrast grey-hue 0.0 white 22.0 :nearest 0.5))
      "22 is past any ratio: the tone at L 0.5, unchecked, as basaku answers"))

(deftest tone-in-band-lands-inside-a-one-tone-band
  (is (= [76 76 76] (c/tone-in-band grey-hue 0.0 white [153 153 153] 3.0 8.7))
      "the one grey with ≤ 8.7 against white and ≥ 3.0 against #999999")
  (is (and (<= (wcag white [76 76 76]) 8.7) (>= (wcag [153 153 153] [76 76 76]) 3.0)
           (> (wcag white [75 75 75]) 8.7) (< (wcag [153 153 153] [77 77 77]) 3.0))
      "witness: its neighbours each break one bound"))

(deftest best-polarity-answers-whichever-contrasts-more
  (is (= [43 17 26] (c/best-polarity white grey-hue)) "on white, the hue-tinted ink")
  (is (= [255 248 250] (c/best-polarity black grey-hue)) "on black, the near-white")
  (is (= [[255 248 250] [43 17 26]]
         [(c/best-polarity [122 122 122] grey-hue) (c/best-polarity [123 123 123] grey-hue)])
      "the crossing between two neighbouring greys"))

;; --- hex, hue -------------------------------------------------------------------------

(deftest hex-round-trips-and-refuses-anything-else
  (is (= "#2563eb" (c/hex [37 99 235])))
  (is (= [37 99 235] (c/parse-hex "#2563EB")))
  (doseq [s ["#fff" "red" nil "#12345g" "2563eb" "#2563eb00" "" "#2563eb\n"]]
    (is (nil? (c/parse-hex s)) (pr-str s))))

(deftest a-grey-has-the-conventional-hue-and-angles-run-from-0-to-360
  (is (= [[1.0 0.0] [1.0 0.0]] [(c/hue-of (c/srgb->oklab [119 119 119])) (c/hue-of [0.5 0.0 0.0])]))
  (is (close? 1e-3 [29.23383 264.05201]
              [(c/hue-degrees (c/hue-of [0.627955 0.224863 0.125846]))
               (c/hue-degrees (c/hue-of [0.452014 -0.032457 -0.311528]))])
      "red's hue and blue's, the second past 180 so the +360 branch runs"))
