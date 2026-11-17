(ns dev.arkaitz.uikit-base.gallery
  "Every component of the kit on one page, served by a real web-base handler under the
  template's own CSP, for the eye and for a browser's checks: `clojure -M:gallery [port]`.
  Each fixture is a section whose id names it; `gallery_test` holds that every public
  component appears here, that the page breaks no rule of the CSP, and that every class it
  draws is one the kit's stylesheets define."
  (:require [dev.arkaitz.uikit-base :as ui]
            [dev.arkaitz.uikit-base.web :as web]
            [dev.arkaitz.web-base :as wb]
            [dev.arkaitz.web-base.response :as response]
            [dev.arkaitz.web-base.server :as server]
            [dev.arkaitz.web-base.shell :as shell]))

(def csp
  "The template's policy, verbatim: no inline style, scripts only under the nonce."
  "default-src 'self'; script-src 'nonce-{nonce}'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")

(def ^:private image
  "A picture that needs no file: the CSP allows `data:` images."
  "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='320' height='160'%3E%3Crect width='320' height='160' fill='%23888'/%3E%3C/svg%3E")

(def ^:private dict
  {:en {:gallery {:due "Due %1" :saved "Saved."}}
   :es {:gallery {:due "Vence el %1" :saved "Guardado."}}})

(defn- fixtures [r]
  [["contents"
    (ui/scrollspy {:label "On this page" :items [["#buttons" "Buttons"] ["#forms" "Forms"] ["#feedback" "Feedback"] ["#lists" "Lists"] ["#tables" "Tables"]
                                                 ["#navigation" "Navigation"] ["#cards" "Cards"] ["#overlays" "Overlays"] ["#motion" "Motion"] ["#text" "Text"]]})]
   ["buttons"
    (into [:div.ui-cluster]
          (concat (for [v [:primary :secondary :success :danger :warning :info :link]] (ui/button {:variant v :type "button"} (name v)))
                  (for [v [:outline-primary :outline-danger]] (ui/button {:variant v :type "button"} (name v)))
                  [(ui/button {:size :sm :type "button"} "sm") (ui/button {:size :lg :type "button"} "lg")
                   (ui/link-button "/" {:variant :secondary :subject "the gallery"} "Home")
                   (ui/close-button r)]))
    (ui/button {:block? true :type "button"} "block")
    (ui/button-group {:label "Alignment"} (ui/button {:type "button"} "Start") (ui/button {:type "button"} "Centre") (ui/button {:type "button"} "End"))
    (ui/button-group {:label "Lesson" :id "g-group-confirm"}
      (ui/link-button "/" {:variant :secondary} "Edit")
      (ui/confirm r "/do" {:label "Delete" :confirm-label "Yes, delete" :subject "the lesson"}))
    (ui/toggle-button r "/do" {:label "Bold" :pressed? true :hidden {:on "false"}})]
   ["forms"
    (ui/post-form r "/do"
      (ui/field r {:id "name" :label "Name" :hint "As it appears on your card." :required? true})
      (ui/field r {:id "bad" :label "Email" :error :gallery/saved :value "x"})
      (ui/field r {:id "small" :label "Small" :size :sm})
      (ui/field r {:id "price" :label "Price in euros" :before "€" :after ".00"})
      (ui/field r {:id "float" :label "Floating" :floating? true :attrs {:placeholder "Floating"}})
      (ui/field r {:id "notes" :label "Notes" :type "textarea"})
      (ui/field r {:id "kind" :label "Kind" :type "select" :options [["a" "A"] ["b" "B"]] :value "b"})
      (ui/field r {:id "agree" :label "I agree" :type "checkbox" :value true})
      (ui/field r {:id "wifi" :label "Wi-Fi" :type "switch" :value true})
      (ui/field r {:id "vol" :label "Volume" :type "range" :attrs {:min 0 :max 10 :value 4}})
      (ui/field r {:id "size" :label "Size" :type "radio" :options [["s" "S"] ["m" "M"]] :value "m"})
      (ui/input-group {:after (ui/button {:type "button"} "Go")} [:input.ui-input {:aria-label "Search" :name "q"}])
      (ui/button {:variant :primary} "Save"))]
   ["feedback"
    (ui/notice :ok "Saved.") (ui/notice :error "Refused.")
    (ui/alert r :warning {:heading "Heads up" :dismiss-href "/"} "Something to read.")
    (ui/alert r :ok {:dismiss-param "outcome"} "Dismissed back to this page, its other parameters kept.")
    [:p (ui/badge :neutral "draft") " " (ui/badge :success "paid") " " (ui/badge :info {:label "unread"} 3)]
    [:p.ui-position-relative "Inbox" (ui/badge :danger {:corner? true :label "new"} 9)]
    (ui/progress {:value 0.4 :label "Upload"}) (ui/progress {:label "Working" :striped? true :animated? true})
    (ui/meter {:value 7 :max 10 :low 3 :high 8 :optimum 10 :label "Places taken"})
    (ui/spinner r) (ui/placeholder r {:lines 2})]
   ["lists"
    (ui/list-group {:items [["/" "Home"] ["/l" "List" {:id "g-row-list" :data-row "list"}] [nil "Plain"]] :current "/"})
    (ui/rows (ui/row "A row") (ui/row "Another"))
    (ui/empty "Nothing here yet.")
    (ui/pairs [["Status" "Open"] ["Owner" "Ada"]])
    (ui/thread (ui/message {:meta "Ada, today"} "Hello.") (ui/message {:meta "Bob, today"} "Hi."))
    (ui/secret r {:id "tok" :label "Token" :value "abc123"})]
   ["tables"
    (ui/table {:caption "Orders" :head ["Id" (ui/cell {:class "ui-num"} "Total")] :rows [["1" (ui/cell {:class "ui-num"} "12.00")] ["2" (ui/cell {:class "ui-num"} "7.50")]]
               :striped? true :hover? true :scroll-label "Orders"})]
   ["navigation"
    (ui/page-head {:title "A page" :subtitle "Its subtitle" :actions [(ui/link-button "/" {} "Back")]})
    (ui/crumbs r {:items [["/" "Home"] [nil "Gallery"]]})
    (ui/tabs-nav r {:label "Views" :items [["/" "All"] ["/open" "Open"]] :current "/"})
    (ui/tabs-nav r {:label "Pills" :style :pills :fill? true :items [["/" "All"] ["/open" "Open"]] :current "/"})
    (ui/pager r {:previous "/?p=1" :next "/?p=3" :pages [["/?p=1" 1] ["/?p=2" 2] ["/?p=3" 3]] :current 2})]
   ["cards"
    (ui/card-group
      (ui/card {:title "With image" :image {:src image :alt "A grey rectangle"} :footer "Footer"} "Body.")
      (ui/card {:title "With nav" :nav (ui/tabs-nav r {:label "Card views" :items [["/" "One"] ["/two" "Two"]] :current "/"})} "Body."))
    (ui/figure {:image {:src image :alt "A grey rectangle"} :caption "A figure."})
    (ui/collapse {:summary "More"} "Hidden until opened.")
    (ui/accordion {:name "faq" :open 0 :items [["First" "One."] ["Second" "Two."]]})
    (ui/scroller {:label "Cards"} "One" "Two" "Three")]
   ["overlays"
    [:div.ui-cluster
     (ui/dialog-button {:dialog "g-dialog" :variant :primary} "Open a dialog")
     (ui/dialog-button {:dialog "g-static"} "Open a static dialog")
     (ui/dialog-button {:dialog "g-start"} "Open a sheet at the start")
     (ui/dialog-button {:dialog "g-end"} "Open a sheet at the end")
     (ui/dropdown {:id "g-menu" :label "Actions" :items [["/" "Home"] ["/open" "Open items"] [nil (ui/action r "/do" {:label "Archive" :subject "the gallery"})]]})
     (ui/popover {:id "g-pop" :label "What is this?" :title "The gallery"} [:p "Every component of the kit on one page."])
     (ui/tooltip {:id "g-tip" :text "Saves the draft, not the page"} (ui/button {:type "button"} "Save draft"))]
    (ui/offcanvas r {:id "g-start" :title "Navigation"} (ui/list-group {:items [["/" "Home"] ["/open" "Open items"]]}))
    (ui/offcanvas r {:id "g-end" :title "Filters" :side :end} [:p "Filters would go here."])
    (ui/dialog r {:id "g-dialog" :title "Rename" :footer (ui/button {:type "button" :commandfor "g-dialog" :command "close"} "Cancel")}
      (ui/post-form r "/do" {:class "ui-form"} (ui/field r {:id "g-name" :label "Name"}) (ui/button {:variant :primary} "Save")))
    (ui/dialog r {:id "g-static" :title "Answer first" :static? true :scrollable? true :size :sm}
      (for [i (range 1 13)] [:p "Paragraph " i "."]))]
   ["motion"
    (ui/carousel r {:label "Featured" :interval-ms 5000 :slides [(ui/card {:title "One"} "The first slide.") (ui/card {:title "Two"} "The second slide.")
                                                                 (ui/card {:title "Three"} "The third slide.")]})
    (ui/carousel r {:label "Still" :slides ["A slide that waits." "Another."]})
    (ui/toasts (ui/toast r {:kind :ok :heading "Saved" :timeout-ms 8000} "Your draft was saved; it is in Drafts too.")
               (ui/toast r {:kind :error} "This one stays until it is closed."))]
   ["text"
    [:p (ui/sentence r :gallery/due (ui/time 1763200000000 "15 Nov"))]
    (ui/action r "/do" {:label "Archive" :subject "order 1" :hidden {:id "1"}})
    (ui/confirm r "/do" {:label "Delete" :confirm-label "Yes, delete" :subject "order 1" :hidden {:id "1"}})]])

(defn- header
  "The navbar every gallery page carries as the shell's header, the page's own link current,
  so a wb/link swap between the two pages shows nav.js moving the mark. Sticky, with a host's
  own plain link among its items, so the browser checks see both. `extra` items go after
  them and `expand` is the navbar's own: `/second` loaded as a page draws its bar from `:sm`
  with more items than its row holds below their need, so it collapses by fit from 640px
  up to that need. A swap keeps the header it came from, as any host's."
  [request expand extra]
  (ui/navbar request {:id "g-nav" :brand ["/" "uikit-base"] :label "Gallery pages" :current (:uri request) :expand expand
                      :items (into [["/" "Gallery"] ["/second" "Second page"]
                                    [nil (ui/dropdown {:id "g-nav-more" :label "More" :items [["/" "Back to the gallery"] ["/second" "The second page"]]})]
                                    [nil [:a {:id "g-nav-raw" :href "/second#second"} "A host's own link"]]]
                                   extra)
                      :sticky? true
                      :end (ui/button {:type "button" :variant :outline-primary :size :sm} "Sign out")}))

(defn page [request]
  (response/ok
    (shell/page {:request request :title "uikit-base gallery" :header (header request :md nil)
                 :content (into [:div.ui-stack]
                                (for [[id & body] (fixtures request)]
                                  (into [:section {:id id :class "ui-stack"} [:h2 id]] body)))})))

(defn- second-page
  "A small page, so a link of the navbar has somewhere to swap to and back."
  [request]
  (response/ok
    (shell/page {:request request :title "uikit-base gallery: second page"
                 :header (header request :sm [[nil [:a {:href "#more"} "More of it"]]])
                 :content [:div.ui-stack {:id "second"} [:h2 "Second page"] [:p "A page to swap to."] (ui/link-button "/" "Back to the gallery") [:p {:id "more"} "More of it."]]})))

(defn handler
  "The gallery's handler: the kit as a plugin, Spanish and English, the template's CSP."
  []
  (wb/handler {:routes  [["/" {:get page}]
                         ["/second" {:get second-page}]
                         ["/do" {:post (fn [_] (response/see-other "/"))}]]
               :session {:key (byte-array 16 (byte 7))}
               :plugins [(web/plugin)]
               :i18n    {:dict dict :default-locale :en :locales [:en :es]}
               :security {:csp csp}}))

(defn -main [& [port]]
  (let [{:keys [port]} (server/start (handler) {:port (if port (Long/parseLong port) 3010)})]
    (println (str "uikit-base gallery on http://localhost:" port "/"))))
