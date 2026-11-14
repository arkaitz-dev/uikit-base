# uikit-base

The look of a [web-base](https://github.com/arkaitz-dev/web-base) application, decided
once. You give one brand colour; you get an accessible palette, light and dark, a CSS kit
whose classes are its contract, and Hiccup components that put those classes on correct
markup. Your own CSS always wins over the kit.

It is not a theme system. Colour is the only thing you configure; spacing, type, radius,
shadows and the focus ring are decided in the kit. Anything else you want different, you
write in your own stylesheet, and it overrides the kit without a fight.

```clojure
dev.arkaitz/uikit-base {:mvn/version "0.1.0"}
```

## In a host

1. **The plugin**, beside web-base's others:

   ```clojure
   (wb/handler {:plugins     [(uikit-web/plugin) …]
                :stylesheets ["/ui-colors.css" "/app.css"]
                :static      {:root "public"} …})
   ```

   It serves `/ui/` and links, after `wb.css`, the kit's default colours and the kit;
   your colours and your stylesheet come after, in your `:stylesheets`.

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
| `ui-btn`, `ui-btn--primary` `--secondary` `--success` `--danger` `--warning` `--info`, `ui-btn--sm` `--lg` | buttons |
| `ui-form` | a form's stacked layout |
| `ui-field`, `ui-field__label`, `ui-input`, `ui-field__error` | a field, its label, control and error |
| `ui-notice`, `ui-notice--ok` `--info` `--warning` `--error` | what an action answered |
| `ui-table` | a table |
| `ui-pager` | the links to the page before and after |
| `ui-badge`, `ui-badge--success` `--danger` `--warning` `--info` | a status as a label |
| `ui-card` | a raised surface |

Scales you can use in your own CSS: `--ui-space-xxs` … `--ui-space-xl`, `--ui-radius-sm`
… `--ui-radius-full`, `--ui-font-xs` … `--ui-font-xl`, `--ui-shadow-sm`.

## Components

```clojure
(require '[dev.arkaitz.uikit-base :as ui])

(ui/post-form request "/things" {:class "ui-form"}
  (ui/field request {:id "name" :label "Name" :value v :error (:name errors)})
  (ui/button {:variant :primary} "Save"))
(ui/notice :ok "Saved.")
(ui/table {:caption "Orders" :head ["Ref" "Total"] :rows rows})
(ui/pager {:previous p :next n :where "page 2 of 5" :label "Pages"
           :previous-label "Previous" :next-label "Next"})
(ui/badge :success "Paid")
(ui/card [:h2 "Totals"] …)
```

Every component takes its own attributes. A `:class` is added to the kit's, and anything
else wins, except what correctness needs, which no attribute changes:

- `post-form` always posts to its action, with its CSRF field first. It refuses the htmx
  attributes that would send it another way (`hx-get`, `hx-method`, `hx-action`,
  `hx-config`, with any modifier). `button` refuses `formaction`, `formmethod` and `form`.
- `field` marks a refused control `aria-invalid` and describes it by its error, which is
  a word of your dictionary (`:error/taken` or `[:error/long 80]`).
- `notice` speaks an error as an alert, and everything else as a status.
- `pager` refuses a link without its label.

## Fine-tuning by hand

Every rule of the kit sits in `@layer uikit`, and none is `!important`. Your stylesheet is
unlayered and linked after the kit, so any rule of yours wins, whatever its specificity:

```css
.ui-btn { border-radius: 0; }
.ui-card { box-shadow: none; border-color: var(--ui-border-strong); }
```

Use the palette's variables in your own rules and they follow the brand and the scheme.
