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
  of four that are not inputs alike: \"select\" draws `options`, `[[value label] …]`, the
  one whose value is `value` selected — compared as text, since a refused form comes back
  as strings; \"textarea\" holds `value` as its text; \"file\" takes no value; \"checkbox\"
  is ticked when `value` is truthy, sends its attrs' `:value` (\"on\" unless given) and
  sits before its label. `:inline? true` lays label and control on one line, for a field
  inside a row. `attrs` go on the control, `:class` joined to the kit's."
  [request {:keys [id label type value error attrs options inline?]}]
  (let [attrs    (normalised attrs)
        error-id (str (or (:id attrs) id) "-error")
        check?   (= "checkbox" type)
        own      (cond-> {:id id :name id :class (if check? "ui-check" "ui-input")}
                   error (assoc :aria-invalid "true" :aria-describedby error-id))
        own      (with-attrs own attrs (if error [:aria-invalid :aria-describedby] []))
        control  (case (or type "text")
                   "select"   (into [:select own]
                                    (for [[v l] options]
                                      [:option (cond-> {:value v} (= (str v) (str value)) (assoc :selected true)) l]))
                   "textarea" [:textarea own value]
                   "file"     [:input (assoc own :type "file")]
                   "checkbox" [:input (cond-> (merge {:value "on"} own {:type "checkbox"}) value (assoc :checked true))]
                   [:input (merge {:type (or type "text") :value value} own)])
        label    [:label.ui-field__label {:for (:id own)} label]]
    ;; A plain field is the very data 0.1.0 answered, so a host test that compares the
    ;; Hiccup, not the HTML, sees no change; a modifier is the wrapper's own class.
    (conj (cond-> [:div.ui-field]
            (or check? inline?) (conj {:class (str/join " " (cond-> [] check? (conj "ui-field--check") inline? (conj "ui-field--inline")))}))
          (if check? control label)
          (if check? label control)
          (when error [:span.ui-field__error {:id error-id} (word request error)]))))

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

(defn cell
  "`(cell attrs & body)` → a table cell with attributes of its own: in `table`'s `head` a
  column header, in a row its `<td>` (or the row's header `<th>`), `:class` joined to
  nothing of the kit's. It means something only inside `table`."
  [attrs & body]
  (into [::cell attrs] body))

(defn- cell-of
  "A cell as `tag`: one made by `cell` with its attributes, anything else its content."
  [tag own c]
  (if (and (vector? c) (= ::cell (first c)))
    (into [tag (with-attrs own (second c) (vec (keys own)))] (nnext c))
    [tag own c]))

(defn table
  "A table of the kit: `head`, the column names, each a `scope=col` header; `rows`, each a
  sequence of cells; `caption`, when given, says what it lists. With `:row-header? true`
  each row's first cell is that row's header. `attrs` go on the table; `row-attrs`, a
  function of a row, answers each `<tr>`'s own — an id, or what htmx targets. A cell made
  by `cell` carries its own attributes. A column whose head is nil is not drawn, in the
  head or in any row, so a column shown only sometimes is `(when shown? \"Label\")` in the
  head and anything at all in the rows; a column always there with an empty header is
  `\"\"`. `:row-header?` makes the first drawn cell of each row its header."
  [{:keys [caption head rows row-header? attrs row-attrs]}]
  (let [gone (set (keep-indexed (fn [i h] (when (nil? h) i)) head))
        kept (fn [cells] (->> cells (map-indexed vector) (remove (comp gone first)) (map second)))]
    [:table (with-attrs {:class "ui-table"} attrs [])
     (when caption [:caption caption])
     (when (seq head) [:thead [:tr (for [h (kept head)] (cell-of :th {:scope "col"} h))]])
     [:tbody (for [row rows]
               (into [:tr (with-attrs {} (when row-attrs (row-attrs row)) [])]
                     (map-indexed (fn [i c] (if (and row-header? (zero? i)) (cell-of :th {:scope "row"} c) (cell-of :td {} c)))
                                  (kept row))))]]))

(defn rows
  "`(rows attrs? & rows)` → a list of rows, each a `row`: what a page lists when a table's
  grid would be wrong — rows that wrap, that carry their own forms, that htmx swaps one
  at a time. `{:ordered? true}` makes it an `<ol>`, for rows whose order means something,
  such as a history; `{:plain? true}` drops the rows' line, padding and border, for a list
  that is only a list — an activity, a history read top to bottom."
  [& more]
  (let [[attrs body] (split-attrs more)]
    (into [(if (:ordered? attrs) :ol :ul)
           (with-attrs {:class (cond-> "ui-rows" (:plain? attrs) (str " ui-rows--plain"))} (dissoc attrs :ordered? :plain?) [])]
          body)))

(defn row
  "`(row attrs? & body)` → one row of `rows`, its parts laid out in a line that wraps.
  `attrs` carry what addresses it: an id, `hx-` attributes, a class of the host's."
  [& more]
  (let [[attrs body] (split-attrs more)]
    (into [:li (with-attrs {:class "ui-row"} attrs [])] body)))

(def words
  "The kit's own words, for the languages the set's hosts speak. The plugin brings them as
  a dictionary, which a host's own overrides; a language the kit does not speak falls back
  to the English here, so no word of the kit is ever missing from a page."
  {:en {:uikit-base {:previous "Previous" :next "Next" :pages "Pages"}}
   :es {:uikit-base {:previous "Anterior" :next "Siguiente" :pages "Páginas"}}})

(defn- kit-word [request given k]
  (or given
      (some-> (:wb/tr request) (apply [k []]))
      (get-in words [:en :uikit-base (keyword (name k))])))

(defn pager
  "`(pager request opts)` → links to the page before and after, or nil when there is
  neither. `previous` and `next` are paths (as web-base's `paging/path` answers them) or
  nil, each a `wb/link` with its `rel`; `where` says where the reader is (\"page 2 of 5\"),
  between them; `attrs` go on the nav. The links and the nav are named by the kit's own
  words in the reader's language — `:uikit-base/previous`, `:uikit-base/next`,
  `:uikit-base/pages`, which the plugin brings and a host's dictionary may override, in
  English where neither speaks the reader's — unless `previous-label`, `next-label` or
  `label` say otherwise."
  [request {:keys [previous next where label previous-label next-label attrs]}]
  (when (or previous next)
    [:nav (with-attrs {:class "ui-pager" :aria-label (kit-word request label :uikit-base/pages)} attrs [])
     (when previous (wb/link previous {:rel "prev"} (kit-word request previous-label :uikit-base/previous)))
     (when where [:span where])
     (when next (wb/link next {:rel "next"} (kit-word request next-label :uikit-base/next)))]))

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
