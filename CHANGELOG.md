# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking** and says what a host must change. Every release is on Clojars as
`dev.arkaitz/uikit-base` and tagged `vX.Y.Z`.

## 0.4.0 — 2026-11-15

More components, and better ones, adopted by every host of the set.
- **A fix you will see:** a variant button kept its own text colour only at rest; hovered
  or pressed it painted the surface's text on its own fill (1.01:1 at worst). Every
  variant now keeps its `on-` colour on hover and press. In every release since 0.1.0.
- **Behaviour change:** a refused field is described by its error *and* by your own
  `aria-describedby`, joined, the error first; it used to drop yours. With no error yours
  stands, and a blank one is no attribute.
- **Markup change:** the pager's "page 2 of 5" is `span.ui-pager__where`.
- `field`: `:hint`, `:required? true`, and `:type "radio"`, a fieldset its legend names,
  each radio refused and described like any control.
- New components: `link-button`, `action` and `confirm` (a two-step action with no
  script), `empty`, `pairs`, `time`, `page-head`, `crumbs` (kit word
  `:uikit-base/breadcrumbs`), `tabs-nav`, `secret` (kit word `:uikit-base/shown-once`),
  `thread` and `message`.
- `table` `:scroll-label` (a region the keyboard scrolls) and the `ui-num` cell; `card`
  `:title` and `:footer`.
- kit.css: control states (hover, placeholder, read-only, disabled over all), an input
  the height of a button, a pressed and a busy button, `ui-stack`, `ui-cluster`,
  `ui-grid`, `ui-sr-only`, `ui-prose`; every side a logical one, so a right-to-left page
  mirrors.
- The kit's own tests now model the cascade, refuse a colour that is not the palette's
  and a focusable without the two-band ring; the defect above is what they found first.

## 0.3.0 — 2026-11-15

Additive; nothing a 0.2.0 host does changes.
- `ui/cell`: a table cell with attributes of its own, `(ui/cell {:class "yours"} …)` — a
  `<td>` in a row, the row's header `<th>` under `:row-header?`, a `scope=col` header in
  `head` (its `scope` kept whatever the attrs say). It means something only inside
  `ui/table`.
- `ui/table`: a column whose `head` entry is nil is not drawn, in the head or in any row,
  so a column shown only sometimes is `(when shown? "Label")` in the head. A column that
  is always there with an empty header is `""`. Without a head, a nil cell is an empty
  `<td>`, as before.
- `ui/rows` `{:plain? true}` (`ui-rows--plain`): rows with no line, padding or border, for
  a list that is only a list (shop's U-K10).

## 0.2.0 — 2026-11-14

From the shop's adoption (its FRICTION.md, U-K2, U-K3, U-K4, U-K6).
- **Breaking:** `ui/pager` takes the request, `(ui/pager request opts)`. Its words are the
  kit's, in the reader's language — `:uikit-base/previous`, `/next`, `/pages`, which the
  plugin brings (English, Spanish) and your dictionary overrides — in English where neither
  speaks it; a label you give still wins, and a link is never unnamed.
- **Breaking:** the plugin brings a dictionary, so a host using it names its locale,
  `:i18n {:default-locale …}`; web-base refuses the boot otherwise, naming the key.
- `ui/rows` and `ui/row`: a list of rows that wrap, each addressed by its own attributes
  (an id, what htmx targets); `{:ordered? true}` for an `<ol>`. `ui/table` takes `:row-attrs`, a function of each row.
- `ui/field` `:type "checkbox"` (ticked by `value`, sending its attrs' `:value`, the box
  before its label) and `:inline? true` (label and control on one line).
- kit.css: `.ui-rows`, `.ui-row`, `.ui-field--inline`, `.ui-field--check`, `.ui-check`.

## 0.1.0 — 2026-11-14

The first release.
- A colour engine ported from basaku: `brand/theme` derives 72 tokens, light and dark, from
  one brand colour, held to basaku's own output for nineteen seeds; `audit` holds them to
  basaku's 145 contrast pairs; `emit` writes the colours file (with the bridge that makes
  web-base's `--wb-*` follow it); `build` generates it for a host, refusing a palette that
  breaks a pair, and answers what a host's drift test compares. Never loaded at run time.
- `kit.css`: buttons, form fields, notices, tables, pager, badges, cards, the focus ring,
  basaku's scales — all in `@layer uikit`, so a host's own CSS always wins.
- Components: `button`, `post-form`, `field`, `notice`, `table`, `pager`, `badge`, `card`.
- `web/plugin`: serves `/ui/` and links the default colours and the kit.
