(ns dev.arkaitz.uikit-base.components-test
  "The four components as markup and, for the form, as web-base receives it. Every
  literal is hiccup2's rendering, measured once and typed (it writes XHTML — `<input />`,
  `selected=\"selected\"` — where web-base's stack writes HTML; the end-to-end test reads
  the stack's). A caller's attributes are kept, its `:class` joined to the kit's, and what
  a component's correctness needs survives any attribute: a form's method, action and
  token, a refused field's ARIA, a notice's role."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base :as ui]
            [dev.arkaitz.uikit-base.web :as web]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]
            [dev.arkaitz.web-base.session :as session]
            [dev.arkaitz.web-base.shell :as shell]
            [dev.arkaitz.web-base.testing :as testing]
            [hiccup2.core :as h]
            [ring.mock.request :as mock])
  (:import [clojure.lang ExceptionInfo]))

(defn- html [x] (str (h/html x)))

(def ^:private req
  "A request as web-base renders a signed-in page: its stored token, and a translator."
  {::security/csrf-stored true :anti-forgery-token "T<&\"" ::security/csrf-used (volatile! false)
   :wb/tr (fn [k args] (str "tr:" k ":" (pr-str args)))})

(defn- refusal [f] (try (f) nil (catch ExceptionInfo e [(ex-message e) (ex-data e)])))

;; --- button ---------------------------------------------------------------------------

(deftest a-button-carries-the-kits-classes-and-submits-unless-told-otherwise
  (is (= "<button class=\"ui-btn\" type=\"submit\">Go</button>" (html (ui/button "Go"))))
  (doseq [[v lit] [[:primary "ui-btn ui-btn--primary"] [:secondary "ui-btn ui-btn--secondary"]
                   [:success "ui-btn ui-btn--success"] [:danger "ui-btn ui-btn--danger"]
                   [:warning "ui-btn ui-btn--warning"] [:info "ui-btn ui-btn--info"]]]
    (is (= (str "<button class=\"" lit "\" type=\"submit\">Go</button>") (html (ui/button {:variant v} "Go"))) (name v)))
  (is (= "<button class=\"ui-btn ui-btn--danger ui-btn--sm\" type=\"submit\">Go<b>x</b></button>"
         (html (ui/button {:variant :danger :size :sm} "Go" [:b "x"]))))
  (is (= "<button class=\"ui-btn ui-btn--lg mine\" id=\"b\" type=\"button\">Go</button>"
         (html (ui/button {:size :lg :type "button" :class "mine" :id "b"} "Go")))
      "the caller's class after the kit's, its type and id kept")
  (is (= "<button class=\"ui-btn a b\" type=\"submit\">Go</button>" (html (ui/button {:class ["a" :b]} "Go"))))
  (is (= "<button class=\"ui-btn\" type=\"submit\">&lt;&amp;&gt;</button>" (html (ui/button "<&>")))))

(deftest a-button-of-no-variant-or-size-of-the-kit-is-refused
  (is (= ["uikit-base: a button's :variant is one of the kit's" {:variant :huge}] (refusal #(ui/button {:variant :huge} "x"))))
  (is (= ["uikit-base: a button's :variant is one of the kit's" {:variant "primary"}] (refusal #(ui/button {:variant "primary"} "x"))))
  (is (= ["uikit-base: a button's :size is :sm or :lg" {:size :md}] (refusal #(ui/button {:size :md} "x")))))

;; --- post-form ------------------------------------------------------------------------

(deftest a-post-form-posts-to-its-action-with-the-token-first-whatever-the-caller-gives
  (is (some? (security/csrf-field req)) "witness: this request carries a token web-base would render")
  (is (= (str "<form action=\"/x\" method=\"post\"><input name=\"__anti-forgery-token\" type=\"hidden\" value=\"T&lt;&amp;&quot;\" />"
              "<button class=\"ui-btn\" type=\"submit\">Go</button></form>")
         (html (ui/post-form req "/x" (ui/button "Go")))))
  (is (= (str "<form action=\"/x\" class=\"ui-form\" enctype=\"multipart/form-data\" hx-post=\"/x\" method=\"post\">"
              "<input name=\"__anti-forgery-token\" type=\"hidden\" value=\"T&lt;&amp;&quot;\" /><p>a</p><p>b</p></form>")
         (html (ui/post-form req "/x" {:method "get" :action "/evil" :enctype "multipart/form-data" :hx-post "/x" :class "ui-form"}
                             [:p "a"] [:p "b"])))
      "method and action are the form's: a GET would put the token in the address bar")
  (is (= "<form action=\"/x\" method=\"post\"><input name=\"__anti-forgery-token\" type=\"hidden\" value=\"T&lt;&amp;&quot;\" /></form>"
         (html (ui/post-form req "/x" {"method" "get" :ACTION "/evil"})))
      "however the caller spells them: a second method attribute would be the one a browser reads first")
  (is (= "<form action=\"/x\" method=\"post\"><button class=\"ui-btn\" type=\"submit\">Go</button></form>"
         (html (ui/post-form {} "/x" (ui/button "Go"))))
      "no stored token, no field: web-base's rule, an anonymous form is checked by its origin"))

(def ^:private KEY "AAAAAAAAAAAAAAAAAAAAAA==")

(def ^:private app
  (wb/handler {:routes  [["/x" {:wb/layouts [shell/page]
                                :get  (fn [q] {:status 200 :body (ui/post-form q "/x" {:id "f"} [:input {:name "title" :value "hi"}] (ui/button "Go"))})
                                :post (fn [q] {:status 200 :body [:p "posted " (get-in q [:form-params "title"])]})}]
                         ["/login" {:post (fn [_] (session/rotate {:status 303 :headers {"Location" "/"} :body ""} {:user "ann"}))}]]
               :session {:key KEY}}))

(defn- form-fields
  "The name and value of every input of the served form, read from its HTML."
  [body]
  (into {} (for [[_ attrs] (re-seq #"<input ([^>]*)>" (re-find #"<form[^>]*id=\"f\".*?</form>" body))]
             [(second (re-find #"name=\"([^\"]*)\"" attrs)) (second (re-find #"value=\"([^\"]*)\"" attrs))])))

(defn- post [cookies fields & [site]]
  (app (cond-> (-> (mock/request :post "/x" fields) (testing/with-cookies cookies))
         site (mock/header "Sec-Fetch-Site" site))))

(deftest web-base-accepts-the-form-it-served-and-refuses-it-without-its-token
  (let [login (app (mock/header (mock/request :post "/login") "Sec-Fetch-Site" "same-origin"))
        page  (app (testing/with-cookies (mock/request :get "/x") login))
        f     (form-fields (:body page))
        token (f "__anti-forgery-token")]
    (is (= 303 (:status login)) "witness: signed in")
    (is (= 200 (:status page)) "witness: the form's page")
    (is (re-matches #"[A-Za-z0-9+/]{80}" (str token)) (str "witness: the served form carries the session's token: " f))
    (let [r (post login f)]
      (is (and (= 200 (:status r)) (str/includes? (:body r) "<main class=\"wb-main\" tabindex=\"-1\"><p>posted hi</p></main>"))
          "the form's own fields are accepted, its title read"))
    (is (= 403 (:status (post login (dissoc f "__anti-forgery-token")))) "without its token, refused")
    (is (= 403 (:status (post login (assoc f "__anti-forgery-token" "nope")))) "with another, refused")
    (is (= 403 (:status (post login (dissoc f "__anti-forgery-token") "same-origin"))) "a session's form is not let through by its origin")
    (is (= 200 (:status (post login f "cross-site"))) "the token decides, not the origin")))

;; --- field ----------------------------------------------------------------------------

(deftest a-field-is-a-label-tied-to-its-control-and-escapes-what-it-shows
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"email\">Email</label>"
              "<input class=\"ui-input\" id=\"email\" name=\"email\" type=\"text\" value=\"a&lt;b&gt;&quot;&amp;\" /></div>")
         (html (ui/field req {:id "email" :label "Email" :value "a<b>\"&"}))))
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"b\">L</label>"
              "<input class=\"ui-input\" id=\"b\" name=\"c\" type=\"text\" /></div>")
         (html (ui/field req {:id "a" :label "L" :attrs {:id "b" :name "c"}})))
      "the label follows the control's id when the caller renames it"))

(deftest a-refused-field-is-marked-and-described-and-no-attribute-undoes-it
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"email\">Email</label>"
              "<input aria-describedby=\"email-error other\" aria-invalid=\"true\" autocomplete=\"email\" class=\"ui-input wide\" "
              "id=\"email\" name=\"email\" required=\"required\" type=\"email\" value=\"x\" />"
              "<span class=\"ui-field__error\" id=\"email-error\">tr::err/bad:[]</span></div>")
         (html (ui/field req {:id "email" :label "Email" :type "email" :value "x" :error :err/bad
                              :attrs {:class "wide" :required true :aria-invalid "false" :aria-describedby "other" :autocomplete "email"}}))))
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"e\">E</label>"
              "<input aria-describedby=\"e-error other two\" aria-invalid=\"true\" class=\"ui-input\" id=\"e\" name=\"e\" type=\"text\" />"
              "<span class=\"ui-field__error\" id=\"e-error\">tr::e:[]</span></div>")
         (html (ui/field req {:id "e" :label "E" :error :e :attrs {"ARIA-DESCRIBEDBY" "  other e-error  two "}})))
      "the caller's description follows the error's, joined once, whatever its spelling")
  (is (str/includes? (html (ui/field req {:id "n" :label "N" :error [:err/long 3 "z"]})) "tr::err/long:[3 &quot;z&quot;]")
      "a word with its arguments")
  (is (str/includes? (html (ui/field req {:id "a" :label "L" :error :e :attrs {:id "b"}})) "aria-describedby=\"b-error\"")
      "described by the error of the control it is")
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"h\">H</label>"
              "<input aria-describedby=\"hint\" aria-invalid=\"false\" class=\"ui-input\" id=\"h\" name=\"h\" type=\"text\" /></div>")
         (html (ui/field req {:id "h" :label "H" :attrs {:aria-invalid "false" :aria-describedby " hint "}})))
      "with no error the caller's ARIA stands")
  (is (= [:input {:type "text" :value nil :id "b" :name "b" :class "ui-input"}]
         (nth (ui/field req {:id "b" :label "B" :attrs {:aria-describedby "  "}}) 2))
      "a blank description is no attribute")
  (is (= ["uikit-base: a field's error is translated by web-base; the request carries no :wb/tr" {}]
         (refusal #(ui/field {} {:id "a" :label "L" :error :e})))
      "an error with no translator is refused by name"))

(deftest select-textarea-and-file-are-drawn-as-their-own-controls
  (let [opts [[1 "One"] [2 "Two"] ["3" "Three"]]]
    (is (str/includes? (html (ui/field req {:id "s" :label "S" :type "select" :value "2" :options opts}))
                       "<select class=\"ui-input\" id=\"s\" name=\"s\"><option value=\"1\">One</option><option selected=\"selected\" value=\"2\">Two</option><option value=\"3\">Three</option></select>")
        "a refused form's string selects a numeric option")
    (is (str/includes? (html (ui/field req {:id "s" :label "S" :type "select" :value 3 :options opts}))
                       "<option selected=\"selected\" value=\"3\">Three</option>")
        "and a number a string one"))
  (is (str/includes? (html (ui/field req {:id "t" :label "T" :type "textarea" :value "a<b" :attrs {:rows 4}}))
                     "<textarea class=\"ui-input\" id=\"t\" name=\"t\" rows=\"4\">a&lt;b</textarea>"))
  (is (str/includes? (html (ui/field req {:id "f" :label "F" :type "file" :value "ignored" :attrs {:accept "image/*"}}))
                     "<input accept=\"image/*\" class=\"ui-input\" id=\"f\" name=\"f\" type=\"file\" />")
      "a file input takes no value"))

;; --- notice ---------------------------------------------------------------------------

(deftest a-notice-has-its-kinds-class-and-role-and-keeps-its-role
  (doseq [k [:ok :info :warning]]
    (is (= (str "<p class=\"ui-notice ui-notice--" (name k) "\" role=\"status\">Saved<b>!</b></p>") (html (ui/notice k "Saved" [:b "!"])))
        (name k)))
  (is (= "<p class=\"ui-notice ui-notice--error\" role=\"alert\">Saved<b>!</b></p>" (html (ui/notice :error "Saved" [:b "!"])))
      "a refusal is an alert")
  (is (= "<p class=\"ui-notice ui-notice--error x\" id=\"flash\" role=\"alert\">No</p>"
         (html (ui/notice :error {:role "none" :id "flash" :class "x"} "No")))
      "the caller's id and class kept, its role not")
  (is (= ["uikit-base: a notice is :ok, :info, :warning or :error" {:kind :bad}] (refusal #(ui/notice :bad "x")))))

;; --- what no attribute may do (Phase 3) -------------------------------------------------

(deftest a-form-refuses-the-htmx-attributes-that-would-send-it-another-way
  (doseq [a [:hx-get :hx-put :hx-patch :hx-delete :hx-method :hx-action :hx-config :data-hx-get "HX-METHOD" "data-hx-action"
             :hx-get:inherited :hx-method:inherited "hx-action:append" :data-hx-get:inherited :hx-config:inherited:append]]
    (testing (pr-str a)
      (let [[msg data] (refusal #(ui/post-form req "/x" {a "get"}))
            n          (keyword (.toLowerCase (name a) java.util.Locale/ROOT))]
        (is (= (str "uikit-base: a form takes no " (name n) ": htmx would send it, token and all, by another method or to another action") msg))
        (is (= {:attributes [n]} data)))))
  (is (vector? (ui/post-form req "/x" {:hx-post "/x" :hx-target "#f" :hx-swap "outerHTML"})) "control: htmx that keeps the form's request is kept"))

(deftest a-button-refuses-a-target-of-its-own
  (doseq [a [:formaction :formmethod :form "FormAction"]]
    (is (= (str "uikit-base: a button takes no " (.toLowerCase (name a) java.util.Locale/ROOT)
                ": a submitter's own target would send its form, token and all, where the form does not")
           (first (refusal #(ui/button {a "x"} "Go"))))
        (pr-str a)))
  (is (= ["uikit-base: an attribute is named by a keyword or a string" {:attribute 1}] (refusal #(ui/button {1 "x"} "Go")))
      "an attribute named by neither is refused by name"))

(deftest a-renamed-refused-field-is-described-by-its-own-error
  (doseq [attrs [{:id "b"} {"id" "b"} {:ID "b"}]]
    (let [h (html (ui/field req {:id "a" :label "L" :error :e :attrs attrs}))]
      (is (and (str/includes? h "for=\"b\"") (str/includes? h "aria-describedby=\"b-error\"") (str/includes? h "<span class=\"ui-field__error\" id=\"b-error\">"))
          (str (pr-str attrs) ": " h)))))

(deftest every-control-a-refused-field-draws-is-marked
  (doseq [type ["select" "textarea" "file" "checkbox"]]
    (let [h (html (ui/field req {:id "c" :label "C" :type type :error :e :options [[1 "One"]] :attrs {:aria-invalid "false"}}))
          x (html (ui/field req {:id "c" :label "C" :type type :error :e :options [[1 "One"]] :attrs {:aria-describedby "x"}}))]
      (is (and (str/includes? h "aria-invalid=\"true\"") (str/includes? h "aria-describedby=\"c-error\"")) (str type ": " h))
      (is (= 1 (count (re-seq #"aria-describedby=\"c-error x\"" x))) (str type " joins the caller's: " x))))
  (is (str/includes? (html (ui/field req {:id "f" :label "F" :type "file" :attrs {:type "text"}})) "type=\"file\"")
      "a file field stays a file input whatever type the caller gives"))

(deftest every-class-the-components-emit-is-the-kits
  (let [kit     (slurp (clojure.java.io/resource "dev/arkaitz/uikit_base/public/kit.css"))
        markup  (html (list (ui/button "x") (for [v [:primary :secondary :success :danger :warning :info]] (ui/button {:variant v} "x"))
                            (ui/button {:size :sm} "x") (ui/button {:size :lg} "x")
                            (ui/field req {:id "a" :label "A" :error :e})
                            (ui/field req {:id "h" :label "H" :hint "h" :required? true})
                            (ui/field req {:id "g" :label "G" :type "radio" :options [["a" "A"]]})
                            (ui/action req "/a" {:label "A" :subject "s"})
                            (ui/confirm req "/a" {:label "A" :confirm-label "Yes"})
                            (ui/empty "x") (ui/pairs [["a" "b"]])
                            (ui/crumbs req {:items [["/a" "A"] [nil "B"]]})
                            (ui/card {:title "t" :footer "f"} "b")
                            (ui/secret req {:id "s" :label "S" :value "v"})
                            (ui/thread (ui/message {:meta "m"} "x"))
                            (ui/tabs-nav req {:label "S" :items [["/a" "A"]] :current "/a"})
                            (ui/page-head {:title "t" :subtitle "s" :actions [(ui/link-button "/a" "x")]})
                            (for [k [:ok :info :warning :error]] (ui/notice k "x"))
                            (ui/table {:caption "c" :head ["h"] :rows [["a" "b"]] :row-header? true :scroll-label "s"})
                            (ui/pager req {:previous "/a" :next "/b" :where "w"})
                            (ui/rows (ui/row "x"))
                            (ui/rows {:plain? true} (ui/row "x"))
                            (ui/field req {:id "c" :label "C" :type "checkbox" :inline? true})
                            (for [k [:neutral :success :danger :warning :info]] (ui/badge k "x"))
                            (ui/card "x")
                            (for [v [:outline-primary :outline-secondary :outline-success :outline-danger :outline-warning :outline-info :link]] (ui/button {:variant v} "x"))
                            (ui/button {:block? true} "x")
                            (ui/button-group {:label "g"} (ui/button "x"))
                            (ui/close-button {})
                            (ui/field req {:id "z" :label "Z" :size :sm}) (ui/field req {:id "z" :label "Z" :size :lg})
                            (ui/field req {:id "w" :label "W" :type "switch"}) (ui/field req {:id "r" :label "R" :type "range"})
                            (ui/field req {:id "f" :label "F" :floating? true :before nil :attrs {:placeholder "p"}})
                            (ui/field req {:id "p" :label "P" :before "€"})
                            (ui/alert req :ok {:heading "h" :dismiss-href "/a"} "x")
                            (ui/badge :info {:corner? true} "1")
                            (ui/progress {:label "p" :animated? true}) (ui/meter {:label "m" :value 1})
                            (ui/spinner req) (ui/placeholder req)
                            (ui/list-group {:items [["/a" "A"] [nil "B"]] :flush? true})
                            (ui/table {:rows [["a"]] :striped? true :hover? true :bordered? true :compact? true})
                            (ui/pager req {:pages [["/a" 1]] :size :sm}) (ui/pager req {:next "/a" :size :lg})
                            (ui/tabs-nav req {:label "S" :items [["/a" "A"]] :style :pills :fill? true})
                            (ui/tabs-nav req {:label "S" :items [["/a" "A"]] :justify? true})
                            (ui/card-group (ui/card {:image {:src "/i" :alt "i"} :nav "n"} "x"))
                            (ui/figure {:image {:src "/i" :alt "i"} :caption "c"})
                            (ui/collapse {:summary "s"} "b") (ui/accordion {:items [["a" "b"]]})
                            (ui/scroller {:label "s"} "a")))
        classes (set (mapcat #(str/split % #" ") (map second (re-seq #"class=\"([^\"]*)\"" markup))))]
    (is (= 115 (count classes)) (str "witness: the classes emitted — 0.5.0's 56, then K9's six outlines, link, block, button-group and close, K10's two sizes, switch, range, floating field, input group and its text, K11's alert and its three parts, corner badge, progress and its two, meter, spinner, placeholders and placeholder, K12's list group and its three, four table variants, numbered pages and two pager sizes, pills, fill and justify, K13's card image, card nav, card group, figure and its two, collapse and its two, accordion and its three, scroller and its two — a count measured once: " (sort classes)))
    (is (= [] (remove #(re-find (re-pattern (str "(?m)(^\\s*|[,{}]\\s*)\\." % "\\s*[,{>]")) kit) classes))
        "each is the whole selector of a rule of kit.css: a class the kit does not style is a component nobody styled")))

;; --- table, pager, badge, card --------------------------------------------------------

(def ^:private link-attrs "hx-push-url=\"true\" hx-select=\"main\" hx-swap=\"outerHTML show:top showTarget:body\" hx-target=\"main\"")
(defn- L [href rel body] (str "<a href=\"" href "\" hx-get=\"" href "\" " link-attrs (when rel (str " rel=\"" rel "\"")) ">" body "</a>"))

(deftest a-link-button-is-a-swapping-link-that-looks-like-a-button
  (is (= (str/replace (L "/a" nil "Go") "<a href" "<a class=\"ui-btn\" href") (html (ui/link-button "/a" "Go"))))
  (is (= (str/replace (L "/a" "next" "Go<b>x</b>") "<a href" "<a class=\"ui-btn ui-btn--primary ui-btn--sm mine\" href")
         (html (ui/link-button "/a" {:variant :primary :size :sm :class "mine" :rel "next"} "Go" [:b "x"])))
      "the variant's and size's classes, the caller's joined, its other attrs kept")
  (is (= (str/replace (L "/" nil "&lt;&amp;&gt;") "<a href" "<a class=\"ui-btn a b\" href") (html (ui/link-button "/" {:class ["a" :b]} "<&>"))))
  (is (= ["uikit-base: a button's :variant is one of the kit's" {:variant :huge}] (refusal #(ui/link-button "/a" {:variant :huge} "x"))))
  (is (= ["uikit-base: a button's :size is :sm or :lg" {:size :md}] (refusal #(ui/link-button "/a" {:size :md} "x"))))
  (doseq [p ["//evil" "https://evil.test/" nil]]
    (is (= ["web-base: link's href must be a path on this origin" {:wb/link :href}] (refusal #(ui/link-button p "x"))) (str "path " (pr-str p))))
  (doseq [a [{:hx-get "/x"} {"HREF" "/x"} {"HX-Get" "/x"} {:data-hx-get "/x"}]]
    (is (= ["web-base: link's attrs may not give an href or an htmx attribute" {:wb/link :attrs}] (refusal #(ui/link-button "/a" a "x"))) (pr-str a))))

(def ^:private TOKEN "<input name=\"__anti-forgery-token\" type=\"hidden\" value=\"T&lt;&amp;&quot;\" />")

(deftest an-action-posts-its-fields-with-the-token-first-and-names-what-it-acts-on
  (is (= (str "<form action=\"/t/1/remove\" class=\"ui-action\" method=\"post\">" TOKEN "<button class=\"ui-btn\" type=\"submit\">Remove</button></form>")
         (html (ui/action req "/t/1/remove" {:label "Remove"}))))
  (is (= (str "<form action=\"/t/1/remove\" class=\"ui-action mine\" hx-target=\"closest li\" method=\"post\">" TOKEN
              "<input name=\"a\" type=\"hidden\" value=\"true\" /><input name=\"b\" type=\"hidden\" value=\"2\" /><input name=\"ns/d\" type=\"hidden\" value=\"\" />"
              "<button class=\"ui-btn ui-btn--danger ui-btn--sm\" id=\"go\" type=\"submit\">Remove<span class=\"ui-sr-only\"> Al&lt;gebra</span></button></form>")
         (html (ui/action req "/t/1/remove" {:label "Remove" :hidden {:b 2 :a true :ns/d nil} :subject "Al<gebra" :variant :danger :size :sm
                                             :attrs {:class "mine" :hx-target "closest li" :method "get" :action "/evil"} :button {:id "go"}})))
      "fields by name, as text; the subject read after the label, never shown; the form's method and action its own")
  (is (= ["uikit-base: a form takes no hx-get: htmx would send it, token and all, by another method or to another action" {:attributes [:hx-get]}]
         (refusal #(ui/action req "/a" {:label "L" :attrs {:hx-get "/x"}}))) "post-form's refusals hold")
  (is (= ["uikit-base: a button takes no formaction: a submitter's own target would send its form, token and all, where the form does not" {:attributes [:formaction]}]
         (refusal #(ui/action req "/a" {:label "L" :button {:formaction "/x"}}))) "and button's")
  (doseq [[a k] [[{"TYPE" "button"} :type] [{:name "id"} :name] [{:value "9"} :value] [{:variant :danger} :variant] [{:size :sm} :size]]]
    (is (= ["uikit-base: an action's button takes no type, name, value, variant or size of its own" {:attributes [k]}]
           (refusal #(ui/action req "/a" {:label "L" :button a}))) (pr-str a)))
  (doseq [k [:__anti-forgery-token "__ANTI-FORGERY-TOKEN"]]
    (is (= ["uikit-base: an action's hidden fields may not carry the CSRF field" {:hidden [(name k)]}] (refusal #(ui/action req "/a" {:label "L" :hidden {k "evil"}}))) (pr-str k)))
  (let [d (java.util.Locale/getDefault)]
    (try (java.util.Locale/setDefault (java.util.Locale. "tr" "TR"))
         (is (= ["uikit-base: an action's hidden fields may not carry the CSRF field" {:hidden ["__ANTI-FORGERY-TOKEN"]}]
                (refusal #(ui/action req "/a" {:label "L" :hidden {"__ANTI-FORGERY-TOKEN" "evil"}})))
             "in a Turkish JVM too, where I lower-cases to a dotless ı")
         (finally (java.util.Locale/setDefault d))))
  (is (= ["uikit-base: an action's hidden field has a name, or a browser would not send it" {:hidden [nil]}] (refusal #(ui/action req "/a" {:label "L" :hidden {nil 1}}))))
  (is (= ["uikit-base: an action's hidden field has a name, or a browser would not send it" {:hidden [" "]}] (refusal #(ui/action req "/a" {:label "L" :hidden {" " 1}}))))
  (is (= ["uikit-base: an action's hidden fields are one per name" {:hidden ["a"]}] (refusal #(ui/action req "/a" {:label "L" :hidden {:a 1 "a" 2}}))))
  (is (= ["uikit-base: an action's hidden value is one value, not a collection" {:hidden ["ids"]}] (refusal #(ui/action req "/a" {:label "L" :hidden {:ids [1 2]}}))))
  (is (str/includes? (html (ui/action req "/a" {:label "L" :hidden {:status :done :to :ns/x}})) "<input name=\"status\" type=\"hidden\" value=\"done\" /><input name=\"to\" type=\"hidden\" value=\"ns/x\" />")
      "a keyword value is sent as its text, as a keyword name is")
  (is (= (str "<form action=\"/a\" class=\"ui-action\" method=\"post\">" TOKEN "<button class=\"ui-btn\" type=\"submit\">L</button></form>")
         (html (ui/action req "/a" {:label "L" :subject ""})))
      "a blank subject is none")
  (doseq [l [nil "" "  " :remove]]
    (is (= ["uikit-base: an action's label is the text its button shows" {:label l}] (refusal #(ui/action req "/a" {:label l}))) (pr-str l))))

(deftest a-confirm-asks-with-a-plain-button-and-posts-from-a-second
  (is (= (str "<details class=\"ui-confirm\"><summary class=\"ui-btn\">Remove<span class=\"ui-sr-only\"> Algebra</span></summary>"
              "<form action=\"/t/1/remove\" class=\"ui-action\" method=\"post\">" TOKEN "<input name=\"id\" type=\"hidden\" value=\"7\" />"
              "<button class=\"ui-btn ui-btn--danger\" type=\"submit\">Yes, remove<span class=\"ui-sr-only\"> Algebra</span></button></form></details>")
         (html (ui/confirm req "/t/1/remove" {:label "Remove" :confirm-label "Yes, remove" :subject "Algebra" :hidden {:id 7}})))
      "the first step only opens; the second, danger unless told, posts")
  (is (= (str "<details class=\"ui-confirm\"><summary class=\"ui-btn\">Archive</summary>"
              "<form action=\"/a\" class=\"ui-action row\" hx-target=\"closest li\" method=\"post\">" TOKEN
              "<button class=\"ui-btn ui-btn--warning ui-btn--sm\" id=\"go\" type=\"submit\">Yes, archive</button></form></details>")
         (html (ui/confirm req "/a" {:label "Archive" :confirm-label "Yes, archive" :variant :warning :size :sm
                                     :attrs {:class "row" :hx-target "closest li"} :button {:id "go"}})))
      "the rest of its opts are the action's: a variant, a size, the form's and the button's attrs")
  (doseq [[o msg] [[{:attrs {:hx-get "/x"}} "uikit-base: a form takes no hx-get: htmx would send it, token and all, by another method or to another action"]
                   [{:button {:formaction "/x"}} "uikit-base: a button takes no formaction: a submitter's own target would send its form, token and all, where the form does not"]
                   [{:button {:type "button"}} "uikit-base: an action's button takes no type, name, value, variant or size of its own"]
                   [{:hidden {"__anti-forgery-token" "x"}} "uikit-base: an action's hidden fields may not carry the CSRF field"]]]
    (is (= msg (first (refusal #(ui/confirm req "/a" (merge {:label "A" :confirm-label "Yes"} o))))) (str "refused through confirm: " (pr-str o))))
  (is (= ["uikit-base: a confirm's two labels differ, or its second step reads as its first" {:label "Remove"}]
         (refusal #(ui/confirm req "/a" {:label "Remove" :confirm-label " Remove "}))))
  (is (= ["uikit-base: a confirm's confirm-label is the text its button shows" {:confirm-label ""}]
         (refusal #(ui/confirm req "/a" {:label "Remove" :confirm-label ""}))))
  (is (= ["uikit-base: a confirm's label is the text its button shows" {:label nil}]
         (refusal #(ui/confirm req "/a" {:confirm-label "Yes"})))))

(deftest an-empty-state-is-a-quiet-line
  (is (= "<p class=\"ui-empty\">No orders &lt;yet&gt;.</p>" (html (ui/empty "No orders <yet>."))))
  (is (= "<p class=\"ui-empty x\" id=\"e\">None</p>" (html (ui/empty {:class "x" :id "e"} "None")))))

(deftest pairs-describe-in-order-and-leave-out-what-has-no-description
  (is (= "<dl class=\"ui-pairs\"><dt>Ref</dt><dd>A1</dd><dt>Total</dt><dd><b>9</b></dd><dt>Note</dt><dd>z</dd></dl>"
         (html (ui/pairs [["Ref" "A1"] ["Paid" nil] ["Total" [:b "9"]] ["Note" "z"]]))))
  (is (= "<dl class=\"ui-pairs k\"><dt>&lt;a&gt;</dt><dd>&lt;b&gt;</dd></dl>" (html (ui/pairs {:class "k"} [["<a>" "<b>"]]))))
  (is (= "<dl class=\"ui-pairs\"><dt>Paid</dt><dd>false</dd><dt>Count</dt><dd>0</dd></dl>" (html (ui/pairs [["Paid" false] ["Count" 0]])))
      "only nil leaves a term out: a false shows, so a slip in the view is seen, not hidden")
  (is (= "<dl class=\"ui-pairs\"></dl>" (html (ui/pairs nil))))
  (doseq [args [[["a" "b"]] [[["a" "b"]] [["c" "d"]]] [[["a"]]]]]
    (is (= ["uikit-base: a pairs takes its attrs and one sequence of [term description]" {}] (refusal #(apply ui/pairs args))) (pr-str args))))

(deftest a-time-carries-its-moment-for-machines-and-the-hosts-words-for-people
  (let [madrid (java.time.ZonedDateTime/of 2026 1 15 20 0 0 0 (java.time.ZoneId/of "Europe/Madrid"))]
    (is (= "<time datetime=\"2026-01-15T19:00:00Z\">15 Jan, 20:00</time>" (html (ui/time (java.time.Instant/parse "2026-01-15T19:00:00Z") "15 Jan, 20:00"))))
    (is (= "<time class=\"c\" datetime=\"2026-01-15T20:00+01:00\">20:00</time>" (html (ui/time madrid {:datetime "x" "DATETIME" "y" :class "c"} "20:00")))
        "a zoned moment as its offset, never the zone's bracketed name; no attribute changes it")
    (is (= "<time datetime=\"2026-01-15T20:00+01:00\">t</time>" (html (ui/time (.toOffsetDateTime madrid) "t"))))
    (is (= "<time datetime=\"2026-01-15T20:00\">t</time>" (html (ui/time (java.time.LocalDateTime/of 2026 1 15 20 0) "t"))))
    (is (= "<time datetime=\"2026-01-15\">15/1</time>" (html (ui/time (java.time.LocalDate/of 2026 1 15) "15/1")))))
  (doseq [v [(java.util.Date. 0) (java.sql.Timestamp. 0) "2026-01-15" nil (java.time.LocalTime/of 1 2)]]
    (is (= ["uikit-base: a time is a java.time moment, date-time or date, or epoch milliseconds" {:value v}] (refusal #(ui/time v "x"))) (pr-str v)))
  (is (= ["uikit-base: a time shows something: its body is how the page says it" {}] (refusal #(ui/time (java.time.LocalDate/of 2026 1 15))))))

(deftest a-page-head-is-the-pages-own-heading-with-what-it-offers
  (is (= (str "<div class=\"ui-page-head x\" id=\"h\"><h3 class=\"ui-page-head__title\">&lt;T&gt;</h3><p class=\"ui-page-head__sub\">s</p>"
              "<div class=\"ui-page-head__actions ui-cluster\"><b>1</b><i>2</i></div></div>")
         (html (ui/page-head {:title "<T>" :subtitle "s" :level 3 :actions [[:b "1"] [:i "2"]] :attrs {:class "x" :id "h"}}))))
  (is (= "<div class=\"ui-page-head\" id=\"h\"><h2 class=\"ui-page-head__title\">T</h2></div>" (html (ui/page-head {:title "T" :level 2 :attrs {:id "h"}}))))
  (is (= [:div {:class "ui-page-head"} [:h2 {:class "ui-page-head__title"} "t"]] (ui/page-head {:title "t" :subtitle nil :actions []}))
      "an h2 unless told — the shell's header holds the page's h1; no subtitle, no actions, nothing drawn for them")
  (is (= [:div {:class "ui-page-head"} [:h1 {:class "ui-page-head__title"} "t"]] (ui/page-head {:title "t" :level 1})))
  (is (str/includes? (html (ui/page-head {:title "t" :actions (map (fn [i] [:b i]) [1])})) "<div class=\"ui-page-head__actions ui-cluster\"><b>1</b></div>"))
  (doseq [t [nil false "" "  "]]
    (is (= ["uikit-base: a page-head has a title" {}] (refusal #(ui/page-head {:title t}))) (pr-str t)))
  (doseq [l [0 4 "1" nil 1.0]]
    (is (= ["uikit-base: a page-head's level is 1, 2 or 3" {:level l}] (refusal #(ui/page-head {:title "t" :level l}))) (pr-str l))))

(deftest crumbs-link-each-page-above-and-mark-this-one
  (is (= (str "<nav aria-label=\"Breadcrumbs\" class=\"ui-crumbs\"><ol><li>" (L "/c" nil "Courses") "</li><li>" (L "/c/1" nil "Al&lt;g") "</li>"
              "<li><span aria-current=\"page\">Week &lt;1&gt;</span></li></ol></nav>")
         (html (ui/crumbs {} {:items [["/c" "Courses"] ["/c/1" "Al<g"] [nil "Week <1>"]]})))
      "in order, links up, this page marked and no link; named in English with no translator")
  (is (str/includes? (html (ui/crumbs {:wb/tr (fn [k _] (str "tr:" k))} {:items [[nil "H"]]})) "aria-label=\"tr::uikit-base/breadcrumbs\"")
      "named by the kit's word in the reader's language")
  (is (= "<nav aria-label=\"Pa&quot;th\" class=\"ui-crumbs x\" id=\"i\"><ol><li><span aria-current=\"page\">Home</span></li></ol></nav>"
         (html (ui/crumbs req {:items [[nil "Home"]] :label "Pa\"th" :attrs {:class "x" :id "i"}}))))
  (is (str/includes? (html (ui/crumbs req {:items [[nil "H"]] :attrs {:aria-label "m"}})) "aria-label=\"m\"") "as a pager, the caller's own name wins")
  (doseq [items [[] nil [["/a" "A"]] [[nil "A"] [nil "B"]] [["/a" "A"] [nil "B"] [nil "C"]]]]
    (is (= ["uikit-base: a crumbs' items are paths up to this page, which has none" {}] (refusal #(ui/crumbs req {:items items}))) (pr-str items)))
  (doseq [p ["//evil.test" "javascript:x"]]
    (is (= ["web-base: link's href must be a path on this origin" {:wb/link :href}] (refusal #(ui/crumbs req {:items [[p "A"] [nil "B"]]}))) p))
  (is (not (str/includes? (html (ui/crumbs req {:items [["/a" {:onclick "evil()"}] [nil "B"]]})) "onclick=\""))
      "a label is the link's body, never its attributes")
  (doseq [items [[["/a" nil] [nil "B"]] [["/a" "A"] [nil " "]]]]
    (is (= ["uikit-base: every item of a crumbs has a label" {}] (refusal #(ui/crumbs req {:items items}))) (pr-str items))))

(deftest a-tabs-nav-links-the-views-of-a-list-and-marks-the-current-one
  (let [cur (fn [href body] (str/replace (L href nil body) "<a href" "<a aria-current=\"page\" href"))]
    (is (= (str "<nav aria-label=\"St&lt;&quot;\" class=\"ui-tabs x\" id=\"i\"><ul><li>" (L "/o" nil "All") "</li><li>" (cur "/o?s=open" "Op&lt;en") "</li></ul></nav>")
           (html (ui/tabs-nav req {:label "St<\"" :items [["/o" "All"] ["/o?s=open" "Op<en"]] :current "/o?s=open" :attrs {:class "x" :id "i"}}))))
    (is (= (str "<nav aria-label=\"S\" class=\"ui-tabs\"><ul><li>" (L "/o" nil "All") "</li><li>" (L "/p" nil "P") "</li></ul></nav>")
           (html (ui/tabs-nav req {:label "S" :items [["/o" "All"] ["/p" "P"]]})))
        "no current, none marked"))
  (doseq [a [{:aria-label "m"} {"Aria-Label" "m"}]]
    (is (str/starts-with? (html (ui/tabs-nav req {:label "S" :items [["/o" "A"]] :attrs a})) "<nav aria-label=\"S\"") (str "its name is its label: " (pr-str a))))
  (doseq [l [" " :k nil]]
    (is (= ["uikit-base: a tabs-nav is named by its label" {:label l}] (refusal #(ui/tabs-nav req {:label l :items [["/o" "A"]]}))) (pr-str l)))
  (doseq [c ["/x" false]]
    (is (= ["uikit-base: a tabs-nav's current is one of its items' paths" {:current c}] (refusal #(ui/tabs-nav req {:label "S" :items [["/o" "A"]] :current c}))) (pr-str c)))
  (doseq [items [[] [["/o" "All"] ["/o" "Again"]]]]
    (is (= ["uikit-base: a tabs-nav's items are one or more, each its own path" {}] (refusal #(ui/tabs-nav req {:label "S" :items items}))) (pr-str items)))
  (doseq [p ["//evil.test" "javascript:x" nil]]
    (is (= ["web-base: link's href must be a path on this origin" {:wb/link :href}] (refusal #(ui/tabs-nav req {:label "S" :items [[p "A"]]}))) (pr-str p)))
  (is (= ["uikit-base: every item of a tabs-nav has a label" {}] (refusal #(ui/tabs-nav req {:label "S" :items [["/a" ""]]})))))

(deftest a-wide-table-scrolls-in-a-named-region-the-keyboard-reaches
  (is (= (str "<div aria-label=\"Orders &lt;all&gt;\" class=\"ui-table-wrap\" role=\"region\" tabindex=\"0\"><table class=\"ui-table\"><tbody><tr><td>a</td>"
              "<td class=\"ui-num\">1.50</td></tr></tbody></table></div>")
         (html (ui/table {:rows [["a" (ui/cell {:class "ui-num"} "1.50")]] :scroll-label "Orders <all>"}))))
  (is (= "<table class=\"ui-table\"><tbody><tr><td>a</td></tr></tbody></table>" (html (ui/table {:rows [["a"]]})))
      "without a label, the table alone, as before")
  (doseq [l ["" "  " :k]]
    (is (= ["uikit-base: a table's scroll-label names its region" {:scroll-label l}] (refusal #(ui/table {:rows [] :scroll-label l}))) (pr-str l))))

(deftest a-card-may-carry-a-heading-and-a-footer-around-its-body
  (is (= "<div class=\"ui-card x\" id=\"c\"><h2 class=\"ui-card__head\">T&lt;</h2><p>b</p><i>c</i><div class=\"ui-card__foot\"><a>f</a></div></div>"
         (html (ui/card {:title "T<" :footer [:a "f"] :class "x" :id "c"} [:p "b"] [:i "c"]))))
  (is (= "<div class=\"ui-card\"><h4 class=\"ui-card__head\">T</h4>b</div>" (html (ui/card {:title "T" :level 4} "b"))))
  (is (= [:div {:class "ui-card"} "b"] (ui/card "b")) "no parts, the card it was")
  (is (= [:div {:class "ui-card" :id "c"} "b"] (ui/card {:id "c" :level 9} "b")) "a level means nothing without a title")
  (doseq [l [1 5 "2"]]
    (is (= ["uikit-base: a card's level is 2, 3 or 4" {:level l}] (refusal #(ui/card {:title "T" :level l} "b"))) (pr-str l))))

(deftest a-secret-is-shown-once-in-a-labelled-field-it-is-never-sent-from
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"tok\">Token</label>"
              "<input aria-describedby=\"tok-once\" autocomplete=\"off\" class=\"ui-input ui-input--code\" id=\"tok\" readonly=\"readonly\" spellcheck=\"false\" type=\"text\" value=\"ab&lt;c\" />"
              "<span class=\"ui-field__hint\" id=\"tok-once\">Copy it now: it will not be shown again.</span></div>")
         (html (ui/secret {} {:id "tok" :label "Token" :value "ab<c"}))))
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"tok\">Token</label>"
              "<input aria-describedby=\"tok-once mine\" autocomplete=\"off\" class=\"ui-input ui-input--code w\" id=\"tok\" readonly=\"readonly\" spellcheck=\"false\" type=\"text\" value=\"v\" />"
              "<span class=\"ui-field__hint\" id=\"tok-once\">Copy it now: it will not be shown again.</span></div>")
         (html (ui/secret {} {:id "tok" :label "Token" :value "v"
                              :attrs {:class "w" "READONLY" false "NAME" "x" :name "y" "Value" "z" :type "password" "ID" "other" :disabled true :aria-describedby "mine"}})))
      "read-only, its value, its id and its type its own; no name, so no form sends it; never disabled, or it could not be copied; the caller's description after its own")
  (let [[_ [_ {f :for}] [_ {i :id d :aria-describedby}] [_ {o :id}]] (ui/secret {} {:id "k" :label "K" :value "v"})]
    (is (and (= f i) (= (first (str/split d #" ")) o)) "the label names the field, and the field is described by the note beside it"))
  (is (str/includes? (html (ui/secret {:wb/tr (fn [k _] (str "tr:" k))} {:id "k" :label "K" :value "v"})) "tr::uikit-base/shown-once")
      "the note is the kit's word in the reader's language")
  (doseq [o [{:id nil} {:id " "} {:id "a b"} {:id :k} {:label nil} {:label ""} {:value nil} {:value " "}]]
    (is (= ["uikit-base: a secret has an id (one word), a label and a value" {:id (:id (merge {:id "k"} o))}]
           (refusal #(ui/secret {} (merge {:id "k" :label "K" :value "v"} o)))) (pr-str o))))

(deftest a-thread-is-its-messages-in-order-each-headed-by-who-and-when
  (is (= (str "<ol class=\"ui-thread t\"><li><article class=\"ui-message m\" id=\"m1\"><header class=\"ui-message__meta\"><span>Ada · 10:00</span></header><p>Hi&lt;</p></article></li>"
              "<li><article class=\"ui-message\">b</article></li><li><article class=\"ui-message\">c</article></li></ol>")
         (html (ui/thread {:class "t"} (ui/message {:meta [:span "Ada · 10:00"] :attrs {:id "m1" :class "m"}} [:p "Hi<"]) (ui/message {} "b") (ui/message {:meta " "} "c")))))
  (is (= "<ol class=\"ui-thread\"></ol>" (html (ui/thread)))))

(deftest a-time-takes-the-epoch-milliseconds-a-column-holds
  (doseq [[v dt] [[0 "1970-01-01T00:00:00Z"] [(int 1000) "1970-01-01T00:00:01Z"] [1763164800000 "2025-11-15T00:00:00Z"] [-1 "1969-12-31T23:59:59.999Z"]]]
    (is (= (str "<time datetime=\"" dt "\">t</time>") (html (ui/time v "t"))) (pr-str v)))
  (doseq [v [1.5 5N (short 5) Long/MAX_VALUE (java.time.Instant/parse "+10000-01-01T00:00:00Z") (java.time.Instant/parse "-0001-01-01T00:00:00Z")]]
    (is (= ["uikit-base: a time is a java.time moment, date-time or date, or epoch milliseconds" {:value v}] (refusal #(ui/time v "t"))) (pr-str v))))

(deftest a-confirms-first-step-and-element-take-their-own-attributes
  (let [h (html (ui/confirm req "/d" {:label "Delete" :confirm-label "Yes" :subject "Ada"
                                      :first {:variant :danger :id "f" :class "x"} :details {:id "c" :open true "OPEN" true :class "y"}}))]
    (is (str/starts-with? h "<details class=\"ui-confirm y\" id=\"c\"><summary class=\"ui-btn ui-btn--danger x\" id=\"f\">Delete<span class=\"ui-sr-only\"> Ada</span></summary>")
        (str "the element's attrs on the details, the first step's on the summary, styled as its variant: " h))
    (is (not (re-find #"(?i)\bopen\b" h)) "never opened by the server: the first step is the person's")
    (is (str/includes? h "<button class=\"ui-btn ui-btn--danger\" type=\"submit\">Yes<span class=\"ui-sr-only\"> Ada</span></button>")))
  (doseq [a [{:type "button"} {:name "n"} {:value "v"} {:size :sm} {"type" "x"}]]
    (is (= ["uikit-base: a confirm's first step takes a variant and attributes, never a type, name, value or size" {:attributes [(keyword (str/lower-case (name (key (first a)))))]}]
           (refusal #(ui/confirm req "/d" {:label "D" :confirm-label "Y" :first a}))) (pr-str a)))
  (is (= ["uikit-base: a button's :variant is one of the kit's" {:variant "x"}] (refusal #(ui/confirm req "/d" {:label "D" :confirm-label "Y" :first {:variant "x"}}))))
  (doseq [[a k] [[{:hx-post "/d" :hx-trigger "click"} [:hx-post :hx-trigger]] [{"DATA-HX-GET" "/x"} [:data-hx-get]] [{:hx-post:inherited "/d"} [:hx-post:inherited]]]]
    (is (= ["uikit-base: a confirm's first step only opens it; it takes no htmx" {:attributes k}]
           (refusal #(ui/confirm req "/d" {:label "D" :confirm-label "Y" :first a}))) (str "the first press only opens: " (pr-str a))))
  (doseq [[a k] [[{:hx-post "/d" :hx-trigger "toggle"} [:hx-post]] [{"data-hx-delete" "/d"} [:data-hx-delete]] [{:hx-put:inherited "/d"} [:hx-put:inherited]]]]
    (is (= ["uikit-base: a confirm's element sends nothing; only its second step does" {:attributes k}]
           (refusal #(ui/confirm req "/d" {:label "D" :confirm-label "Y" :details a}))) (str "opening it sends nothing: " (pr-str a))))
  (is (str/includes? (html (ui/confirm req "/d" {:label "D" :confirm-label "Y" :details {:hx-get "/n" :hx-trigger "toggle"}})) "hx-get=\"/n\"")
      "a GET on opening, to fetch what the second step shows, is the host's"))

(deftest a-link-button-names-its-row-and-a-download-loads-as-a-document
  (is (str/ends-with? (html (ui/link-button "/a" {:subject "Ada"} "Edit")) "Edit<span class=\"ui-sr-only\"> Ada</span></a>"))
  (is (not (str/includes? (html (ui/link-button "/a" {:subject "Ada"} "Edit")) "subject")) "the subject is no attribute")
  (is (= (html (ui/link-button "/a" "Edit")) (html (ui/link-button "/a" {:subject " "} "Edit"))) "a blank subject is none")
  (is (= "<a class=\"ui-btn ui-btn--primary\" download=\"m.csv\" href=\"/a.csv\">CSV<span class=\"ui-sr-only\"> May</span></a>"
         (html (ui/link-button "/a.csv" {:download? true :variant :primary :subject "May" :download "m.csv"} "CSV")))
      "a plain link: no swap, so the browser loads it as a document")
  (doseq [p ["//evil" "javascript:x" "https://e.test/a" "a.csv" "/\\evil" "" nil "/a b"]]
    (is (= ["uikit-base: a download's path is a path on this origin" {:path p}] (refusal #(ui/link-button p {:download? true} "x"))) (pr-str p)))
  (doseq [a [{:hx-get "/x"} {:href "/x"} {:data-hx-get "/x"} {"HX-GET" "/x"}]]
    (is (= "uikit-base: a download's attrs give no href and no htmx" (first (refusal #(ui/link-button "/a" (assoc a :download? true) "x")))) (pr-str a))))

(deftest an-action-shows-what-it-answers-before-its-button
  (is (= (str "<form action=\"/d\" class=\"ui-action\" method=\"post\">" TOKEN "<input name=\"a\" type=\"hidden\" value=\"1\" /><p>Sure?</p>"
              "<button class=\"ui-btn\" type=\"submit\">Go</button></form>")
         (html (ui/action req "/d" {:label "Go" :hidden {:a 1} :before [:p "Sure?"]})))))

(deftest a-secret-may-carry-the-hosts-own-note
  (let [h (html (ui/secret {} {:id "s" :label "S" :value "v" :note "Keep it safe."}))]
    (is (str/includes? h "<span class=\"ui-field__hint\" id=\"s-once\">Keep it safe.</span>"))
    (is (not (str/includes? h "Copy it now")) "in place of the kit's, not beside it"))
  (is (str/includes? (html (ui/secret {} {:id "s" :label "S" :value "v" :note " "})) "Copy it now: it will not be shown again.")
      "a blank note is none"))

(def ^:private es-tr
  (wb/translator {:default-locale :es :dict {:es {:a {:due "Entrega: %1, antes de %2." :rev "%2 antes que %1" :twice "%1 y %1"
                                                      :lost "Sin nada" :raw "<b>&</b> %1"}}}} [:es]))

(deftest a-sentence-holds-its-parts-where-the-translation-puts-them
  (let [r {:wb/tr es-tr} s #(html [:p (apply ui/sentence r %&)])]
    (is (= "<p>Entrega: <time datetime=\"1970-01-01T00:00:00Z\">1 ene</time>, antes de <b>&lt;x&gt;</b>.</p>" (s :a/due (ui/time 0 "1 ene") [:b "<x>"])))
    (is (= "<p><i>2</i> antes que <i>1</i></p>" (s :a/rev [:i 1] [:i 2])) "in the translation's order, not the call's")
    (is (= "<p><i>1</i> y <i>1</i></p>" (s :a/twice [:i 1])))
    (is (= "<p>Sin nada</p>" (s :a/lost [:i 1])) "a part the translation leaves out is left out")
    (is (= "<p>&lt;b&gt;&amp;&lt;/b&gt; &lt;raw&gt;</p>" (s :a/raw "<raw>")) "the word's text and a text part are escaped")
    (is (not (re-find #"[\uE000\uE001]" (s :a/due "a" "b"))) "no marker of the kit's is ever shown")
    (is (= "<p>y antes que x\uE0000\uE001</p>" (s :a/rev "x\uE0000\uE001" "y")) "a part that spells a marker is shown as given, never expanded"))
  (is (= ["uikit-base: a sentence is translated by web-base; the request carries no :wb/tr" {:word :a/due}] (refusal #(ui/sentence {} :a/due "x"))))
  (is (= ["uikit-base: a sentence's word is text in the dictionary" {:word :a/missing}] (refusal #(ui/sentence {:wb/tr es-tr} :a/missing))))
  (is (= ["uikit-base: a sentence's word asks for more parts than it was given" {:word :a/due :parts 1}] (refusal #(ui/sentence {:wb/tr es-tr} :a/due "x"))))
  (is (= ["uikit-base: a sentence's parts are something to show" {:word :a/rev}] (refusal #(ui/sentence {:wb/tr es-tr} :a/rev "x" nil)))))

(deftest a-table-heads-its-columns-and-captions-what-it-lists
  (is (= (str "<table class=\"ui-table\"><caption>C&lt;</caption><thead><tr><th scope=\"col\">A</th><th scope=\"col\">B&amp;</th></tr></thead>"
              "<tbody><tr><td>a1</td><td>b1</td></tr><tr><td>a2</td><td><b>x&lt;</b></td></tr></tbody></table>")
         (html (ui/table {:caption "C<" :head ["A" "B&"] :rows [["a1" "b1"] ["a2" [:b "x<"]]]}))))
  (is (= "<table class=\"ui-table\"><tbody><tr><td>a</td></tr></tbody></table>" (html (ui/table {:rows [["a"]]})))
      "no caption or head, none drawn"))

(deftest a-row-header-is-each-rows-first-cell-and-no-other
  (is (= (str "<table class=\"ui-table\"><thead><tr><th scope=\"col\">A</th><th scope=\"col\">B</th></tr></thead>"
              "<tbody><tr><th scope=\"row\">a1</th><td>b1</td></tr><tr><th scope=\"row\">a2</th><td>b2</td></tr></tbody></table>")
         (html (ui/table {:head ["A" "B"] :rows [["a1" "b1"] ["a2" "b2"]] :row-header? true})))))

(deftest a-table-cell-holds-any-markup-and-the-table-keeps-the-callers-attributes
  (is (= (str "<table class=\"ui-table mine\" id=\"t\"><tbody><tr><td>" (L "/t/1" nil "R1")
              "</td><td><form method=\"post\"><button>x</button></form></td><td>a<i>b</i></td></tr></tbody></table>")
         (html (ui/table {:rows [[(wb/link "/t/1" "R1") [:form {:method "post"} [:button "x"]] (list "a" [:i "b"])]] :attrs {:class "mine" :id "t"}}))))
  (is (= ["uikit-base: an attribute is named by a keyword or a string" {:attribute 1}] (refusal #(ui/table {:rows [] :attrs {1 2}})))))

(deftest a-cell-carries-its-own-attributes-and-the-header-keeps-its-scope
  (is (= "<table class=\"ui-table\"><tbody><tr><td class=\"yours\" id=\"c\">x<b>y</b></td><td>z</td></tr></tbody></table>"
         (html (ui/table {:rows [[(ui/cell {:class "yours" :id "c"} "x" [:b "y"]) "z"]]}))))
  (is (= (str "<table class=\"ui-table\"><thead><tr><th class=\"num\" id=\"h\" scope=\"col\">A</th><th scope=\"col\">B</th></tr></thead>"
              "<tbody></tbody></table>")
         (html (ui/table {:head [(ui/cell {:class "num" :scope "row" :id "h"} "A") "B"] :rows []})))
      "a head cell keeps scope=col whatever its attrs say")
  (is (= "<table class=\"ui-table\"><thead><tr><th scope=\"col\">A</th></tr></thead><tbody></tbody></table>"
         (html (ui/table {:head [(ui/cell {"SCOPE" "row"} "A")] :rows []})))
      "nor by a string spelling of it")
  (is (= "<table class=\"ui-table\"><tbody><tr><th class=\"k\" id=\"r\" scope=\"row\">a</th><td scope=\"x\">b</td></tr></tbody></table>"
         (html (ui/table {:rows [[(ui/cell {:class "k" :scope "col" :id "r"} "a") (ui/cell {:scope "x"} "b")]] :row-header? true})))
      "a row header keeps scope=row; an ordinary cell has no scope of the kit's to keep")
  (is (= ["uikit-base: an attribute is named by a keyword or a string" {:attribute 1}]
         (refusal #(html (ui/table {:rows [[(ui/cell {1 2} "a")]]}))))))

(defn- booking-day [other?]
  (html (ui/table {:head ["Hour" (when other? "Yours") "Who" ""]
                   :rows [["09:00" (ui/cell {:class "yours"} "10:00") "ada" [:form "f"]]
                          ["10:00" (ui/cell {:class "yours"} "11:00") "free" nil]]})))

(deftest a-column-whose-head-is-nil-is-drawn-nowhere
  (is (= (str "<table class=\"ui-table\"><thead><tr><th scope=\"col\">Hour</th><th scope=\"col\">Who</th><th scope=\"col\"></th></tr></thead>"
              "<tbody><tr><td>09:00</td><td>ada</td><td><form>f</form></td></tr><tr><td>10:00</td><td>free</td><td></td></tr></tbody></table>")
         (booking-day false))
      "the nil column leaves the head and every row; the empty-headed one stays, its nil cell an empty td")
  (is (= (str "<table class=\"ui-table\"><thead><tr><th scope=\"col\">Hour</th><th scope=\"col\">Yours</th><th scope=\"col\">Who</th><th scope=\"col\"></th></tr></thead>"
              "<tbody><tr><td>09:00</td><td class=\"yours\">10:00</td><td>ada</td><td><form>f</form></td></tr>"
              "<tr><td>10:00</td><td class=\"yours\">11:00</td><td>free</td><td></td></tr></tbody></table>")
         (booking-day true)))
  (is (not-any? #(str/includes? % "<cell") [(booking-day false) (booking-day true)]) "the cell marker is never drawn"))

(deftest without-a-nil-head-nothing-is-dropped
  (is (= "<table class=\"ui-table\"><tbody><tr><td>a</td><td></td><td>c</td></tr></tbody></table>"
         (html (ui/table {:rows [["a" nil "c"]]}))
         (html (ui/table {:head [] :rows [["a" nil "c"]]})))
      "without a head a nil cell is an empty td")
  (is (= "<table class=\"ui-table\"><thead><tr><th scope=\"col\">A</th></tr></thead><tbody><tr><td>a</td><td>b</td></tr></tbody></table>"
         (html (ui/table {:head ["A"] :rows [["a" "b"]]})))
      "a cell past the head is still drawn: only a nil head entry drops a column"))

(defn- tr-for
  "A translator as web-base builds it for a host speaking `locales`, with the kit's plugin
  merged in: the real merge, never the kit's dictionary read back."
  [locale]
  (wb/translator (:i18n (wb/expand {:routes [] :plugins [(web/plugin)] :i18n {:default-locale :en :locales [:en :es]}})) [locale]))

(deftest a-pager-links-each-side-in-the-readers-language-around-where-the-reader-is
  (let [o {:previous "/l?page=1" :next "/l?page=3&q=a" :where "page 2 of 5"}]
    (is (= (str "<nav aria-label=\"Pages\" class=\"ui-pager\">" (L "/l?page=1" "prev" "Previous") "<span class=\"ui-pager__where\">page 2 of 5</span>"
                (L "/l?page=3&amp;q=a" "next" "Next") "</nav>")
           (html (ui/pager {:wb/tr (tr-for :en)} o))))
    (is (= (str "<nav aria-label=\"Páginas\" class=\"ui-pager\">" (L "/l?page=1" "prev" "Anterior") "<span class=\"ui-pager__where\">page 2 of 5</span>"
                (L "/l?page=3&amp;q=a" "next" "Siguiente") "</nav>")
           (html (ui/pager {:wb/tr (tr-for :es)} o)))
        "in Spanish")
    (is (= (str "<nav aria-label=\"Pp\" class=\"ui-pager\">" (L "/l?page=1" "prev" "Back") "<span class=\"ui-pager__where\">page 2 of 5</span>"
                (L "/l?page=3&amp;q=a" "next" "Fwd") "</nav>")
           (html (ui/pager {:wb/tr (tr-for :es)} (assoc o :label "Pp" :previous-label "Back" :next-label "Fwd"))))
        "an explicit label wins over the dictionary"))
  (is (= (str "<nav aria-label=\"Pages\" class=\"ui-pager\">" (L "/l?page=2" "next" "Next") "</nav>")
         (html (ui/pager {} {:next "/l?page=2"})))
      "with no translator at all, the kit's English: a link always has a name")
  (is (= (str "<nav aria-label=\"Pages\" class=\"ui-pager\">" (L "/l?page=2" "next" "Next") "</nav>")
         (html (ui/pager {:wb/tr (wb/translator {:default-locale :fr :dict {:fr {:x {:y "Y"}}}} [:fr])} {:next "/l?page=2"})))
      "and in a language the kit does not speak")
  (is (= (str "<nav aria-label=\"Mine\" class=\"ui-pager x\" id=\"p\">" (L "/l" "prev" "Anterior") "</nav>")
         (html (ui/pager {:wb/tr (tr-for :es)} {:previous "/l" :attrs {:class "x" :aria-label "Mine" :id "p"}})))
      "attrs on the nav, its own aria-label too")
  (is (= ["web-base: link's href must be a path on this origin" #:wb{:link :href}]
         (refusal #(ui/pager {} {:previous "https://evil.test/"})))
      "its links are web-base's: an off-origin path is refused there"))

(deftest a-pager-with-no-page-either-side-is-nothing
  (is (nil? (ui/pager {:wb/tr (tr-for :es)} {:where "page 1 of 1" :label "Pages"})))
  (is (nil? (ui/pager {} {}))))

(deftest the-plugin-brings-the-kits-words-and-a-host-may-change-any
  (let [o    {:previous "/a" :next "/b" :where "page 2 of 5"}
        app  (fn [i18n] (wb/handler {:routes [["/" {:wb/layouts [shell/page] :get (fn [q] {:status 200 :body (ui/pager q o)})}]]
                                    :session {:key KEY} :plugins [(web/plugin)] :i18n i18n}))
        main #(second (re-find #"(<main[^>]*>.*</main>)" (str (:body %))))]
    (is (= (str "<main class=\"wb-main\" tabindex=\"-1\"><nav aria-label=\"Páginas\" class=\"ui-pager\">" (L "/a" "prev" "Anterior")
                "<span class=\"ui-pager__where\">page 2 of 5</span>" (L "/b" "next" "Siguiente") "</nav></main>")
           (main ((app {:default-locale :es}) (mock/request :get "/"))))
        "a host speaking Spanish: the plugin's words, merged by web-base")
    (is (str/includes? (str (:body ((wb/handler {:routes [["/" {:wb/layouts [shell/page] :get (fn [q] {:status 200 :body (ui/crumbs q {:items [[nil "x"]]})})}]]
                                                 :session {:key KEY} :plugins [(web/plugin)] :i18n {:default-locale :es}})
                                    (mock/request :get "/"))))
                       "<nav aria-label=\"Ruta de navegación\" class=\"ui-crumbs\">")
        "and the crumbs' name")
    (is (str/includes? (str (:body ((wb/handler {:routes [["/" {:wb/layouts [shell/page] :get (fn [q] {:status 200 :body (ui/secret q {:id "k" :label "K" :value "v"})})}]]
                                                 :session {:key KEY} :plugins [(web/plugin)] :i18n {:default-locale :es}})
                                    (mock/request :get "/"))))
                       "Cópialo ahora: no se volverá a mostrar.")
        "and the secret's note")
    (doseq [[component spanish] [[#(ui/close-button %) "<span class=\"ui-sr-only\">Cerrar</span>"]
                                 [#(ui/spinner %) "<span class=\"ui-sr-only\">Cargando…</span>"]]]
      (is (str/includes? (str (:body ((wb/handler {:routes [["/" {:wb/layouts [shell/page] :get (fn [q] {:status 200 :body (component q)})}]]
                                                   :session {:key KEY} :plugins [(web/plugin)] :i18n {:default-locale :es}})
                                      (mock/request :get "/"))))
                         spanish)
          (str "and the kit's word in " spanish)))
    (is (str/includes? (str (main ((app {:default-locale :es :dict {:es {:uikit-base {:next "Más"}}}}) (mock/request :get "/"))))
                       (str (L "/b" "next" "Más") "</nav>"))
        "a host's own word wins over the kit's")
    (is (= "web-base: plugins bring dictionaries for [:en :es]; the host chooses the locale with :i18n :default-locale, as in :i18n {:default-locale :en}"
           (try (app nil) nil (catch ExceptionInfo e (ex-message e))))
        "a host that brings words through the plugin chooses its locale, or the boot is refused naming the key")))

(deftest rows-are-a-list-of-rows-each-addressed-by-its-attributes
  (is (= "<ul class=\"ui-rows\"></ul>" (html (ui/rows))))
  (is (= "<ul class=\"ui-rows mine\" hx-target=\"this\" id=\"list\"><li class=\"ui-row a b\" hx-target=\"#r1\" id=\"r1\"><span>A&lt;</span>t</li><li class=\"ui-row\">plain</li></ul>"
         (html (ui/rows {:id "list" :class "mine" :hx-target "this"} (ui/row {:id "r1" :hx-target "#r1" :class ["a" :b]} [:span "A<"] "t") (ui/row "plain")))))
  (is (= "<li class=\"ui-row x\" id=\"i\">b</li>" (html (ui/row {"CLASS" "x" :ID "i"} "b"))) "attributes named as a browser reads them")
  (is (= "<ol class=\"ui-rows h\"><li class=\"ui-row\">first</li></ol>" (html (ui/rows {:ordered? true :class "h"} (ui/row "first"))))
      "rows whose order means something are an ol, the option no attribute of it")
  (is (= "<ul class=\"ui-rows ui-rows--plain\"><li class=\"ui-row\">x</li></ul>" (html (ui/rows {:plain? true} (ui/row "x")))))
  (is (= "<ol class=\"ui-rows ui-rows--plain h\" id=\"l\"><li class=\"ui-row\">x</li></ol>"
         (html (ui/rows {:plain? true :ordered? true :class "h" :id "l"} (ui/row "x"))))
      "plain rows combine with ordered and the caller's class, neither option an attribute")
  (is (= "<ul class=\"ui-rows\"><li class=\"ui-row\">x</li></ul>" (html (ui/rows {:plain? false} (ui/row "x")))) "plain only when asked")
  (is (= ["uikit-base: an attribute is named by a keyword or a string" {:attribute 1}] (refusal #(ui/row {1 2} "b")))))

(deftest a-tables-rows-take-their-own-attributes
  (is (= "<table class=\"ui-table\" id=\"t\"><tbody><tr class=\"sel\" hx-target=\"this\" id=\"row-a1\"><td>a1</td><td>b1</td></tr><tr><td>a2</td><td>b2</td></tr></tbody></table>"
         (html (ui/table {:rows [["a1" "b1"] ["a2" "b2"]] :attrs {:id "t"}
                          :row-attrs (fn [r] (when (= "a1" (first r)) {:id "row-a1" :class "sel" :hx-target "this"}))})))
      "each row its own, a row without none, the table its own")
  (is (= "<table class=\"ui-table\"><tbody><tr id=\"a1\"><th scope=\"row\">a1</th><td>b1</td></tr></tbody></table>"
         (html (ui/table {:rows [["a1" "b1"]] :row-header? true :row-attrs (fn [r] {:id (first r)})}))))
  (is (= ["uikit-base: an attribute is named by a keyword or a string" {:attribute 1}]
         (refusal #(html (ui/table {:rows [["a"]] :row-attrs (fn [_] {1 2})}))))))

(deftest a-checkbox-field-is-ticked-by-its-value-and-sends-its-own
  (let [W "<div class=\"ui-field ui-field--check\">" LB "<label class=\"ui-field__label\" for=\"ok\">Agree</label>"
        f #(html (ui/field req (merge {:id "ok" :label "Agree" :type "checkbox"} %)))]
    (is (= (str W "<input class=\"ui-check\" id=\"ok\" name=\"ok\" type=\"checkbox\" value=\"on\" />" LB "</div>") (f {})) "unticked, the box before its label")
    (is (= (f {}) (f {:value false})) "false is unticked")
    (is (= (str W "<input checked=\"checked\" class=\"ui-check\" id=\"ok\" name=\"ok\" type=\"checkbox\" value=\"on\" />" LB "</div>") (f {:value true})) "ticked")
    (is (= (f {:value true}) (f {:value "on"})) "a refused form's \"on\" is ticked")
    (is (= (str W "<input checked=\"checked\" class=\"ui-check x\" id=\"ok\" name=\"ok\" type=\"checkbox\" value=\"yes\" />" LB "</div>")
           (f {:value true :attrs {:value "yes" :class "x"}}))
        "what it sends is the caller's")
    (is (str/includes? (f {:attrs {:type "text"}}) "type=\"checkbox\"") "and it stays a checkbox")
    (is (= (str W "<input aria-describedby=\"ok-error o\" aria-invalid=\"true\" class=\"ui-check\" id=\"ok\" name=\"ok\" type=\"checkbox\" value=\"on\" />" LB
                "<span class=\"ui-field__error\" id=\"ok-error\">tr::e:[]</span></div>")
           (f {:error :e :attrs {:aria-invalid "false" :aria-describedby "o"}}))
        "a refused box is marked and described like any field, the caller's description after the error's")))

(deftest an-inline-field-lays-its-label-and-control-on-one-line
  (is (= "<div class=\"ui-field ui-field--inline\"><label class=\"ui-field__label\" for=\"q\">Q</label><input class=\"ui-input\" id=\"q\" name=\"q\" type=\"text\" /></div>"
         (html (ui/field req {:id "q" :label "Q" :inline? true}))))
  (is (str/starts-with? (html (ui/field req {:id "q" :label "Q" :inline? false})) "<div class=\"ui-field\"><label") "not unless asked")
  (is (str/starts-with? (html (ui/field req {:id "c" :label "C" :type "checkbox" :inline? true})) "<div class=\"ui-field ui-field--check ui-field--inline\">")))

(deftest a-badge-has-its-kinds-class-and-neutral-has-none-of-its-own
  (is (= "<span class=\"ui-badge\">Open</span>" (html (ui/badge :neutral "Open"))))
  (doseq [k [:success :danger :warning :info]]
    (is (= (str "<span class=\"ui-badge ui-badge--" (name k) "\">Open</span>") (html (ui/badge k "Open"))) (name k)))
  (is (= "<span class=\"ui-badge ui-badge--success mine\" id=\"b\" title=\"t\">Paid<b>!&lt;</b></span>"
         (html (ui/badge :success {:class "mine" :id "b" :title "t"} "Paid" [:b "!<"]))))
  (doseq [k [:bad nil "success" :ok :primary]]
    (is (= ["uikit-base: a badge is :neutral, :success, :danger, :warning or :info" {:kind k}] (refusal #(ui/badge k "x"))) (pr-str k))))

(deftest a-card-is-a-surface-around-its-body
  (is (= "<div class=\"ui-card\"><h2>T</h2>body&lt;</div>" (html (ui/card [:h2 "T"] "body<"))))
  (is (= "<div class=\"ui-card mine\" id=\"c\"><p>x</p></div>" (html (ui/card {:class "mine" :id "c"} [:p "x"])))))

(defn- radio [opts] (html (ui/field req (merge {:id "r" :label "R" :type "radio" :options [[1 "One"] [2 "Two"] ["3" "Three"]]} opts))))
(defn- R [i v l attrs] (str "<label class=\"ui-field__choice\"><input " attrs "class=\"ui-check\" id=\"r-" i "\" name=\"r\" type=\"radio\" value=\"" v "\" />" l "</label>"))

(deftest a-radio-group-is-a-fieldset-named-by-its-legend-and-every-radio-is-wired
  (let [w "aria-describedby=\"r-error r-hint\" aria-invalid=\"true\" "]
    (is (= (str "<fieldset class=\"ui-field ui-field--group\"><legend class=\"ui-field__label\">R<span aria-hidden=\"true\" class=\"ui-field__req\">*</span></legend>"
                "<span class=\"ui-field__hint\" id=\"r-hint\">H</span>"
                "<label class=\"ui-field__choice\"><input " w "class=\"ui-check\" id=\"r-0\" name=\"r\" required=\"required\" type=\"radio\" value=\"1\" />One</label>"
                "<label class=\"ui-field__choice\"><input " w "checked=\"checked\" class=\"ui-check\" id=\"r-1\" name=\"r\" required=\"required\" type=\"radio\" value=\"2\" />Two</label>"
                "<label class=\"ui-field__choice\"><input " w "class=\"ui-check\" id=\"r-2\" name=\"r\" required=\"required\" type=\"radio\" value=\"3\" />Three</label>"
                "<span class=\"ui-field__error\" id=\"r-error\">tr::e:[]</span></fieldset>")
           (radio {:value "2" :error :e :hint "H" :required? true}))
        "one name, the value's radio checked, each refused, described and required")
    (is (= (str "<fieldset class=\"ui-field ui-field--group\"><legend class=\"ui-field__label\">R</legend>"
                (R 0 1 "One" "") (R 1 2 "Two" "") (R 2 3 "Three" "") "</fieldset>")
           (radio {}))
        "a plain group")))

(deftest a-radio-is-checked-by-its-value-as-text-and-the-caller-cannot-break-the-group
  (doseq [[value checked] [["2" "value=\"2\""] [2 "value=\"2\""] [3 "value=\"3\""] ["3" "value=\"3\""]]]
    (let [h (radio {:value value})]
      (is (= [checked] (map second (re-seq #"checked=\"checked\" class=\"ui-check\" id=\"r-\d\" name=\"r\" type=\"radio\" (value=\"\d\")" h)))
          (str (pr-str value) ": exactly one checked, compared as text"))))
  (is (not (str/includes? (html (ui/field req {:id "r" :label "R" :type "radio" :options [["" "None"] ["a" "A"]] :value nil})) "checked"))
      "no value, nothing checked: not even the option whose value is empty")
  (is (= (str "<fieldset class=\"ui-field ui-field--group\"><legend class=\"ui-field__label\">R</legend>"
              "<label class=\"ui-field__choice\"><input checked=\"checked\" class=\"ui-check k\" id=\"b-0\" name=\"x\" type=\"radio\" value=\"3\" />T</label>"
              "<label class=\"ui-field__choice\"><input class=\"ui-check k\" id=\"b-1\" name=\"x\" type=\"radio\" value=\"4\" />U</label></fieldset>")
         (html (ui/field req {:id "r" :label "R" :type "radio" :options [[3 "T"] [4 "U"]] :value "3"
                              :attrs {:id "b" :value "z" :checked true :type "text" :name "x" :class "k"}})))
      "the caller's attrs go on each radio; its name names the group, its id their base; never their type, value or checked"))

(deftest a-plain-field-is-the-hiccup-0-1-0-answered
  ;; Hosts compare a field's Hiccup, not only its HTML (the template's views_test does):
  ;; a plain one keeps 0.1.0's data, the wrapper's class in its tag. Each literal was
  ;; printed by v0.1.0 itself (459d683), run from a worktree, never by this code.
  (is (= [:div.ui-field [:label.ui-field__label {:for "a"} "A"] [:input {:type "text" :value nil :id "a" :name "a" :class "ui-input"}] nil]
         (ui/field req {:id "a" :label "A"})))
  (is (= [:div.ui-field [:label.ui-field__label {:for "a"} "A"] [:input {:type "text", :value nil, :id "a", :name "a", :class "ui-input", :aria-invalid "true", :aria-describedby "a-error"}] [:span.ui-field__error {:id "a-error"} "tr::e:[]"]]
         (ui/field req {:id "a" :label "A" :error :e})))
  (is (= [:div.ui-field [:label.ui-field__label {:for "s"} "S"] [:select {:id "s", :name "s", :class "ui-input"} [:option {:value "1"} "One"] [:option {:value "2", :selected true} "Two"]] nil]
         (ui/field req {:id "s" :label "S" :type "select" :value "2" :options [["1" "One"] ["2" "Two"]]})))
  (is (= [:div.ui-field [:label.ui-field__label {:for "t"} "T"] [:textarea {:id "t", :name "t", :class "ui-input"} "x"] nil]
         (ui/field req {:id "t" :label "T" :type "textarea" :value "x"}))))

(deftest a-hint-describes-its-control-and-a-required-one-says-so-twice
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"e\">E<span aria-hidden=\"true\" class=\"ui-field__req\">*</span></label>"
              "<span class=\"ui-field__hint\" id=\"e-hint\">H</span>"
              "<input aria-describedby=\"e-error e-hint other\" aria-invalid=\"true\" class=\"ui-input\" id=\"e\" name=\"e\" required=\"required\" type=\"text\" />"
              "<span class=\"ui-field__error\" id=\"e-error\">tr::e:[]</span></div>")
         (html (ui/field req {:id "e" :label "E" :error :e :hint "H" :required? true :attrs {:aria-describedby "other"}})))
      "the error, then the hint, then the caller's; required on the control, the asterisk hidden from a reader")
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"b\">A</label><span class=\"ui-field__hint\" id=\"b-hint\"><em>a&lt;b</em></span>"
              "<input aria-describedby=\"b-hint\" class=\"ui-input\" id=\"b\" name=\"a\" type=\"text\" /></div>")
         (html (ui/field req {:id "a" :label "A" :hint [:em "a<b"] :attrs {:id "b"}})))
      "a hint is the view's markup, escaped, and named after the control it describes")
  (is (= (str "<div class=\"ui-field ui-field--check\"><input aria-describedby=\"c-error c-hint\" aria-invalid=\"true\" class=\"ui-check\" id=\"c\" name=\"c\" required=\"required\" type=\"checkbox\" value=\"on\" />"
              "<label class=\"ui-field__label\" for=\"c\">C<span aria-hidden=\"true\" class=\"ui-field__req\">*</span></label>"
              "<span class=\"ui-field__hint\" id=\"c-hint\">H</span><span class=\"ui-field__error\" id=\"c-error\">tr::e:[]</span></div>")
         (html (ui/field req {:id "c" :label "C" :type "checkbox" :hint "H" :required? true :error :e})))
      "a box: the box, its label, the hint, the error")
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"r\">R<span aria-hidden=\"true\" class=\"ui-field__req\">*</span></label>"
              "<input class=\"ui-input\" id=\"r\" name=\"r\" required=\"required\" type=\"text\" /></div>")
         (html (ui/field req {:id "r" :label "R" :required? true :attrs {:required false}})))
      "no attribute makes a required field optional")
  (is (= "e-error" (get-in (ui/field req {:id "e" :label "E" :error :e}) [2 1 :aria-describedby])) "no hint, no hint id"))

;; --- K9: buttons ------------------------------------------------------------------------

(deftest outline-link-and-block-buttons-carry-their-classes
  (doseq [v [:primary :secondary :success :danger :warning :info]]
    (is (= (str "<button class=\"ui-btn ui-btn--outline-" (name v) "\" type=\"submit\">Go</button>")
           (html (ui/button {:variant (keyword (str "outline-" (name v)))} "Go")))
        (str "outline-" (name v))))
  (is (= "<button class=\"ui-btn ui-btn--link\" type=\"submit\">Go</button>" (html (ui/button {:variant :link} "Go")))
      "a button that reads as a link is still a button")
  (is (= "<button class=\"ui-btn ui-btn--primary ui-btn--lg ui-btn--block x\" type=\"submit\">&lt;Go&gt;</button>"
         (html (ui/button {:variant :primary :size :lg :block? true :class "x"} "<Go>")))
      "block after the variant and size, the caller's class last; block? is no attribute")
  (is (= "<button class=\"ui-btn\" type=\"submit\">Go</button>" (html (ui/button {:block? false} "Go"))) "not block unless asked")
  (is (= (str/replace (L "/a" nil "Go") "<a href" "<a class=\"ui-btn ui-btn--outline-info ui-btn--block\" href")
         (html (ui/link-button "/a" {:variant :outline-info :block? true} "Go")))
      "a link-button takes them alike")
  (doseq [v [:outline :outline-huge :outline-link "outline-primary"]]
    (is (= ["uikit-base: a button's :variant is one of the kit's" {:variant v}] (refusal #(ui/button {:variant v} "x"))) (pr-str v))))

(deftest a-toggle-button-says-its-state-and-no-attribute-changes-it
  (is (= (str "<form action=\"/s/mute\" class=\"ui-action\" method=\"post\">" TOKEN "<input name=\"on\" type=\"hidden\" value=\"false\" />"
              "<button aria-pressed=\"true\" class=\"ui-btn ui-btn--sm\" id=\"m\" type=\"submit\">Mute &lt;all&gt;<span class=\"ui-sr-only\"> Ada</span></button></form>")
         (html (ui/toggle-button req "/s/mute" {:label "Mute <all>" :pressed? true :hidden {:on false} :subject "Ada" :size :sm
                                                :button {:id "m" "ARIA-PRESSED" "false" :aria-pressed "mixed"}})))
      "pressed, as the server drew it, whatever the caller's spelling of aria-pressed")
  (is (= (str "<form action=\"/s\" class=\"ui-action\" method=\"post\">" TOKEN "<button aria-pressed=\"false\" class=\"ui-btn\" type=\"submit\">Mute</button></form>")
         (html (ui/toggle-button req "/s" {:label "Mute" :pressed? false}))))
  (doseq [p [nil "true" 1 :on]]
    (is (= ["uikit-base: a toggle-button's :pressed? is true or false" {:pressed? p}] (refusal #(ui/toggle-button req "/s" {:label "M" :pressed? p}))) (pr-str p)))
  (doseq [l [nil " " :mute]]
    (is (= ["uikit-base: a toggle-button's label is the text its button shows" {:label l}] (refusal #(ui/toggle-button req "/s" {:label l :pressed? true}))) (pr-str l)))
  (is (= ["uikit-base: a toggle-button takes no :variant: its pressed look is the kit's" {:variant :primary}]
         (refusal #(ui/toggle-button req "/s" {:label "M" :pressed? true :variant :primary}))))
  (is (= "uikit-base: an action's button takes no type, name, value, variant or size of its own"
         (first (refusal #(ui/toggle-button req "/s" {:label "M" :pressed? true :button {:type "button"}}))))
      "action's refusals hold"))

(deftest a-button-group-is-a-named-group-whose-role-and-name-are-its-own
  (is (= "<div aria-label=\"Lesson &lt;tools&gt;\" class=\"ui-btn-group x\" id=\"g\" role=\"group\"><button class=\"ui-btn\" type=\"submit\">A</button><b>B</b></div>"
         (html (ui/button-group {:label "Lesson <tools>" :class "x" :id "g" :role "toolbar" "ARIA-LABEL" "other"} (ui/button "A") [:b "B"]))))
  (doseq [l [nil "" "  " :k]]
    (is (= ["uikit-base: a button-group is named by its label" {:label l}] (refusal #(ui/button-group {:label l} (ui/button "A")))) (pr-str l))))

(deftest a-close-button-is-a-hidden-mark-named-in-the-readers-language
  (is (= "<button class=\"ui-close\" type=\"button\"><span aria-hidden=\"true\">×</span><span class=\"ui-sr-only\">Close</span></button>"
         (html (ui/close-button {})))
      "with no translator, the kit's English")
  (is (= "<button class=\"ui-close\" type=\"button\"><span aria-hidden=\"true\">×</span><span class=\"ui-sr-only\">Cerrar</span></button>"
         (html (ui/close-button {:wb/tr (tr-for :es)})))
      "in Spanish, through the plugin's dictionary")
  (is (= "<button class=\"ui-close x\" id=\"c\" type=\"submit\"><span aria-hidden=\"true\">×</span><span class=\"ui-sr-only\">Dismiss &lt;it&gt;</span></button>"
         (html (ui/close-button {:wb/tr (tr-for :es)} {:label "Dismiss <it>" :class "x" :id "c" :type "submit"})))
      "its own label wins, and the caller's type")
  (is (= (html (ui/close-button {})) (html (ui/close-button {} {:label " "}))) "a blank label is none")
  (is (= "uikit-base: a close-button takes no formaction: a submitter's own target would send its form, token and all, where the form does not"
         (first (refusal #(ui/close-button {} {"FormAction" "/x"}))))))

;; --- K10: forms -------------------------------------------------------------------------

(deftest a-fields-size-is-a-class-of-its-control-and-only-a-text-control-takes-one
  (is (= "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"q\">Q</label><input class=\"ui-input ui-input--sm\" id=\"q\" name=\"q\" type=\"text\" /></div>"
         (html (ui/field req {:id "q" :label "Q" :size :sm}))))
  (is (str/includes? (html (ui/field req {:id "s" :label "S" :type "select" :size :lg :options [["a" "A"]] :attrs {:class "x"}}))
                     "<select class=\"ui-input ui-input--lg x\" id=\"s\" name=\"s\">")
      "a select too, the caller's class after the kit's")
  (is (= [:div.ui-field [:label.ui-field__label {:for "a"} "A"] [:input {:type "text" :value nil :id "a" :name "a" :class "ui-input"}] nil]
         (ui/field req {:id "a" :label "A" :size nil}))
      "no size, the Hiccup 0.1.0 answered")
  (doseq [z [:md "sm" 1]]
    (is (= ["uikit-base: a field's :size is :sm or :lg" {:size z}] (refusal #(ui/field req {:id "q" :label "Q" :size z}))) (pr-str z)))
  (doseq [t ["checkbox" "switch" "radio" "range"]]
    (is (= ["uikit-base: a field's :size sizes a text control, a select or a textarea" {:type t}]
           (refusal #(ui/field req {:id "q" :label "Q" :type t :size :sm :options [["a" "A"]]}))) t)))

(deftest a-switch-is-a-checkbox-read-as-on-or-off
  (is (= (str "<div class=\"ui-field ui-field--check\"><input checked=\"checked\" class=\"ui-check ui-switch x\" id=\"n\" name=\"n\" role=\"switch\" type=\"checkbox\" value=\"on\" />"
              "<label class=\"ui-field__label\" for=\"n\">Notify &lt;me&gt;</label></div>")
         (html (ui/field req {:id "n" :label "Notify <me>" :type "switch" :value true :attrs {:class "x" "ROLE" "checkbox" :type "text"}})))
      "ticked by its value, a switch and a checkbox whatever the attrs say")
  (is (= (str "<div class=\"ui-field ui-field--check\"><input aria-describedby=\"n-error\" aria-invalid=\"true\" class=\"ui-check ui-switch\" id=\"n\" name=\"n\" role=\"switch\" type=\"checkbox\" value=\"on\" />"
              "<label class=\"ui-field__label\" for=\"n\">N</label><span class=\"ui-field__error\" id=\"n-error\">tr::e:[]</span></div>")
         (html (ui/field req {:id "n" :label "N" :type "switch" :error :e})))
      "off, refused like any field"))

(deftest a-range-is-a-slider-whose-bounds-are-its-attributes
  (is (= "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"v\">Volume</label><input class=\"ui-range\" id=\"v\" max=\"10\" min=\"0\" name=\"v\" step=\"2\" type=\"range\" value=\"4\" /></div>"
         (html (ui/field req {:id "v" :label "Volume" :type "range" :value 4 :attrs {:min 0 :max 10 :step 2}})))))

(deftest an-input-group-joins-text-and-buttons-to-one-control
  (is (= "<div class=\"ui-input-group g\" id=\"i\"><span class=\"ui-input-group__text\">€&lt;</span><input name=\"p\" /><button class=\"ui-btn\" type=\"submit\">Go</button></div>"
         (html (ui/input-group {:before "€<" :after (ui/button "Go") :attrs {:class "g" :id "i"}} [:input {:name "p"}])))
      "text as the group's text, escaped; a button as it is")
  (is (= "<div class=\"ui-input-group\"><input /><span class=\"ui-input-group__text\">3</span></div>" (html (ui/input-group {:after 3} [:input])))
      "a number is text; nothing before, nothing drawn there")
  (is (= (str "<div class=\"ui-field\"><label class=\"ui-field__label\" for=\"p\">Price</label><div class=\"ui-input-group\"><span class=\"ui-input-group__text\">€</span>"
              "<input aria-describedby=\"p-error\" aria-invalid=\"true\" class=\"ui-input\" id=\"p\" name=\"p\" type=\"text\" /><span class=\"ui-input-group__text\">.00</span></div>"
              "<span class=\"ui-field__error\" id=\"p-error\">tr::e:[]</span></div>")
         (html (ui/field req {:id "p" :label "Price" :before "€" :after ".00" :error :e})))
      "a field's control inside the group, its label, refusal and error its own")
  (is (= ["uikit-base: an input-group holds one control" {}] (refusal #(ui/input-group {:before "€"} nil))))
  (doseq [t ["checkbox" "switch" "radio" "range"]]
    (is (= ["uikit-base: an input-group holds a text control, a select or a file" {:type t}]
           (refusal #(ui/field req {:id "p" :label "P" :type t :after "x" :options [["a" "A"]]}))) t)))

(deftest a-floating-label-is-the-controls-label-drawn-after-it
  (is (= (str "<div class=\"ui-field ui-field--floating\"><input aria-describedby=\"e-hint\" class=\"ui-input\" id=\"e\" name=\"e\" placeholder=\"a@b.test\" type=\"email\" value=\"x&lt;\" />"
              "<label class=\"ui-field__label\" for=\"e\">Email</label><span class=\"ui-field__hint\" id=\"e-hint\">H</span></div>")
         (html (ui/field req {:id "e" :label "Email" :type "email" :value "x<" :hint "H" :floating? true :attrs {:placeholder "a@b.test"}})))
      "control first, so CSS can float the label after it; still its label for")
  (is (str/starts-with? (html (ui/field req {:id "t" :label "T" :type "textarea" :floating? true :attrs {"PLACEHOLDER" "p"}}))
                        "<div class=\"ui-field ui-field--floating\"><textarea class=\"ui-input\" id=\"t\" name=\"t\" placeholder=\"p\"></textarea><label")
      "a textarea too, its placeholder however spelt")
  (doseq [a [nil {} {:placeholder " "} {:placeholder nil}]]
    (is (= ["uikit-base: a floating label needs a placeholder: it floats when the placeholder is not shown" {:id "e"}]
           (refusal #(ui/field req {:id "e" :label "E" :floating? true :attrs a}))) (pr-str a)))
  (doseq [t ["select" "file" "checkbox" "switch" "radio" "range"]]
    (is (= ["uikit-base: a floating label floats over a text control or a textarea" {:type t}]
           (refusal #(ui/field req {:id "e" :label "E" :type t :floating? true :attrs {:placeholder "p"}}))) t))
  (is (= ["uikit-base: a floating label floats over its control alone, never an input-group" {}]
         (refusal #(ui/field req {:id "e" :label "E" :floating? true :before "€" :attrs {:placeholder "p"}})))))

;; --- K11: feedback ----------------------------------------------------------------------

(defn- dismiss [href word] (str/replace (L href nil (str "<span aria-hidden=\"true\">×</span><span class=\"ui-sr-only\">" word "</span>"))
                                        "<a href" "<a class=\"ui-close ui-alert__dismiss\" href"))

(deftest an-alert-is-a-notice-with-a-heading-and-a-link-back-to-the-page-without-it
  (is (= (str "<div class=\"ui-notice ui-notice--ok ui-alert x\" id=\"flash\" role=\"status\"><div class=\"ui-alert__body\"><p class=\"ui-alert__heading\">Saved &lt;1&gt;</p>"
              "The order <b>A1</b></div>" (dismiss "/orders" "Close") "</div>")
         (html (ui/alert {} :ok {:heading "Saved <1>" :dismiss-href "/orders" :class "x" :id "flash" :role "none"} "The order " [:b "A1"])))
      "heading, body and the way out; the caller's id and class kept, its role not")
  (is (= "<div class=\"ui-notice ui-notice--error ui-alert\" role=\"alert\"><div class=\"ui-alert__body\">No</div></div>"
         (html (ui/alert {} :error "No")))
      "a refusal is an alert; no heading, no way out, none drawn")
  (is (= (str "<div class=\"ui-notice ui-notice--info ui-alert\" role=\"status\"><div class=\"ui-alert__body\">x</div>" (dismiss "/o?tab=a&amp;b=1" "Cerrar") "</div>")
         (html (ui/alert {:wb/tr (tr-for :es)} :info {:dismiss-href "/o?tab=a&b=1"} "x")))
      "named in the reader's language")
  (is (str/includes? (html (ui/alert {} :warning {:dismiss-href "/o" :dismiss-label "Hide &"} "x")) "<span class=\"ui-sr-only\">Hide &amp;</span>"))
  (is (str/includes? (html (ui/alert {} :warning {:dismiss-href "/o" :dismiss-label " "} "x")) "<span class=\"ui-sr-only\">Close</span>") "a blank label is none")
  (is (= ["web-base: link's href must be a path on this origin" {:wb/link :href}] (refusal #(ui/alert {} :ok {:dismiss-href "https://evil.test/"} "x"))))
  (is (= ["uikit-base: an alert is :ok, :info, :warning or :error" {:kind :success}] (refusal #(ui/alert {} :success "x")))))

(deftest a-count-says-what-it-counts-and-a-corner-badge-sits-at-the-inline-end
  (is (= "<span class=\"ui-badge ui-badge--info\">3<span class=\"ui-sr-only\"> unread &lt;msgs&gt;</span></span>" (html (ui/badge :info {:label "unread <msgs>"} 3))))
  (is (= "<span class=\"ui-badge ui-badge--danger ui-badge--corner x\" id=\"b\">9+</span>" (html (ui/badge :danger {:corner? true :class "x" :id "b" :label " "} "9+")))
      "a corner badge; a blank label is none, and neither option is an attribute")
  (is (= [:span {:class "ui-badge"} "Open"] (ui/badge :neutral "Open")) "without a label, the Hiccup it was"))

(deftest a-progress-is-the-platforms-own-named-bar
  (is (= "<progress aria-label=\"Upload &lt;a&gt;\" class=\"ui-progress x\" id=\"p\" max=\"10\" value=\"4\"></progress>"
         (html (ui/progress {:label "Upload <a>" :value 4 :max 10 :attrs {:class "x" :id "p" :max 99 "VALUE" 9 :aria-label "o"}})))
      "its value, max and name are its own")
  (is (= "<progress aria-label=\"Sync\" class=\"ui-progress\" max=\"1\"></progress>" (html (ui/progress {:label "Sync" :attrs {:value 0.5}})))
      "no value, under way — and no caller's value makes it say how far")
  (is (= "<progress aria-label=\"S\" class=\"ui-progress ui-progress--striped\" max=\"1\" value=\"0.5\"></progress>" (html (ui/progress {:label "S" :value 0.5 :striped? true}))))
  (is (= "<progress aria-label=\"S\" class=\"ui-progress ui-progress--striped ui-progress--animated\" max=\"1\" value=\"1\"></progress>"
         (html (ui/progress {:label "S" :value 1 :animated? true})))
      "moving stripes are stripes")
  (doseq [l [nil "" " " :x]]
    (is (= ["uikit-base: a progress is named by its label" {:label l}] (refusal #(ui/progress {:label l :value 0}))) (pr-str l)))
  (doseq [m [0 -1 "10" ##Inf 1/2]]
    (is (= ["uikit-base: a progress's :max is a number above zero" {:max m}] (refusal #(ui/progress {:label "P" :max m}))) (pr-str m)))
  (doseq [v [-1 11 "4" ##NaN 1/3]]
    (is (= ["uikit-base: a progress's :value is a number from 0 to its :max" {:value v}] (refusal #(ui/progress {:label "P" :max 10 :value v}))) (pr-str v))))

(deftest a-meter-is-a-named-measure-within-its-range
  (is (= "<meter aria-label=\"Places &lt;taken&gt;\" class=\"ui-meter x\" high=\"18\" low=\"5\" max=\"20\" min=\"0\" optimum=\"2\" value=\"17\"></meter>"
         (html (ui/meter {:label "Places <taken>" :value 17 :max 20 :low 5 :high 18 :optimum 2 :attrs {:class "x" :high 1 "MIN" 3 :value 0}})))
      "its range, its value and its name are its own")
  (is (= "<meter aria-label=\"M\" class=\"ui-meter\" max=\"1\" min=\"0\" value=\"0.2\"></meter>" (html (ui/meter {:label "M" :value 0.2})))
      "0 to 1 unless told, and no low, high or optimum unless given")
  (is (= ["uikit-base: a meter is named by its label" {:label nil}] (refusal #(ui/meter {:value 1}))))
  (doseq [[mn mx] [[1 1] [2 1] ["0" 1] [0 ##Inf]]]
    (is (= ["uikit-base: a meter's :min is a number below its :max" {:min mn :max mx}] (refusal #(ui/meter {:label "M" :value 1 :min mn :max mx}))) (pr-str [mn mx])))
  (doseq [o [{:value nil} {:value 2} {:value -0.1} {:low 3} {:high -1} {:optimum "1"}]]
    (is (= "uikit-base: a meter's :value, :low, :high and :optimum are numbers from its :min to its :max"
           (first (refusal #(ui/meter (merge {:label "M" :value 0.5} o))))) (pr-str o)))
  (is (= ["uikit-base: a meter's :low is not above its :high" {:low 0.8 :high 0.2}] (refusal #(ui/meter {:label "M" :value 0.5 :low 0.8 :high 0.2})))))

(deftest a-spinner-and-a-placeholder-say-loading-in-words
  (is (= "<span class=\"ui-spinner x\" role=\"status\"><span class=\"ui-sr-only\">Loading…</span></span>" (html (ui/spinner {} {:class "x" :role "img"}))))
  (is (= "<span class=\"ui-spinner\" role=\"status\"><span class=\"ui-sr-only\">Cargando…</span></span>" (html (ui/spinner {:wb/tr (tr-for :es)}))))
  (is (= "<span class=\"ui-spinner\" role=\"status\"><span class=\"ui-sr-only\">Saving &lt;x&gt;</span></span>" (html (ui/spinner {} {:label "Saving <x>"}))))
  (is (= (html (ui/spinner {})) (html (ui/spinner {} {:label ""}))) "a blank label is none")
  (is (= (str "<div aria-busy=\"true\" class=\"ui-placeholders x\" id=\"p\"><span class=\"ui-sr-only\">Loading…</span>"
              "<span aria-hidden=\"true\" class=\"ui-placeholder\"></span><span aria-hidden=\"true\" class=\"ui-placeholder\"></span></div>")
         (html (ui/placeholder {} {:lines 2 :class "x" :id "p" :aria-busy "false"})))
      "bars hidden from a screen reader, which hears the word; busy whatever the caller says")
  (is (= 3 (count (re-seq #"ui-placeholder\"" (html (ui/placeholder {:wb/tr (tr-for :es)}))))) "three bars unless told")
  (is (str/includes? (html (ui/placeholder {:wb/tr (tr-for :es)})) "<span class=\"ui-sr-only\">Cargando…</span>"))
  (doseq [n [0 -1 1.5 "2" nil]]
    (is (= ["uikit-base: a placeholder's :lines is a whole number from 1" {:lines n}] (refusal #(ui/placeholder {} {:lines n}))) (pr-str n))))

;; --- K12: lists, tables, navigation -----------------------------------------------------

(defn- with-class [link cls] (str/replace link "<a href" (str "<a class=\"" cls "\" href")))

(deftest a-list-group-links-its-items-marks-the-current-one-and-holds-the-rest
  (is (= (str "<ul class=\"ui-list-group x\" id=\"l\"><li class=\"ui-list-group__item\">" (with-class (L "/a" nil "A&lt;") "ui-list-group__action") "</li>"
              "<li class=\"ui-list-group__item\">" (str/replace (with-class (L "/b" nil "B") "ui-list-group__action") "<a class" "<a aria-current=\"page\" class") "</li>"
              "<li class=\"ui-list-group__item\"><b>C</b></li></ul>")
         (html (ui/list-group {:items [["/a" "A<"] ["/b" "B"] [nil [:b "C"]]] :current "/b" :attrs {:class "x" :id "l"}})))
      "a path is a link filling its row, the current one marked; nil, the content as it is")
  (is (str/starts-with? (html (ui/list-group {:items [[nil "x"]] :flush? true})) "<ul class=\"ui-list-group ui-list-group--flush\"><li")
      "flush is a class, never an attribute")
  (is (= ["uikit-base: a list-group's items are one or more" {}] (refusal #(ui/list-group {:items []}))))
  (is (= ["uikit-base: a list-group's current is one of its items' paths" {:current "/z"}] (refusal #(ui/list-group {:items [["/a" "A"]] :current "/z"}))))
  (is (= ["uikit-base: every item of a list-group has a label" {}] (refusal #(ui/list-group {:items [["/a" " "]]}))))
  (is (= ["web-base: link's href must be a path on this origin" {:wb/link :href}] (refusal #(ui/list-group {:items [["//evil" "A"]]})))))

(deftest a-tables-variants-are-classes-of-the-table
  (is (= "<table class=\"ui-table ui-table--striped ui-table--hover ui-table--bordered ui-table--compact x\"><tbody><tr><td>a</td></tr></tbody></table>"
         (html (ui/table {:rows [["a"]] :striped? true :hover? true :bordered? true :compact? true :attrs {:class "x"}}))))
  (is (= "<table class=\"ui-table ui-table--hover\"><tbody><tr><td>a</td></tr></tbody></table>" (html (ui/table {:rows [["a"]] :hover? true :striped? false})))))

(deftest a-pager-numbers-its-pages-and-marks-the-current-one
  (let [cur #(str/replace (L %1 nil %2) "<a href" "<a aria-current=\"page\" href")]
    (is (= (str "<nav aria-label=\"Pages\" class=\"ui-pager ui-pager--sm\">" (L "/l?p=1" "prev" "Previous")
                "<ul class=\"ui-pager__pages\"><li>" (L "/l?p=1" nil "1") "</li><li>" (cur "/l?p=2" "2") "</li><li>" (L "/l?p=3" nil "3") "</li></ul>"
                (L "/l?p=3" "next" "Next") "</nav>")
           (html (ui/pager {} {:previous "/l?p=1" :next "/l?p=3" :pages [["/l?p=1" 1] ["/l?p=2" 2] ["/l?p=3" 3]] :current 2 :size :sm})))
        "the previous, the pages, the next, the current one marked"))
  (is (= (str "<nav aria-label=\"Pages\" class=\"ui-pager ui-pager--lg\"><ul class=\"ui-pager__pages\"><li>" (L "/l" nil "1") "</li></ul></nav>")
         (html (ui/pager {} {:pages [["/l" 1]] :size :lg})))
      "pages alone are a pager; no current, none marked")
  (is (= [:nav {:class "ui-pager" :aria-label "Pages"} (wb/link "/n" {:rel "prev"} "Previous") nil nil]
         (ui/pager {} {:previous "/n"}))
      "witness: without pages, the Hiccup's shape is 0.5.0's — one place for each of previous, where and next")
  (is (= ["uikit-base: a pager's current is one of its pages" {:current 4}] (refusal #(ui/pager {} {:pages [["/l" 1]] :current 4}))))
  (is (= ["uikit-base: a pager's :size is :sm or :lg" {:size :md}] (refusal #(ui/pager {} {:next "/n" :size :md}))))
  (is (= ["uikit-base: every item of a pager has a label" {}] (refusal #(ui/pager {} {:pages [["/l" nil]]}))))
  (is (= ["web-base: link's href must be a path on this origin" {:wb/link :href}] (refusal #(ui/pager {} {:pages [["https://e.test/" 1]]})))))

(deftest a-tabs-nav-may-be-pills-and-share-its-line
  (is (str/starts-with? (html (ui/tabs-nav req {:label "S" :items [["/a" "A"]] :style :pills :fill? true})) "<nav aria-label=\"S\" class=\"ui-tabs ui-tabs--pills ui-tabs--fill\"><ul>"))
  (is (str/starts-with? (html (ui/tabs-nav req {:label "S" :items [["/a" "A"]] :style :tabs :justify? true :attrs {:class "x"}})) "<nav aria-label=\"S\" class=\"ui-tabs ui-tabs--justify x\"><ul>"))
  (doseq [st [:pill "pills" :underline]]
    (is (= ["uikit-base: a tabs-nav's :style is :tabs or :pills" {:style st}] (refusal #(ui/tabs-nav req {:label "S" :items [["/a" "A"]] :style st}))) (pr-str st)))
  (is (= ["uikit-base: a tabs-nav is :fill? or :justify?, not both" {}] (refusal #(ui/tabs-nav req {:label "S" :items [["/a" "A"]] :fill? true :justify? true})))))

;; --- K13: cards and media ---------------------------------------------------------------

(deftest a-cards-image-says-what-it-shows-unless-it-says-it-shows-nothing
  (is (= (str "<div class=\"ui-card\"><img alt=\"A &lt;lake&gt;\" class=\"ui-card__img x\" height=\"90\" src=\"/l.png?a=1&amp;b=2\" width=\"160\" />"
              "<h3 class=\"ui-card__head\">T</h3>b</div>")
         (html (ui/card {:image {:src "/l.png?a=1&b=2" :alt "A <lake>" :attrs {:class "x" :width 160 :height 90 :src "/evil.png" "ALT" ""}} :title "T" :level 3} "b")))
      "the image first, edge to edge; its src and alt are its own")
  (is (str/includes? (html (ui/card {:image {:src "/d.png" :decorative? true}} "b")) "<img alt=\"\" class=\"ui-card__img\" src=\"/d.png\" />")
      "decorative, and said so: an empty alt")
  (is (= [:div {:class "ui-card"} "b"] (ui/card {:image nil :nav nil} "b")) "no image and no nav, the card it was")
  (doseq [im [{:src "/a.png"} {:src "/a.png" :alt ""} {:src "/a.png" :alt "  "} {:src "/a.png" :alt :k}]]
    (is (= ["uikit-base: a card's image has an alt that says what it shows, or is :decorative? true" {:alt (:alt im)}]
           (refusal #(ui/card {:image im} "b"))) (pr-str im)))
  (is (= ["uikit-base: a card's decorative image has an empty alt" {:alt "A lake"}] (refusal #(ui/card {:image {:src "/a.png" :alt "A lake" :decorative? true}} "b"))))
  (doseq [src [nil "" " " :a]]
    (is (= ["uikit-base: a card's image has a src" {:src src}] (refusal #(ui/card {:image {:src src :alt "a"}} "b"))) (pr-str src))))

(deftest a-cards-nav-sits-in-a-header-strip-above-its-title
  (is (= (str "<div class=\"ui-card\"><img alt=\"i\" class=\"ui-card__img\" src=\"/i.png\" /><div class=\"ui-card__nav\"><nav>n</nav></div>"
              "<h2 class=\"ui-card__head\">T</h2>b<div class=\"ui-card__foot\">f</div></div>")
         (html (ui/card {:image {:src "/i.png" :alt "i"} :nav [:nav "n"] :title "T" :footer "f"} "b")))
      "image, nav, title, body, footer; neither option an attribute"))

(deftest a-card-group-holds-cards-side-by-side
  (is (= "<div class=\"ui-card-group x\"><div class=\"ui-card\">a</div><div class=\"ui-card\">b</div></div>"
         (html (ui/card-group {:class "x"} (ui/card "a") (ui/card "b"))))))

(deftest a-figure-is-an-image-and-the-caption-that-says-what-it-is
  (is (= "<figure class=\"ui-figure x\"><img alt=\"Chart\" class=\"ui-figure__img\" src=\"/c.svg\" /><figcaption class=\"ui-figure__caption\">Sales &lt;2026&gt;</figcaption></figure>"
         (html (ui/figure {:image {:src "/c.svg" :alt "Chart"} :caption "Sales <2026>" :attrs {:class "x"}}))))
  (doseq [c [nil "" " "]]
    (is (= ["uikit-base: a figure has a caption" {}] (refusal #(ui/figure {:image {:src "/c.svg" :alt "C"} :caption c}))) (pr-str c)))
  (is (= ["uikit-base: a figure's image has an alt that says what it shows, or is :decorative? true" {:alt nil}]
         (refusal #(ui/figure {:image {:src "/c.svg"} :caption "c"})))))

(deftest a-collapse-is-a-details-whose-summary-opens-it
  (is (= "<details class=\"ui-collapse x\"><summary class=\"ui-collapse__summary\">More &lt;info&gt;</summary><div class=\"ui-collapse__body\"><p>b</p></div></details>"
         (html (ui/collapse {:summary "More <info>" :attrs {:class "x" :open true "OPEN" true}} [:p "b"])))
      "closed unless asked, whatever the attrs say")
  (is (= "<details class=\"ui-collapse\" name=\"faq\" open=\"open\"><summary class=\"ui-collapse__summary\">S</summary><div class=\"ui-collapse__body\">b</div></details>"
         (html (ui/collapse {:summary "S" :open? true :name "faq" :attrs {:name "other"}} "b")))
      "open when asked; its group's name its own")
  (doseq [n ["" " " :faq]]
    (is (= ["uikit-base: a collapse's :name is the text that joins its details into one group" {:name n}] (refusal #(ui/collapse {:summary "S" :name n} "b"))) (pr-str n)))
  (doseq [s [nil "" " "]]
    (is (= ["uikit-base: a collapse has a summary" {}] (refusal #(ui/collapse {:summary s} "b"))) (pr-str s))))

(deftest an-accordion-is-one-group-of-details-one-open-at-a-time
  (is (= (str "<div class=\"ui-accordion x\"><details class=\"ui-accordion__item\" name=\"faq\"><summary class=\"ui-accordion__summary\">A&lt;</summary><div class=\"ui-accordion__body\">a</div></details>"
              "<details class=\"ui-accordion__item\" name=\"faq\" open=\"open\"><summary class=\"ui-accordion__summary\">B</summary><div class=\"ui-accordion__body\"><p>b</p></div></details></div>")
         (html (ui/accordion {:items [["A<" "a"] ["B" [:p "b"]]] :name "faq" :open 1 :attrs {:class "x"}})))
      "one name, so the platform keeps one open; the one asked for open")
  (is (= "<div class=\"ui-accordion\"><details class=\"ui-accordion__item\"><summary class=\"ui-accordion__summary\">A</summary><div class=\"ui-accordion__body\">a</div></details></div>"
         (html (ui/accordion {:items [["A" "a"]]})))
      "no name, each opens alone; no open, all closed")
  (doseq [o [2 -1 "0" 0.0]]
    (is (= ["uikit-base: an accordion's :open is the index of one of its items" {:open o}] (refusal #(ui/accordion {:items [["A" "a"] ["B" "b"]] :open o}))) (pr-str o)))
  (is (= ["uikit-base: an accordion's items are one or more" {}] (refusal #(ui/accordion {:items []}))))
  (is (= ["uikit-base: every item of an accordion has a summary" {}] (refusal #(ui/accordion {:items [["A" "a"] [" " "b"]]}))))
  (is (= ["uikit-base: an accordion's :name is the text that joins its details into one group" {:name ""}] (refusal #(ui/accordion {:items [["A" "a"]] :name ""})))))

(deftest a-scroller-is-a-named-region-the-keyboard-reaches
  (is (= (str "<div aria-label=\"Courses &lt;new&gt;\" class=\"ui-scroller x\" id=\"s\" role=\"region\" tabindex=\"0\"><ul class=\"ui-scroller__list\">"
              "<li class=\"ui-scroller__item\">a</li><li class=\"ui-scroller__item\"><b>b</b></li></ul></div>")
         (html (ui/scroller {:label "Courses <new>" :attrs {:class "x" :id "s" :role "list" :tabindex "-1" "ARIA-LABEL" "o"}} "a" [:b "b"])))
      "its role, name and place in the tab order are its own")
  (doseq [l [nil "" " " :k]]
    (is (= ["uikit-base: a scroller is named by its label" {:label l}] (refusal #(ui/scroller {:label l} "a"))) (pr-str l))))

(deftest a-blank-label-of-the-callers-never-leaves-a-kit-component-unnamed
  (let [r {:wb/tr (fn [k _] (str "tr:" k))}
        pager (html (ui/pager r {:previous "/l?p=1" :next "/l?p=3" :label "" :previous-label " " :next-label ""}))
        crumbs (html (ui/crumbs r {:label "  " :items [[nil "H"]]}))]
    (is (not (str/includes? (str pager crumbs) "aria-label=\"\"")) "no landmark is named by nothing")
    (is (str/includes? pager "aria-label=\"tr::uikit-base/pages\"") "the pager's name is the kit's word")
    (is (str/includes? pager ">tr::uikit-base/previous</a>") "the previous link reads the kit's word")
    (is (str/includes? pager ">tr::uikit-base/next</a>") "the next link reads the kit's word")
    (is (str/includes? crumbs "aria-label=\"tr::uikit-base/breadcrumbs\"") "the crumbs' name is the kit's word")))

;; --- K16: dialogs -----------------------------------------------------------------------

(def ^:private close-en "<span aria-hidden=\"true\">×</span><span class=\"ui-sr-only\">Close</span></button>")

(deftest a-dialog-is-a-modal-named-by-its-title-that-its-close-button-closes
  (is (= (str "<dialog aria-labelledby=\"edit-title\" class=\"ui-dialog x\" closedby=\"any\" id=\"edit\"><div class=\"ui-dialog__head\">"
              "<h2 class=\"ui-dialog__title\" id=\"edit-title\">Edit &lt;it&gt;</h2>"
              "<button class=\"ui-close\" command=\"close\" commandfor=\"edit\" type=\"button\">" close-en "</div>"
              "<div class=\"ui-dialog__body\"><p>body</p></div><div class=\"ui-dialog__foot\"><b>f</b></div></dialog>")
         (html (ui/dialog {} {:id "edit" :title "Edit <it>" :footer [:b "f"] :attrs {:class "x" :open true "ID" "o" :aria-labelledby "o"}} [:p "body"])))
      "named by its heading, closed by its button, Esc or the backdrop; never drawn open, its id and name its own")
  (is (= (str "<dialog aria-labelledby=\"d-title\" class=\"ui-dialog ui-dialog--lg ui-dialog--fullscreen ui-dialog--scrollable\" closedby=\"none\" id=\"d\">"
              "<div class=\"ui-dialog__head\"><h2 class=\"ui-dialog__title\" id=\"d-title\">T</h2>"
              "<button class=\"ui-close\" command=\"close\" commandfor=\"d\" type=\"button\">" close-en "</div>"
              "<div class=\"ui-dialog__body\" tabindex=\"0\">b</div></dialog>")
         (html (ui/dialog {} {:id "d" :title "T" :size :lg :fullscreen? true :scrollable? true :static? true :attrs {:closedby "any"}} "b")))
      "static: neither Esc nor the backdrop closes it, whatever the attrs say; a scrolling body the keyboard reaches")
  (is (str/includes? (html (ui/dialog {:wb/tr (tr-for :es)} {:id "d" :title "T"})) "<span class=\"ui-sr-only\">Cerrar</span>")
      "its close button speaks the reader's language")
  (doseq [id [nil "" "two words" "1d" :d "a\"b"]]
    (is (= ["uikit-base: a dialog's :id is one word, a letter first, that an element carries as its id" {:id id}]
           (refusal #(ui/dialog {} {:id id :title "T"}))) (pr-str id)))
  (doseq [t [nil "" "  "]]
    (is (= ["uikit-base: a dialog has a title, which names it" {:id "d"}] (refusal #(ui/dialog {} {:id "d" :title t}))) (pr-str t)))
  (is (= ["uikit-base: a dialog's :size is :sm or :lg" {:size :md}] (refusal #(ui/dialog {} {:id "d" :title "T" :size :md}))))
  (doseq [f [nil "" " " [] ()]]
    (is (not (str/includes? (html (ui/dialog {} {:id "d" :title "T" :footer f} "b")) "ui-dialog__foot")) (str "a footer of nothing draws no foot " (pr-str f))))
  (is (str/includes? (html (ui/dialog {} {:id "d" :title "T" :footer 0} "b")) "<div class=\"ui-dialog__foot\">0</div>") "witness: a footer of something is drawn"))

(deftest a-dialog-button-opens-its-dialog-by-the-platforms-invoker-command
  (is (= "<button class=\"ui-btn ui-btn--primary ui-btn--sm x\" command=\"show-modal\" commandfor=\"edit\" type=\"button\">Open <b>it</b></button>"
         (html (ui/dialog-button {:dialog "edit" :variant :primary :size :sm :class "x" :command "close" "COMMANDFOR" "other" :type "submit"} "Open " [:b "it"])))
      "its target, command and type are its own")
  (is (= ["uikit-base: a dialog-button's :dialog is one word, a letter first, that an element carries as its id" {:dialog nil}]
         (refusal #(ui/dialog-button {} "Open"))))
  (is (= ["uikit-base: a button's :variant is one of the kit's" {:variant :huge}] (refusal #(ui/dialog-button {:dialog "d" :variant :huge} "x")))))

;; --- K17: offcanvas, dropdown, popover, tooltip -------------------------------------------

(deftest an-offcanvas-is-a-dialog-drawn-along-one-side-whose-body-scrolls
  (is (= (str "<dialog aria-labelledby=\"nav-title\" class=\"ui-dialog ui-dialog--scrollable ui-offcanvas ui-offcanvas--end x\" closedby=\"any\" id=\"nav\">"
              "<div class=\"ui-dialog__head\"><h2 class=\"ui-dialog__title\" id=\"nav-title\">Menu</h2>"
              "<button class=\"ui-close\" command=\"close\" commandfor=\"nav\" type=\"button\">" close-en "</div>"
              "<div class=\"ui-dialog__body\" tabindex=\"0\"><p>b</p></div></dialog>")
         (html (ui/offcanvas {} {:id "nav" :title "Menu" :side :end :attrs {:class "x"}} [:p "b"]))))
  (is (str/includes? (html (ui/offcanvas {} {:id "nav" :title "Menu"} "b")) "class=\"ui-dialog ui-dialog--scrollable ui-offcanvas ui-offcanvas--start\"")
      "the inline start unless told")
  (is (str/includes? (html (ui/offcanvas {} {:id "nav" :title "Menu" :static? true} "b")) "closedby=\"none\"") "static as a dialog is")
  (doseq [side [:left :right "end"]]
    (is (= ["uikit-base: an offcanvas's :side is :start or :end" {:side side}] (refusal #(ui/offcanvas {} {:id "n" :title "M" :side side}))) (pr-str side)))
  (is (= ["uikit-base: an offcanvas has a title, which names it" {:id "n"}] (refusal #(ui/offcanvas {} {:id "n"})))))

(def ^:private nav-attrs "hx-push-url=\"true\" hx-select=\"main\" hx-swap=\"outerHTML show:top showTarget:body\" hx-target=\"main\"")

(deftest a-dropdown-is-a-button-and-a-popover-of-links-and-actions
  (is (= (str "<div class=\"ui-dropdown x\"><button class=\"ui-btn ui-btn--secondary ui-btn--sm\" popovertarget=\"acts\" type=\"button\">Actions &lt;x&gt;</button>"
              "<div class=\"ui-dropdown__menu\" id=\"acts\" popover=\"auto\"><ul class=\"ui-dropdown__items\">"
              "<li><a class=\"ui-dropdown__item\" href=\"/a\" hx-get=\"/a\" " nav-attrs ">Edit &lt;it&gt;</a></li><li><b>act</b></li></ul></div></div>")
         (html (ui/dropdown {:id "acts" :label "Actions <x>" :variant :secondary :size :sm :attrs {:class "x"} :items [["/a" "Edit <it>"] [nil [:b "act"]]]})))
      "a link swaps <main> as every link does; a nil path holds its content; no role=menu")
  (is (= ["uikit-base: a dropdown's items are one or more" {}] (refusal #(ui/dropdown {:id "m" :label "M" :items []}))))
  (is (= ["uikit-base: every item of a dropdown has a label" {}] (refusal #(ui/dropdown {:id "m" :label "M" :items [["/a" " "]]}))))
  (is (= ["uikit-base: a dropdown's label is the text its button shows" {:label ""}] (refusal #(ui/dropdown {:id "m" :label "" :items [["/a" "A"]]}))))
  (is (= ["uikit-base: a dropdown's :id is one word, a letter first, that an element carries as its id" {:id "a b"}]
         (refusal #(ui/dropdown {:id "a b" :label "M" :items [["/a" "A"]]})))))

(deftest a-popover-is-a-button-and-the-box-it-opens
  (is (= (str "<div class=\"ui-popover-wrap\" id=\"w\"><button class=\"ui-btn\" popovertarget=\"p\" type=\"button\">Info</button>"
              "<div class=\"ui-popover\" id=\"p\" popover=\"auto\"><p class=\"ui-popover__head\">About &lt;it&gt;</p><p>b</p></div></div>")
         (html (ui/popover {:id "p" :label "Info" :title "About <it>" :attrs {:id "w"}} [:p "b"]))))
  (is (= "<div class=\"ui-popover-wrap\"><button class=\"ui-btn\" popovertarget=\"p\" type=\"button\">Info</button><div class=\"ui-popover\" id=\"p\" popover=\"auto\">b</div></div>"
         (html (ui/popover {:id "p" :label "Info" :title " "} "b")))
      "a blank title draws no head")
  (is (= ["uikit-base: a popover's label is the text its button shows" {:label nil}] (refusal #(ui/popover {:id "p"} "b")))))

(deftest a-tooltip-describes-its-trigger-with-the-servers-text
  (is (= (str "<span class=\"ui-tooltip-wrap\"><button aria-describedby=\"tip hint\" class=\"ui-btn y\" type=\"button\">Save</button>"
              "<span class=\"ui-tooltip\" id=\"tip\" popover=\"manual\" role=\"tooltip\">Saves &lt;now&gt;</span></span>")
         (html (ui/tooltip {:id "tip" :text "Saves <now>"} (ui/button {:type "button" :aria-describedby "hint tip" :class "y"} "Save"))))
      "its text first among the trigger's descriptions, once")
  (is (= "<span class=\"ui-tooltip-wrap\"><a aria-describedby=\"tip\" href=\"/x\">x</a><span class=\"ui-tooltip\" id=\"tip\" popover=\"manual\" role=\"tooltip\">T</span></span>"
         (html (ui/tooltip {:id "tip" :text "T"} [:a {"HREF" "/x"} "x"]))))
  (is (= ["uikit-base: a tooltip's :text is what it says about its trigger" {:text " "}] (refusal #(ui/tooltip {:id "t" :text " "} [:a "x"]))))
  (doseq [t ["x" nil [[:a "x"]]]]
    (is (= ["uikit-base: a tooltip's trigger is one element, which it describes" {}] (refusal #(ui/tooltip {:id "t" :text "T"} t))) (pr-str t)))
  (doseq [id [nil "" "a b" "1t" :t]]
    (is (= ["uikit-base: a tooltip's :id is one word, a letter first, that an element carries as its id" {:id id}]
           (refusal #(ui/tooltip {:id id :text "T"} [:button "x"]))) (pr-str id)))
  (doseq [[t el] [[[:a "x"] :a] [[:span "x"] :span] [[:div.icon {:class "y"} "x"] :div] [[:img {:src "/i.png" :alt "i"}] :img]]]
    (is (= ["uikit-base: a tooltip's trigger takes the focus: a button, a link with an href, a form control, a summary, or an element with a tabindex" {:trigger el}]
           (refusal #(ui/tooltip {:id "t" :text "T"} t))) (pr-str t)))
  (doseq [t [[:button "x"] [:a {:href "/x"} "x"] [:input {:type "text"}] [:select] [:textarea] [:summary "s"] [:span {"TABINDEX" "0"} "i"] [:button#b.c "x"]]]
    (is (str/includes? (html (ui/tooltip {:id "t" :text "T"} t)) "aria-describedby=\"t\"") (str "a trigger that takes the focus is described " (pr-str t)))))

;; --- K18: toasts and carousels -----------------------------------------------------------

(deftest toasts-are-a-polite-live-region-and-a-toast-says-its-message-with-a-way-out
  (is (= (str "<div aria-live=\"polite\" class=\"ui-toasts x\"><div class=\"ui-toast ui-toast--ok\" data-timeout-ms=\"4000\" id=\"t1\">"
              "<div class=\"ui-toast__body\"><p class=\"ui-toast__heading\">Saved &lt;it&gt;</p>Body</div>"
              "<button class=\"ui-close ui-toast__close\" type=\"button\">" close-en "</div></div>")
         (html (ui/toasts {:class "x" :aria-live "off"} (ui/toast {} {:kind :ok :heading "Saved <it>" :timeout-ms 4000 :attrs {:id "t1" "DATA-TIMEOUT-MS" 1}} "Body"))))
      "the region is live whatever the attrs say; the timeout is the toast's own, a number")
  (is (= "<div aria-live=\"polite\" class=\"ui-toasts\"></div>" (html (ui/toasts))) "on the page before any toast")
  (is (= (str "<div class=\"ui-toast\"><div class=\"ui-toast__body\">B</div><button class=\"ui-close ui-toast__close\" type=\"button\">"
              "<span aria-hidden=\"true\">×</span><span class=\"ui-sr-only\">Cerrar</span></button></div>")
         (html (ui/toast {:wb/tr (tr-for :es)} {:heading " "} "B")))
      "no kind, no timeout, no blank heading; its close button in the reader's language")
  (is (= (str "<div class=\"ui-toast\" id=\"t\"><div class=\"ui-toast__body\">x</div><button class=\"ui-close ui-toast__close\" type=\"button\">" close-en "</div>")
         (html (ui/toast {} {:attrs {:id "t" :data-timeout-ms 10 "data-TIMEOUT-ms" 20}} "x")))
      "a host's own data-timeout-ms, without :timeout-ms, gives the toast no timeout")
  (doseq [k [:success "ok" :danger]]
    (is (= ["uikit-base: a toast's :kind is :ok, :info, :warning or :error" {:kind k}] (refusal #(ui/toast {} {:kind k} "x"))) (pr-str k)))
  (doseq [t [0 -1 1.5 "5000" 5000N]]
    (is (= ["uikit-base: a toast's :timeout-ms is a whole number of milliseconds above zero" {:timeout-ms t}] (refusal #(ui/toast {} {:timeout-ms t} "x"))) (pr-str t))))

(deftest a-carousel-is-a-named-region-of-numbered-slides-whose-buttons-wait-for-the-script
  (is (= (str "<section aria-label=\"Featured &lt;new&gt;\" aria-roledescription=\"carousel\" class=\"ui-carousel x\" data-interval-ms=\"5000\">"
              "<div class=\"ui-carousel__controls\" hidden=\"hidden\"><button class=\"ui-btn ui-btn--sm ui-carousel__rotate\" type=\"button\">"
              "<span class=\"ui-carousel__stop\">Stop automatic slide show</span><span class=\"ui-carousel__start\">Start automatic slide show</span></button>"
              "<button class=\"ui-btn ui-btn--sm ui-carousel__prev\" type=\"button\">Previous slide</button>"
              "<button class=\"ui-btn ui-btn--sm ui-carousel__next\" type=\"button\">Next slide</button></div>"
              "<div aria-live=\"polite\" class=\"ui-carousel__viewport\" tabindex=\"0\">"
              "<div aria-label=\"1 of 2\" aria-roledescription=\"slide\" class=\"ui-carousel__slide\" role=\"group\">A</div>"
              "<div aria-label=\"2 of 2\" aria-roledescription=\"slide\" class=\"ui-carousel__slide\" role=\"group\"><b>B</b></div></div></section>")
         (html (ui/carousel {} {:label "Featured <new>" :slides ["A" [:b "B"]] :interval-ms 5000
                                :attrs {:class "x" "ARIA-LABEL" "o" :aria-roledescription "o" :data-interval-ms 1}})))
      "the rotation button first, then previous and next, all hidden until the script; each slide numbered")
  (let [es (html (ui/carousel {:wb/tr (tr-for :es)} {:label "Destacados" :slides ["A" "B" "C"]}))]
    (is (not (str/includes? es "ui-carousel__rotate")) "no interval, no rotation button")
    (is (not (str/includes? es "data-interval-ms")) "and no interval")
    (doseq [w ["aria-roledescription=\"carrusel\"" "aria-label=\"3 de 3\"" "aria-roledescription=\"diapositiva\"" ">Diapositiva anterior<" ">Diapositiva siguiente<"]]
      (is (str/includes? es w) (str "in Spanish: " w))))
  (is (str/includes? (html (ui/carousel {:wb/tr (tr-for :es)} {:label "D" :slides ["A"] :interval-ms 334})) ">Detener el pase automático</span><span class=\"ui-carousel__start\">Iniciar el pase automático<")
      "the rotation's two labels in Spanish")
  (is (str/includes? (html (ui/carousel {:wb/tr (fn [k args] (str "tr:" k ":" (pr-str args)))} {:label "D" :slides ["A" "B"]})) "aria-label=\"tr::uikit-base/slide-of:[2 2]\"")
      "a slide's number goes to the translator as the word's two arguments")
  (doseq [l [nil "" " "]]
    (is (= ["uikit-base: a carousel is named by its label" {:label l}] (refusal #(ui/carousel {} {:label l :slides ["A"]}))) (pr-str l)))
  (is (= ["uikit-base: a carousel's slides are one or more" {}] (refusal #(ui/carousel {} {:label "C" :slides []}))))
  (doseq [i [0 333 2147483648 "5" 2.5 400N]]
    (is (= ["uikit-base: a carousel's :interval-ms is a whole number of milliseconds from 334 to 2147483647" {:interval-ms i}]
           (refusal #(ui/carousel {} {:label "C" :slides ["A"] :interval-ms i}))) (pr-str i)))
  (doseq [i [334 2147483647]]
    (is (str/includes? (html (ui/carousel {} {:label "C" :slides ["A"] :interval-ms i})) (str "data-interval-ms=\"" i "\""))
        (str "the edge " i " is taken: three changes a second at most, and the most a browser's timer holds")))
  (doseq [sl ["AB" [:div "x"] [:img {:src "/a.png"}] {:a 1} 7]]
    (is (= ["uikit-base: a carousel's :slides is a sequence of slides, never one element or a string" {}]
           (refusal #(ui/carousel {} {:label "C" :slides sl}))) (pr-str sl)))
  (is (str/includes? (html (ui/carousel {} {:label "C" :slides (list [:div "x"] [:div "y"])})) "aria-label=\"2 of 2\"")
      "a list of elements is a sequence of slides"))

(deftest every-word-of-the-kit-is-spoken-in-both-its-languages
  (let [ks (fn [l] (set (keys (get-in ui/words [l :uikit-base]))))]
    (is (= 14 (count (ks :en))) "witness: the kit's words, a count measured once")
    (is (= (ks :en) (ks :es)) "a word missing in Spanish would fall back to English mid-page")))

;; --- K19: scrollspy ------------------------------------------------------------------------

(deftest a-scrollspy-is-a-named-nav-of-this-pages-sections
  (is (= "<nav aria-label=\"On this page &lt;x&gt;\" class=\"ui-scrollspy x\"><ul><li><a href=\"#intro\">Intro &lt;1&gt;</a></li><li><a href=\"#usage\">Usage</a></li></ul></nav>"
         (html (ui/scrollspy {:label "On this page <x>" :items [["#intro" "Intro <1>"] ["#usage" "Usage"]] :attrs {:class "x" "ARIA-LABEL" "o"}})))
      "plain fragment links, which work without a script; its name its own; nothing marked current until the script sees a section")
  (doseq [l [nil "" " "]]
    (is (= ["uikit-base: a scrollspy is named by its label" {:label l}] (refusal #(ui/scrollspy {:label l :items [["#a" "A"]]}))) (pr-str l)))
  (is (= ["uikit-base: a scrollspy's items are one or more" {}] (refusal #(ui/scrollspy {:label "S" :items []}))))
  (is (= ["uikit-base: every item of a scrollspy has a label" {}] (refusal #(ui/scrollspy {:label "S" :items [["#a" " "]]}))))
  (doseq [f ["/page#a" "a" "#" "#1a" "#a b" "#a\"" nil]]
    (is (= ["uikit-base: a scrollspy's items are #fragments of this page, each one word, a letter first" {:items [f]}]
           (refusal #(ui/scrollspy {:label "S" :items [["#ok" "OK"] [f "X"]]}))) (pr-str f)))
  (is (= ["uikit-base: a scrollspy's items are each its own section" {}] (refusal #(ui/scrollspy {:label "S" :items [["#a" "A"] ["#a" "B"]]})))))
