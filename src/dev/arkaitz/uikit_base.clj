(ns dev.arkaitz.uikit-base
  "The kit's components: functions that answer Hiccup carrying the kit's classes, for a
  web-base host. Each takes the caller's attributes and merges them with its own: a
  `:class` is added to the kit's, never put in its place; anything else the caller gives
  wins, except what the component's correctness needs — a form's method, action and CSRF
  field, a refused field's ARIA wiring, a notice's role.

  This namespace never loads the colour engine (`color`, `brand`, `audit`, `emit`,
  `build`): a host's colours are generated once, committed, and served as a file."
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.security :as security]))

(defn- class-str [c]
  (cond (nil? c) nil
        (string? c) c
        (keyword? c) (name c)
        :else (str/join " " (keep class-str c))))

(defn- normalised
  "The caller's attributes named as a browser reads them. Hiccup renders a string key, or
  a keyword in another case, as an attribute of its own beside the component's, and a
  browser keeps the first of two: so the caller's `\"method\"` is the form's `:method`."
  [attrs]
  (into {} (map (fn [[k v]]
                  (when-not (or (keyword? k) (string? k))
                    (throw (ex-info "uikit-base: an attribute is named by a keyword or a string" {:attribute k})))
                  [(keyword (.toLowerCase (name k) java.util.Locale/ROOT)) v]))
        attrs))

(defn- refuse-attrs!
  "Refuses the caller's attributes in `names` (as normalised), naming the component."
  [attrs names component why]
  ;; By the name before any modifier: htmx 4 reads `hx-get:inherited`, `hx-action:append`
  ;; and their kin on the element itself, as the plain name.
  (when-let [found (seq (filter #((set names) (keyword (first (str/split (name %) #":")))) (keys attrs)))]
    (throw (ex-info (str "uikit-base: a " component " takes no " (str/join ", " (map name (sort found))) ": " why)
                    {:attributes (vec (sort found))}))))

(defn- with-attrs
  "`own` with the caller's `attrs` over it, the classes joined, and `kept` — the keys the
  component's correctness needs — taken from `own` whatever the caller gave."
  [own attrs kept]
  (let [attrs   (normalised attrs)
        classes (str/join " " (keep class-str [(:class own) (:class attrs)]))]
    (cond-> (merge own (dissoc attrs :class) (select-keys own kept))
      (seq classes) (assoc :class classes))))

(defn- split-attrs [more] (if (map? (first more)) [(first more) (rest more)] [nil more]))

(def ^:private variants #{:primary :secondary :success :danger :warning :info})
(def ^:private sizes #{:sm :lg})

(defn button
  "`(button opts? & body)` → a `<button>` of the kit. `opts` may name a `:variant` —
  `:primary`, `:secondary`, `:success`, `:danger`, `:warning` or `:info`, or none for a
  plain button on the surface — and a `:size`, `:sm` or `:lg`; the rest are the button's
  attributes, `:type` \"submit\" unless given — never `formaction`, `formmethod` or `form`,
  which would send its form elsewhere. An unknown variant or size is refused."
  [& more]
  (let [[opts body] (split-attrs more)
        {:keys [variant size]} opts]
    (refuse-attrs! (normalised (dissoc opts :variant :size)) [:formaction :formmethod :form] "button"
                   "a submitter's own target would send its form, token and all, where the form does not")
    (when-not (or (nil? variant) (variants variant))
      (throw (ex-info "uikit-base: a button's :variant is one of the kit's" {:variant variant})))
    (when-not (or (nil? size) (sizes size))
      (throw (ex-info "uikit-base: a button's :size is :sm or :lg" {:size size})))
    (into [:button (with-attrs {:type "submit"
                                :class (str/join " " (cond-> ["ui-btn"]
                                                       variant (conj (str "ui-btn--" (name variant)))
                                                       size (conj (str "ui-btn--" (name size)))))}
                               (dissoc opts :variant :size) [])]
          body)))

(defn post-form
  "`(post-form request action attrs? & body)` → a form that posts to `action` with the
  CSRF field every POST needs, first. `attrs` are the form's own — `{:enctype
  \"multipart/form-data\"}` for a file, `hx-post`, `hx-target` and the like for htmx,
  `{:class \"ui-form\"}` for the kit's stacked layout — and never its method or action, nor
  an htmx attribute that would change them (`hx-get`, `hx-method`, `hx-action`, `hx-config`…):
  a form that sent its token by GET would put it in the address bar and the access log.
  A submitter's own target would too, which is why `button` refuses one; a hand-written
  `[:button {:formaction …}]` in the body is the host's to answer for."
  [request action & more]
  (let [[attrs body] (split-attrs more)]
    ;; htmx reads these before the form's own method and action (htmx.min.js, #A).
    (refuse-attrs! (normalised attrs)
                   (for [p ["hx-" "data-hx-"] a ["get" "put" "patch" "delete" "method" "action" "config"]] (keyword (str p a)))
                   "form" "htmx would send it, token and all, by another method or to another action")
    (into [:form (with-attrs {:method "post" :action action} attrs [:method :action])
           (security/csrf-field request)]
          body)))

(defn- word
  "A word of the host's dictionary: a key, or a vector of the key and its arguments,
  translated by web-base's `:wb/tr`, which a request it renders always carries."
  [request w]
  (let [tr (or (:wb/tr request)
               (throw (ex-info "uikit-base: a field's error is translated by web-base; the request carries no :wb/tr" {})))]
    (if (vector? w) (tr (first w) (vec (rest w))) (tr w []))))

(defn field
  "A labelled control, marked and explained when the server refused it. `error` is a word
  of the host's dictionary, or a vector of the word and its arguments, as a handler's
  refusal names it; the control is then `aria-invalid` and described by the error, which
  no attribute of the caller's undoes. `type` is an input's, \"text\" unless given, or one
  of three that are not inputs alike: \"select\" draws `options`, `[[value label] …]`, the
  one whose value is `value` selected — compared as text, since a refused form comes back
  as strings; \"textarea\" holds `value` as its text; \"file\" takes no value. `attrs` go on
  the control, `:class` joined to the kit's."
  [request {:keys [id label type value error attrs options]}]
  (let [attrs    (normalised attrs)
        error-id (str (or (:id attrs) id) "-error")
        own      (cond-> {:id id :name id :class "ui-input"}
                   error (assoc :aria-invalid "true" :aria-describedby error-id))
        own      (with-attrs own attrs (if error [:aria-invalid :aria-describedby] []))
        control  (case (or type "text")
                   "select"   (into [:select own]
                                    (for [[v l] options]
                                      [:option (cond-> {:value v} (= (str v) (str value)) (assoc :selected true)) l]))
                   "textarea" [:textarea own value]
                   "file"     [:input (assoc own :type "file")]
                   [:input (merge {:type (or type "text") :value value} own)])]
    [:div.ui-field
     [:label.ui-field__label {:for (:id own)} label]
     control
     (when error [:span.ui-field__error {:id error-id} (word request error)])]))

(def ^:private notice-roles
  "A refusal is an alert, read out at once; an outcome or a note waits its turn."
  {:ok "status" :info "status" :warning "status" :error "alert"})

(defn notice
  "`(notice kind attrs? & body)` → a line saying what an action answered: `kind` is `:ok`,
  `:info`, `:warning` or `:error`. Its role is the kind's — `alert` for an error, `status`
  otherwise — and no attribute changes it."
  [kind & more]
  (when-not (notice-roles kind)
    (throw (ex-info "uikit-base: a notice is :ok, :info, :warning or :error" {:kind kind})))
  (let [[attrs body] (split-attrs more)]
    (into [:p (with-attrs {:class (str "ui-notice ui-notice--" (name kind)) :role (notice-roles kind)} attrs [:role])]
          body)))

(defn table
  "A table of the kit: `head`, the column names, each a `scope=col` header; `rows`, each a
  sequence of cells; `caption`, when given, says what it lists. With `:row-header? true`
  each row's first cell is that row's header. `attrs` go on the table."
  [{:keys [caption head rows row-header? attrs]}]
  [:table (with-attrs {:class "ui-table"} attrs [])
   (when caption [:caption caption])
   (when (seq head) [:thead [:tr (for [h head] [:th {:scope "col"} h])]])
   [:tbody (for [row rows]
             [:tr (map-indexed (fn [i cell] (if (and row-header? (zero? i)) [:th {:scope "row"} cell] [:td cell])) row)])]])

(defn pager
  "Links to the page before and after, or nil when there is neither: `previous` and
  `next` are paths (as web-base's `paging/path` answers them) or nil, each a `wb/link`
  with its `rel` and its label, `previous-label` or `next-label` — refused when missing,
  since a link with no name is one a screen reader cannot say. `where` says where the
  reader is (\"page 2 of 5\"), between them; `label` names the navigation; `attrs` go on it."
  [{:keys [previous next where label previous-label next-label attrs]}]
  (when (or (and previous (nil? previous-label)) (and next (nil? next-label)))
    (throw (ex-info "uikit-base: a pager's link needs its label" {:previous? (some? previous) :next? (some? next)})))
  (when (or previous next)
    [:nav (with-attrs (cond-> {:class "ui-pager"} label (assoc :aria-label label)) attrs [])
     (when previous (wb/link previous {:rel "prev"} previous-label))
     (when where [:span where])
     (when next (wb/link next {:rel "next"} next-label))]))

(def ^:private badge-kinds #{:neutral :success :danger :warning :info})

(defn badge
  "`(badge kind attrs? & body)` → a status as a small filled label: `kind` is `:neutral`,
  `:success`, `:danger`, `:warning` or `:info`. The colour is the kind's, never the only
  sign: the body names it."
  [kind & more]
  (when-not (badge-kinds kind)
    (throw (ex-info "uikit-base: a badge is :neutral, :success, :danger, :warning or :info" {:kind kind})))
  (let [[attrs body] (split-attrs more)]
    (into [:span (with-attrs {:class (str "ui-badge" (when (not= :neutral kind) (str " ui-badge--" (name kind))))} attrs [])]
          body)))

(defn card
  "`(card attrs? & body)` → a raised surface holding its body."
  [& more]
  (let [[attrs body] (split-attrs more)]
    (into [:div (with-attrs {:class "ui-card"} attrs [])] body)))
