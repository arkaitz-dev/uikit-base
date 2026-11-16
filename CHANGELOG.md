# Changelog

Versions are `0.MINOR.PATCH` while the API settles. A **patch** fixes a defect and never
changes what a working host sees. A **minor** adds, and may break: when it does, the entry
opens with **Breaking** and says what a host must change. Every release is on Clojars as
`dev.arkaitz/uikit-base` and tagged `vX.Y.Z`.

## 0.6.0 — 2026-11-16

basaku's scales and personality, Bootstrap 5.3's utilities and grid, and the components
that need a script, with the one script they need.

**Breaking:**
- **The scales are renamed** after basaku's, and kit.css declares no custom property: the
  scales live in the generated colours file now. In your own CSS:

  | 0.5.0 | 0.6.0 |
  |---|---|
  | `--ui-space-xxs` … `--ui-space-xl` | `--ui-spacing-xxs` … `--ui-spacing-xl` (new: `-xxl`, `-xxxl`) |
  | `--ui-font-xs` … `--ui-font-xl` | `--ui-font-size-xs` … `--ui-font-size-xl` (new: `-xxl`, `-xxxl`) |
  | `--ui-duration` | `--ui-duration-normal` (new: `-fast`, `-slow`) |
  | `--ui-ring` | `--ui-focus-outline-width` |

  `--ui-radius-sm` `-md` `-lg` `-full` and `--ui-shadow-sm` keep their names. An old name
  is simply unset, with no error anywhere: search your app.css for it.
- **A change you will see:** the layout scales grow with the screen, at 640px and 1024px,
  as basaku's do. Below 640px `--ui-spacing-md` is 0.85rem (it was 1rem everywhere) and
  `--ui-font-size-xl` 1.25rem (it was 1.44rem); dark shadows are darker than light ones.
- **web-base 0.25.0 is required:** the plugin loads its script through a plugin's
  `:scripts`, which 0.25.0 is the first to take.
- **A script on every page:** the plugin loads `/ui/ui.js`, deferred, under the page's
  nonce, and a third stylesheet, `/ui/ui-utilities.css`, after kit.css. A CSP that allows
  scripts by nonce needs nothing more.
- **The generated colours file holds the scales**, so a 0.5.0 file is not what
  `build/expected` answers for the same brand: your drift test reds until you regenerate.
- **brand.edn is a closed map of more keys.** `read-brand` takes `:primary` and the options
  below, and refuses anything else, naming the path and what is wrong: a file that cannot
  be read, is not EDN or holds no map; a missing `:primary`; a key the kit does not know
  (listed); a seed that is no `#rrggbb` colour; a level that is none of its axis's
  (listed). These replace 0.5.0's one message, "must hold {:primary …} and nothing else".
- **CLAUDE.md's rule 4 changed**, by the user's decision of 2026-11-15: from "colour is the
  only knob" to basaku's own closed knobs, the colours, a presence and four personality
  axes. Everything else is still decided once, and hand CSS still overrides anything.

**Added — theme.**
- brand.edn takes up to three seeds, `:primary`, `:secondary` and `:tertiary` (the last
  two derived from the primary when absent); `:presence`, `:calm` (the default),
  `:balanced` or `:vivid`; and four personality axes, each one of basaku's levels:
  `:density` (`:compact` `:cozy` `:comfortable`), `:roundness` (`:sharp` `:soft` `:round`),
  `:typography` (`:sans` `:serif` `:mono`) and `:elevation` (`:flat` `:normal` `:raised`,
  which also sets the dark surfaces' tiers). The defaults are basaku's: cozy, soft, sans,
  normal.
- The scales are basaku's: spacing `xxs`…`xxxl`, radius `none`…`full`, type `xs`…`xxxl`,
  shadows `sm`…`xl`, families, line heights, weights, motion, z-index, opacity and focus.
  Held to basaku's own output, exact: the colour options for 19 seeds by 3 presences by 3
  elevations and 30 seed cases (14472 tokens; all 201 palettes pass the full audit), and
  the scales for eleven personality cases of 99 declarations each.
- `@layer uikit` names three sub-layers, `base`, `components` and `utilities`, in that
  order: a utility beats a component by its layer, with no `!important`, and your
  unlayered stylesheet still beats all three.

**Added — utilities and grid.** `ui-utilities.css`, generated in `@layer uikit.utilities`.
- A twelve-column CSS grid: `ui-layout`; spans `ui-col-*` and `ui-col-{bp}-*`; `ui-offset-*`;
  gutters `ui-g-*` `ui-gx-*` `ui-gy-*`; `ui-order-*`; `ui-layout-cols-*`; `ui-col` and
  `ui-col-auto`; and the containers `ui-container`, `ui-container-fluid`, `ui-container-{bp}`.
- Every family of Bootstrap 5.3.8's utilities as `ui-<name>`: display (print too), flex,
  spacing on the kit's scale, sizing, position, borders, radii, shadows, type, text,
  colours, and the helpers (clearfix, ratio, fixed and sticky, stacks, visually-hidden,
  stretched-link, truncate, vr, icon-link, focus-ring).
- Breakpoints are basaku's: `sm` 640, `md` 768, `lg` 1024, `xl` 1280, `xxl` 1536.
- 79908 bytes, 10132 gzipped (Bootstrap's utilities and grid are 107938 and 70323 bytes),
  held to that plus a tenth by a test.

**Added — CSS components**, with no script.
- Buttons: `:variant` `:outline-<fill>` and `:link`, `:block? true`; `toggle-button`, its
  `aria-pressed` the server's; `button-group`, named by a required `:label`, each item
  keeping its own height, so a `confirm` that opens inside it stretches none of its
  neighbours; `close-button`,
  named by the new kit word `:uikit-base/close`.
- Forms: `field` `:size`, `:type "switch"` and `"range"`, `:before` and `:after` (an input
  group), `:floating? true` (a placeholder required); `input-group`. A text control is
  edged in danger once left invalid, and in success once a required one is filled, never
  before it was touched.
- Feedback: `alert` (a `:heading`; a way out, either `:dismiss-href` or `:dismiss-param
  "outcome"`, which goes back to the request's own path and query with every pair of that
  name, as Ring decodes it, taken out and the others kept as they came, so a page's
  `?day=` survives it);
  `badge` `:label` and `:corner?
  true`; `progress` and `meter`, named by a required label, their numbers checked;
  `spinner` and `placeholder`, saying the kit word `:uikit-base/loading`. Every motion
  stops under reduced motion.
- Lists: `list-group`, its items `[path content attrs?]`, the attrs on the item's `<li>`
  (an `id`, `data-*`, `draggable`, a hook class) with the kit's class kept; `table` `:striped?` `:hover?` `:bordered?` `:compact?`; `pager`
  `:pages`, `:current` and `:size`; `tabs-nav` `:style :pills`, `:fill?` or `:justify?`.
- Cards: `card` `:image` (an alt required unless `:decorative? true`) and `:nav`;
  `card-group`; `figure`, its caption required; `collapse` and `accordion` on the
  platform's `<details>`, `:name` for one exclusive group; `scroller`, a named,
  keyboard-reachable line that snaps.

**Added — interactive components**, on the platform's own elements, with `ui.js` for what a
browser of the floor lacks.
- `dialog` and `dialog-button`: the modal `<dialog>`, opened by an invoker command, named by
  its title; `:size`, `:fullscreen?`, `:scrollable?`, and `:static?` (`closedby=none`: only
  its own buttons close it). `offcanvas`: a dialog drawn as a sheet at the inline `:start`
  or `:end`.
- `dropdown` and `popover`: a button and the platform's popover, placed by CSS anchor
  positioning. A dropdown holds links and actions, never an ARIA menu.
- `tooltip`: the server's text in a `role=tooltip`, named first in the trigger's
  `aria-describedby`; shown on hover and focus, kept while the pointer rests on it, hidden
  150 ms after neither is hovered or focused, and by Esc (WCAG 1.4.13). A trigger that Tab
  cannot reach is refused.
- `toasts`, a polite live region, and `toast`: `:timeout-ms` counts down only while it is
  neither hovered nor focused, and a toast is never the only copy of a message (WCAG 2.2.1).
- `carousel`, as the ARIA practices draw one: without the script, a row that snaps; with it,
  previous and next buttons, and `:interval-ms` from 334 ms (WCAG 2.3.1's three changes a
  second) to 2147483647 ms (the most a browser's timer holds), refused outside. The
  rotation waits while hovered, stops for good once the focus enters, and never starts under
  reduced motion.
- `scrollspy`: `#fragment` links, the one whose section is in view `aria-current=location`.
- `ui.js`, the kit's one script: invoker commands, `closedby` and anchor positioning where
  the browser has none, detected once as it loads; the toasts' timer, the carousel, the
  scrollspy. It writes no markup, no text and no inline style as a string, evaluates no
  string, fetches nothing, and positions only through single CSSOM properties
  (`style.setProperty`), which `style-src 'self'` allows. At most
  150 lines of code, which `ui_js_test` holds.
- Seven new kit words for the carousel, in English and Spanish, beside `:close` and
  `:loading`.

**Added — testing.**
- `testing/alerts`: every notice and alert of a page, in page order, as `{:kind :role
  :heading :text :dismiss}`, the dismissal `{:href :name}` or nil: what the reader is told
  and where its way out goes, read from the kit's markup, so a host's test pins those and
  not the markup.
- `testing/unaudited-pairs`: every element of a page whose own classes combine colour
  utilities into a pair basaku never audited — a text utility on a fill, a fill on a
  background utility, two fills.
- `clojure -M:gallery [port]`: every public component on one page, served by web-base under
  the template's CSP; `gallery_test` holds that none is missing, that the page carries no
  inline style, handler or un-nonced script, and that every class it draws is defined.

**Named deviations from Bootstrap.** A parity test reads Bootstrap 5.3.8's utilities and
grid and requires each of its 1705 classes to equal the kit's, after a stated
normalisation, or to be one of 444 named deviations. They fall in these classes:
- logical sides only (`ui-ms-*` is `margin-inline-start`), no `!important`, basaku's
  breakpoints and scales;
- colour utilities only from audited pairs: inks at AA on every canvas, background
  utilities on the three neutral surfaces only, a fill with its own ink (`ui-text-bg-*`),
  edges from the audited ones; no opacity, subtle, emphasis, gradient or transparent
  variants, and one focus ring;
- the grid a CSS grid, not a flex row with negative margins: `row` is `ui-layout` and
  `row-cols-*` is `ui-layout-cols-*`, since `ui-row` is the kit's rows component;
- a scrolling region is positioned, and a shadow keeps the focus ring's halo beneath it.

The full list, each entry with its reason, is `deviations` in
`test/dev/arkaitz/uikit_base/kit_test.clj`. Beyond the utilities, there is no navbar (an
`offcanvas` holds a narrow screen's navigation), and a dropdown is never `role=menu`.

**Browser evidence.** `test/browser/gallery-check.mjs` runs the gallery in WebKit 26.4 and
Chromium 145, natively and with the floor's gaps forced (invoker commands cancelled,
`closedBy` deleted, anchor positioning faked away; in Chromium also anchor positioning
without `anchor-scope`): twelve checks per mode, all passing, recorded in
`test/resources/browser-checks.edn`. `browser_checks_test` holds that every check passed
and that kit.css, ui-utilities.css, ui.js and the script are the bytes they ran against.
Not verified:
- the `closedby` shim in a Chromium without it (131–133): Chromium's own cannot be
  switched off, so the shim's Esc decision is read from `defaultPrevented`; WebKit 26.4,
  which lacks `closedby`, runs the shim for real;
- Firefox: no engine of it was on the machine;
- a real Safari 18.x: its gaps are forced in WebKit 26.4 and Chromium, never run.

**A platform note:** in WebKit, a dialog opened by a mouse click gives the focus to
`<main>` on Esc, not to its button, since a click does not focus a button there; opened by
the keyboard it gives it back to the button (measured).

**In your app:**
1. Bump web-base to 0.25.0.
2. Rename the tokens in app.css, by the table above.
3. Take the brand options you want into brand.edn, if any, and regenerate ui-colors.css
   with `build/write!`; your drift test goes green again.

Optional: your hand-built literals of a notice or an alert in tests can become
`testing/alerts`, and the dismiss paths your views compute (`(:uri request)` and its
kin) can become `:dismiss-param "outcome"`, which also keeps the page's other parameters.

Nothing else is required. A few keys that 0.5.0 passed through as attributes are options
now: `badge`'s `:label` and `:corner?`, `card`'s `:image` and `:nav`, and `:block?` on
`button` and `link-button`.

## 0.5.0 — 2026-11-15

- **A fix you will see on a phone:** a confirm's screen-reader text inside a scrolling
  table escaped the region and widened the page (366px on a 360px screen, measured in
  booking). The region is positioned now, and a test requires every scrolling rule to be.
- `ui/sentence`: Hiccup parts where a translated word's `%1`, `%2` stand, in the
  translation's order, so a time inside "Due %1" keeps its `datetime`.
- `dev.arkaitz.uikit-base.testing`, for a host's tests: `actions` reads the kit's
  actions and confirms back from a page (path, hidden fields, visible label, the name a
  screen reader hears, a confirm's first step); `accessible-name`. Hold the name, not
  the label: the label stays the same when a subject is lost.

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
- From the four hosts' adoption, before release: `page-head` is an `h2` unless told (the
  shell's header holds the `h1`); `confirm` takes `:first` (the opening step's variant
  and attributes, never htmx, which would post on the first press) and `:details` (the
  element's, never `open` nor a non-GET htmx verb); `link-button` takes a `:subject` and
  `:download? true` (a plain link to a path on this origin, loaded as a document);
  `secret` a `:note` in place of the kit's; `action` a `:before`; `time` epoch
  milliseconds, years 1 to 9999.
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
