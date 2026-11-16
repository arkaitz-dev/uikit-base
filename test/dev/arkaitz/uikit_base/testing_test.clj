(ns dev.arkaitz.uikit-base.testing-test
  "The kit's actions read back from markup web-base's renderer wrote, for a session that
  holds a token, so its CSRF field is there to be left out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.uikit-base :as ui]
            [dev.arkaitz.uikit-base.testing :as ut]
            [dev.arkaitz.web-base.render :as render]
            [dev.arkaitz.web-base.security :as security])
  (:import [clojure.lang ExceptionInfo]))

(defn- page-of
  "`content` as web-base's renderer writes it (HTML, not hiccup2's XHTML), for a request
  whose session holds a token."
  [content]
  (str (render/html (content {::security/csrf-stored true :anti-forgery-token "T<&" ::security/csrf-used (volatile! false)}))))

(def ^:private body
  (fn [q] (list (ui/action q "/a?x=1&y=2" {:label "Remove" :subject "Ada <x> & \"q\"" :hidden {:q "a&b \"c\""}})
                (ui/confirm q "/d" {:label "Delete" :confirm-label "Yes" :subject "W" :hidden {:l 3}})
                (ui/post-form q "/o" [:button "Plain"])
                [:details [:summary "more"] (ui/action q "/inner" {:label "Mark"})])))

(deftest the-kits-actions-read-back-as-a-reader-meets-them
  (let [html (page-of body)]
    (is (str/includes? html "name=\"__anti-forgery-token\"") "witness: the page carries a token")
    (is (= [{:path "/a?x=1&y=2" :hidden {"q" "a&b \"c\""} :label "Remove" :name "Remove Ada <x> & \"q\"" :first nil}
            {:path "/d" :hidden {"l" "3"} :label "Yes" :name "Yes W" :first {:label "Delete" :name "Delete W"}}
            {:path "/inner" :hidden {} :label "Mark" :name "Mark" :first nil}]
           (vec (ut/actions html)))
        "in page order: what each posts, its fields but the token, the label the eye reads and the name a reader hears; a confirm's first step; a plain form is none; an action inside another details is found")))

(deftest a-confirm-without-its-form-is-refused-and-a-name-is-read-as-a-reader-does
  (is (= "uikit-base: a confirm on the page has no form: it is not the kit's"
         (try (doall (ut/actions "<details class=\"ui-confirm\"><summary>x</summary></details>")) nil (catch ExceptionInfo e (ex-message e)))))
  (is (= "a&lt;b c d" (ut/accessible-name "a&amp;lt;b <b>c</b>\n d")) "entities read once, tags dropped, space collapsed"))

(def ^:private colour-classes
  "The colour utilities as the shipped file declares them, read here by the test's own
  pattern: text inks, canvases and fills, by class."
  (let [css (slurp (clojure.java.io/resource "dev/arkaitz/uikit_base/public/ui-utilities.css"))
        rules (for [[_ c body] (re-seq #"(?m)^\.(ui-[\w-]+)\{([^}]*)\}$" css)] [c body])
        sets (fn [body prop] (second (re-find (re-pattern (str "(?:^|;)" prop ":var\\(--ui-([a-z-]+)\\)")) body)))]
    {:inks  (vec (for [[c b] rules :when (re-matches #"ui-(text|link)-.*" c) :when (not (str/starts-with? c "ui-text-bg-")) :let [t (sets b "color")] :when t] [c (keyword t)]))
     :bgs   (vec (for [[c b] rules :when (str/starts-with? c "ui-bg-") :let [t (sets b "background-color")] :when t] [c (keyword t)]))
     :fills (vec (for [[c b] rules :when (str/starts-with? c "ui-text-bg-")] [c [(keyword (sets b "background-color")) (keyword (sets b "color"))]]))}))

(deftest unaudited-pairs-names-every-colour-combination-basaku-never-audited
  (let [{:keys [inks bgs fills]} colour-classes
        p (fn [cls] (str "<div><p class=\"" cls "\">x</p></div>"))]
    (is (= [13 5 7] (mapv count [inks bgs fills])) "witness: thirteen text utilities, eight canvases, seven fills")
    (is (= [] (for [[i] inks [b] bgs :let [r (ut/unaudited-pairs (p (str i " " b)))] :when (seq r)] [i b r]))
        "a text utility on a background utility is an audited pair, every one of the 104")
    (is (= [] (for [[f] fills :let [r (ut/unaudited-pairs (p (str "ui-p-2 " f)))] :when (seq r)] [f r]))
        "a fill with its own ink is one, beside a utility that paints nothing")
    (is (= [] (ut/unaudited-pairs (p "ui-text-muted ui-text-danger"))) "two inks with no background are no pair")
    (is (= (vec (for [[i ink] inks [f [fill own]] fills] {:tag "p" :class (str i " " f) :pairs [[fill ink]]}))
           (vec (mapcat #(ut/unaudited-pairs (p (str (first %) " " (second %)))) (for [[i] inks [f] fills] [i f]))))
        "a text utility on a fill is named, the fill's background against the utility's ink, all 91")
    (is (= [{:tag "p" :class "ui-text-bg-primary ui-bg-surface" :pairs [[:surface :on-primary]]}]
           (ut/unaudited-pairs (p "ui-text-bg-primary ui-bg-surface")))
        "a fill on a background utility: its ink on the other background")
    (is (= [{:tag "span" :class "ui-text-bg-primary ui-text-bg-dark" :pairs [[:primary :on-surface-inverse] [:surface-inverse :on-primary]]}]
           (ut/unaudited-pairs "<span class=\"ui-text-bg-primary ui-text-bg-dark\"></span>"))
        "two fills: each ink on the other's ground, whichever the cascade lets win")
    (is (= [{:tag "a" :class "x ui-link-primary  ui-text-bg-info" :pairs [[:info :link]]}]
           (ut/unaudited-pairs (str "<p class=\"ui-text-muted\">ok</p><a href=\"/\" class=\"x ui-link-primary  ui-text-bg-info\">" "</a><div class=\"ui-bg-surface\"></div>")))
        "only the element that combines them, read among others, its class kept as written")))

(def ^:private alerts-page
  (fn [q] (list (ui/notice :error "Refused <x> &" [:b " now"])
                [:div {:role "alert"} "Not the kit's"]
                [:p {:class "ui-notice-like" :role "status"} "Nor this"]
                (ui/alert q :ok {:heading "Saved & sent" :dismiss-href "/orders?day=2&tab=a"} "The order " [:b "A1"] [:div [:div "nested"] "after"])
                (ui/alert q :warning "Heads up")
                (ui/alert q :info {:dismiss-href "/" :dismiss-label "Hide"} "Plain")
                (ui/notice :ok {:role "none"} "Done."))))

(deftest alerts-reads-every-kit-notice-and-alert-as-a-reader-meets-it
  (let [html (page-of alerts-page)]
    (is (str/includes? html "<div role=\"alert\">Not the kit&apos;s</div>") "witness: a role=alert the kit did not draw is on the page")
    (is (= [{:kind :error :role "alert" :heading nil :text "Refused <x> & now" :dismiss nil}
            {:kind :ok :role "status" :heading "Saved & sent" :text "The order A1 nested after" :dismiss {:href "/orders?day=2&tab=a" :name "Close"}}
            {:kind :warning :role "status" :heading nil :text "Heads up" :dismiss nil}
            {:kind :info :role "status" :heading nil :text "Plain" :dismiss {:href "/" :name "Hide"}}
            {:kind :ok :role "status" :heading nil :text "Done." :dismiss nil}]
           (ut/alerts html))
        "in page order, notices and alerts alike: kind, role, heading, the body's text without heading or way out, and the way out's path and spoken name; a role=alert or a lookalike class is none")))

(deftest a-ui-notice-the-kit-did-not-draw-is-refused
  (doseq [[html why] [["<div class=\"ui-notice ui-notice--ok\">x</div>" "a div without ui-alert"]
                      ["<p class=\"ui-notice ui-notice--ok ui-alert\">x</p>" "an alert drawn as a p"]
                      ["<p class=\"ui-notice ui-notice--success\">x</p>" "a kind the kit has not"]
                      ["<div class=\"ui-notice ui-notice--ok ui-alert\"><p>x</p></div>" "an alert without its body"]]]
    (is (= "uikit-base: a ui-notice on the page is neither the kit's notice nor its alert"
           (try (doall (ut/alerts html)) nil (catch ExceptionInfo e (ex-message e))))
        why))
  (is (= "uikit-base: an alert on the page is never closed: it is not the kit's"
         (try (doall (ut/alerts "<p class=\"ui-notice ui-notice--ok\">x")) nil (catch ExceptionInfo e (ex-message e))))))

(deftest alerts-reads-a-heading-only-when-the-kit-drew-one-and-a-way-out-only-when-it-is-the-alerts
  ;; Both found by a Phase-3 panel: a body that opens with a paragraph of its own is no
  ;; heading, and a link after an alert with no way out is the page's, not the alert's.
  (is (= [{:kind :ok :role "status" :heading nil :text "First para rest" :dismiss nil}]
         (ut/alerts (page-of (fn [q] (ui/alert q :ok [:p "First para"] "rest")))))
      "a body's own first paragraph is its text")
  (is (= [{:kind :ok :role "status" :heading nil :text "Saved" :dismiss nil}]
         (ut/alerts (page-of (fn [q] (list (ui/alert q :ok "Saved") [:a {:href "/x"} "y"])))))
      "a link after the alert, the page's own, is no way out"))
