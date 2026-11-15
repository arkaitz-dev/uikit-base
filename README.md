# uikit-base

The look of a [web-base](https://github.com/arkaitz-dev/web-base) application, decided
once. You give one brand colour; you get an accessible palette, light and dark, a CSS kit
whose classes are its contract, and Hiccup components that put those classes on correct
markup. Your own CSS always wins over the kit.

It is not a theme system. Colour is the only thing you configure; spacing, type, radius,
shadows and the focus ring are decided in the kit. Anything else you want different, you
write in your own stylesheet, and it overrides the kit without a fight.

```clojure
dev.arkaitz/uikit-base {:mvn/version "0.4.0"}
```

## In a host

1. **The plugin**, beside web-base's others:

   ```clojure
   (wb/handler {:plugins     [(uikit-web/plugin) …]
                :stylesheets ["/ui-colors.css" "/app.css"]
                :static      {:root "public"} …})
   ```

   It serves `/ui/` and links, after `wb.css`, the kit's default colours and the kit;
   your colours and your stylesheet come after, in your `:stylesheets`. It brings the
   kit's own words (English, Spanish) as a dictionary, so your `:i18n` names a
   `:default-locale`; a word of yours under `:uikit-base/…` replaces the kit's.

2. **Your brand**, in `brand.edn` at the project's root: `{:primary "#2563eb"}`.

3. **Your colours**, generated once and committed (`resources/public/ui-colors.css`),
   never by hand and never at run time:

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

   The engine (`color`, `brand`, `audit`, `emit`, `build`) is in the jar, but a running
   host never loads it.

## The palette

From one colour come the 72 colours of basaku's `brandTheme`: `--ui-background`,
`--ui-surface`, `--ui-on-surface`, `--ui-primary`, `--ui-on-primary`, `--ui-primary-hover`,
`--ui-danger`, `--ui-danger-ink`, `--ui-focus-ring`, … The engine is a faithful port, held
to basaku's own output for nineteen seeds. Every pair basaku audits holds by construction,
in light and in dark:

- text on its surfaces at 4.5:1;
- strong text at 7:1;
- boundaries at 3:1;
- the focus ring against its halo at 9:1.

The status colours stay distinguishable under the three colour-vision deficiencies.

Dark follows `prefers-color-scheme`; a page with `class="dark"` or `class="light"` on
`<html>` says it instead. web-base's own `--wb-*` follow the palette, so the shell and
auth-base's pages wear the brand too.

## The kit's classes

| Class | What it is |
|---|---|
| `ui-btn`, `ui-btn--primary` `--secondary` `--success` `--danger` `--warning` `--info`, `ui-btn--sm` `--lg` | buttons, and links that look like them |
| `ui-action`, `ui-confirm` | a row's one-button form; the same that asks first |
| `ui-form` | a form's stacked layout |
| `ui-field`, `ui-field__label`, `ui-input`, `ui-field__hint`, `ui-field__req`, `ui-field__error` | a field, its label, control, hint, required mark and error |
| `ui-field--inline`, `ui-field--check`, `ui-check` | a field on one line; a checkbox field and its box |
| `ui-field--group`, `ui-field__choice` | a radio group and each of its choices |
| `ui-input--code` | a control in figures of one width (a secret) |
| `ui-notice`, `ui-notice--ok` `--info` `--warning` `--error` | what an action answered |
| `ui-page-head`, `ui-page-head__title` `__sub` `__actions` | a page's own heading |
| `ui-crumbs`, `ui-tabs` | where a page sits; the views of one list |
| `ui-table`, `ui-table-wrap`, `ui-num` | a table, the region that scrolls a wide one, a number's cell |
| `ui-rows`, `ui-row`, `ui-rows--plain` | a list of rows that wrap; one with no line, padding or border |
| `ui-pairs` | a description list |
| `ui-empty` | what a list says when it has nothing |
| `ui-thread`, `ui-message`, `ui-message__meta` | a conversation, a message and who wrote it when |
| `ui-pager`, `ui-pager__where` | the links to the page before and after, and where the reader is |
| `ui-badge`, `ui-badge--success` `--danger` `--warning` `--info` | a status as a label |
| `ui-card`, `ui-card__head`, `ui-card__foot` | a raised surface, its heading and its footer |
| `ui-stack`, `ui-cluster`, `ui-grid` | blocks one under another; items that wrap; as many columns as fit |
| `ui-sr-only` | text a screen reader reads and the eye does not see |
| `ui-prose` | text you write: headings, links, code |

Scales you can use in your own CSS: `--ui-space-xxs` … `--ui-space-xl`, `--ui-radius-sm`
… `--ui-radius-full`, `--ui-font-xs` … `--ui-font-xl`, `--ui-shadow-sm`.

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
(ui/action request "/lines/7/remove" {:label "Remove" :subject "Algebra" :hidden {:back here}})
(ui/confirm request "/lessons/7/delete" {:label "Delete" :confirm-label "Yes, delete" :subject "Week 1"})
(ui/page-head {:title "Orders" :subtitle "Placed today" :actions [(ui/link-button "/new" "New")]})
(ui/crumbs request {:items [["/courses" "Courses"] ["/courses/1" "Algebra"] [nil "Week 1"]]})
(ui/tabs-nav request {:label "Status" :items [["/o" "All"] ["/o?s=open" "Open"]] :current here})
(ui/table {:rows rows :scroll-label "Orders"})                            ; a wide one scrolls
(ui/pairs [["Ref" ref] ["Paid" (when paid? (ui/time paid-at shown))]])
(ui/time instant "15 Jan, 20:00")                                       ; datetime for machines
(ui/empty "No orders yet.")
(ui/thread (for [m messages] (ui/message {:meta [:span (:who m) " · " (:when m)]} (:text m))))
(ui/secret request {:id "token" :label "Your token" :value token})
```

Every component takes its own attributes. A `:class` is added to the kit's, and anything
else wins, except what correctness needs, which no attribute changes:

- `post-form` always posts to its action, with its CSRF field first. It refuses the htmx
  attributes that would send it another way (`hx-get`, `hx-method`, `hx-action`,
  `hx-config`, with any modifier). `button` refuses `formaction`, `formmethod` and `form`.
- `field` marks a refused control `aria-invalid` and describes it by its error, which is
  a word of your dictionary (`:error/taken` or `[:error/long 80]`), then by its hint,
  then by your own `aria-describedby`, joined. A `:required?` one stays required.
- `action` and `confirm` are `post-form`s; a `:subject` names what a repeated "Remove"
  acts on for a screen reader. `confirm` posts only from its second step, and its two
  labels differ. Neither stops a second submit: make the handler harmless to repeat.
- `link-button`, `crumbs` and `tabs-nav` link through `wb/link`, so every path is
  web-base's to refuse. `tabs-nav` is named by your label; `crumbs` by the kit's word.
- `table`'s `:scroll-label` names a region the keyboard reaches; `time`'s `datetime` is
  its value's; a `secret` has no name, so no form sends it, and is never disabled.
- `notice` speaks an error as an alert, and everything else as a status.
- `pager` names its links and itself in the reader's language (the kit's words, or yours,
  or English), so a link never goes unnamed; a label you give wins.

## Fine-tuning by hand

Every rule of the kit sits in `@layer uikit`, and none is `!important`. Your stylesheet is
unlayered and linked after the kit, so any rule of yours wins, whatever its specificity:

```css
.ui-btn { border-radius: 0; }
.ui-card { box-shadow: none; border-color: var(--ui-border-strong); }
```

Use the palette's variables in your own rules and they follow the brand and the scheme.
