(ns dev.arkaitz.uikit-base
  "The kit's components: functions that answer Hiccup carrying the kit's classes, for a
  web-base host. Each takes the caller's attributes and merges them with its own: a
  `:class` is added to the kit's, never put in its place; anything else the caller gives
  wins, except what the component's correctness needs — a form's method, action and CSRF
  field, a refused field's ARIA wiring, a notice's role.

  This namespace never loads the colour engine (`color`, `brand`, `audit`, `emit`,
  `build`): a host's colours are generated once, committed, and served as a file."
  (:require [clojure.string :as str]
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
