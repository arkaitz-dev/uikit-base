(ns dev.arkaitz.uikit-base.scales-test
  "The scales against basaku's own CSS: for each personality level, and two mixes, every
  non-colour declaration basaku's writeThemeCss writes, block by block — light, dark, from
  40em, from 64em (640px and 1024px at the default) — exactly, under the kit's prefix. The table is basaku's output, never
  this port's."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base.scales :as scales]))

(def ^:private goldens (edn/read-string (slurp (io/file "test/resources/basaku-personality-goldens.edn"))))

(deftest every-scale-of-every-personality-is-basakus-own-declaration
  (is (= 11 (count (:cases goldens))) "witness: the default, each other level of each axis, and two mixes")
  (is (= #{99} (set (map #(reduce + (map count (vals (:blocks %)))) (:cases goldens)))) "witness: 99 declarations each")
  (doseq [{:keys [opts blocks]} (:cases goldens)]
    (testing (pr-str opts)
      (let [ours (into {} (for [[k ds] (scales/declarations opts)] [k (into (sorted-map) ds)]))]
        (is (= (set (keys blocks)) (set (keys ours))) "the same blocks")
        (doseq [[k want] blocks]
          (is (= want (get ours k)) (str k)))))))

(deftest the-scales-are-written-for-every-scheme-and-width
  (let [css (scales/css {:density :compact :elevation :raised})]
    (is (str/starts-with? css ":root{--ui-spacing-xxs:0.125rem;") "the light scheme first, unlayered")
    (doseq [prelude [":root.dark{--ui-shadow-sm:" "@media (prefers-color-scheme:dark){:root:not(.light){--ui-shadow-sm:"
                     "@media (min-width:40em){:root{--ui-spacing-md:0.8rem;" "@media (min-width:40em){:root.dark{"
                     "@media (prefers-color-scheme:dark) and (min-width:40em){:root:not(.light){"
                     "@media (min-width:64em){:root{--ui-spacing-md:0.92rem;" "@media (min-width:64em){:root.dark{"
                     "@media (prefers-color-scheme:dark) and (min-width:64em){:root:not(.light){"]]
      (is (str/includes? css prelude) prelude))
    (is (= ["min-width:40em" "min-width:40em" "min-width:40em" "min-width:64em" "min-width:64em" "min-width:64em"]
           (re-seq #"min-width:[^)]*" css))
        "six width blocks, all in em, and no other")
    (is (not (str/includes? css "@layer")) "never in a layer: a host's own colours file wins the kit's by being unlayered")))
