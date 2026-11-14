# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking** and says what a host must change. Every release is on Clojars as
`dev.arkaitz/uikit-base` and tagged `vX.Y.Z`.

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
