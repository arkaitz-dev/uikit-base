(ns dev.arkaitz.uikit-base.testing
  "For a host's tests: the kit's actions on a rendered page, as data. A test of a button
  the kit drew holds the name a screen reader hears — the label and, when the call gave
  one, the subject — never the visible label alone, which stays the same when the subject
  is lost. A `confirm` is read in two places: its opening step, a `<summary>`, and the
  form its second step posts; `actions` answers both in one map. It reads the markup the
  kit writes, and needs nothing but Clojure.

  `unaudited-pairs` reads a page for colour utilities combined on one element into a pair
  basaku never audited: a text utility on a `ui-text-bg-*` fill, a fill on a background
  utility, two fills. A text utility on a background utility is always an audited pair,
  which the kit's own tests prove in every palette."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- unescape [s]
  (-> s (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'") (str/replace "&apos;" "'") (str/replace "&amp;" "&")))

(defn accessible-name
  "The text of an HTML fragment as a screen reader reads it: tags dropped, whitespace
  collapsed, entities read."
  [html]
  (str/trim (str/replace (unescape (str/replace (str html) #"<[^>]*>" " ")) #"\s+" " ")))

(defn- visible-label
  "What the eye reads: the fragment without the kit's screen-reader text."
  [html]
  (accessible-name (str/replace (str html) #"<span class=\"ui-sr-only\">.*?</span>" "")))

(defn- attrs [tag]
  (into {} (map (fn [[_ k v]] [k (unescape v)])) (re-seq #"([a-zA-Z][\w:-]*)=\"([^\"]*)\"" tag)))

(defn- classes [as] (set (str/split (get as "class" "") #"\s+")))

(defn- form-of
  "One `<form>`'s open tag and body, as an action."
  [open body]
  (let [as     (attrs open)
        button (second (re-find #"(?s)<button[^>]*>(.*?)</button>" body))]
    {:path   (get as "action")
     :hidden (into {} (for [input (re-seq #"<input[^>]*>" body)
                            :let [ia (attrs input)]
                            :when (= "hidden" (get ia "type"))
                            :when (not= "__anti-forgery-token" (get ia "name"))]
                        [(get ia "name") (get ia "value")]))
     :label  (some-> button visible-label)
     :name   (some-> button accessible-name)}))

(defn actions
  "Every `action` and `confirm` the kit drew on `html`, in page order, each
  `{:path :hidden {name value} :label :name :first}`: where it posts, its hidden fields but
  the CSRF one, its button's visible label and the name a screen reader hears, and — for a
  confirm — its opening step's `{:label :name}`, nil for an action. A form the kit's
  `action` did not draw is not one."
  [html]
  (let [html (str html)]
    (for [[whole details-open inner form-open form-body]
          ;; Only a confirm's details is read whole: any other details is looked into, so an
          ;; action inside it is still found.
          (re-seq #"(?s)<details([^>]*\bclass=\"(?:[^\"]*\s)?ui-confirm(?:\s[^\"]*)?\"[^>]*)>(.*?)</details>|<form([^>]*)>(.*?)</form>" html)
          :let [confirm? (some? details-open)]
          :when (or confirm? (contains? (classes (attrs form-open)) "ui-action"))]
      (if confirm?
        (let [summary (second (re-find #"(?s)<summary[^>]*>(.*?)</summary>" inner))
              [_ fo fb] (or (re-find #"(?s)<form([^>]*)>(.*?)</form>" inner)
                            (throw (ex-info "uikit-base: a confirm on the page has no form: it is not the kit's" {:html whole})))]
          (assoc (form-of fo fb) :first {:label (visible-label summary) :name (accessible-name summary)}))
        (assoc (form-of form-open form-body) :first nil)))))

(def ^:private utility-colours
  "Each colour utility of the kit's file, by class, as the tokens it sets: `{\"color\"
  :link}`, `{\"background-color\" :surface}`, or both for a fill. Read from the file
  the kit ships, so the two never disagree."
  (delay
    (let [css (slurp (io/resource "dev/arkaitz/uikit_base/public/ui-utilities.css"))]
      (into {} (for [[_ c body] (re-seq #"(?m)^\.(ui-(?:text|link|bg)-[\w-]+)\{([^}]*)\}$" css)
                     :let [d (into {} (for [[_ k t] (re-seq #"(?<![\w-])(color|background-color):var\(--ui-([a-z-]+)\)" body)] [k (keyword t)]))]
                     :when (seq d)]
                 [c d])))))

(defn unaudited-pairs
  "Every element of `html` whose own class list combines the kit's colour utilities into a
  pair basaku does not audit, in page order, each `{:tag :class :pairs}`: its tag, its class
  attribute, and each `[background ink]` its utilities could paint together that is not one
  of them — every ink against every background its classes set, so a pair is reported even
  where the cascade happens to hide it. A text utility (`ui-text-*`, `ui-link-*`) on a
  background utility (`ui-bg-*`) is audited, and a fill (`ui-text-bg-*`) with its own ink;
  nothing else combined is. An element with one colour utility, or none, is never one."
  [html]
  (let [colours @utility-colours
        fills   (set (for [[_ d] colours :when (= 2 (count d))] [(d "background-color") (d "color")]))
        inks    (set (for [[_ d] colours :when (= ["color"] (keys d))] (d "color")))
        grounds (set (for [[_ d] colours :when (= ["background-color"] (keys d))] (d "background-color")))
        audited? (fn [[bg fg :as pair]] (or (fills pair) (and (grounds bg) (inks fg))))]
    (for [[_ tag attr-text] (re-seq #"<([a-zA-Z][\w-]*)(\s[^>]*)?>" (str html))
          :let [cls  (get (attrs (or attr-text "")) "class" "")
                own  (keep colours (str/split (str/trim cls) #"\s+"))
                bgs  (distinct (keep #(get % "background-color") own))
                fgs  (distinct (keep #(get % "color") own))
                bad  (vec (for [bg bgs fg fgs :when (not (audited? [bg fg]))] [bg fg]))]
          :when (seq bad)]
      {:tag tag :class cls :pairs bad})))
