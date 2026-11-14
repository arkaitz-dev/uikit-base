(ns dev.arkaitz.uikit-base.emit
  "A palette as the CSS a host serves: every token a custom property `--ui-<token>`, the
  light values on `:root`, the dark ones on `:root.dark` and under `prefers-color-scheme:
  dark` unless the page says `.light`, each scheme declaring its `color-scheme` so form
  controls and scrollbars follow it — as basaku's theme_builder.zig:137-152 writes them.

  Then the bridge: web-base's `--wb-*` onto the palette, so the shell and auth-base's
  pages (whose `--ab-*` default to `--wb-*`) follow the brand. All of it unlayered, on
  purpose: wb.css is unlayered, and an unlayered rule beats every layered one, so a
  bridge inside a layer would leave the shell neutral with nothing to say so.")

(def bridge
  "Which palette token each of wb.css's colour properties takes."
  [["--wb-bg" :background] ["--wb-text" :on-background] ["--wb-muted" :text-muted]
   ["--wb-border" :border] ["--wb-accent" :link] ["--wb-danger" :danger-ink]])

(defn- declarations [tokens i]
  (apply str (for [[token pair] (sort-by key tokens)] (str "--ui-" (name token) ":" (pair i) ";"))))

(defn css
  "The colours file for `tokens` (what `brand/theme` answers), headed by `comment`."
  [tokens comment]
  (str "/* " comment " */\n"
       ":root{color-scheme:light;" (declarations tokens 0) "}\n"
       ":root.dark{color-scheme:dark;" (declarations tokens 1) "}\n"
       "@media (prefers-color-scheme:dark){:root:not(.light){color-scheme:dark;" (declarations tokens 1) "}}\n"
       ":root{" (apply str (for [[property token] bridge] (str property ":var(--ui-" (name token) ");"))) "}\n"))
