(ns dev.arkaitz.uikit-base.color
  "The perceptual colour space under the palette: sRGB ↔ OKLab/OKLCh, a gamut clamp, the
  WCAG contrast ratio, and solvers that find the lightness hitting a contrast target. A
  port of basaku's `src/css/color_space.zig` and the contrast of `wcag_audit.zig`, kept
  line for line so the palette answers the same hex as basaku's (CLAUDE.md, rule 2).

  A colour is `[r g b]`, each 0–255. A hue is a unit vector `[ua ub]` in OKLab's (a, b)
  plane, as basaku carries it — grey collapses to `[1 0]`. `StrictMath` throughout: the
  same doubles on every JVM, so a golden that matches once matches everywhere.

  Two sRGB gamma thresholds, on purpose and as basaku has them: 0.04045 for OKLab and
  0.03928 for WCAG luminance. On 8-bit input both fall between 10/255 and 11/255, so
  either would answer the same; they are kept apart so the port stays the original.")

(defn- pow [^double x ^double y] (StrictMath/pow x y))

(defn gamma->linear
  "sRGB transfer, inverted: an encoded channel in [0, 1] → linear light."
  ^double [^double c]
  (if (<= c 0.04045) (/ c 12.92) (pow (/ (+ c 0.055) 1.055) 2.4)))

(defn linear->gamma
  "sRGB transfer: linear light in [0, 1] → an encoded channel."
  ^double [^double c]
  (if (<= c 0.0031308) (* 12.92 c) (- (* 1.055 (pow c (/ 1.0 2.4))) 0.055)))

(defn- cbrt
  "The cube root of a cone response, which is never negative where it is taken."
  ^double [^double x]
  (if (<= x 0.0) 0.0 (pow x (/ 1.0 3.0))))

(defn- clamp01 ^double [^double x] (cond (< x 0.0) 0.0 (> x 1.0) 1.0 :else x))

(defn srgb->oklab
  "`[r g b]` (0–255) → `[L a b]`, through Björn Ottosson's matrices."
  [[r g b]]
  (let [r (gamma->linear (/ (double r) 255.0))
        g (gamma->linear (/ (double g) 255.0))
        b (gamma->linear (/ (double b) 255.0))
        l (cbrt (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b)))
        m (cbrt (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b)))
        s (cbrt (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b)))]
    [(- (+ (* 0.2104542553 l) (* 0.7936177850 m)) (* 0.0040720468 s))
     (+ (- (* 1.9779984951 l) (* 2.4285922050 m)) (* 0.4505937099 s))
     (- (+ (* 0.0259040371 l) (* 0.7827717662 m)) (* 0.8086757660 s))]))

(defn- oklab->linear
  "`[L a b]` → linear `[r g b]`, unclamped: what leaves [0, 1] is out of gamut."
  [[L a b]]
  (let [l (+ L (* 0.3963377774 a) (* 0.2158037573 b))
        m (- L (* 0.1055613458 a) (* 0.0638541728 b))
        s (- L (* 0.0894841775 a) (* 1.2914855480 b))
        l (* l l l) m (* m m m) s (* s s s)]
    [(+ (- (* 4.0767416621 l) (* 3.3077115913 m)) (* 0.2309699292 s))
     (- (+ (* -1.2684380046 l) (* 2.6097574011 m)) (* 0.3413193965 s))
     (+ (- (* -0.0041960863 l) (* 0.7034186147 m)) (* 1.7076147010 s))]))

(defn- in-gamut? [lin]
  (every? #(and (>= (double %) -0.001) (<= (double %) (+ 1.0 0.001))) lin))

(defn chroma-of ^double [[_ a b]] (Math/sqrt (+ (* (double a) a) (* (double b) b))))

(defn hue-of
  "The hue of an OKLab colour as a unit vector; grey is `[1 0]`."
  [lab]
  (let [c (chroma-of lab) [_ a b] lab]
    (if (< c 1e-6) [1.0 0.0] [(/ (double a) c) (/ (double b) c)])))

(defn hue-degrees
  "A hue's angle in [0, 360), for the per-hue calibration curves."
  ^double [[ua ub]]
  (let [d (/ (* (StrictMath/atan2 ub ua) 180.0) Math/PI)]
    (if (< d 0.0) (+ d 360.0) d)))

(defn rotate
  "A hue turned by a fixed angle given its cosine and sine."
  [[ua ub] ^double cos-t ^double sin-t]
  [(- (* ua cos-t) (* ub sin-t)) (+ (* ua sin-t) (* ub cos-t))])

(defn- lab-at [^double l ^double c [ua ub]] [l (* c ua) (* c ub)])

(defn- channel
  "Linear light → an 8-bit channel, rounded half away from zero as Zig's `@round` does
  (the value is never negative here, so half up)."
  ^long [^double linear]
  (long (Math/floor (+ (* (clamp01 (linear->gamma (clamp01 linear))) 255.0) 0.5))))

(defn rgb-at
  "The sRGB colour at lightness `l`, chroma `c` and `hue`, the chroma lowered — lightness
  and hue kept — to the largest that sRGB holds, by 24 halvings."
  [^double l ^double c hue]
  (let [c (if (in-gamut? (oklab->linear (lab-at l c hue)))
            c
            (loop [lo 0.0 hi c i 0]
              (if (= i 24)
                lo
                (let [mid (/ (+ lo hi) 2.0)]
                  (if (in-gamut? (oklab->linear (lab-at l mid hue)))
                    (recur mid hi (inc i))
                    (recur lo mid (inc i)))))))]
    (mapv channel (oklab->linear (lab-at l c hue)))))

(def ^:private wcag-linear
  "WCAG 2's channel linearisation for each 8-bit value, as basaku tabulates it."
  (double-array (for [i (range 256)
                      :let [c (/ (double i) 255.0)]]
                  (if (<= c 0.03928) (/ c 12.92) (pow (/ (+ c 0.055) 1.055) 2.4)))))

(defn luminance
  "WCAG 2 relative luminance of `[r g b]`."
  ^double [[r g b]]
  (+ (* 0.2126 (aget ^doubles wcag-linear (int r)))
     (* 0.7152 (aget ^doubles wcag-linear (int g)))
     (* 0.0722 (aget ^doubles wcag-linear (int b)))))

(defn contrast
  "WCAG 2 contrast ratio between two colours, from 1 to 21."
  ^double [a b]
  (let [la (luminance a) lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(def ^:private steps
  "The lightness grid every solver scans: 257 samples, 0 to 1 inclusive."
  256)

(defn tone-for-contrast
  "The tone of `hue` and `chroma` whose contrast against `bg` reaches `target`, chosen
  among the passing ones by `pick`: `:darkest`, `:lightest`, or `:nearest` to `start-l`.
  Ties keep the first found. When none passes it answers the tone at `start-l`
  unchecked, as basaku does: its callers verify and fall back."
  [hue chroma bg target pick start-l]
  (let [chroma (double chroma) target (double target) start-l (double start-l)]
  (loop [i 0 best-l start-l best-score Double/POSITIVE_INFINITY found? false]
    (if (> i steps)
      (rgb-at best-l chroma hue)
      (let [l (/ (double i) (double steps))]
        (if (>= (contrast bg (rgb-at l chroma hue)) target)
          (let [score (case pick :darkest l :lightest (- 1.0 l) :nearest (Math/abs (- l start-l)))]
            (if (or (not found?) (< score best-score))
              (recur (inc i) l score true)
              (recur (inc i) best-l best-score found?)))
          (recur (inc i) best-l best-score found?)))))))

(defn tone-in-band
  "The tone of `hue` and `chroma` whose contrast lands inside [`lo`, `hi`]: the ceiling
  held against `bg-hi`, the surface it contrasts with most, the floor against `bg-lo`.
  The lowest score wins: a violation weighs a hundred times the distance from the band's
  centre."
  [hue chroma bg-hi bg-lo lo hi]
  (let [chroma (double chroma) lo (double lo) hi (double hi)]
  (loop [i 0 best-l 0.5 best-score Double/POSITIVE_INFINITY]
    (if (> i steps)
      (rgb-at best-l chroma hue)
      (let [l    (/ (double i) (double steps))
            tone (rgb-at l chroma hue)
            chi  (contrast bg-hi tone)
            clo  (contrast bg-lo tone)
            viol (+ (max 0.0 (- chi hi)) (max 0.0 (- lo clo)))
            score (+ (* viol 100.0) (Math/abs (- (/ (+ chi clo) 2.0) (/ (+ lo hi) 2.0))))]
        (if (< score best-score)
          (recur (inc i) l score)
          (recur (inc i) best-l best-score)))))))

(defn best-polarity
  "A near-white or a hue-tinted near-ink, whichever contrasts more with `bg`; white on
  a tie. The text colour on a fill."
  [bg hue]
  (let [white (rgb-at 0.985 0.012 hue)
        ink   (rgb-at 0.22 0.045 hue)]
    (if (>= (contrast bg white) (contrast bg ink)) white ink)))

(defn hex
  "`[r g b]` → `\"#rrggbb\"`."
  [[r g b]]
  (format "#%02x%02x%02x" (long r) (long g) (long b)))

(defn parse-hex
  "`\"#rrggbb\"` → `[r g b]`, or nil for anything else."
  [s]
  (when-let [[_ h] (and (string? s) (re-matches #"#([0-9a-fA-F]{6})" s))]
    (let [n (Long/parseLong h 16)]
      [(bit-and (bit-shift-right n 16) 0xff) (bit-and (bit-shift-right n 8) 0xff) (bit-and n 0xff)])))
