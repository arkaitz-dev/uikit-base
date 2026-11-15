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

(defn- btn-class
  "The classes of a button of `variant` and `size`, either refused when it is not the kit's."
  [variant size]
  (when-not (or (nil? variant) (variants variant))
    (throw (ex-info "uikit-base: a button's :variant is one of the kit's" {:variant variant})))
  (when-not (or (nil? size) (sizes size))
    (throw (ex-info "uikit-base: a button's :size is :sm or :lg" {:size size})))
  (str/join " " (cond-> ["ui-btn"]
                  variant (conj (str "ui-btn--" (name variant)))
                  size (conj (str "ui-btn--" (name size))))))

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
    (into [:button (with-attrs {:type "submit" :class (btn-class variant size)} (dissoc opts :variant :size) [])]
          body)))

(defn link-button
  "`(link-button path opts? & body)` → a link that looks like a `button`: a `wb/link` to
  `path`, so it swaps the page's `<main>` and its path is web-base's to refuse. `opts`
  name a `:variant` and a `:size` as `button`'s do, refused alike; the rest are the
  link's attributes, never an `href` or an htmx one, which `wb/link` refuses. For going
  somewhere; what changes something is a form's button."
  [path & more]
  (let [[opts body] (split-attrs more)]
    (apply wb/link path (with-attrs {:class (btn-class (:variant opts) (:size opts))} (dissoc opts :variant :size) []) body)))

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

(defn- label!
  "`l` when it is text a button can show, refused by name otherwise."
  [component k l]
  (when-not (and (string? l) (not (str/blank? l)))
    (throw (ex-info (str "uikit-base: " component "'s " (name k) " is the text its button shows") {k l})))
  l)

(defn- field-name
  "A field's name or value as a form sends it: a keyword as its text, namespace kept
  (`:ns/d` is `\"ns/d\"`, which `wb/param` reads back as that string)."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- subject-of [subject]
  (when-not (or (nil? subject) (and (string? subject) (str/blank? subject)))
    [:span.ui-sr-only " " subject]))

(defn action
  "`(action request path opts)` → a form that posts to `path` with one button: what a row
  does to itself — remove, move, mark. `opts`: `:label`, the button's text; `:hidden`, a
  map of fields it sends, in the order of their names, each a string, a number, a
  boolean or a keyword (sent as its text), one per name; `:subject`, what it acts on,
  read after the label by a screen reader and never shown, so the tenth \"Remove\" on a
  page still says which; `:variant` and `:size`, the button's; `:attrs`, the form's,
  refused as `post-form` refuses them — an `hx-post` there is where htmx posts, as on
  any form; `:button`, the button's own attributes, never a `:type`, `:name`, `:value`,
  `:variant` or `:size`, which are the action's to decide. A hidden field named as the
  CSRF field is refused: the form carries the real one first."
  [request path {:keys [label hidden subject variant size attrs] button-attrs :button}]
  (label! "an action" :label label)
  (when-let [bad (seq (filter #{:type :name :value :variant :size} (keys (normalised button-attrs))))]
    (throw (ex-info "uikit-base: an action's button takes no type, name, value, variant or size of its own" {:attributes (vec (sort bad))})))
  (let [names (map field-name (keys hidden))]
    (when (some str/blank? names)
      (throw (ex-info "uikit-base: an action's hidden field has a name, or a browser would not send it" {:hidden (vec (keys hidden))})))
    (when-let [bad (seq (filter #(= "__anti-forgery-token" (.toLowerCase ^String % java.util.Locale/ROOT)) names))]
      (throw (ex-info "uikit-base: an action's hidden fields may not carry the CSRF field" {:hidden (vec bad)})))
    (when-let [bad (seq (for [[n c] (frequencies names) :when (< 1 c)] n))]
      (throw (ex-info "uikit-base: an action's hidden fields are one per name" {:hidden (vec (sort bad))}))))
  (when-let [bad (seq (filter (comp coll? val) hidden))]
    (throw (ex-info "uikit-base: an action's hidden value is one value, not a collection" {:hidden (vec (sort (map (comp field-name key) bad)))})))
  (post-form request path (with-attrs {:class "ui-action"} attrs [])
             (for [[k v] (sort-by (comp field-name key) hidden)]
               [:input {:type "hidden" :name (field-name k) :value (if (nil? v) "" (field-name v))}])
             (button (merge (normalised button-attrs) {:variant variant :size size}) label (subject-of subject))))

(defn confirm
  "`(confirm request path opts)` → an `action` that asks first, with no script: a plain
  button, `:label`, that opens a second, `:confirm-label` (\"Yes, delete\"), which posts —
  the second is not rendered, so not reachable, until the first is pressed. Both are
  text, and never the same, or the second step would read as the first. The second is
  `:danger` unless `:variant` says otherwise; the rest of `opts` are `action`'s,
  `:subject` read by both. It asks once; it does not stop a second submit, which is the
  handler's to make harmless."
  [request path {:keys [label confirm-label subject variant] :as opts}]
  (label! "a confirm" :label label)
  (label! "a confirm" :confirm-label confirm-label)
  (when (= (str/trim label) (str/trim confirm-label))
    (throw (ex-info "uikit-base: a confirm's two labels differ, or its second step reads as its first" {:label label})))
  [:details {:class "ui-confirm"}
   [:summary {:class "ui-btn"} label (subject-of subject)]
   (action request path (assoc (dissoc opts :confirm-label) :label confirm-label :variant (or variant :danger)))])

(defn- word
  "A word of the host's dictionary: a key, or a vector of the key and its arguments,
  translated by web-base's `:wb/tr`, which a request it renders always carries."
  [request w]
  (let [tr (or (:wb/tr request)
               (throw (ex-info "uikit-base: a field's error is translated by web-base; the request carries no :wb/tr" {})))]
    (if (vector? w) (tr (first w) (vec (rest w))) (tr w []))))

(defn- radio-group
  "`field`'s \"radio\": a fieldset named by its legend, one radio per option, each marked
  and described like any refused control — the group's error, then its hint, then the
  caller's — since the focus lands on a radio, never on the group."
  [request {:keys [id label value error hint required? attrs options]}]
  (let [attrs     (normalised attrs)
        base      (or (:id attrs) id)
        error-id  (str base "-error")
        hint-id   (str base "-hint")
        described (str/join " " (remove str/blank? (distinct (concat [(when error error-id) (when hint hint-id)]
                                                                     (str/split (str/trim (str (:aria-describedby attrs ""))) #"\s+")))))
        theirs    (dissoc attrs :id :value :checked :type :aria-describedby)]
    (-> [:fieldset {:class "ui-field ui-field--group"}
         (cond-> [:legend.ui-field__label label] required? (conj [:span.ui-field__req {:aria-hidden "true"} "*"]))]
        (cond-> hint (conj [:span.ui-field__hint {:id hint-id} hint]))
        (into (map-indexed (fn [i [v l]]
                             (let [own (cond-> {:type "radio" :name id :id (str base "-" i) :value v :class "ui-check"}
                                         (and (some? value) (= (str v) (str value))) (assoc :checked true)
                                         error (assoc :aria-invalid "true")
                                         required? (assoc :required true))
                                   own (cond-> (with-attrs own theirs (cond-> [] error (conj :aria-invalid) required? (conj :required)))
                                         (seq described) (assoc :aria-describedby described))]
                               [:label.ui-field__choice [:input own] l]))
                           options))
        (conj (when error [:span.ui-field__error {:id error-id} (word request error)])))))

(defn field
  "A labelled control, marked and explained when the server refused it. `error` is a word
  of the host's dictionary, or a vector of the word and its arguments, as a handler's
  refusal names it; the control is then `aria-invalid` and described by the error, which
  no attribute of the caller's undoes — a caller's own `aria-describedby` is kept after
  it, never in its place. `hint`, the view's own text, says what the field wants; the
  control is described by it too, after the error. `:required? true` makes the control
  `required`, whatever the attrs say, and marks the label with an asterisk a screen reader
  skips: it reads the control's own `required`. `type` is an input's, \"text\" unless
  given, or one of four that are not inputs alike: \"select\" draws `options`, `[[value
  label] …]`, the one whose value is `value` selected — compared as text, since a refused
  form comes back as strings; \"textarea\" holds `value` as its text; \"file\" takes no
  value; \"checkbox\" is ticked when `value` is truthy, sends its attrs' `:value` (\"on\"
  unless given) and sits before its label; \"radio\" is a group named by its label, one
  radio per option, the one whose value is `value` checked, as text, and `attrs` on each.
  `:inline? true` lays label and control on one line, for a field inside a row. `attrs` go
  on the control, `:class` joined to the kit's."
  [request {:keys [id label type value error hint required? attrs options inline?] :as opts}]
  (if (= "radio" type)
    (radio-group request opts)
  (let [attrs    (normalised attrs)
        base     (or (:id attrs) id)
        error-id (str base "-error")
        hint-id  (str base "-hint")
        check?   (= "checkbox" type)
        described (distinct (concat [(when error error-id) (when hint hint-id)]
                                    (str/split (str/trim (str (:aria-describedby attrs ""))) #"\s+")))
        described (str/join " " (remove str/blank? described))
        own      (cond-> {:id id :name id :class (if check? "ui-check" "ui-input")}
                   error (assoc :aria-invalid "true")
                   required? (assoc :required true))
        own      (cond-> (with-attrs own (dissoc attrs :aria-describedby) (cond-> [] error (conj :aria-invalid) required? (conj :required)))
                   (seq described) (assoc :aria-describedby described))
        control  (case (or type "text")
                   "select"   (into [:select own]
                                    (for [[v l] options]
                                      [:option (cond-> {:value v} (= (str v) (str value)) (assoc :selected true)) l]))
                   "textarea" [:textarea own value]
                   "file"     [:input (assoc own :type "file")]
                   "checkbox" [:input (cond-> (merge {:value "on"} own {:type "checkbox"}) value (assoc :checked true))]
                   [:input (merge {:type (or type "text") :value value} own)])
        label    (cond-> [:label.ui-field__label {:for (:id own)} label]
                   required? (conj [:span.ui-field__req {:aria-hidden "true"} "*"]))]
    ;; A plain field is the very data 0.1.0 answered, so a host test that compares the
    ;; Hiccup, not the HTML, sees no change; a modifier is the wrapper's own class.
    (let [hint (when hint [:span.ui-field__hint {:id hint-id} hint])]
      (-> (cond-> [:div.ui-field]
            (or check? inline?) (conj {:class (str/join " " (cond-> [] check? (conj "ui-field--check") inline? (conj "ui-field--inline")))}))
          (into (remove nil? (if check? [control label hint] [label hint control])))
          (conj (when error [:span.ui-field__error {:id error-id} (word request error)])))))))

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
