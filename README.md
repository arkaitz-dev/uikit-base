# uikit-base

The look of a [web-base](https://github.com/arkaitz-dev/web-base) application, decided
once. You give a brand colour; you get an accessible palette, light and dark, basaku's
scales, a CSS kit whose classes are its contract, Bootstrap's utilities and a grid, and
Hiccup components that put those classes on correct markup. Your own CSS always wins over
the kit.

It is not a theme system. The knobs are basaku's and closed: up to three seed colours, a
presence, and four personality axes, each one of a few levels. Everything else is decided
in the kit. Anything you want different, you write in your own stylesheet, and it
overrides the kit without a fight.

```clojure
dev.arkaitz/uikit-base {:mvn/version "0.6.0"}
```

It needs web-base 0.25.0 or later.

## In a host

1. **The plugin**, beside web-base's others:

   ```clojure
   (wb/handler {:plugins     [(uikit-web/plugin) …]
                :stylesheets ["/ui-colors.css" "/app.css"]
                :static      {:root "public"} …})
   ```

   It serves `/ui/` and links, after `wb.css`, the kit's default colours, the kit and its
   utilities; your colours and your stylesheet come after, in your `:stylesheets`. It
   loads the kit's one script, `/ui/ui.js`, on every page. It brings the kit's own words
   (English, Spanish) as a dictionary, so your `:i18n` names a `:default-locale`; a word
   of yours under `:uikit-base/…` replaces the kit's.

2. **Your brand**, in `brand.edn` at the project's root. Only `:primary` is required:

   ```clojure
   {:primary    "#2563eb"
    :secondary  "#64748b"        ; and :tertiary; both derived from :primary when absent
    :presence   :balanced        ; :calm (default) :balanced :vivid
    :density    :compact         ; :compact :cozy (default) :comfortable
    :roundness  :round           ; :sharp :soft (default) :round
    :typography :serif           ; :sans (default) :serif :mono
    :elevation  :raised}         ; :flat :normal (default) :raised
   ```

   Presence is how much of the brand the canvases carry. Density scales the spacing,
   roundness the radii, typography the families and their leading, elevation the shadows
   and the dark surfaces' tiers. A key the kit does not know, a seed that is no `#rrggbb`
   colour, or a level an axis does not have is refused, naming it.

3. **Your colours**, generated once and committed (`resources/public/ui-colors.css`),
   never by hand and never at run time. The file holds the palette and the scales your
   brand shapes:

   ```clojure
   (require 'dev.arkaitz.uikit-base.build)
   (dev.arkaitz.uikit-base.build/write! "brand.edn" "resources/public/ui-colors.css")
   ```

   A palette that would break an audited contrast pair is refused, naming the pair, and
   nothing is written. Add a test that holds the file to the brand, so one never changes
   without the other:

   ```clojure
   (is (= (build/expected "brand.edn") (slurp "resources/public/ui-colors.css")))
   ```

   The engine (`color`, `brand`, `scales`, `audit`, `emit`, `build`, `utilities`) is in
   the jar, but a running host never loads it.

## The palette

From the seeds come the 72 colours of basaku's `brandTheme`: `--ui-background`,
`--ui-surface`, `--ui-on-surface`, `--ui-primary`, `--ui-on-primary`, `--ui-primary-hover`,
`--ui-danger`, `--ui-danger-ink`, `--ui-focus-ring`, … The engine is a faithful port, held
to basaku's own output for nineteen seeds and for 201 cases of its options. Every pair
basaku audits holds by construction, in light and in dark:

- text on its surfaces at 4.5:1;
- strong text at 7:1;
- boundaries at 3:1;
- the focus ring against its halo at 9:1.

The status colours stay distinguishable under the three colour-vision deficiencies.

Dark follows `prefers-color-scheme`; a page with `class="dark"` or `class="light"` on
`<html>` says it instead. web-base's own `--wb-*` follow the palette, so the shell and
auth-base's pages wear the brand too.

## The scales

basaku's, under the kit's prefix, in your colours file: `--ui-spacing-xxs` …
`--ui-spacing-xxxl`, `--ui-radius-none` … `--ui-radius-full`, `--ui-font-size-xs` …
`--ui-font-size-xxxl`, `--ui-shadow-sm` … `--ui-shadow-xl`, `--ui-font-family-*`,
`--ui-line-height-*`, `--ui-font-weight-*`, `--ui-duration-fast` `-normal` `-slow`,
`--ui-easing-*`, `--ui-z-index-*`, `--ui-opacity-*`, `--ui-focus-outline-width` and
`-offset`. Spacing, type and shadows grow at 640px and 1024px; shadows differ by scheme.
Use them in your own CSS and they follow the brand.

## The kit's classes

| Class | What it is |
|---|---|
| `ui-btn`, `ui-btn--primary` `--secondary` `--success` `--danger` `--warning` `--info`, `ui-btn--sm` `--lg` | buttons, and links that look like them |
| `ui-btn--outline-primary` … `--outline-info`, `ui-btn--link`, `ui-btn--block` | a fill's outline, a button that reads as a link, one as wide as its container |
| `ui-btn-group`, `ui-close` | buttons drawn as one control; a close mark |
| `ui-action`, `ui-confirm` | a row's one-button form; the same that asks first |
| `ui-form` | a form's stacked layout |
| `ui-field`, `ui-field__label`, `ui-input`, `ui-field__hint`, `ui-field__req`, `ui-field__error` | a field, its label, control, hint, required mark and error |
| `ui-field--inline`, `ui-field--check`, `ui-check` | a field on one line; a checkbox field and its box |
| `ui-field--group`, `ui-field__choice` | a radio group and each of its choices |
| `ui-input--sm` `--lg`, `ui-input--code` | a control level with a small or large button; one in figures of one width |
| `ui-switch`, `ui-range`, `ui-field--floating` | an on-off switch, a slider, a label floating inside its control |
| `ui-input-group`, `ui-input-group__text` | text or buttons joined to a control's edges |
| `ui-notice`, `ui-notice--ok` `--info` `--warning` `--error` | what an action answered |
| `ui-alert`, `ui-alert__body` `__heading` `__dismiss` | a notice with a heading and a way out |
| `ui-page-head`, `ui-page-head__title` `__sub` `__actions` | a page's own heading |
| `ui-crumbs`, `ui-tabs`, `ui-tabs--pills` `--fill` `--justify` | where a page sits; the views of one list, as tabs or pills |
| `ui-table`, `ui-table-wrap`, `ui-num`, `ui-table--striped` `--hover` `--bordered` `--compact` | a table, the region that scrolls a wide one, a number's cell, its variants |
| `ui-rows`, `ui-row`, `ui-rows--plain` | a list of rows that wrap; one with no line, padding or border |
| `ui-list-group`, `ui-list-group__item` `__action`, `ui-list-group--flush` | items in one bordered column, linked or held |
| `ui-pairs` | a description list |
| `ui-empty` | what a list says when it has nothing |
| `ui-thread`, `ui-message`, `ui-message__meta` | a conversation, a message and who wrote it when |
| `ui-pager`, `ui-pager__where`, `ui-pager__pages`, `ui-pager--sm` `--lg` | the links to the page before and after, where the reader is, the numbered pages |
| `ui-badge`, `ui-badge--success` `--danger` `--warning` `--info`, `ui-badge--corner` | a status as a label; one on its container's corner |
| `ui-progress`, `ui-progress--striped` `--animated`, `ui-meter` | the platform's progress and meter |
| `ui-spinner`, `ui-placeholders`, `ui-placeholder` | something loading; bars standing in for content |
| `ui-card`, `ui-card__head`, `ui-card__foot`, `ui-card__img`, `ui-card__nav`, `ui-card-group` | a raised surface, its heading, footer, image and nav; cards joined side by side |
| `ui-figure`, `ui-figure__img` `__caption` | an image and what it is |
| `ui-collapse`, `ui-collapse__summary` `__body`, `ui-accordion`, `ui-accordion__item` `__summary` `__body` | details that open and close; a column of them |
| `ui-scroller`, `ui-scroller__list` `__item` | one line that scrolls sideways and snaps |
| `ui-dialog`, `ui-dialog__head` `__title` `__body` `__foot`, `ui-dialog--sm` `--lg` `--fullscreen` `--scrollable` | a modal dialog |
| `ui-offcanvas`, `ui-offcanvas--start` `--end` | a dialog drawn as a sheet along one side |
| `ui-dropdown`, `ui-dropdown__menu` `__items` `__item`, `ui-popover-wrap`, `ui-popover`, `ui-popover__head` | a menu of links and actions; a box beside its button |
| `ui-tooltip-wrap`, `ui-tooltip` | a control and the text that describes it |
| `ui-toasts`, `ui-toast`, `ui-toast--ok` `--info` `--warning` `--error`, `ui-toast__body` `__heading` `__close` | where toasts appear; a toast |
| `ui-carousel`, `ui-carousel__controls` `__viewport` `__slide` `__prev` `__next` `__rotate` | slides shown one at a time |
| `ui-scrollspy` | links to this page's sections, the one in view marked |
| `ui-stack`, `ui-cluster`, `ui-grid` | blocks one under another; items that wrap; as many columns as fit |
| `ui-sr-only` | text a screen reader reads and the eye does not see |
| `ui-prose` | text you write: headings, links, code |

## Utilities and the grid

`ui-utilities.css` is Bootstrap 5.3.8's utilities as `ui-<name>`: display, flex, spacing,
sizing, position, borders, radii, shadows, type, text, colours and the helpers
(`ui-d-flex`, `ui-mt-3`, `ui-text-center`, `ui-visually-hidden`, `ui-ratio-16x9`, …). The
grid is twelve CSS grid tracks:

```html
<div class="ui-container">
  <div class="ui-layout ui-g-3">
    <div class="ui-col-12 ui-col-md-8">…</div>
    <div class="ui-col-12 ui-col-md-4">…</div>
  </div>
</div>
```

A span is `ui-col-*`, an offset `ui-offset-*`, a gutter `ui-g-*` `ui-gx-*` `ui-gy-*`;
`ui-layout-cols-*` gives every child one track of so many, and `ui-col`, `ui-col-auto` and
`ui-layout-cols-auto` make the line a wrapping flex one. Breakpoints are basaku's: `sm`
640, `md` 768, `lg` 1024, `xl` 1280, `xxl` 1536. Spacing steps 0 to 5 read the spacing
scale, so a brand's density scales them.

The rule: every Bootstrap class is the kit's, equal after a stated normalisation, or a
named deviation with its reason, and a test holds all 1705 to it. The deviations are
logical sides only (`ui-ms-*` is `margin-inline-start`), no `!important`, colour only from
audited pairs (a text utility on a background utility, a fill `ui-text-bg-*` with its own
ink), the grid a CSS grid (`row` is `ui-layout`, since `ui-row` is the kit's rows), and no
opacity or subtle colour variants. The full list is `deviations` in
`test/dev/arkaitz/uikit_base/kit_test.clj`. Utilities sit in a later layer than the
components, so a utility always wins over a component's rule.

## Components

```clojure
(require '[dev.arkaitz.uikit-base :as ui])

(ui/post-form request "/things" {:class "ui-form"}
  (ui/field request {:id "name" :label "Name" :value v :error (:name errors)})
  (ui/button {:variant :primary} "Save"))
(ui/notice :ok "Saved.")
(ui/table {:caption "Orders" :head ["Ref" "Total"] :rows rows
          :row-attrs (fn [r] {:id (str "order-" (first r))})})
(ui/table {:head ["Hour" (when other? "Your time") "Booked by" ""]       ; nil: no column
           :rows [[h (ui/cell {:class "yours"} t) who form]]})
(ui/rows (for [l lines] (ui/row {:id (str "line-" (:id l))} [:span (:name l)] …)))
(ui/rows {:plain? true :ordered? true} (for [e events] (ui/row (:text e))))
(ui/field request {:id "on-sale" :label "On sale" :type "checkbox" :value on-sale?})
(ui/field request {:id "qty" :label "Qty" :type "number" :value 2 :inline? true})
(ui/pager request {:previous p :next n :where "page 2 of 5"})
(ui/badge :success "Paid")
(ui/card {:title "Totals" :footer (ui/link-button "/orders" "All orders")} …)
(ui/field request {:id "kind" :label "Kind" :type "radio" :options [["bug" "Bug"] ["idea" "Idea"]]
                   :hint "What it is" :required? true :value kind :error (:kind errors)})
(ui/link-button "/things/new" {:variant :primary} "New thing")
(ui/link-button "/orders.csv" {:download? true} "CSV")                   ; loads as a document
(ui/action request "/lines/7/remove" {:label "Remove" :subject "Algebra" :hidden {:back here}})
(ui/confirm request "/lessons/7/delete" {:label "Delete" :confirm-label "Yes, delete" :subject "Week 1"})
(ui/page-head {:title "Orders" :subtitle "Placed today" :actions [(ui/link-button "/new" "New")]})
(ui/crumbs request {:items [["/courses" "Courses"] ["/courses/1" "Algebra"] [nil "Week 1"]]})
(ui/tabs-nav request {:label "Status" :items [["/o" "All"] ["/o?s=open" "Open"]] :current here})
(ui/table {:rows rows :scroll-label "Orders"})                            ; a wide one scrolls
(ui/pairs [["Ref" ref] ["Paid" (when paid? (ui/time paid-at shown))]])
(ui/time instant "15 Jan, 20:00")                ; or epoch millis; datetime for machines
(ui/empty "No orders yet.")
(ui/thread (for [m messages] (ui/message {:meta [:span (:who m) " · " (:when m)]} (:text m))))
(ui/secret request {:id "token" :label "Your token" :value token})
```

Since 0.6.0, with no script:

```clojure
(ui/button {:variant :outline-danger} "Discard")                          ; or :link, :block? true
(ui/toggle-button request "/bold" {:label "Bold" :pressed? bold? :hidden {:on (not bold?)}})
(ui/button-group {:label "Alignment"} (ui/button "Start") (ui/button "End"))   ; a confirm too
(ui/close-button request)
(ui/field request {:id "wifi" :label "Wi-Fi" :type "switch" :value on?})  ; or "range"
(ui/field request {:id "price" :label "Price in euros" :before "€" :size :lg})
(ui/field request {:id "name" :label "Name" :floating? true :attrs {:placeholder "Name"}})
(ui/input-group {:after (ui/button "Go")} [:input.ui-input {:aria-label "Search" :name "q"}])
(ui/alert request :warning {:heading "Heads up" :dismiss-href "/orders"} "Something to read.")
(ui/alert request :ok {:dismiss-param "outcome"} "Saved.")   ; back to this page and its query, without ?outcome=
(ui/badge :danger {:corner? true :label "new"} 9)       ; in a ui-position-relative container
(ui/progress {:value 0.4 :label "Upload"})               ; :striped? :animated?
(ui/meter {:value 7 :max 10 :low 3 :high 8 :optimum 10 :label "Places taken"})
(ui/spinner request) (ui/placeholder request {:lines 2})
(ui/list-group {:items [["/" "Home"] [nil "Plain"]] :current "/"})        ; :flush?
(ui/list-group {:items (for [c courses] [(path c) (:title c) {:id (str "course-" (:id c)) :data-course (:id c)}])})
(ui/table {:rows rows :striped? true :hover? true})       ; :bordered? :compact?
(ui/pager request {:previous p :next n :pages [["/?p=1" 1] ["/?p=2" 2]] :current 2})
(ui/tabs-nav request {:label "Views" :style :pills :fill? true :items items :current here})
(ui/card {:title "One" :image {:src src :alt "What it shows"} :nav nav} …)
(ui/card-group card-a card-b)
(ui/figure {:image {:src src :alt "What it shows"} :caption "A figure."})
(ui/collapse {:summary "More"} "Hidden until opened.")
(ui/accordion {:name "faq" :open 0 :items [["First" "One."] ["Second" "Two."]]})
(ui/scroller {:label "Cards"} "One" "Two" "Three")
```

And with `ui.js` for what a browser lacks:

```clojure
(ui/dialog-button {:dialog "rename" :variant :primary} "Rename")
(ui/dialog request {:id "rename" :title "Rename" :footer (ui/button {:type "button" :commandfor "rename" :command "close"} "Cancel")}
  (ui/post-form request "/rename" {:class "ui-form"} …))                ; :size :fullscreen? :scrollable? :static?
(ui/offcanvas request {:id "nav" :title "Navigation" :side :start} …)
(ui/dropdown {:id "more" :label "Actions" :items [["/open" "Open"] [nil (ui/action request "/archive" {:label "Archive"})]]})
(ui/popover {:id "what" :label "What is this?" :title "Orders"} [:p "…"])
(ui/tooltip {:id "tip" :text "Saves the draft, not the page"} (ui/button {:type "button"} "Save draft"))
(ui/toasts (ui/toast request {:kind :ok :heading "Saved" :timeout-ms 8000} "It is in Drafts too."))
(ui/carousel request {:label "Featured" :interval-ms 5000 :slides [a b c]})
(ui/scrollspy {:label "On this page" :items [["#buttons" "Buttons"] ["#forms" "Forms"]]})
```

Every component takes its own attributes. A `:class` is added to the kit's, and anything
else wins, except what correctness needs, which no attribute changes:

- `post-form` always posts to its action, with its CSRF field first. It refuses the htmx
  attributes that would send it another way (`hx-get`, `hx-method`, `hx-action`,
  `hx-config`, with any modifier). `button` refuses `formaction`, `formmethod` and `form`.
- `field` marks a refused control `aria-invalid` and describes it by its error, which is
  a word of your dictionary (`:error/taken` or `[:error/long 80]`), then by its hint,
  then by your own `aria-describedby`, joined. A `:required?` one stays required. A
  floating label needs a placeholder.
- `action` and `confirm` are `post-form`s; a `:subject` names what a repeated "Remove"
  acts on for a screen reader. `confirm` posts only from its second step, and its two
  labels differ. Neither stops a second submit: make the handler harmless to repeat.
  `toggle-button`'s `aria-pressed` is the server's `:pressed?`, and it takes no variant.
- `link-button`, `crumbs`, `tabs-nav`, `list-group`, `dropdown` and `pager` link through
  `wb/link`, so every path is web-base's to refuse. `tabs-nav` is named by your label;
  `crumbs` by the kit's word.
- `table`'s `:scroll-label` and `scroller`'s `:label` name a region the keyboard reaches;
  `time`'s `datetime` is its value's; a `secret` has no name, so no form sends it, and is
  never disabled.
- `notice` and `alert` speak an error as an alert, and everything else as a status. An
  alert's way out is `:dismiss-href`, or `:dismiss-param`, never both: the request's own
  `:uri` and `:query-string` with every pair of that name taken out and the others copied
  as they came, nothing decoded. An empty pair, or one holding a backslash, whitespace or
  a control character, is dropped rather than let `wb/link` refuse the whole link.
- A `list-group` item's attrs, a map, go on its `<li>`, its `:class` joined to the
  kit's; the link inside stays the kit's.
- A `button-group` lets each item keep its own height, so an open `confirm` in it
  stretches none of its neighbours.
- `pager` names its links and itself in the reader's language (the kit's words, or yours,
  or English), so a link never goes unnamed; a label you give wins.
- `button-group`, `progress`, `meter`, `scroller`, `carousel` and `scrollspy` are named by
  a label you must give; a `card`'s or `figure`'s image has an alt unless `:decorative?
  true`; a `figure` has a caption.
- A `dialog` is named by its title and opened by its button, never by `open`; Esc and a
  backdrop click close it unless `:static? true`. A `dropdown` holds links and forms,
  never an ARIA menu.
- A `tooltip` describes a trigger that is named already; one Tab cannot reach is refused.
- A `toast`'s timer stops while it is hovered or focused, and a toast is never the only
  copy of a message. `toasts` belongs in the page's frame: a toast is read out only when
  it is inserted into a region already on the page.
- A `carousel`'s `:interval-ms` runs from 334 to 2147483647 and is refused outside; it
  stops once the focus enters, and never starts under reduced motion.

## The script

`/ui/ui.js` does what some browser of the floor lacks and no element does alone. As it
loads, it detects invoker commands (`commandfor`), a dialog's `closedby`, and CSS anchor
positioning with `anchor-scope`; where one is missing it stands in for it: it opens and
closes dialogs and returns the focus, refuses Esc on a static dialog and closes the others
on a backdrop press, and places dropdowns, popovers and tooltips. A browser that has them
gets the platform's own. It also shows tooltips, times and closes toasts, turns carousels
and marks the scrollspy, and it runs again after every htmx swap.

It writes no markup and no text: the server rendered both, escaped. It writes no inline
style as a string, by attribute or by `style =`; it positions only through single CSSOM
properties (`style.setProperty`), which `style-src 'self'` allows. It evaluates no
string, sets no timer from a string and fetches nothing. The plugin loads it deferred, under
the page's nonce, so a CSP of `script-src 'nonce-…'` needs nothing more. It is at most 150
lines of code, comments and blank lines excluded, never minified; `ui_js_test` holds that
and every ban above.

## Browsers

The floor is two years: Chrome 131, Firefox 133, Safari 18.2. The script covers what
Safari 18.x lacks of invoker commands, `closedby` and anchor positioning.

The gallery is every public component on one page, served by web-base under the
template's CSP:

    clojure -M:gallery [port]          # 3010 unless given

`test/browser/gallery-check.mjs` runs it in a real engine, natively and with the floor's
gaps forced, and prints what it saw as JSON:

    PLAYWRIGHT_CORE=<path to playwright-core/index.mjs> \
    PLAYWRIGHT_BROWSERS_PATH=<where Playwright's browsers are> \
    node test/browser/gallery-check.mjs <webkit|chromium> http://localhost:3010/

`CHROMIUM=<executable>` runs a Chromium Playwright did not install. The results live in
`test/resources/browser-checks.edn`, with the sha256 of kit.css, ui-utilities.css, ui.js
and the script they ran against: a change to any of the four reds `browser_checks_test`
until the checks are run again in each engine and that file regenerated. What stays
unverified is written there, under `:not-verified`.

## Testing your pages

```clojure
(require '[dev.arkaitz.uikit-base.testing :as ut])
(ut/actions html)   ; [{:path "/lessons/7/delete" :hidden {} :label "Yes, delete"
                    ;   :name "Yes, delete Week 1" :first {:label "Delete" :name "Delete Week 1"}} …]
(ut/alerts html)    ; [{:kind :ok :role "status" :heading "Saved" :text "Order A1 is paid."
                    ;   :dismiss {:href "/orders?day=2" :name "Close"}} …]
(ut/unaudited-pairs html)   ; [] unless an element combines colour utilities basaku never audited
```

A confirm is read in two places, its first step and its form; `actions` gives both.
Assert `:name`, the name a screen reader hears: `:label` stays the same when a subject
is lost. `alerts` answers each notice and alert by the kit's class, never by its role: its
body's text without the heading or the way out, and where dismissing it goes. Pin that
rather than the markup, which is the kit's to change. `unaudited-pairs` answers each element whose own classes put a text utility on a
fill, a fill on a background utility, or two fills together, with the pairs it could
paint. `(ui/sentence request :due (ui/time at shown))` puts a component inside a
translated word.

## Fine-tuning by hand

Every rule of the kit sits in `@layer uikit`, in its sub-layers `base`, `components` and
`utilities`, and none is `!important`. Your stylesheet is unlayered and linked after the
kit, so any rule of yours wins, whatever its specificity:

```css
.ui-btn { border-radius: 0; }
.ui-card { box-shadow: none; border-color: var(--ui-border-strong); }
```

Use the palette's and the scales' variables in your own rules and they follow the brand
and the scheme.
