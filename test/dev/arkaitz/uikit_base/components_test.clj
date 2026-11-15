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
                            (for [k [:ok :info :warning :error]] (ui/notice k "x"))
                            (ui/table {:caption "c" :head ["h"] :rows [["a" "b"]] :row-header? true})
                            (ui/pager req {:previous "/a" :next "/b"})
                            (ui/rows (ui/row "x"))
                            (ui/rows {:plain? true} (ui/row "x"))
                            (ui/field req {:id "c" :label "C" :type "checkbox" :inline? true})
                            (for [k [:neutral :success :danger :warning :info]] (ui/badge k "x"))
                            (ui/card "x")))
        classes (set (mapcat #(str/split % #" ") (map second (re-seq #"class=\"([^\"]*)\"" markup))))]
    (is (= 34 (count classes)) (str "witness: the classes emitted, a count measured once: " (sort classes)))
    (is (= [] (remove #(re-find (re-pattern (str "(?m)(^\\s*|[,{}]\\s*)\\." % "\\s*[,{>]")) kit) classes))
        "each is the whole selector of a rule of kit.css: a class the kit does not style is a component nobody styled")))

;; --- table, pager, badge, card --------------------------------------------------------

(def ^:private link-attrs "hx-push-url=\"true\" hx-select=\"main\" hx-swap=\"outerHTML show:top showTarget:body\" hx-target=\"main\"")
(defn- L [href rel body] (str "<a href=\"" href "\" hx-get=\"" href "\" " link-attrs (when rel (str " rel=\"" rel "\"")) ">" body "</a>"))

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
    (is (= (str "<nav aria-label=\"Pages\" class=\"ui-pager\">" (L "/l?page=1" "prev" "Previous") "<span>page 2 of 5</span>"
                (L "/l?page=3&amp;q=a" "next" "Next") "</nav>")
           (html (ui/pager {:wb/tr (tr-for :en)} o))))
    (is (= (str "<nav aria-label=\"Páginas\" class=\"ui-pager\">" (L "/l?page=1" "prev" "Anterior") "<span>page 2 of 5</span>"
                (L "/l?page=3&amp;q=a" "next" "Siguiente") "</nav>")
           (html (ui/pager {:wb/tr (tr-for :es)} o)))
        "in Spanish")
    (is (= (str "<nav aria-label=\"Pp\" class=\"ui-pager\">" (L "/l?page=1" "prev" "Back") "<span>page 2 of 5</span>"
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
                "<span>page 2 of 5</span>" (L "/b" "next" "Siguiente") "</nav></main>")
           (main ((app {:default-locale :es}) (mock/request :get "/"))))
        "a host speaking Spanish: the plugin's words, merged by web-base")
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
