# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking** and says what a host must change. Every release is on Clojars as
`dev.arkaitz/uikit-base` and tagged `vX.Y.Z`.

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
