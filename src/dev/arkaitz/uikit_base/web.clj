(ns dev.arkaitz.uikit-base.web
  "uikit-base as a web-base plugin: it serves the kit under `/ui/` and links its three
  stylesheets after web-base's own — the default colours, the kit, then its utilities. A host links its
  own colours file (generated from its brand) and its own stylesheet after these, in its
  `:stylesheets`, so both override the kit by order and by being unlayered. It brings the
  kit's own words (the pager's) as a dictionary, so a host must choose its locale
  (`:i18n :default-locale`), as with any plugin that brings words. Like the components, it never loads the
  colour engine."
  (:require [dev.arkaitz.uikit-base :as ui]))

(def ^:private asset-root {:path "/ui/" :root "dev/arkaitz/uikit_base/public"})

(defn plugin
  "The plugin map for web-base's `:plugins`."
  []
  {:wb.plugin/name :uikit-base
   :assets         asset-root
   :stylesheets    ["/ui/ui-colors.css" "/ui/kit.css" "/ui/ui-utilities.css"]
   :i18n           {:dict ui/words}})
