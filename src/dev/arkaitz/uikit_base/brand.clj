(ns dev.arkaitz.uikit-base.brand
  "A whole accessible palette, light and dark, from one brand colour: a port of basaku's
  `brandTheme` (`src/css/brand.zig`, with `state_colors.zig` and `boundary.zig`) at
  basaku 97024284, kept line for line — floating-point order included — so it answers
  the same hex for every token (CLAUDE.md, rule 2).

  Its options are basaku's colour ones: `:presence` (`:calm`, the default, `:balanced`,
  `:vivid`, brand.zig:56-75), a `:secondary` and a `:tertiary` seed (derived from the
  primary when absent, brand.zig:667-685), and `:elevation`'s dark tonal tiers (`:flat`,
  `:normal`, the default, `:raised`, personality.zig:332-352). Every contrast-critical role
  is solved against the surfaces it is audited on, as in basaku.

  `cvd-ok?` is basaku's status-separability gate, which there fires at compile time; it
  is a check on the derivation, never part of it."
  (:require [dev.arkaitz.uikit-base.color :as c]))

(def ^:private white [255 255 255])

;; ── Canvas calibration (brand.zig:42-54) ─────────────────────────────────
(def ^:private cap-light 0.052)
(def ^:private cap-dark 0.060)
(def ^:private bg-l-light 0.952)
(def ^:private su-l-light 0.935)
(def ^:private sa-l-light 0.925)
(def ^:private sh-l-light 0.905)
(def ^:private bg-l-dark 0.300)
(def ^:private sa-l-dark 0.327)
(def ^:private su-l-dark 0.350)
(def ^:private sh-l-dark 0.390)

;; Presence: the canvas chroma multiplier and the lightness dips (brand.zig:62-75).
(def ^:private presences
  {:calm     {:cmult 1.0 :dip-l 0.0 :dip-d 0.0}
   :balanced {:cmult 1.8 :dip-l 0.050 :dip-d 0.012}
   :vivid    {:cmult 2.8 :dip-l 0.100 :dip-d 0.028}})

;; Elevation's dark tonal deltas (personality.zig:337-352).
(def ^:private tonals
  {:flat   {:low 0.0 :mid 0.0 :high 0.0}
   :normal {:low 0.04 :mid 0.08 :high 0.12}
   :raised {:low 0.06 :mid 0.10 :high 0.12}})

(defn- cap-mult
  "Green/cyan tint a near-white field far more than blue/violet at equal chroma, so the
  canvas cap is equalised for perceived presence."
  ^double [^double hue-deg]
  (cond (and (>= hue-deg 140.0) (<= hue-deg 205.0)) 0.62
        (and (> hue-deg 205.0) (<= hue-deg 255.0)) 0.95
        (and (> hue-deg 255.0) (<= hue-deg 335.0)) 1.25
        :else 1.0))

(defn- lab-hue-chroma [rgb]
  (let [lab (c/srgb->oklab rgb)] [lab (c/hue-of lab) (c/chroma-of lab)]))

;; ── state_colors.zig ─────────────────────────────────────────────────────
(def ^:private step-l 0.07)
(def ^:private aa-normal 4.5)

(defn- clamp ^double [^double x ^double lo ^double hi] (cond (< x lo) lo (> x hi) hi :else x))

(defn- direction-fits?
  "The FULL two-step target must stay in the usable tone range and keep `floor` against
  the chip's own text, so hover and active always share one direction."
  [[L] h ch on sign floor]
  (let [target (+ (double L) (* (* sign 2.0) step-l))]
    (and (not (or (< target 0.05) (> target 0.95)))
         (>= (c/contrast (c/rgb-at target ch h) on) floor))))

(defn- state-solve [base on preferred-sign steps floor]
  (let [[lab h ch] (lab-hue-chroma base)
        sign     (cond (direction-fits? lab h ch on preferred-sign floor) preferred-sign
                       (direction-fits? lab h ch on (- preferred-sign) floor) (- preferred-sign)
                       :else preferred-sign)
        target-l (clamp (+ (double (first lab)) (* (* sign steps) step-l)) 0.05 0.95)
        candidate (c/rgb-at target-l ch h)]
    (if (>= (c/contrast candidate on) floor)
      candidate
      (c/tone-for-contrast h ch on floor :nearest target-l))))

(defn- state-pair
  "Light deepens, dark brightens, unless the ladder lacks room that way."
  [{bl :light bd :dark} {ol :light od :dark} steps floor]
  {:light (state-solve bl ol -1.0 steps floor)
   :dark  (state-solve bd od 1.0 steps floor)})

;; ── boundary.zig ─────────────────────────────────────────────────────────
(def ^:private boundary-target 3.3)
(def ^:private ink-target 4.8)
(def ^:private ring-pair-target (+ 9.0 0.3))
(def ^:private halo-light [255 255 255])
(def ^:private halo-dark [0 0 0])

(defn- ring-for [h chroma seed-l halo]
  (c/tone-for-contrast h chroma halo ring-pair-target :nearest seed-l))

(defn- worst-against ^double [tone surfaces]
  (reduce (fn [^double w s] (min w (c/contrast tone s))) Double/MAX_VALUE surfaces))

(defn- boundary-solve-to
  "The nearest tone of the hue clearing `floor` against every surface; a fill that
  already does is its own boundary, since re-deriving it would desaturate it."
  [h chroma fill surfaces floor]
  (if (>= (worst-against fill surfaces) floor)
    fill
    (let [ch     (min (double chroma) 0.16)
          fill-l (double (first (c/srgb->oklab fill)))
          found  (first (for [d (range 100)
                              :let [off (/ (double d) 100.0)]
                              [k l] [[0 (- fill-l off)] [1 (+ fill-l off)]]
                              :when (not (and (zero? d) (= k 1)))
                              :when (not (or (< l 0.02) (> l 0.98)))
                              :let [tone (c/rgb-at l ch h)]
                              :when (>= (worst-against tone surfaces) floor)]
                          tone))]
      (or found
          (let [lo (c/rgb-at 0.02 ch h) hi (c/rgb-at 0.98 ch h)]
            (if (> (worst-against hi surfaces) (worst-against lo surfaces)) hi lo))))))

(defn- boundary-solve [h chroma fill surfaces]
  (boundary-solve-to h chroma fill surfaces boundary-target))

;; ── Status families (brand.zig:180-436) ──────────────────────────────────
(def ^:private status-target 7.3)
(def ^:private status-fallback 4.8)
(def ^:private ladder-lo 0.40)
(def ^:private ladder-hi 0.80)

(def ^:private status-seeds
  "success, danger, warning, info: hue and chroma come from these, lightness from the ladder."
  [{:light [0x15 0x80 0x3d] :dark [0x4a 0xde 0x80]}
   {:light [0xdc 0x26 0x26] :dark [0xf8 0x71 0x71]}
   {:light [0xf5 0x9e 0x0b] :dark [0xfb 0xbf 0x24]}
   {:light [0x0e 0x74 0x90] :dark [0x22 0xd3 0xee]}])

(def ^:private status-names [:success :danger :warning :info])

(defn- status-ink [h base side]
  (let [ink (c/tone-for-contrast h 0.06 base status-target side 0.22)]
    (if (>= (c/contrast ink base) status-target)
      ink
      (c/tone-for-contrast h 0.025 base status-fallback side 0.22))))

(defn- natural-peak
  "The lightness where the hue carries most chroma: the chroma asked for is beyond any
  gamut, so `rgb-at` clamps it to what the hue can hold there."
  [h]
  (reduce (fn [[^double best-l ^double best-c :as best] i]
            (let [l  (/ (double i) 100.0)
                  ch (c/chroma-of (c/srgb->oklab (c/rgb-at l 0.4 h)))]
              (if (> ch best-c) [l ch] best)))
          [0.5 0.0]
          (range 6 97)))

(defn- ladder-slots
  "Each family's rung, ranked by natural peak; the index breaks a total tie so the
  ranking is a permutation."
  [scheme]
  (let [peaks (mapv #(natural-peak (c/hue-of (c/srgb->oklab (scheme %)))) status-seeds)
        n     (count peaks)
        span  (double (dec n))]
    (vec (for [i (range n)
               :let [[li ci] (peaks i)
                     rank (count (for [j (range n)
                                       :when (not= j i)
                                       :let [[lj cj] (peaks j)]
                                       :when (or (< lj li)
                                                 (and (== lj li) (or (< cj ci) (and (== cj ci) (< j i)))))]
                                   j))]]
           (+ ladder-lo (/ (* (- ladder-hi ladder-lo) (double rank)) span))))))

(defn- place-at-slot
  "The lightness nearest the rung that still clears the target with some ink."
  [h chroma ^double slot]
  (let [ch (min (double chroma) 0.16)]
    (or (first (for [d (range 40)
                     :let [off (/ (double d) 100.0)]
                     [k l] [[0 (- slot off)] [1 (+ slot off)]]
                     :when (not (and (zero? d) (= k 1)))
                     :when (not (or (< l 0.06) (> l 0.96)))
                     :let [fill (c/rgb-at l ch h)]
                     side [:lightest :darkest]
                     :let [ink (c/tone-for-contrast h 0.06 fill status-target side 0.5)]
                     :when (>= (c/contrast fill ink) status-target)]
                 {:fill fill :ink ink}))
        (let [fill (c/rgb-at slot ch h)]
          {:fill fill :ink (status-ink h fill :lightest)}))))

(defn- status-pair [index slots-l slots-d]
  (let [seed  (status-seeds index)
        place (fn [scheme slots]
                (let [[_ h ch] (lab-hue-chroma (seed scheme))] (place-at-slot h ch (slots index))))
        light (place :light slots-l)
        dark  (place :dark slots-d)
        base  {:light (:fill light) :dark (:fill dark)}
        on    {:light (:ink light) :dark (:ink dark)}]
    {:base base :on on
     :hover (state-pair base on 1.0 status-target)
     :active (state-pair base on 2.0 status-target)}))

(def ^:private status
  "Seed-independent, so solved once, on first use."
  (delay (let [sl (ladder-slots :light) sd (ladder-slots :dark)]
           (mapv #(status-pair % sl sd) (range (count status-seeds))))))

(defn- status-against
  "Borders or inks of the four families: a relationship with the seed's canvas."
  [surfaces-l surfaces-d floor]
  (mapv (fn [seed {:keys [base]}]
          (let [solve (fn [scheme surfaces]
                        (let [[_ h ch] (lab-hue-chroma (seed scheme))]
                          (boundary-solve-to h ch (base scheme) surfaces floor)))]
            {:light (solve :light surfaces-l) :dark (solve :dark surfaces-d)}))
        status-seeds @status))

;; ── CVD gate (cvd.zig, brand.zig:438-513) ────────────────────────────────
(def ^:private rgb->lms [[17.8824 43.5161 4.11935] [3.45565 27.1554 3.86714] [0.0299566 0.184309 1.46709]])
(def ^:private lms->rgb [[0.080944 -0.130504 0.116721] [-0.0102485 0.0540194 -0.113615] [-0.000365294 -0.00412163 0.693513]])
(def ^:private projections
  {:deuteranopia [[1.0 0.0 0.0] [0.494207 0.0 1.24827] [0.0 0.0 1.0]]
   :protanopia   [[0.0 2.02344 -2.52581] [0.0 1.0 0.0] [0.0 0.0 1.0]]
   :tritanopia   [[1.0 0.0 0.0] [0.0 1.0 0.0] [-0.395913 0.801109 0.0]]})
(def ^:private visions [:normal :deuteranopia :protanopia :tritanopia])
(def ^:private status-separability-floor 0.10)

(defn- apply-m [m [v0 v1 v2]]
  (mapv (fn [[m0 m1 m2]] (+ (+ (* (double m0) v0) (* (double m1) v1)) (* (double m2) v2))) m))

(defn- to-byte ^long [^double x]
  (let [g (* (c/linear->gamma (/ (clamp x 0.0 255.0) 255.0)) 255.0)]
    (long (Math/floor (+ (clamp g 0.0 255.0) 0.5)))))

(defn- simulate [rgb vision]
  (if (= vision :normal)
    rgb
    (let [lin (mapv #(* (c/gamma->linear (/ (double %) 255.0)) 255.0) rgb)]
      (mapv to-byte (apply-m lms->rgb (apply-m (projections vision) (apply-m rgb->lms lin)))))))

(defn- distance ^double [a b]
  (let [[la aa ba] (c/srgb->oklab a)
        [lb ab bb] (c/srgb->oklab b)
        dl (- la lb) da (- aa ab) db (- ba bb)]
    (Math/sqrt (+ (+ (* dl dl) (* da da)) (* db db)))))

(defn cvd-ok?
  "Whether the four status fills stay at least a full ramp step (0.10 in OKLab) apart,
  pairwise, under normal vision and the three dichromacies, in both schemes — the promise
  contrast cannot make, since it is blind to hue. `false` means the solver regressed."
  []
  (let [fills (mapv :base @status)]
    (every? (fn [scheme]
              (let [ds (for [i (range (count fills)) j (range (inc i) (count fills)) v visions]
                         (distance (simulate ((fills i) scheme) v) (simulate ((fills j) scheme) v)))]
                (and (= 24 (count ds)) (>= (reduce min ds) status-separability-floor))))
            [:light :dark])))

;; ── Accent tracks (brand.zig:515-564) ────────────────────────────────────
(defn- track [h chroma ^double ref-l]
  (let [ch          (min (double chroma) 0.16)
        eval-l      (if (> ref-l 0.7) ref-l (min ref-l 0.5))
        rep         (c/rgb-at eval-l ch h)
        pol-l       (c/best-polarity rep h)
        base-l      (c/tone-for-contrast h ch pol-l 4.6 :nearest ref-l)
        lightc      (min ch 0.11)
        plight-l    (c/rgb-at 0.86 lightc h)
        on-plight-l (c/tone-for-contrast h (min ch 0.06) plight-l 4.6 :darkest 0.22)
        pdark-l     (c/tone-for-contrast h ch white 5.0 :nearest (min ref-l 0.42))
        ink-d       (c/rgb-at 0.22 (min ch 0.10) h)
        base-d      (c/tone-for-contrast h ch ink-d 4.6 :nearest 0.72)
        plight-d    (c/rgb-at 0.80 lightc h)
        on-plight-d (c/tone-for-contrast h (min ch 0.06) plight-d 4.6 :darkest 0.22)
        pdark-d     (c/tone-for-contrast h ch white 5.0 :lightest 0.5)
        base        {:light base-l :dark base-d}
        on          {:light pol-l :dark ink-d}]
    {:base base :on on
     :hover (state-pair base on 1.0 aa-normal)
     :active (state-pair base on 2.0 aa-normal)
     :light {:light plight-l :dark plight-d}
     :on-light {:light on-plight-l :dark on-plight-d}
     :dark {:light pdark-l :dark pdark-d}
     :on-dark {:light white :dark white}}))

(defn- brand-border [{bl :light bd :dark} surfaces-l surfaces-d]
  (let [[_ hl cl] (lab-hue-chroma bl)
        [_ hd cd] (lab-hue-chroma bd)]
    {:light (boundary-solve hl cl bl surfaces-l)
     :dark  (boundary-solve hd cd bd surfaces-d)}))

(defn- v [l d] {:light l :dark d})

(defn- verified
  "The house target when it is reached, else the fallback: the solver answers its start
  tone unchecked when the target is out of reach."
  [try-tone against target fallback]
  (if (>= (c/contrast try-tone against) target) try-tone (fallback)))

(defn- seed-of [k hex]
  (or (c/parse-hex hex) (throw (ex-info (str "the " (name k) " must be a #rrggbb colour") {k hex}))))

(defn theme
  "The 72 tokens of basaku's `brandTheme` for `{:primary \"#rrggbb\"}` and its colour
  options — `:secondary`, `:tertiary`, `:presence`, `:elevation` — as keyword →
  `[\"#light\" \"#dark\"]`."
  [{:keys [primary secondary tertiary presence elevation] :or {presence :calm elevation :normal}}]
  (let [seed (seed-of :primary primary)
        pres  (or (presences presence) (throw (ex-info "the presence is :calm, :balanced or :vivid" {:presence presence})))
        tonal (or (tonals elevation) (throw (ex-info "the elevation is :flat, :normal or :raised" {:elevation elevation})))
        [lab hp cp] (lab-hue-chroma seed)
        lab-l    (double (first lab))
        mult     (cap-mult (c/hue-degrees hp))
        cc-l     (min cp (* (* cap-light mult) (:cmult pres)))
        cc-d     (min cp (* (* cap-dark mult) (:cmult pres)))
        dl (:dip-l pres) dd (:dip-d pres)
        bg       (v (c/rgb-at (- bg-l-light dl) cc-l hp) (c/rgb-at (- bg-l-dark dd) cc-d hp))
        surface  (v (c/rgb-at (- su-l-light dl) (* cc-l 1.05) hp) (c/rgb-at (- su-l-dark dd) cc-d hp))
        surf-alt (v (c/rgb-at (- sa-l-light dl) (* cc-l 1.10) hp) (c/rgb-at (- sa-l-dark dd) (* cc-d 1.05) hp))
        surf-hov (v (c/rgb-at (- sh-l-light dl) (* cc-l 1.15) hp) (c/rgb-at (- sh-l-dark dd) (* cc-d 1.05) hp))
        bg-l (:light bg) bg-d (:dark bg) su-d (:dark surface)
        sa-l (:light surf-alt) sh-l (:light surf-hov) su-light (:light surface)
        tier-low-d  (c/rgb-at (+ (- su-l-dark dd) (:low tonal)) cc-d hp)
        tier-mid-d  (c/rgb-at (+ (- su-l-dark dd) (:mid tonal)) cc-d hp)
        tier-high-d (c/rgb-at (+ (- su-l-dark dd) (:high tonal)) cc-d hp)
        surfaces-l [bg-l su-light sa-l sh-l]
        surfaces-d [bg-d su-d (:dark surf-alt) (:dark surf-hov) tier-low-d tier-mid-d tier-high-d]
        st         @status
        st-borders (status-against surfaces-l surfaces-d boundary-target)
        st-inks    (status-against surfaces-l surfaces-d ink-target)

        on-canvas (v (c/tone-for-contrast hp 0.03 sh-l 5.5 :lightest 0.22)
                     (verified (c/tone-for-contrast hp 0.015 tier-high-d 5.5 :darkest 0.92) tier-high-d 5.5
                               #(c/tone-for-contrast hp 0.015 tier-high-d 4.7 :darkest 0.95)))
        si    (v (c/rgb-at 0.20 (+ cc-l 0.01) hp) (c/rgb-at 0.95 cc-l hp))
        si-l  (:light si) si-d (:dark si)
        on-si (v (c/tone-for-contrast hp 0.02 si-l 5.5 :darkest 0.92)
                 (c/tone-for-contrast hp 0.03 si-d 5.5 :lightest 0.20))

        tert-h (c/rotate hp -0.8660254037844387 0.5)
        p   (track hp cp lab-l)
        s   (if secondary
              (let [[sl sh sc] (lab-hue-chroma (seed-of :secondary secondary))] (track sh sc (double (first sl))))
              (track hp (* cp 0.25) lab-l))
        ter (if tertiary
              (let [[tl th tc] (lab-hue-chroma (seed-of :tertiary tertiary))] (track th tc (double (first tl))))
              (track tert-h (* cp 0.9) lab-l))
        pri-border (brand-border (:base p) surfaces-l surfaces-d)
        sec-border (brand-border (:base s) surfaces-l surfaces-d)

        neutral-l    (c/rgb-at 0.76 (min cp 0.035) hp)
        on-neutral-l (c/tone-for-contrast hp 0.03 neutral-l 4.6 :lightest 0.25)
        on-neutral-d (c/rgb-at 0.92 0.015 hp)
        neutral-d    (c/tone-for-contrast hp (min cp 0.05) on-neutral-d 4.6 :darkest 0.40)

        link     (v (c/tone-for-contrast hp cp sa-l 4.7 :lightest lab-l)
                    (verified (c/tone-for-contrast hp cp tier-high-d 4.7 :darkest 0.72) tier-high-d 4.7
                              #(c/tone-for-contrast hp 0.02 tier-high-d 4.6 :darkest 0.95)))
        link-inv (v (c/tone-for-contrast hp cp si-l 4.7 :darkest 0.72)
                    (c/tone-for-contrast hp cp si-d 4.7 :lightest 0.40))
        tms   (v (c/tone-for-contrast hp 0.04 sa-l 7.2 :lightest 0.30)
                 (verified (c/tone-for-contrast hp 0.03 tier-high-d 7.2 :darkest 0.85) tier-high-d 7.2
                           #(c/tone-for-contrast hp 0.015 tier-high-d 4.8 :darkest 0.95)))
        tm    (v (c/tone-for-contrast hp 0.045 sa-l 4.8 :lightest 0.40)
                 (verified (c/tone-for-contrast hp 0.035 tier-high-d 4.8 :darkest 0.74) tier-high-d 4.8
                           #(c/tone-for-contrast hp 0.015 tier-high-d 4.6 :darkest 0.95)))
        tmsub (v (c/tone-in-band hp 0.04 bg-l sa-l 2.6 4.4)
                 (c/tone-in-band hp 0.035 bg-d su-d 2.6 4.4))

        border        (v (c/rgb-at 0.90 cc-l hp) (c/rgb-at 0.33 cc-d hp))
        border-strong (v (c/tone-for-contrast hp 0.05 sa-l 3.3 :lightest 0.55)
                         (c/tone-for-contrast hp 0.05 su-d 3.3 :darkest 0.62))
        focus     (v (ring-for hp cp lab-l halo-light) (ring-for hp cp lab-l halo-dark))
        sel-l     (c/rgb-at 0.88 (min cp 0.10) hp)
        on-sel-l  (c/tone-for-contrast hp 0.03 sel-l 4.6 :lightest 0.25)
        on-sel-d  (c/rgb-at 0.92 0.015 hp)
        sel-d     (c/tone-for-contrast hp (min cp 0.10) on-sel-d 4.6 :darkest 0.34)
        overlay   (c/rgb-at 0.12 cc-d hp)

        tokens (merge
                {:background bg :on-background on-canvas
                 :surface surface :on-surface on-canvas
                 :surface-hover surf-hov :surface-alt surf-alt :on-surface-alt on-canvas
                 :surface-low (v su-light tier-low-d) :surface-mid (v su-light tier-mid-d)
                 :surface-high (v su-light tier-high-d)
                 :surface-inverse si :on-surface-inverse on-si
                 :overlay (v overlay overlay)
                 :tertiary (:base ter) :on-tertiary (:on ter)
                 :neutral (v neutral-l neutral-d) :on-neutral (v on-neutral-l on-neutral-d)
                 :primary-border pri-border :secondary-border sec-border
                 :border border :border-strong border-strong
                 :link link :link-inverse link-inv
                 :text-muted-strong tms :text-muted tm :text-muted-subtle tmsub
                 :selection (v sel-l sel-d) :on-selection (v on-sel-l on-sel-d)
                 :disabled-bg (v (c/rgb-at 0.90 cc-l hp) (c/rgb-at 0.33 cc-d hp))
                 :disabled-fg (v (c/rgb-at 0.74 (min cp 0.03) hp) (c/rgb-at 0.48 (min cp 0.04) hp))
                 :focus-ring focus :focus-ring-halo (v halo-light halo-dark)}
                (into {} (for [[prefix t] [["primary" p] ["secondary" s]]
                               [suffix k] [["" :base] ["hover" :hover] ["active" :active] ["light" :light]
                                           ["dark" :dark]]]
                           [(keyword (if (= "" suffix) prefix (str prefix "-" suffix))) (t k)]))
                (into {} (for [[prefix t] [["primary" p] ["secondary" s]]
                               [suffix k] [["" :on] ["light" :on-light] ["dark" :on-dark]]]
                           [(keyword (str "on-" prefix (when (seq suffix) (str "-" suffix)))) (t k)]))
                (into {} (for [[n pair border ink] (map vector status-names st st-borders st-inks)
                               :let [n (name n)]
                               kv [[(keyword n) (:base pair)] [(keyword (str "on-" n)) (:on pair)]
                                   [(keyword (str n "-hover")) (:hover pair)]
                                   [(keyword (str n "-active")) (:active pair)]
                                   [(keyword (str n "-border")) border]
                                   [(keyword (str n "-ink")) ink]]]
                           kv)))]
    (update-vals tokens (fn [{:keys [light dark]}] [(c/hex light) (c/hex dark)]))))
