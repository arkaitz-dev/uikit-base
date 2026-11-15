(ns dev.arkaitz.uikit-base.ui-js-test
  "The kit's one script, read as source: what a browser does with it no JVM sees (the
  browser checks do), but what it is allowed to contain is decided here. It writes no
  markup and no text — the server rendered both, escaped — evaluates no string, fetches
  nothing, and positions through single CSSOM properties, which `style-src 'self'` allows.
  Its size is the user's bound: 150 lines of code. Every class and data attribute it names
  is one the kit's stylesheet or its markup carries."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.uikit-base.gallery :as gallery]
            [ring.mock.request :as mock]))

(def ^:private source (slurp (io/resource "dev/arkaitz/uikit_base/public/ui.js")))

(defn- code
  "The script with its comments blanked and its strings kept, read by a lexer of its
  quotes and comments: a comment may name what the code never does, and a `//` inside a
  string is no comment. Answers `{:code … :open … :refused …}`: `:open` the quote a string
  was left open in, `:refused` `[what index]` where the lexer met what it cannot read and
  stopped, so a desync is a red, never code blanked as a string or a comment. The rule for a
  slash is strict and has no context to get wrong: `//` and `/*` open comments, a division
  is written ` / `, a space on each side, and any other `/` — a regex literal, or a division
  written tight — is refused. A backtick, a template literal, is refused too."
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
            (= c \`) {:code (str out) :open nil :refused [:template-literal i]}
            (#{\" \'} c) (do (.append out c) (recur (inc i) c))
            (and (= c \/) (= nxt \/)) (recur (or (str/index-of text "\n" i) n) nil)
            (and (= c \/) (= nxt \*)) (recur (+ 2 (or (str/index-of text "*/" (+ i 2)) (- n 2))) nil)
            (and (= c \/) (not (and (pos? i) (= \space (.charAt text (dec i))) (= \space nxt)
                                    (not (#{\" \' \/} (when (< (+ i 2) n) (.charAt text (+ i 2))))))))
            {:code (str out) :open nil :refused [:slash i]}
            :else (do (.append out c) (recur (inc i) nil))))))))

(deftest the-lexer-refuses-what-it-cannot-read
  ;; Each measured against an earlier lexer, which blanked the innerHTML after it.
  (is (= [:slash 10] (:refused (code "if (ok && /\\/\\//.test(s)) el.innerHTML = s;"))) "a regex after &&")
  (is (= [:slash 8] (:refused (code "if (ok) /[//]/.test(s) && (el.innerHTML = s);"))) "a regex after a closing parenthesis")
  (is (= [:slash 4] (:refused (code "x = /[/*]/.test(s); el.innerHTML = s; // */"))) "a regex holding a comment's opening")
  (doseq [[src at] [["return /a/.test(x);" 7] ["x = !/a/.test(y);" 5] ["f(a ? /b/ : c);" 6] ["[/a/];" 1] ["a; /b/.exec(c);" 3]
                    ["x = y || /z/;" 9] ["a/2" 1] ["a /2" 2] ["a/ 2" 1] ["/a/" 0]
                    ["x = / ' / .test(s); el.innerHTML = s; y = ' ';" 4] ["x = / \" /;" 4]]]
    (is (= [:slash at] (:refused (code src))) src))
  (is (= [:template-literal 4] (:refused (code "x = `${a}` + innerHTML;"))) "a template literal")
  (doseq [src ["a / 2" "(a + b) / c" "x[1] / y" "w / h // half" "a /* b */ / c"]]
    (is (nil? (:refused (code src))) (str "a division written / is read as one: " src)))
  (doseq [d ["(a.width - p.width) / 2" "Math.abs(view.scrollLeft) / view.clientWidth"]]
    (is (str/includes? source d) (str "witness: ui.js divides, written as the rule asks: " d))))

(def ^:private banned
  "Each construct the script may never contain, and why. The scan reads the raw source,
  comments included, so no misreading of the lexer can hide a word — and a comment may not
  name a banned construct either. It is lexical: it reads the script's words, so it holds the code honest and does not claim to catch an obfuscation —
  a name built by concatenation, an access through a computed key — which review must."
  [[#"\binnerHTML\b" "markup from a string"] [#"\bouterHTML\b" "markup from a string"]
   [#"\binsertAdjacentHTML\b" "markup from a string"] [#"\bdocument\s*\.\s*write" "markup from a string"]
   [#"\bsetHTMLUnsafe\b" "markup from a string"]
   [#"\bcreateContextualFragment\b" "markup from a string"] [#"\bDOMParser\b" "markup from a string"]
   [#"\btextContent\b" "text the server did not render"] [#"\binnerText\b" "text the server did not render"]
   [#"\bcreateTextNode\b" "text the server did not render"] [#"\bcreateElement(NS)?\b" "markup the server did not render"]
   [#"\.\s*(append|prepend|before|after|replaceWith|appendChild|insertBefore|replaceChildren)\s*\(" "nodes placed the server did not render"]
   [#"\binsertAdjacent(Element|Text)\b" "nodes or text placed the server did not render"]
   [#"\.\s*nodeValue\b" "text the server did not render"] [#"\bnew\s+(Option|Text)\b" "a node the server did not render"]
   [#"toggleAttribute\s*\(\s*[\"']style" "an inline style, which style-src refuses"]
   [#"\beval\b" "a string evaluated"] [#"\bFunction\s*\(" "a string evaluated"]
   [#"\bset(Timeout|Interval)\s*\((?!\s*function\s*\()" "a timer whose first argument is no function literal"]
   [#"\bReflect\s*\." "a call through reflection"] [#"\.\s*constructor\b" "a constructor reached, the road to Function"]
   [#"setAttribute(NS)?\s*\(\s*[\"']style" "an inline style, which style-src refuses"]
   [#"\bsetAttributeNS\b" "an attribute written by namespace"]
   [#"\.style\s*\.\s*(?!setProperty\b|removeProperty\b)\w+" "a style written but through setProperty or removeProperty"]
   [#"\.style\s*\[" "a style written but through setProperty"] [#"\.style\s*=" "an inline style, which style-src refuses"]
   [#"\bfetch\b" "a request of its own"] [#"\bXMLHttpRequest\b" "a request of its own"]
   [#"\bimport\s*\(" "code loaded at run time"] [#"\bRegExp\b" "a regex, which the lexer here cannot read"]
   [#"\bdataset\s*\.\s*\w+\s*=" "a data attribute written"] [#"\bdataset\s*\[" "a data attribute by a computed name"]
   [#"setAttribute\s*\(\s*[\"']data-" "a data attribute written"]])

(defn- found
  "Each banned construct in `text`, with why, read over the text as it is."
  [text]
  (for [[re why] banned :let [m (re-find re text)] :when m] [(if (string? m) m (first m)) why]))

(deftest a-construct-the-lexer-misreads-is-still-found
  ;; Measured: the lexer reads `/ a'/` as a division, the quote opens a string, and the
  ;; string's `//` then hides the rest of the line as a comment. The bans read the raw text.
  (let [text "x = / a'/.test(s); y = 'http://x'; el.innerHTML = s;"]
    (is (not (str/includes? (:code (code text)) "innerHTML")) "witness: the lexer hides it")
    (is (= [["innerHTML" "markup from a string"]] (found text)) "the bans find it all the same")))

(deftest the-script-holds-none-of-the-banned-constructs
  (let [{c :code open :open refused :refused} (code source)]
    (is (nil? refused) (str "the lexer met what it cannot read in ui.js, at " refused ": rewrite it so the scan sees every word"))
    (is (nil? open) "witness: every string of the script closes, so the lexer read it to its end")
    (is (str/includes? c "on(\"htmx:after:swap\", init);") "witness: the comments went and the code stayed")
    (is (str/includes? source "so a swap of <main> re-binds nothing") "witness: the phrase below is in a comment of ui.js")
    (is (not (str/includes? c "re-binds nothing")) "witness: and the comment is gone from the code scanned")
    (is (= [] (found source))
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
        place (second (re-find #"(?s)function place\(pop\) \{(.*?)\n  \}" source))]
    (is (re-find #"position-area\s*:" kit) "witness: the stylesheet places popovers by an anchor area")
    (is (some? place) "witness: the script's placement was found")
    (is (str/includes? (str place) "\"position-area\": \"none\"") "the placement sets position-area to none before its own top and left")
    (is (re-find #"position-try-fallbacks\s*:" kit) "witness: the stylesheet flips popovers by a try fallback")
    (is (str/includes? (str place) "\"position-try-fallbacks\": \"none\"") "the placement sets position-try-fallbacks to none, or the stylesheet's flip would move its box")))

(defn- kebab [camel] (str/replace camel #"[A-Z]" #(str "-" (str/lower-case %))))

(deftest every-class-and-data-attribute-the-script-names-is-the-kits
  ;; A name the script reads that nothing renders is a feature that silently does nothing.
  ;; Whole names only: a class is `.name` in a rule of kit.css, its comments gone, or one
  ;; token of a class attribute of the gallery's page; a data name is `[name` there, or an
  ;; attribute of the page.
  (let [c       source
        strings (map second (re-seq #"\"([^\"]*)\"" c))
        classes (set (for [st strings [_ pre nm] (re-seq #"(data-)?(ui-[\w-]+)" st) :when (nil? pre)] nm))
        data    (into (set (for [st strings nm (re-seq #"data-[\w-]+" st)] nm))
                      (for [[_ k] (re-seq #"\bdataset\.(\w+)" c)] (str "data-" (kebab k))))
        css     (str/replace (slurp (io/resource "dev/arkaitz/uikit_base/public/kit.css")) #"(?s)/\*.*?\*/" "")
        page    (:body ((gallery/handler) (mock/request :get "/")))
        drawn   (set (for [[_ v] (re-seq #"class=\"([^\"]*)\"" page) t (str/split v #"\s+")] t))
        q       #(java.util.regex.Pattern/quote %)
        class?  (fn [nm] (or (drawn nm) (re-find (re-pattern (str "\\." (q nm) "(?![\\w-])")) css)))
        datum?  (fn [nm] (or (re-find (re-pattern (str "\\[" (q nm) "(?![\\w-])")) css) (re-find (re-pattern (str "\\s" (q nm) "=")) page)))]
    (is (every? classes ["ui-carousel__viewport" "ui-tooltip-wrap" "ui-toast__close" "ui-scrollspy" "ui-dropdown__menu"])
        (str "witness: the script names the classes it drives, read from its strings: " (vec (sort classes))))
    (is (= #{"data-timeout-ms" "data-interval-ms" "data-ui-rotating"} data) "witness: the data attributes the script names")
    (is (= [] (sort (remove class? classes))) "classes ui.js names that neither kit.css nor the gallery's page carries whole")
    (is (= [] (sort (remove datum? data))) "data attributes ui.js names that neither kit.css nor the gallery's page carries whole")))
