(ns dev.arkaitz.uikit-base.ui-js-test
  "The kit's one script, read as source: what a browser does with it no JVM sees (the
  browser checks do), but what it is allowed to contain is decided here. It writes no
  markup and no text — the server rendered both, escaped — evaluates no string, fetches
  nothing, and positions through single CSSOM properties, which `style-src 'self'` allows.
  Its size is the user's bound: 150 lines."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private source (slurp (io/resource "dev/arkaitz/uikit_base/public/ui.js")))

(defn- code
  "The script with its comments blanked and its strings kept, read by a lexer of its
  quotes and comments: a comment may name what the code never does, and a `//` inside a
  string is no comment. Answers `{:code … :open …}`, `:open` the quote a string was left
  open in, nil when every one closed. The script holds no regex literal, whose quotes and
  slashes this lexer would misread; the bans name `RegExp` for that reason."
  [^String text]
  (let [n (count text) out (StringBuilder.)]
    (loop [i 0 quote nil]
      (if (>= i n)
        {:code (str out) :open quote}
        (let [c (.charAt text i) nxt (when (< (inc i) n) (.charAt text (inc i)))]
          (cond
            quote (do (.append out c)
                      (cond (= c \\) (do (when nxt (.append out nxt)) (recur (+ i 2) quote))
                            (= c quote) (recur (inc i) nil)
                            :else (recur (inc i) quote)))
            (#{\" \' \`} c) (do (.append out c) (recur (inc i) c))
            (and (= c \/) (= nxt \/)) (recur (or (str/index-of text "\n" i) n) nil)
            (and (= c \/) (= nxt \*)) (recur (+ 2 (or (str/index-of text "*/" (+ i 2)) (- n 2))) nil)
            :else (do (.append out c) (recur (inc i) nil))))))))

(def ^:private banned
  "Each construct the script may never contain, and why."
  [[#"\binnerHTML\b" "markup from a string"] [#"\bouterHTML\b" "markup from a string"]
   [#"\binsertAdjacentHTML\b" "markup from a string"] [#"\bdocument\s*\.\s*write" "markup from a string"]
   [#"\bcreateContextualFragment\b" "markup from a string"] [#"\bDOMParser\b" "markup from a string"]
   [#"\btextContent\b" "text the server did not render"] [#"\binnerText\b" "text the server did not render"]
   [#"\bcreateTextNode\b" "text the server did not render"] [#"\bcreateElement\b" "markup the server did not render"]
   [#"\beval\b" "a string evaluated"] [#"\bFunction\s*\(" "a string evaluated"]
   [#"\bset(Timeout|Interval)\s*\(\s*[\"'`]" "a string timer, evaluated"]
   [#"setAttribute\s*\(\s*[\"'`]style" "an inline style, which style-src refuses"]
   [#"\.style\s*\.\s*(?!setProperty\b|removeProperty\b)\w+" "a style written but through setProperty or removeProperty"]
   [#"\.style\s*=" "an inline style, which style-src refuses"]
   [#"\bfetch\b" "a request of its own"] [#"\bXMLHttpRequest\b" "a request of its own"]
   [#"\bimport\s*\(" "code loaded at run time"] [#"\bRegExp\b|[=(,:]\s*/[^/*\s]" "a regex, which the lexer here cannot read"] [#"\bdataset\s*\.\s*\w+\s*=" "a data attribute written"]])

(deftest the-script-holds-none-of-the-banned-constructs
  (let [{c :code open :open} (code source)]
    (is (nil? open) "witness: every string of the script closes, so the lexer read it to its end")
    (is (str/includes? c "on(\"htmx:after:swap\", init);") "witness: the comments went and the code stayed")
    (is (not (str/includes? c "the server rendered both")) "witness: and a comment is gone")
    (is (= [] (for [[re why] banned :let [m (re-find re c)] :when m] [(if (string? m) m (first m)) why]))
        "constructs found in ui.js, each with why it is banned")))

(deftest the-script-is-within-its-bound
  ;; The user's bound is 150 lines of code: a comment or a blank line is not one. Counted by
  ;; newlines, since splitting into lines drops the empty ones at the end.
  (let [lines (str/split source #"\n" -1)
        code  (remove #(re-matches #"\s*(//.*)?" %) lines)]
    (is (= "" (last lines)) "witness: the file ends with a newline, so the count is of whole lines")
    (is (< 20 (count code)) (str "witness: the script was read, " (count code) " lines of code"))
    (is (<= (count code) 150) (str "ui.js is " (count code) " lines of code (" (dec (count lines)) " in all); the user's bound is 150, readable, never minified"))
    (is (= [] (filter #(< 120 (count %)) (str/split source #"\n"))) "lines crammed past 120 characters")))

(deftest the-fallback-placement-undoes-the-stylesheets-anchor-area
  ;; A browser with anchor-name but no anchor-scope runs the script's placement, and would
  ;; apply the stylesheet's position-area as well: the box would land twice as far out.
  (let [kit (slurp (io/resource "dev/arkaitz/uikit_base/public/kit.css"))
        place (second (re-find #"(?s)function place\(pop\) \{(.*?)\n  \}" (:code (code source))))]
    (is (re-find #"position-area\s*:" kit) "witness: the stylesheet places popovers by an anchor area")
    (is (some? place) "witness: the script's placement was found")
    (is (str/includes? (str place) "\"position-area\": \"none\"") "the placement sets position-area to none before its own top and left")))
