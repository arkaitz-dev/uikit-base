(ns dev.arkaitz.uikit-base.web
  "uikit-base as a web-base plugin: it serves the kit under `/ui/` and links its two
  stylesheets after web-base's own — the default colours, then the kit. A host links its
  own colours file (generated from its brand) and its own stylesheet after these, in its
  `:stylesheets`, so both override the kit by order and by being unlayered. Like the
  components, it never loads the colour engine.")

(def ^:private asset-root {:path "/ui/" :root "dev/arkaitz/uikit_base/public"})

(defn plugin
  "The plugin map for web-base's `:plugins`."
  []
  {:wb.plugin/name :uikit-base
   :assets         asset-root
   :stylesheets    ["/ui/ui-colors.css" "/ui/kit.css"]})
