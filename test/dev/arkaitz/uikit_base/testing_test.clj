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
