(ns dev.arkaitz.uikit-base.control.outer
  "A positive control for structure_test: it loads another namespace outside its ns form,
  which a scan of ns forms would not see and a fresh JVM must.")

(require 'dev.arkaitz.uikit-base.control.inner)
