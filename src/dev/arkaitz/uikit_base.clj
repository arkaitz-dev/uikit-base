(ns dev.arkaitz.uikit-base
  "The kit's components: functions that answer Hiccup carrying the kit's classes, for a
  web-base host. Each takes the caller's attributes and merges them with its own: a
  `:class` is added to the kit's, never put in its place; anything else the caller gives
  wins, except what the component's correctness needs — a form's method, action and CSRF
  field, a refused field's ARIA wiring, a notice's role.

  This namespace never loads the colour engine (`color`, `brand`, `audit`, `emit`,
  `build`): a host's colours are generated once, committed, and served as a file."
  (:refer-clojure :exclude [empty time])
  (:require [clojure.string :as str]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.render :as render]
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

(def ^:private fills #{:primary :secondary :success :danger :warning :info})
(def ^:private variants
  "A fill, its outline — the fill's edge and ink on the surface, filled when hovered — or a
  button drawn as a link."
  (into (conj fills :link) (map #(keyword (str "outline-" (name %)))) fills))
(def ^:private sizes #{:sm :lg})

(defn- btn-class
  "The classes of a button of `variant` and `size`, either refused when it is not the kit's,
  as wide as its container when `block?`."
  ([variant size] (btn-class variant size nil))
  ([variant size block?]
   (when-not (or (nil? variant) (variants variant))
     (throw (ex-info "uikit-base: a button's :variant is one of the kit's" {:variant variant})))
   (when-not (or (nil? size) (sizes size))
     (throw (ex-info "uikit-base: a button's :size is :sm or :lg" {:size size})))
   (str/join " " (cond-> ["ui-btn"]
                   variant (conj (str "ui-btn--" (name variant)))
                   size (conj (str "ui-btn--" (name size)))
                   block? (conj "ui-btn--block")))))

(defn button
  "`(button opts? & body)` → a `<button>` of the kit. `opts` may name a `:variant` —
  `:primary`, `:secondary`, `:success`, `:danger`, `:warning` or `:info`, their outlines
  `:outline-primary` … `:outline-info`, `:link` for a button that reads as a link, or none
  for a plain button on the surface — a `:size`, `:sm` or `:lg`, and `:block? true` for one
  as wide as its container; the rest are the button's attributes, `:type` \"submit\" unless
  given — never `formaction`, `formmethod` or `form`, which would send its form elsewhere.
  An unknown variant or size is refused."
  [& more]
  (let [[opts body] (split-attrs more)
        {:keys [variant size block?]} opts
        attrs (dissoc opts :variant :size :block?)]
    (refuse-attrs! (normalised attrs) [:formaction :formmethod :form] "button"
                   "a submitter's own target would send its form, token and all, where the form does not")
    (into [:button (with-attrs {:type "submit" :class (btn-class variant size block?)} attrs [])]
          body)))

(declare subject-of)

(defn link-button
  "`(link-button path opts? & body)` → a link that looks like a `button`: a `wb/link` to
  `path`, so it swaps the page's `<main>` and its path is web-base's to refuse. `opts`
  name a `:variant`, a `:size` and `:block?` as `button`'s do, refused alike; a `:subject`, read
  after the body by a screen reader and never shown, so the tenth \"Edit\" says which;
  and `:download? true` for what must load as a document — a CSV, a file — which is then
  a plain link to a path on this origin, never swapped. The rest are the link's
  attributes, never an `href` or an htmx one. For going somewhere; what changes something
  is a form's button."
  [path & more]
  (let [[opts body] (split-attrs more)
        attrs (with-attrs {:class (btn-class (:variant opts) (:size opts) (:block? opts))} (dissoc opts :variant :size :block? :subject :download?) [])
        body  (concat body [(subject-of (:subject opts))])]
    (if (:download? opts)
      (do (when-not (or (= "/" path) (render/local-path? path))
            (throw (ex-info "uikit-base: a download's path is a path on this origin" {:path path})))
          (when (some #(or (= :href %) (str/starts-with? (name %) "hx-") (str/starts-with? (name %) "data-hx-")) (keys attrs))
            (throw (ex-info "uikit-base: a download's attrs give no href and no htmx" {:attributes (vec (sort (keys attrs)))})))
          (into [:a (assoc attrs :href path)] body))
      (apply wb/link path attrs body))))

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

(defn- nothing?
  "Whether `x` shows nothing: nil, or blank text."
  [x]
  (or (nil? x) (and (string? x) (str/blank? x))))

(defn- named!
  "Refuses `label` unless it is text that can name `component`: a screen reader reads it
  as the component's name."
  [component label]
  (when-not (and (string? label) (not (str/blank? label)))
    (throw (ex-info (str "uikit-base: " component " is named by its label") {:label label}))))

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
  `:variant` or `:size`, which are the action's to decide; `:before`, what the form shows
  before its button — the sentence the button answers. A hidden field named as the CSRF
  field is refused: the form carries the real one first."
  [request path {:keys [label hidden subject variant size attrs before] button-attrs :button}]
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
             before
             (button (merge (normalised button-attrs) {:variant variant :size size}) label (subject-of subject))))

(defn confirm
  "`(confirm request path opts)` → an `action` that asks first, with no script: a plain
  button, `:label`, that opens a second, `:confirm-label` (\"Yes, delete\"), which posts —
  the second is not rendered, so not reachable, until the first is pressed. Both are
  text, and never the same, or the second step would read as the first. The second is
  `:danger` unless `:variant` says otherwise; `:first` styles the first, `{:variant
  :danger}` and its attributes; `:details` are the element's own, an id or what htmx
  targets, never `open`. The rest of `opts` are `action`'s, `:subject` read by both. It asks once; it does not stop a second submit, which is the
  handler's to make harmless."
  [request path {:keys [label confirm-label subject variant first details] :as opts}]
  (label! "a confirm" :label label)
  (when-let [bad (seq (filter #{:type :name :value :size} (keys (normalised (dissoc first :variant)))))]
    (throw (ex-info "uikit-base: a confirm's first step takes a variant and attributes, never a type, name, value or size" {:attributes (vec (sort bad))})))
  ;; Either would post on the first press, and the second step would never be asked.
  (when-let [bad (seq (filter #(re-find #"^(data-)?hx-" (name %)) (keys (normalised first))))]
    (throw (ex-info "uikit-base: a confirm's first step only opens it; it takes no htmx" {:attributes (vec (sort bad))})))
  (when-let [bad (seq (filter #(re-find #"^(data-)?hx-(post|put|patch|delete)(:|$)" (name %)) (keys (normalised details))))]
    (throw (ex-info "uikit-base: a confirm's element sends nothing; only its second step does" {:attributes (vec (sort bad))})))
  (label! "a confirm" :confirm-label confirm-label)
  (when (= (str/trim label) (str/trim confirm-label))
    (throw (ex-info "uikit-base: a confirm's two labels differ, or its second step reads as its first" {:label label})))
  [:details (with-attrs {:class "ui-confirm"} (dissoc (normalised details) :open) [])
   [:summary (with-attrs {:class (btn-class (:variant first) nil)} (dissoc first :variant) []) label (subject-of subject)]
   (action request path (assoc (dissoc opts :confirm-label :first :details) :label confirm-label :variant (or variant :danger)))])

(defn toggle-button
  "`(toggle-button request path opts)` → an `action` whose button is a toggle the server
  drew in its state: `aria-pressed` is `:pressed?`, true or false, which no attribute of
  the caller's changes. Its `:label` names the setting and stays the same in both states —
  a screen reader says pressed or not — and its `:hidden` fields say what pressing it asks
  for (`{:on (not pressed?)}`). The rest of `opts` are `action`'s but `:variant`, refused:
  a toggle is drawn pressed in the kit's own look, which a fill would hide."
  [request path {:keys [label pressed?] :as opts}]
  (label! "a toggle-button" :label label)
  (when-not (boolean? pressed?)
    (throw (ex-info "uikit-base: a toggle-button's :pressed? is true or false" {:pressed? pressed?})))
  (when (contains? opts :variant)
    (throw (ex-info "uikit-base: a toggle-button takes no :variant: its pressed look is the kit's" {:variant (:variant opts)})))
  (action request path (-> (dissoc opts :pressed?)
                           (assoc :button (assoc (normalised (:button opts)) :aria-pressed (str pressed?))))))

(defn button-group
  "`(button-group opts & buttons)` → buttons drawn as one control, their edges joined: a
  `role=group` named by `:label`, which is required — a group of unnamed buttons is read as
  loose ones. The rest of `opts` are its attributes; its role and name are its own."
  [opts & body]
  (let [label (:label opts)]
    (named! "a button-group" label)
    (into [:div (with-attrs {:class "ui-btn-group" :role "group" :aria-label label} (dissoc opts :label) [:role :aria-label])]
          body)))

(declare kit-word)

(defn- given
  "The caller's own word, or nil when it gave a blank one, so the kit's stands."
  [l]
  (when-not (and (string? l) (str/blank? l)) l))

(defn close-button
  "`(close-button request opts?)` → a button that closes something: a mark the eye reads,
  hidden from a screen reader, which reads its name — the kit's word `:uikit-base/close` in
  the reader's language, or `:label`. The rest of `opts` are its attributes, `:type`
  \"button\" unless given, never `formaction`, `formmethod` or `form`, as `button`'s."
  [request & [opts]]
  (let [attrs (dissoc opts :label)
        label (:label opts)]
    (refuse-attrs! (normalised attrs) [:formaction :formmethod :form] "close-button"
                   "a submitter's own target would send its form, token and all, where the form does not")
    [:button (with-attrs {:type "button" :class "ui-close"} attrs [])
     [:span {:aria-hidden "true"} "\u00d7"]
     [:span.ui-sr-only (kit-word request label :uikit-base/close)]]))

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

(defn sentence
  "`(sentence request k & parts)` → word `k` of the host's dictionary with `parts` — any
  Hiccup: a `time`, a link, a strong — where its `%1`, `%2`… stand, in the order the
  translation puts them, so a language may say them in another order. web-base's
  translator answers text, so a time inside \"Due %1\" would otherwise be text too, with
  no `datetime`. A word the dictionary lacks is refused, naming it."
  [request k & parts]
  (let [tr    (or (:wb/tr request)
                  (throw (ex-info "uikit-base: a sentence is translated by web-base; the request carries no :wb/tr" {:word k})))
        mark  (fn [i] (str (char 0xE000) i (char 0xE001)))
        ;; Three markers past the parts: a word that asks for one of them asks for more
        ;; than it was given, and the missing one would print as nothing.
        said  (tr k (mapv mark (range (+ 3 (count parts)))))]
    (when (some nil? parts)
      (throw (ex-info "uikit-base: a sentence's parts are something to show" {:word k})))
    (when-not (string? said)
      (throw (ex-info "uikit-base: a sentence's word is text in the dictionary" {:word k})))
    (when (some #(str/includes? said (mark %)) (range (count parts) (+ 3 (count parts))))
      (throw (ex-info "uikit-base: a sentence's word asks for more parts than it was given" {:word k :parts (count parts)})))
    (let [m (re-matcher #"\uE000(\d+)\uE001" said)]
      (loop [at 0 out []]
        (if (.find m)
          (recur (.end m) (cond-> out
                            (< at (.start m)) (conj (subs said at (.start m)))
                            true (conj (nth parts (Long/parseLong (.group m 1))))))
          (seq (cond-> out (< at (count said)) (conj (subs said at)))))))))

(defn input-group
  "`(input-group opts control)` → `control` with what comes `:before` and `:after` it
  joined to its edges: text — a currency, a unit — drawn as the group's text, anything
  else (a button) as it is. `:attrs` go on the group. The text is not the control's name:
  its label says the unit too. `field`'s `:before` and `:after` draw one around its
  control. A group without a control is refused."
  [{:keys [before after attrs]} control]
  (when (nil? control) (throw (ex-info "uikit-base: an input-group holds one control" {})))
  (let [part (fn [p] (if (or (string? p) (number? p)) [:span.ui-input-group__text p] p))]
    (cond-> [:div (with-attrs {:class "ui-input-group"} attrs [])]
      (some? before) (conj (part before))
      true (conj control)
      (some? after) (conj (part after)))))

(def ^:private checks #{"checkbox" "switch" "radio" "range"})

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
  \"switch\" is a checkbox read as an on-off switch (`role=switch`), drawn as one; \"range\"
  is a slider whose `min`, `max` and `step` are the attrs'.
  `:inline? true` lays label and control on one line, for a field inside a row. `:size`,
  `:sm` or `:lg`, sizes a text control, a select or a textarea to sit level with a button
  of that size. `:before` and `:after` put text or a button against the control's edges,
  as `input-group` does. `:floating? true` draws the label inside the control, floating up
  once it is focused or filled — a text control or a textarea, with a `:placeholder` in its
  attrs, since the label floats when the placeholder is not shown; the label is still the
  control's `<label for>`. A text control is edged in danger once the person has left it
  invalid (`:user-invalid`), and in success once a required one is filled validly — never
  before it was touched — while the server's `aria-invalid` edges it in danger whatever
  the person does. `attrs` go on the control, `:class` joined to the kit's."
  [request {:keys [id label type value error hint required? attrs options inline? size before after floating?] :as opts}]
  (when-not (or (nil? size) (sizes size))
    (throw (ex-info "uikit-base: a field's :size is :sm or :lg" {:size size})))
  (when (and size (checks type))
    (throw (ex-info "uikit-base: a field's :size sizes a text control, a select or a textarea" {:type type})))
  (when (and (or (some? before) (some? after)) (checks type))
    (throw (ex-info "uikit-base: an input-group holds a text control, a select or a file" {:type type})))
  (when floating?
    (when (#{"select" "file" "checkbox" "switch" "radio" "range"} type)
      (throw (ex-info "uikit-base: a floating label floats over a text control or a textarea" {:type type})))
    (when (or (some? before) (some? after))
      (throw (ex-info "uikit-base: a floating label floats over its control alone, never an input-group" {})))
    (when (str/blank? (str (:placeholder (normalised attrs))))
      (throw (ex-info "uikit-base: a floating label needs a placeholder: it floats when the placeholder is not shown" {:id id}))))
  (if (= "radio" type)
    (radio-group request opts)
  (let [attrs    (normalised attrs)
        base     (or (:id attrs) id)
        error-id (str base "-error")
        hint-id  (str base "-hint")
        switch?  (= "switch" type)
        check?   (or switch? (= "checkbox" type))
        described (distinct (concat [(when error error-id) (when hint hint-id)]
                                    (str/split (str/trim (str (:aria-describedby attrs ""))) #"\s+")))
        described (str/join " " (remove str/blank? described))
        own      (cond-> {:id id :name id :class (cond switch? "ui-check ui-switch" check? "ui-check" (= "range" type) "ui-range"
                                                       size (str "ui-input ui-input--" (name size)) :else "ui-input")}
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
                   "switch"   [:input (cond-> (merge {:value "on"} own {:type "checkbox" :role "switch"}) value (assoc :checked true))]
                   [:input (merge {:type (or type "text") :value value} own)])
        control  (if (or (some? before) (some? after)) (input-group {:before before :after after} control) control)
        label    (cond-> [:label.ui-field__label {:for (:id own)} label]
                   required? (conj [:span.ui-field__req {:aria-hidden "true"} "*"]))]
    ;; A plain field is the very data 0.1.0 answered, so a host test that compares the
    ;; Hiccup, not the HTML, sees no change; a modifier is the wrapper's own class.
    (let [hint (when hint [:span.ui-field__hint {:id hint-id} hint])]
      (-> (cond-> [:div.ui-field]
            (or check? inline? floating?) (conj {:class (str/join " " (cond-> [] check? (conj "ui-field--check") inline? (conj "ui-field--inline")
                                                                          floating? (conj "ui-field--floating")))}))
          (into (remove nil? (cond check? [control label hint] floating? [control label hint] :else [label hint control])))
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

(defn- form-name
  "A query pair's name as Ring's form decoding reads it, or as spelt when it does not decode."
  [raw]
  (try (java.net.URLDecoder/decode ^String raw java.nio.charset.StandardCharsets/UTF_8)
       (catch IllegalArgumentException _ raw)))

(defn- without-param
  "The request's own path and query with every pair named `param` taken out, the others
  kept in their order and copied byte for byte — nothing decoded, so nothing re-encoded
  differently; the bare path when none is left. A pair's name is what precedes its first
  `=`, compared as Ring reads it — percent-decoded, `+` a space, case kept — so a name
  spelt `out%63ome` goes as `outcome` does; one that does not decode is compared as it is
  spelt. An empty pair (`&&`, a leading or trailing `&`)
  carries nothing and is dropped; so is one holding a backslash, whitespace or a control
  character, which `wb/link` would refuse the whole way out for — a crafted query must not
  turn the page into a refusal."
  [{:keys [uri query-string]} param]
  (let [kept (for [pair (str/split (or query-string "") #"&")
                   :when (not= "" pair)
                   :when (not (re-find #"[\\\s\p{Cntrl}]" pair))
                   :when (not= param (form-name (first (str/split pair #"=" 2))))]
               pair)]
    (if (seq kept) (str uri "?" (str/join "&" kept)) uri)))

(defn alert
  "`(alert request kind opts? & body)` → a `notice` that may carry a heading and a way out.
  `:heading` is drawn above the body. The way out is a `wb/link` back to the page without
  the `?outcome=` that drew the alert, so it goes and a reload does not bring it back,
  named by the kit's word `:uikit-base/close` in the reader's language, or by
  `:dismiss-label`. Either the host computes it, as `:dismiss-href`, or the kit does, from
  `:dismiss-param \"outcome\"`: the request's own `:uri` and `:query-string` with every
  pair of that name taken out and every other one kept, so a page's `?day=` or filter
  survives the dismissal. `(:uri request)` alone drops them. The name is one the query
  spells one way only — letters, digits, `-`, `.`, `_`, `~` — and the two options are one
  or the other. Its kind and role are `notice`'s, and no attribute changes the role; the
  rest of `opts` are its attributes."
  [request kind & more]
  (when-not (notice-roles kind)
    (throw (ex-info "uikit-base: an alert is :ok, :info, :warning or :error" {:kind kind})))
  (let [[opts body] (split-attrs more)
        {:keys [heading dismiss-href dismiss-param dismiss-label]} opts
        _ (when (and (some? dismiss-href) (some? dismiss-param))
            (throw (ex-info "uikit-base: an alert's way out is :dismiss-href or :dismiss-param, never both" {})))
        _ (when (and (some? dismiss-param) (not (and (string? dismiss-param) (re-matches #"[A-Za-z0-9._~-]+" dismiss-param))))
            (throw (ex-info "uikit-base: an alert's :dismiss-param is a query parameter's name: letters, digits, -, ., _ or ~"
                            {:dismiss-param dismiss-param})))
        _ (when (and (some? dismiss-param) (not (string? (:uri request))))
            (throw (ex-info "uikit-base: an alert's :dismiss-param builds its way out from the request's :uri, which this request lacks" {})))
        dismiss-href (if (some? dismiss-param) (without-param request dismiss-param) dismiss-href)]
    (cond-> [:div (with-attrs {:class (str "ui-notice ui-notice--" (name kind) " ui-alert") :role (notice-roles kind)}
                    (dissoc opts :heading :dismiss-href :dismiss-param :dismiss-label) [:role])
             (into (cond-> [:div.ui-alert__body] (some? heading) (conj [:p.ui-alert__heading heading])) body)]
      (some? dismiss-href) (conj (wb/link dismiss-href {:class "ui-close ui-alert__dismiss"}
                                          [:span {:aria-hidden "true"} "\u00d7"]
                                          [:span.ui-sr-only (kit-word request dismiss-label :uikit-base/close)])))))

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
  `\"\"`. `:row-header?` makes the first drawn cell of each row its header. A cell of
  `{:class \"ui-num\"}` holds a number, aligned to its end in figures of one width.

  `scroll-label` puts the table in a region that scrolls sideways on a narrow screen,
  named by it and reachable by the keyboard, which is how a keyboard scrolls it; without
  one the table is drawn alone. `:striped? true` grounds every other body row,
  `:hover? true` the row under the pointer, `:bordered? true` draws every cell's edge and
  `:compact? true` halves the cells' padding."
  [{:keys [caption head rows row-header? attrs row-attrs scroll-label striped? hover? bordered? compact?]}]
  (when (and (some? scroll-label) (not (and (string? scroll-label) (not (str/blank? scroll-label)))))
    (throw (ex-info "uikit-base: a table's scroll-label names its region" {:scroll-label scroll-label})))
  (let [gone  (set (keep-indexed (fn [i h] (when (nil? h) i)) head))
        kept  (fn [cells] (->> cells (map-indexed vector) (remove (comp gone first)) (map second)))
        class (str/join " " (cond-> ["ui-table"] striped? (conj "ui-table--striped") hover? (conj "ui-table--hover")
                                     bordered? (conj "ui-table--bordered") compact? (conj "ui-table--compact")))
        table [:table (with-attrs {:class class} attrs [])
               (when caption [:caption caption])
               (when (seq head) [:thead [:tr (for [h (kept head)] (cell-of :th {:scope "col"} h))]])
               [:tbody (for [row rows]
                         (into [:tr (with-attrs {} (when row-attrs (row-attrs row)) [])]
                               (map-indexed (fn [i c] (if (and row-header? (zero? i)) (cell-of :th {:scope "row"} c) (cell-of :td {} c)))
                                            (kept row))))]]]
    (if scroll-label
      [:div {:class "ui-table-wrap" :role "region" :tabindex "0" :aria-label scroll-label} table]
      table)))

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
  {:en {:uikit-base {:previous "Previous" :next "Next" :pages "Pages" :breadcrumbs "Breadcrumbs"
                     :shown-once "Copy it now: it will not be shown again." :close "Close" :loading "Loading…" :menu "Menu"
                     :carousel "carousel" :slide "slide" :slide-of "%1 of %2" :previous-slide "Previous slide"
                     :next-slide "Next slide" :stop-rotation "Stop automatic slide show" :start-rotation "Start automatic slide show"}}
   :es {:uikit-base {:previous "Anterior" :next "Siguiente" :pages "Páginas" :breadcrumbs "Ruta de navegación"
                     :shown-once "Cópialo ahora: no se volverá a mostrar." :close "Cerrar" :loading "Cargando…" :menu "Menú"
                     :carousel "carrusel" :slide "diapositiva" :slide-of "%1 de %2" :previous-slide "Diapositiva anterior"
                     :next-slide "Diapositiva siguiente" :stop-rotation "Detener el pase automático" :start-rotation "Iniciar el pase automático"}}})

(defn- kit-word
  "The caller's word, or the kit's `k` in the reader's language when the caller gave none
  or a blank one, which would leave what it names unnamed."
  [request own k]
  (or (given own)
      (some-> (:wb/tr request) (apply [k []]))
      (get-in words [:en :uikit-base (keyword (name k))])))

(declare named-items!)

(defn pager
  "`(pager request opts)` → links to the page before and after, or nil when there is
  neither. `previous` and `next` are paths (as web-base's `paging/path` answers them) or
  nil, each a `wb/link` with its `rel`; `where` says where the reader is (\"page 2 of 5\"),
  between them; `attrs` go on the nav. The links and the nav are named by the kit's own
  words in the reader's language — `:uikit-base/previous`, `:uikit-base/next`,
  `:uikit-base/pages`, which the plugin brings and a host's dictionary may override, in
  English where neither speaks the reader's — unless `previous-label`, `next-label` or
  `label` say otherwise. `pages`, `[[path n] …]`, numbers the pages between the two, each
  a `wb/link`, the one whose number is `current` marked `aria-current=page`; a current
  that is no page's number, or a page with no number, is refused. `size`, `:sm` or `:lg`,
  sizes its type."
  [request {:keys [previous next where label previous-label next-label attrs pages current size]}]
  (when-not (or (nil? size) (sizes size))
    (throw (ex-info "uikit-base: a pager's :size is :sm or :lg" {:size size})))
  (named-items! "pager" pages)
  (when (and (some? current) (not-any? #(= current (second %)) pages))
    (throw (ex-info "uikit-base: a pager's current is one of its pages" {:current current})))
  (when (or previous next (seq pages))
    (cond-> [:nav (with-attrs {:class (cond-> "ui-pager" size (str " ui-pager--" (name size)))
                               :aria-label (kit-word request label :uikit-base/pages)} attrs [])
             (when previous (wb/link previous {:rel "prev"} (kit-word request previous-label :uikit-base/previous)))]
      (seq pages) (conj (into [:ul.ui-pager__pages] (for [[p n] pages] [:li (wb/link p (when (= n current) {:aria-current "page"}) n)])))
      true (conj (when where [:span.ui-pager__where where])
                 (when next (wb/link next {:rel "next"} (kit-word request next-label :uikit-base/next)))))))

(defn- named-items!
  "Refuses an item with nothing to name its link: a link without a name is read as its
  address, or not at all."
  [component items]
  (when (some (fn [[_ l]] (or (nil? l) (and (string? l) (str/blank? l)))) items)
    (throw (ex-info (str "uikit-base: every item of a " component " has a label") {}))))

(defn crumbs
  "`(crumbs request opts)` → where this page sits: `items`, `[[path label] …]`, each a
  `wb/link` but the last, `[nil label]`, which is this page and says so
  (`aria-current`). The nav is named by the kit's word `:uikit-base/breadcrumbs` in the
  reader's language unless `label` says otherwise; `attrs` go on the nav. Every item but
  the last has a path and the last has none, or the call is refused."
  [request {:keys [items label attrs]}]
  (let [[here & up] (reverse items)]
    (when (or (nil? here) (some? (first here)) (some (comp nil? first) up))
      (throw (ex-info "uikit-base: a crumbs' items are paths up to this page, which has none" {})))
    (named-items! "crumbs" items)
    [:nav (with-attrs {:class "ui-crumbs" :aria-label (kit-word request label :uikit-base/breadcrumbs)} attrs [])
     (into [:ol] (concat (for [[p l] (reverse up)] [:li (wb/link p nil l)])
                         [[:li [:span {:aria-current "page"} (second here)]]]))]))

(defn tabs-nav
  "`(tabs-nav request opts)` → links between views of one list (all, open, closed): a
  nav named by `label` — required, since a page holds the shell's nav beside it — whose
  `items`, `[[path label] …]`, are `wb/link`s, the one whose path is `current` marked
  `aria-current`. Links, not a tablist: each is a page. No items, two items with one
  path, or a `current` that is no item's path, is refused; nil marks none. `:style
  :pills` draws each as a pill, the current one filled; `:fill? true` shares the line
  among the items by their width, `:justify? true` in equal parts — one or the other."
  [request {:keys [label items current attrs style fill? justify?]}]
  (when-not (contains? #{nil :tabs :pills} style)
    (throw (ex-info "uikit-base: a tabs-nav's :style is :tabs or :pills" {:style style})))
  (when (and fill? justify?)
    (throw (ex-info "uikit-base: a tabs-nav is :fill? or :justify?, not both" {})))
  (when-not (and (string? label) (not (str/blank? label)))
    (throw (ex-info "uikit-base: a tabs-nav is named by its label" {:label label})))
  (when (or (empty? items) (not= (count items) (count (distinct (map first items)))))
    (throw (ex-info "uikit-base: a tabs-nav's items are one or more, each its own path" {})))
  (named-items! "tabs-nav" items)
  (when (and (some? current) (not-any? #(= current (first %)) items))
    (throw (ex-info "uikit-base: a tabs-nav's current is one of its items' paths" {:current current})))
  [:nav (with-attrs {:class (str/join " " (cond-> ["ui-tabs"] (= :pills style) (conj "ui-tabs--pills")
                                             fill? (conj "ui-tabs--fill") justify? (conj "ui-tabs--justify")))
                     :aria-label label} attrs [:aria-label])
   (into [:ul] (for [[p l] items] [:li (wb/link p (when (= p current) {:aria-current "page"}) l)]))])

(declare id!)

(def ^:private expands #{:sm :md :lg})

(defn navbar
  "`(navbar request opts)` → the bar at the top of every page, passed as web-base's shell
  `:header` with its `:nav` and `:identity` left empty: the bar holds both, and a second nav
  beside it would be a second landmark. `:brand`, `[path label]`, is a `wb/link` at its start.
  `:items`, `[[path content attrs?] …]`, are `wb/link`s in a nav named by `:label`, required,
  marked `data-wb-nav` so web-base's `nav.js` moves `aria-current=page` after every swap; the
  one whose path is `:current` is marked so already, and a `:current` that is no item's path —
  a page the nav does not list, a 404 — marks none, so `(:uri request)` serves every page. An
  item's `attrs`, a map, go on its link — an id an out-of-band swap targets survives — its
  `:class` joined to the kit's; `{:aria-current \"true\"}` or `\"location\"` there marks the
  section a page belongs to, drawn as the current page is, and `nav.js` leaves it. An item
  with a nil path holds its content as it is: a `dropdown`, drawn as one of the links, or an
  `[:a]` of the host's own that carries htmx a `wb/link` refuses, painted as the kit's links
  are. `:end` — the identity, a sign-out form — sits after the nav; a kit button in it, at
  any depth, is drawn with the bar's thin block padding whatever its size, in the bar and in
  its panel alike, so inline it stands no taller than the bar's compact row.

  Below `:expand`, `:sm`, `:md` (unless given) or `:lg`, the items and the end are a panel —
  the platform's popover, `:id` its one-word id — that a toggle named by the kit's word
  `:uikit-base/menu` opens under the bar; Esc and a click outside close it, and so does a link
  chosen in it, by the plugin's `/ui/ui.js`; it reaches the bottom of the screen at most and
  scrolls inside itself past that. From that width up the toggle is gone and the panel is
  drawn inline. `:sticky? true` keeps the bar at the top as the page scrolls, and a fragment
  or a focus scrolled to stops below it. In the shell's header it follows its `:header-width`:
  across the window under `:full`, on the column under `:measure`, and under `:aligned` on
  the column with its content, its panel's too, over `<main>`'s text and its ground painted
  out to the window's edges — as `border-image`, which a host restyling the bar's background
  or bottom border there restyles too.
  `:attrs` go on the bar. No items, two with one path, an item with nothing in it, or an
  `:expand` that is not one of the three, is refused."
  [request {:keys [id brand label items current end expand sticky? attrs]}]
  (id! "a navbar" :id id)
  (named! "a navbar" label)
  (when-not (or (nil? expand) (expands expand))
    (throw (ex-info "uikit-base: a navbar's :expand is :sm, :md or :lg" {:expand expand})))
  (when (empty? items) (throw (ex-info "uikit-base: a navbar's items are one or more" {})))
  (named-items! "navbar" items)
  (when-let [bad (first (keep (fn [[_ _ a]] (when (and (some? a) (not (map? a))) [a])) items))]
    (throw (ex-info "uikit-base: a navbar item's attributes are a map" {:attributes (first bad)})))
  (let [paths (keep first items)]
    (when-not (= (count paths) (count (distinct paths)))
      (throw (ex-info "uikit-base: a navbar's items are each its own path" {}))))
  (when-not (or (nil? brand) (and (vector? brand) (= 2 (count brand)) (not (nothing? (second brand)))))
    (throw (ex-info "uikit-base: a navbar's brand is [path label], the label something to read" {})))
  (let [class (str "ui-navbar ui-navbar--expand-" (name (or expand :md)) (when sticky? " ui-navbar--sticky"))]
    (cond-> [:div (with-attrs {:class class} attrs [])]
      (some? brand) (conj (wb/link (first brand) {:class "ui-navbar__brand"} (second brand)))
      ;; One box for the toggle and its panel, drawn as none, so ui.js places the panel under
      ;; its parent's first child, the toggle, as it places a dropdown's menu.
      true (conj [:div.ui-navbar__collapse
                  [:button {:type "button" :class "ui-btn ui-navbar__toggle" :popovertarget id}
                   [:span.ui-navbar__bars {:aria-hidden "true"}]
                   [:span.ui-sr-only (kit-word request nil :uikit-base/menu)]]
                  (cond-> [:div {:id id :popover "auto" :class "ui-navbar__panel"}
                           [:nav {:aria-label label :data-wb-nav true}
                            (into [:ul.ui-navbar__items]
                                  (for [[p c item-attrs] items]
                                    [:li (if (some? p)
                                           (wb/link p (cond-> (with-attrs {:class "ui-navbar__link"} item-attrs [])
                                                        (= p current) (assoc :aria-current "page"))
                                                    c)
                                           c)]))]]
                    (not (nothing? end)) (conj [:div.ui-navbar__end end]))]))))

(defn list-group
  "`(list-group opts)` → items in one bordered column. `:items`, `[[path content attrs?] …]`:
  an item with a path is a `wb/link` that fills its row, the one whose path is `:current`
  marked `aria-current=page`; an item with a nil path holds its content as it is — text,
  or an `action` whose button acts on it. An item's `attrs`, a map, go on its `<li>` — an
  `id`, `data-*`, `draggable` or a hook class a host's script and tests read — its `:class`
  joined to the kit's. `:flush? true` drops the outer edge and corners, for a list inside
  a card; `:attrs` go on the list. No items, a current that is no item's path, an item
  with nothing in it, or an item's attrs that are not a map, is refused."
  [{:keys [items current flush? attrs]}]
  (when (empty? items)
    (throw (ex-info "uikit-base: a list-group's items are one or more" {})))
  (named-items! "list-group" items)
  (when-let [bad (first (keep (fn [[_ _ a]] (when (and (some? a) (not (map? a))) [a])) items))]
    (throw (ex-info "uikit-base: a list-group item's attributes are a map" {:attributes (first bad)})))
  (when (and (some? current) (not-any? #(= current (first %)) items))
    (throw (ex-info "uikit-base: a list-group's current is one of its items' paths" {:current current})))
  (into [:ul (with-attrs {:class (cond-> "ui-list-group" flush? (str " ui-list-group--flush"))} attrs [])]
        (for [[p c item-attrs] items]
          [:li (with-attrs {:class "ui-list-group__item"} item-attrs [])
           (if (some? p) (wb/link p (cond-> {:class "ui-list-group__action"} (= p current) (assoc :aria-current "page")) c) c)])))

(defn secret
  "`(secret request {:id :label :value :attrs})` → a value shown once — a token, a feed's
  address — in a labelled, read-only field the reader selects and copies, in figures of
  one width, described by the kit's word `:uikit-base/shown-once` in the reader's
  language — or by `note`, the host's own sentence in its place — then by the caller's
  own `aria-describedby`. It is never sent: it has no
  name. `attrs` go on the field, never its `id`, `readonly`, `value`, `type`, `name` or
  `disabled`, which would stop it being copied."
  [request {:keys [id label value attrs note]}]
  (when (or (not (string? id)) (str/blank? id) (re-find #"\s" id)
            (nil? label) (and (string? label) (str/blank? label)) (str/blank? (str value)))
    (throw (ex-info "uikit-base: a secret has an id (one word), a label and a value" {:id id})))
  (let [attrs (normalised attrs)
        once  (str id "-once")
        desc  (str/join " " (remove str/blank? (distinct (cons once (str/split (str/trim (str (:aria-describedby attrs ""))) #"\s+")))))]
    [:div.ui-field
     [:label.ui-field__label {:for id} label]
     [:input (with-attrs {:id id :type "text" :class "ui-input ui-input--code" :readonly true :value value
                          :spellcheck "false" :autocomplete "off" :aria-describedby desc}
                         (dissoc attrs :name :disabled :aria-describedby) [:id :readonly :value :type])]
     [:span.ui-field__hint {:id once} (if (or (nil? note) (and (string? note) (str/blank? note))) (kit-word request nil :uikit-base/shown-once) note)]]))

(defn thread
  "`(thread attrs? & messages)` → a conversation read top to bottom, each a `message`."
  [& more]
  (let [[attrs body] (split-attrs more)]
    (into [:ol (with-attrs {:class "ui-thread"} attrs [])] body)))

(defn message
  "`(message {:meta :attrs} & body)` → one message of a `thread`: `meta` — who, when — in
  its header, then its body. `attrs` go on the message."
  [{:keys [meta attrs]} & body]
  [:li (into (cond-> [:article (with-attrs {:class "ui-message"} attrs [])]
               (not (or (nil? meta) (false? meta) (and (string? meta) (str/blank? meta)))) (conj [:header.ui-message__meta meta]))
             body)])

(def ^:private badge-kinds #{:neutral :success :danger :warning :info})

(defn badge
  "`(badge kind attrs? & body)` → a status as a small filled label: `kind` is `:neutral`,
  `:success`, `:danger`, `:warning` or `:info`. The colour is the kind's, never the only
  sign: the body names it. A count says what it counts through `:label`, read after it by
  a screen reader and never shown — `(badge :info {:label \"unread\"} 3)` is \"3 unread\".
  `:corner? true` sets it on the corner of its container, at the inline end, which must be
  positioned (`ui-position-relative`)."
  [kind & more]
  (when-not (badge-kinds kind)
    (throw (ex-info "uikit-base: a badge is :neutral, :success, :danger, :warning or :info" {:kind kind})))
  (let [[attrs body] (split-attrs more)
        {:keys [label corner?]} attrs]
    (cond-> (into [:span (with-attrs {:class (str "ui-badge" (when (not= :neutral kind) (str " ui-badge--" (name kind))) (when corner? " ui-badge--corner"))}
                           (dissoc attrs :label :corner?) [])]
                  body)
      (subject-of label) (conj (subject-of label)))))

(defn- finite? [x] (and (number? x) (not (ratio? x)) (Double/isFinite (double x))))

(defn progress
  "`(progress {:value :max :label :striped? :animated? :attrs})` → the platform's
  `<progress>`, named by `:label`, which is required: a bar nobody names is read as a
  number about nothing. `:value` runs from 0 to `:max` (1 unless given); without one it
  says only that something is under way. `:striped? true` stripes its bar and
  `:animated? true` moves the stripes, which stop where the reader asked for reduced
  motion. Its value, max and name are its own; `attrs` are the rest."
  [{:keys [value max label striped? animated? attrs]}]
  (named! "a progress" label)
  (let [mx (if (nil? max) 1 max)]
    (when-not (and (finite? mx) (pos? mx))
      (throw (ex-info "uikit-base: a progress's :max is a number above zero" {:max max})))
    (when-not (or (nil? value) (and (finite? value) (<= 0 value mx)))
      (throw (ex-info "uikit-base: a progress's :value is a number from 0 to its :max" {:value value})))
    [:progress (with-attrs (cond-> {:class (str/join " " (cond-> ["ui-progress"] (or striped? animated?) (conj "ui-progress--striped")
                                                           animated? (conj "ui-progress--animated")))
                                    :max mx :aria-label label}
                             (some? value) (assoc :value value))
                           (dissoc (normalised attrs) :value) [:max :aria-label])]))

(defn meter
  "`(meter {:value :min :max :low :high :optimum :label :attrs})` → the platform's
  `<meter>`: a measure within a known range — places taken, a score — named by `:label`,
  which is required. `:min` is 0 and `:max` 1 unless given; `:low`, `:high` and
  `:optimum` say which part of the range is good, and the bar is drawn in success,
  warning or danger by the browser's reading of them. The value and each of the three
  lie within the range, and a low is never above a high. They and the name are its own;
  `attrs` are the rest."
  [{:keys [value min max low high optimum label attrs]}]
  (named! "a meter" label)
  (let [mn (if (nil? min) 0 min) mx (if (nil? max) 1 max)]
    (when-not (and (finite? mn) (finite? mx) (< mn mx))
      (throw (ex-info "uikit-base: a meter's :min is a number below its :max" {:min min :max max})))
    (when-not (and (finite? value) (every? #(or (nil? %) (and (finite? %) (<= mn % mx))) [value low high optimum]))
      (throw (ex-info "uikit-base: a meter's :value, :low, :high and :optimum are numbers from its :min to its :max"
                      {:value value :low low :high high :optimum optimum})))
    (when (and (some? low) (some? high) (> low high))
      (throw (ex-info "uikit-base: a meter's :low is not above its :high" {:low low :high high})))
    (let [own (into {:class "ui-meter" :value value :min mn :max mx :aria-label label}
                    (filter (comp some? val)) {:low low :high high :optimum optimum})]
      [:meter (with-attrs own (dissoc (normalised attrs) :low :high :optimum) (vec (keys own)))])))

(defn spinner
  "`(spinner request opts?)` → a turning ring that says something is loading: a `status`
  whose text, the kit's word `:uikit-base/loading` in the reader's language or `:label`,
  a screen reader reads and the eye does not. It stops turning where the reader asked for
  reduced motion. The rest of `opts` are its attributes; its role is its own."
  [request & [opts]]
  [:span (with-attrs {:class "ui-spinner" :role "status"} (dissoc opts :label) [:role])
   [:span.ui-sr-only (kit-word request (:label opts) :uikit-base/loading)]])

(defn placeholder
  "`(placeholder request opts?)` → what stands in for content still loading: `:lines`
  bars (three unless given), hidden from a screen reader, in a container that is
  `aria-busy` and says the kit's word `:uikit-base/loading` (or `:label`) to it instead.
  The bars glow, except where the reader asked for reduced motion. The rest of `opts` are
  the container's attributes; that it is busy is its own."
  [request & [opts]]
  (let [n (get opts :lines 3)]
    (when-not (and (int? n) (pos? n))
      (throw (ex-info "uikit-base: a placeholder's :lines is a whole number from 1" {:lines n})))
    (into [:div (with-attrs {:class "ui-placeholders" :aria-busy "true"} (dissoc opts :lines :label) [:aria-busy])
           [:span.ui-sr-only (kit-word request (:label opts) :uikit-base/loading)]]
          (repeat n [:span {:class "ui-placeholder" :aria-hidden "true"}]))))

(defn empty
  "`(empty attrs? & body)` → what a list says when it has nothing to list: \"No orders
  yet.\" in the kit's quiet voice."
  [& more]
  (let [[attrs body] (split-attrs more)]
    (into [:p (with-attrs {:class "ui-empty"} attrs [])] body)))

(defn pairs
  "`(pairs attrs? entries)` → a description list: `entries`, `[[term description] …]`, in
  order, a term whose description is nil left out — so an optional detail is
  `[\"Paid\" (when paid? when)]`."
  [& more]
  (let [[attrs [entries & extra]] (split-attrs more)]
    (when (or (seq extra) (not (every? #(and (sequential? %) (= 2 (count %))) entries)))
      (throw (ex-info "uikit-base: a pairs takes its attrs and one sequence of [term description]" {})))
    (into [:dl (with-attrs {:class "ui-pairs"} attrs [])]
          (mapcat (fn [[t d]] (when (some? d) [[:dt t] [:dd d]])) entries))))

(defn- datetime
  "`t` as the machine-readable form a `<time>` carries, or nil for a value that is no
  moment or day."
  [t]
  (condp instance? t
    Long (datetime (java.time.Instant/ofEpochMilli t))
    Integer (datetime (java.time.Instant/ofEpochMilli (long t)))
    ;; Past year 9999 or before year 1 an Instant prints a sign HTML's datetime has none of.
    java.time.Instant (when (<= -62135596800000 (.toEpochMilli ^java.time.Instant t) 253402300799999) (str t))
    java.time.OffsetDateTime (str t)
    java.time.ZonedDateTime (str (.toOffsetDateTime ^java.time.ZonedDateTime t))
    java.time.LocalDateTime (str t)
    java.time.LocalDate (str t)
    nil))

(defn time
  "`(time t attrs? & body)` → a `<time>` whose `datetime` is `t` — an `Instant`, epoch
  milliseconds (as a database column holds them), an offset or zoned date-time, a local
  date-time or a date — and whose body is how the page
  shows it, which is the host's: it knows the reader's language and zone. Anything else
  is refused; no attribute changes `datetime`."
  [t & more]
  (let [dt (or (datetime t) (throw (ex-info "uikit-base: a time is a java.time moment, date-time or date, or epoch milliseconds" {:value t})))
        [attrs body] (split-attrs more)]
    (when-not (seq body) (throw (ex-info "uikit-base: a time shows something: its body is how the page says it" {})))
    (into [:time (with-attrs {:datetime dt} attrs [:datetime])] body)))

(defn page-head
  "`(page-head {:title :subtitle :level :actions :attrs})` → a page's own heading inside
  `<main>`, never the shell's header, which holds the page's `h1`: `title` as an `h2`
  (or the `level`, 1 to 3), a
  `subtitle` under it, and `actions` — links or buttons — beside it, wrapping under it
  on a narrow screen."
  [{:keys [title subtitle level actions attrs] :or {level 2}}]
  (when (or (nil? title) (false? title) (and (string? title) (str/blank? title)))
    (throw (ex-info "uikit-base: a page-head has a title" {})))
  (when-not (#{1 2 3} level) (throw (ex-info "uikit-base: a page-head's level is 1, 2 or 3" {:level level})))
  (cond-> [:div (with-attrs {:class "ui-page-head"} attrs [])
           [(keyword (str "h" level)) {:class "ui-page-head__title"} title]]
    subtitle (conj [:p.ui-page-head__sub subtitle])
    (seq actions) (conj (into [:div.ui-page-head__actions.ui-cluster] actions))))

(defn- image!
  "An `<img>` of `class` from `{:src :alt :decorative? :attrs}`: a src, and an alt that
  says what it shows — empty only when `:decorative? true` says the image shows nothing a
  reader would miss. Its src and alt are its own; `:attrs` are the rest (a width and a
  height, so the page does not jump; `loading`)."
  [component class {:keys [src alt decorative? attrs]}]
  (when-not (and (string? src) (not (str/blank? src)))
    (throw (ex-info (str "uikit-base: " component "'s image has a src") {:src src})))
  (if decorative?
    (when-not (or (nil? alt) (= "" alt))
      (throw (ex-info (str "uikit-base: " component "'s decorative image has an empty alt") {:alt alt})))
    (when-not (and (string? alt) (not (str/blank? alt)))
      (throw (ex-info (str "uikit-base: " component "'s image has an alt that says what it shows, or is :decorative? true") {:alt alt}))))
  [:img (with-attrs {:class class :src src :alt (if decorative? "" alt)} attrs [:src :alt])])

(defn card
  "`(card attrs? & body)` → a raised surface holding its body. `attrs` may name a `:title`,
  drawn as the card's heading (an `h2`, or the `:level`, 2 to 4), and a `:footer`, set
  under the body; an `:image`, `{:src :alt :decorative? :attrs}`, drawn edge to edge at
  its top — its alt says what it shows, and is empty only when `:decorative? true`; and a
  `:nav` — a `tabs-nav`, say — in a header strip above the title. The rest are the card's
  attributes."
  [& more]
  (let [[opts body] (split-attrs more)
        {:keys [title level footer image nav] :or {level 2}} opts]
    (when (and title (not (#{2 3 4} level)))
      (throw (ex-info "uikit-base: a card's level is 2, 3 or 4" {:level level})))
    (cond-> (into (cond-> [:div (with-attrs {:class "ui-card"} (dissoc opts :title :level :footer :image :nav) [])]
                    image (conj (image! "a card" "ui-card__img" image))
                    nav (conj [:div.ui-card__nav nav])
                    title (conj [(keyword (str "h" level)) {:class "ui-card__head"} title]))
                  body)
      footer (conj [:div.ui-card__foot footer]))))

(defn card-group
  "`(card-group attrs? & cards)` → cards side by side as one, their edges joined, wrapping
  on a narrow screen."
  [& more]
  (let [[attrs body] (split-attrs more)]
    (into [:div (with-attrs {:class "ui-card-group"} attrs [])] body)))

(defn figure
  "`(figure {:image :caption :attrs})` → an image and the caption that says what it is: a
  `<figure>` whose `<figcaption>` is `:caption`, required. `:image` is a card's,
  `{:src :alt :decorative? :attrs}`, its alt required alike."
  [{:keys [image caption attrs]}]
  (when (nothing? caption)
    (throw (ex-info "uikit-base: a figure has a caption" {})))
  [:figure (with-attrs {:class "ui-figure"} attrs [])
   (image! "a figure" "ui-figure__img" image)
   [:figcaption.ui-figure__caption caption]])

(defn- group-name!
  "`n` when it is a name that joins details into one exclusive group, refused otherwise."
  [component n]
  (when-not (or (nil? n) (and (string? n) (not (str/blank? n))))
    (throw (ex-info (str "uikit-base: " component "'s :name is the text that joins its details into one group") {:name n})))
  n)

(defn collapse
  "`(collapse {:summary :open? :name :attrs} & body)` → a `<details>` whose `<summary>` —
  `:summary`, required — opens and closes its body with no script: the summary is the
  control, reached by the keyboard and ringed when focused. `:open? true` draws it open.
  `:name` joins it to every details of that name on the page, of which the platform keeps
  one open at a time. `:attrs` go on the details, never `open`."
  [{:keys [summary open? name attrs]} & body]
  (when (nothing? summary)
    (throw (ex-info "uikit-base: a collapse has a summary" {})))
  (group-name! "a collapse" name)
  [:details (with-attrs (cond-> {:class "ui-collapse"} name (assoc :name name) open? (assoc :open true))
                        (dissoc (normalised attrs) :open) [:name])
   [:summary.ui-collapse__summary summary]
   (into [:div.ui-collapse__body] body)])

(defn accordion
  "`(accordion {:items :name :open :attrs})` → collapses in one bordered column. `:items`,
  `[[summary body] …]`, each summary required. With `:name` they are one group, opening
  one closing the one open, by the platform's `details name`; without, each opens alone.
  `:open` is the index of the item drawn open. `:attrs` go on the column."
  [{:keys [items name open attrs]}]
  (when (empty? items) (throw (ex-info "uikit-base: an accordion's items are one or more" {})))
  (when (some (comp nothing? first) items)
    (throw (ex-info "uikit-base: every item of an accordion has a summary" {})))
  (group-name! "an accordion" name)
  (when-not (or (nil? open) (and (int? open) (< -1 open (count items))))
    (throw (ex-info "uikit-base: an accordion's :open is the index of one of its items" {:open open})))
  (into [:div (with-attrs {:class "ui-accordion"} attrs [])]
        (map-indexed (fn [i [s b]]
                       [:details (cond-> {:class "ui-accordion__item"} name (assoc :name name) (= i open) (assoc :open true))
                        [:summary.ui-accordion__summary s]
                        [:div.ui-accordion__body b]])
                     items)))

(defn scroller
  "`(scroller {:label :attrs} & items)` → items in one line that scrolls sideways, each
  snapping to its start: a region named by `:label`, which is required, and reached by the
  keyboard, which is how a keyboard scrolls it — as a wide table's is. `:attrs` go on the
  region; its role, name and tabindex are its own."
  [{:keys [label attrs]} & items]
  (named! "a scroller" label)
  [:div (with-attrs {:class "ui-scroller" :role "region" :tabindex "0" :aria-label label} attrs [:role :tabindex :aria-label])
   (into [:ul.ui-scroller__list] (for [i items] [:li.ui-scroller__item i]))])

(defn- id!
  "`id` when it is one word an element can carry and a button can name, refused otherwise."
  [component k id]
  (when-not (and (string? id) (re-matches #"[A-Za-z][\w-]*" id))
    (throw (ex-info (str "uikit-base: " component "'s " k " is one word, a letter first, that an element carries as its id") {k id})))
  id)

(def ^:private dialog-sizes #{:sm :lg})

(defn- sheet
  "The `<dialog>` a `dialog` and an `offcanvas` share: `class` its look, named by its title."
  [request component class {:keys [id title static? scrollable? footer attrs]} body]
  (id! component :id id)
  (when (nothing? title)
    (throw (ex-info (str "uikit-base: " component " has a title, which names it") {:id id})))
  (let [title-id (str id "-title")]
    (cond-> [:dialog (with-attrs {:id id :class class :aria-labelledby title-id :closedby (if static? "none" "any")}
                                 (dissoc (normalised attrs) :open) [:id :aria-labelledby :closedby])
             [:div.ui-dialog__head
              [:h2 {:id title-id :class "ui-dialog__title"} title]
              (close-button request {:commandfor id :command "close"})]
             (into [:div (cond-> {:class "ui-dialog__body"} scrollable? (assoc :tabindex "0"))] body)]
      (not (or (nothing? footer) (and (coll? footer) (every? nothing? footer)))) (conj [:div.ui-dialog__foot footer]))))

(defn dialog
  "`(dialog request opts & body)` → the platform's modal `<dialog>`, opened by a
  `dialog-button` naming its `:id` — one word — and named by its `:title`, required, drawn
  as its `h2` beside a `close-button` that closes it. Esc closes it, and so does a click on
  the backdrop, unless `:static? true`, for a dialog that must be answered: then only its
  own buttons do. The focus goes into it when it opens and back to the button that opened
  it when it closes. `:size`, `:sm` or `:lg`, narrows or widens it; `:fullscreen? true`
  fills the screen; `:scrollable? true` keeps its head and foot in view and scrolls its
  body, which the keyboard then reaches. `:footer` is drawn under the body — its buttons.
  A form inside is a `post-form`, whose token holds as on any page; a button that only
  closes it is `(button {:type \"button\" :commandfor id :command \"close\"} …)`. `:attrs`
  go on the dialog, never `open`: a dialog is opened by its button, modal. It needs the
  plugin's `/ui/ui.js` where the browser has no invoker commands or no `closedby`."
  [request {:keys [id size fullscreen?] :as opts} & body]
  (when-not (or (nil? size) (dialog-sizes size))
    (throw (ex-info "uikit-base: a dialog's :size is :sm or :lg" {:size size})))
  (sheet request "a dialog"
         (str/join " " (cond-> ["ui-dialog"] size (conj (str "ui-dialog--" (name size)))
                         fullscreen? (conj "ui-dialog--fullscreen") (:scrollable? opts) (conj "ui-dialog--scrollable")))
         opts body))

(defn dialog-button
  "`(dialog-button opts & body)` → a button that opens the `dialog` whose id is
  `:dialog`, by the platform's invoker command (`commandfor`, `command=show-modal`). Its
  `:variant`, `:size` and `:block?` are `button`'s, refused alike; the rest of `opts` are
  its attributes. Its type, target and command are its own."
  [& more]
  (let [[opts body] (split-attrs more)
        {:keys [dialog variant size block?]} opts]
    (id! "a dialog-button" :dialog dialog)
    (into [:button (with-attrs {:type "button" :class (btn-class variant size block?) :commandfor dialog :command "show-modal"}
                               (dissoc opts :dialog :variant :size :block?) [:type :commandfor :command])]
          body)))

(defn offcanvas
  "`(offcanvas request opts & body)` → a `dialog` drawn as a sheet along one side of the
  screen, `:side` `:start` (unless given) or `:end` — the inline start or end, so a page in a
  right-to-left language draws it on the other side. Opened by a `dialog-button` naming its
  `:id`, named by its `:title`, closed as a dialog is, `:static?` alike; its body scrolls,
  and the keyboard reaches it. A navigation on a narrow screen is one: its links inside."
  [request {:keys [side] :as opts} & body]
  (when-not (contains? #{nil :start :end} side)
    (throw (ex-info "uikit-base: an offcanvas's :side is :start or :end" {:side side})))
  (sheet request "an offcanvas" (str "ui-dialog ui-dialog--scrollable ui-offcanvas ui-offcanvas--" (name (or side :start)))
         (assoc opts :scrollable? true) body))

(defn- toggle
  "The button that opens popover `id`, by the platform's `popovertarget`."
  [component id label variant size]
  (id! component :id id)
  (label! component :label label)
  [:button {:type "button" :class (btn-class variant size) :popovertarget id} label])

(defn dropdown
  "`(dropdown opts)` → a button, `:label`, that opens a menu of `:items` below it — the
  platform's popover, which Esc and a click outside close, and the keyboard reaches after
  the button. `:items`, `[[path content] …]`: an item with a path is a `wb/link`; one with a
  nil path holds its content as it is, an `action` whose button acts. Links and forms, not
  an ARIA menu: Tab moves through them, as through any links. Choosing one closes the menu.
  `:id` is the menu's, one word; `:variant` and `:size` are the button's; `:attrs` go on
  the wrapper. Placed below the button, flipped above when the screen has no room, by CSS
  anchor positioning, or by the plugin's `/ui/ui.js` where the browser has none."
  [{:keys [id label items variant size attrs]}]
  (when (empty? items) (throw (ex-info "uikit-base: a dropdown's items are one or more" {})))
  (named-items! "dropdown" items)
  [:div (with-attrs {:class "ui-dropdown"} attrs [])
   (toggle "a dropdown" id label variant size)
   [:div {:id id :popover "auto" :class "ui-dropdown__menu"}
    (into [:ul.ui-dropdown__items]
          (for [[p c] items] [:li (if (some? p) (wb/link p {:class "ui-dropdown__item"} c) c)]))]])

(defn popover
  "`(popover opts & body)` → a button, `:label`, that opens `body` beside it in a box — the
  platform's popover, which Esc and a click outside close, and the keyboard reaches after
  the button. `:title`, when given, heads the box. `:id` is the box's, one word; `:variant`
  and `:size` are the button's; `:attrs` go on the wrapper. Placed as a `dropdown`'s menu."
  [{:keys [id label title variant size attrs]} & body]
  [:div (with-attrs {:class "ui-popover-wrap"} attrs [])
   (toggle "a popover" id label variant size)
   (into (cond-> [:div {:id id :popover "auto" :class "ui-popover"}] (not (nothing? title)) (conj [:p.ui-popover__head title]))
         body)])

(defn tooltip
  "`(tooltip opts trigger)` → `trigger`, one element that takes the focus — a button, a
  link — described by `:text`, which is shown above it while it is hovered or focused, and
  while the pointer rests on the text itself, and hidden by Esc. The text is the server's,
  in a `role=tooltip` that the trigger's `aria-describedby` names, before any of the
  trigger's own; a screen reader reads it with or without a script. A tooltip says more
  about a control that is named already; it never names one. `:id` is the text's, one word.
  The trigger must take the focus by Tab — a button, a link with an `href`, a form control
  neither hidden nor disabled, a summary, or an element with a `tabindex` of 0 or more — or
  it is refused. Shown by the plugin's
  `/ui/ui.js`."
  [{:keys [id text]} trigger]
  (id! "a tooltip" :id id)
  (when (or (not (string? text)) (str/blank? text))
    (throw (ex-info "uikit-base: a tooltip's :text is what it says about its trigger" {:text text})))
  (when-not (and (vector? trigger) (keyword? (first trigger)))
    (throw (ex-info "uikit-base: a tooltip's trigger is one element, which it describes" {})))
  (let [[tag & more] trigger
        el (keyword (.toLowerCase (str (first (str/split (name tag) #"[.#]"))) java.util.Locale/ROOT))
        a  (normalised (first (split-attrs more)))
        tab (some-> (:tabindex a) str str/trim parse-long)]
    ;; Shown on focus as well as hover: a trigger Tab cannot reach hides it from the keyboard.
    (when-not (and (or (#{:button :select :textarea :summary} el) (and (= :input el) (not= "hidden" (some-> (:type a) (as-> t (if (instance? clojure.lang.Named t) (name t) (str t))) (.toLowerCase java.util.Locale/ROOT))))
                       (and (= :a el) (some? (:href a)) (not (false? (:href a)))) (and (some? (:tabindex a)) (some? tab) (<= 0 tab)))
                   ;; Only a form control is disabled by the attribute; a span or a link keeps its focus.
                   (not (and (#{:button :input :select :textarea} el) (:disabled a)))
                   (not (and (some? (:tabindex a)) (or (nil? tab) (neg? tab)))))
      (throw (ex-info "uikit-base: a tooltip's trigger takes the focus by Tab: a button, a link with an href, a form control that is neither hidden nor disabled, a summary, or an element with a tabindex of 0 or more" {:trigger el}))))
  (let [[tag & more] trigger
        [attrs body] (split-attrs more)
        attrs (normalised attrs)
        described (str/join " " (distinct (cons id (remove str/blank? (str/split (str/trim (str (:aria-describedby attrs ""))) #"\s+")))))]
    [:span.ui-tooltip-wrap
     (into [tag (assoc attrs :aria-describedby described)] body)
     [:span {:id id :popover "manual" :role "tooltip" :class "ui-tooltip"} text]]))

(def ^:private toast-kinds #{:ok :info :warning :error})

(defn toasts
  "`(toasts attrs? & toasts)` → where toasts appear: a polite live region in a corner of the
  screen, at the block end and the inline end. A live region announces what changes in it,
  never what it held when it arrived: a toast is read out only when it is inserted into a
  region already on the page — by htmx into it, `hx-swap=beforeend` — and neither a swap
  of `<main>` that brings the region with its toasts nor an out-of-band swap of the region
  itself announces anything. So it belongs in the page's frame, outside what is swapped.
  `attrs` go on it; that it is live is its own."
  [& more]
  (let [[attrs body] (split-attrs more)]
    (into [:div (with-attrs {:class "ui-toasts" :aria-live "polite"} attrs [:aria-live])] body)))

(defn toast
  "`(toast request opts & body)` → a short message in `toasts`, with a `close-button` that
  removes it. `:kind`, `:ok`, `:info`, `:warning` or `:error`, edges it in that colour, and
  the body says it in words; `:heading` is drawn above the body. `:timeout-ms`, a whole
  number of milliseconds, removes it once it has been shown that long, the time stopped
  while the pointer rests on it or the focus is inside it — so a toast is never the only
  copy of a message: what it says is on the page, or in the place it came from, too (WCAG
  2.2.1). `:attrs` go on the toast. The script that times and closes it is the plugin's
  `/ui/ui.js`; without it a toast stays."
  [request {:keys [kind heading timeout-ms attrs]} & body]
  (when-not (or (nil? kind) (toast-kinds kind))
    (throw (ex-info "uikit-base: a toast's :kind is :ok, :info, :warning or :error" {:kind kind})))
  (when-not (or (nil? timeout-ms) (pos-int? timeout-ms))
    (throw (ex-info "uikit-base: a toast's :timeout-ms is a whole number of milliseconds above zero" {:timeout-ms timeout-ms})))
  [:div (with-attrs (cond-> {:class (str "ui-toast" (when kind (str " ui-toast--" (name kind))))} timeout-ms (assoc :data-timeout-ms timeout-ms))
                    (dissoc (normalised attrs) :data-timeout-ms) [])
   (into (cond-> [:div.ui-toast__body] (not (nothing? heading)) (conj [:p.ui-toast__heading heading])) body)
   (close-button request {:class "ui-toast__close"})])

(defn- kit-said
  "The kit's word `k` with `args` where its `%1`, `%2`… stand, in the reader's language, or
  in the kit's English when the request has no translator."
  [request k args]
  (let [said (or (some-> (:wb/tr request) (apply [k (vec args)])) (get-in words [:en :uikit-base (keyword (name k))]))]
    (reduce-kv (fn [s i a] (str/replace s (str "%" (inc i)) (str a))) said (vec args))))

(defn carousel
  "`(carousel request opts)` → `:slides`, one or more, shown one at a time, as the ARIA
  practices draw a carousel: a region named by `:label`, required, read as a carousel, each
  slide a group read as \"2 of 5\" in the reader's language, and buttons to the previous
  and next slide. Without a script it is a row that scrolls sideways a slide at a time, by
  the keyboard too, its buttons hidden, since they would do nothing. With `:interval-ms`,
  a whole number, the slides advance on their own every so many milliseconds, with a
  button first that stops and starts it; the advance waits while the pointer rests on the
  carousel, stops for good once the focus enters it — from 334 ms, three changes a second
  (WCAG 2.3.1), to 2147483647, the most a browser's timer holds — and never starts where the reader
  asked for reduced motion. `:attrs` go on the region; its role description and name are
  its own. The buttons and the advance are the plugin's `/ui/ui.js`."
  [request {:keys [label slides interval-ms attrs]}]
  (named! "a carousel" label)
  (when-not (and (sequential? slides) (not (and (vector? slides) (keyword? (first slides)))))
    (throw (ex-info "uikit-base: a carousel's :slides is a sequence of slides, never one element or a string" {})))
  (when (empty? slides) (throw (ex-info "uikit-base: a carousel's slides are one or more" {})))
  ;; From 334: WCAG 2.3.1 allows three changes a second. To 2147483647: setInterval takes a
  ;; WebIDL long, and a larger value wraps and fires every few milliseconds.
  (when-not (or (nil? interval-ms) (and (int? interval-ms) (<= 334 interval-ms 2147483647)))
    (throw (ex-info "uikit-base: a carousel's :interval-ms is a whole number of milliseconds from 334 to 2147483647" {:interval-ms interval-ms})))
  (let [w (fn [k] (kit-word request nil k))
        n (count slides)]
    [:section (with-attrs (cond-> {:class "ui-carousel" :aria-roledescription (w :uikit-base/carousel) :aria-label label}
                            interval-ms (assoc :data-interval-ms interval-ms))
                          (dissoc (normalised attrs) :data-interval-ms) [:aria-roledescription :aria-label])
     (cond-> [:div {:class "ui-carousel__controls" :hidden true}]
       interval-ms (conj [:button {:type "button" :class "ui-btn ui-btn--sm ui-carousel__rotate"}
                          [:span.ui-carousel__stop (w :uikit-base/stop-rotation)] [:span.ui-carousel__start (w :uikit-base/start-rotation)]])
       true (conj (button {:type "button" :size :sm :class "ui-carousel__prev"} (w :uikit-base/previous-slide))
                  (button {:type "button" :size :sm :class "ui-carousel__next"} (w :uikit-base/next-slide))))
     (into [:div {:class "ui-carousel__viewport" :tabindex "0" :aria-live "polite"}]
           (map-indexed (fn [i s] [:div {:class "ui-carousel__slide" :role "group" :aria-roledescription (w :uikit-base/slide)
                                         :aria-label (kit-said request :uikit-base/slide-of [(inc i) n])} s])
                        slides))]))

(defn scrollspy
  "`(scrollspy opts)` → links to the sections of this page, `:items` `[[\"#id\" label] …]`,
  each id one word and each once, in a nav named by `:label`, which is required — a page
  holds the shell's nav beside it. The link whose section is in view is marked
  `aria-current=location` as the reader scrolls, by the plugin's `/ui/ui.js`, which watches
  the sections anew after htmx swaps `<main>`; without it the links still go there.
  `:attrs` go on the nav; its name is its own."
  [{:keys [label items attrs]}]
  (named! "a scrollspy" label)
  (when (empty? items) (throw (ex-info "uikit-base: a scrollspy's items are one or more" {})))
  (named-items! "scrollspy" items)
  (when-let [bad (seq (remove #(and (string? %) (re-matches #"#[A-Za-z][\w-]*" %)) (map first items)))]
    (throw (ex-info "uikit-base: a scrollspy's items are #fragments of this page, each one word, a letter first" {:items (vec bad)})))
  (when-not (apply distinct? (map first items))
    (throw (ex-info "uikit-base: a scrollspy's items are each its own section" {})))
  [:nav (with-attrs {:class "ui-scrollspy" :aria-label label} attrs [:aria-label])
   (into [:ul] (for [[f l] items] [:li [:a {:href f} l]]))])
