(ns dev.arkaitz.uikit-base.scales-test
  "The scales against basaku's own CSS: for each personality level, and two mixes, every
  non-colour declaration basaku's writeThemeCss writes, block by block — light, dark, from
  640px, from 1024px — exactly, under the kit's prefix. The table is basaku's output, never
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
                     "@media (min-width:640px){:root{--ui-spacing-md:0.8rem;" "@media (min-width:640px){:root.dark{"
                     "@media (prefers-color-scheme:dark) and (min-width:640px){:root:not(.light){"
                     "@media (min-width:1024px){:root{--ui-spacing-md:0.92rem;" "@media (min-width:1024px){:root.dark{"
                     "@media (prefers-color-scheme:dark) and (min-width:1024px){:root:not(.light){"]]
      (is (str/includes? css prelude) prelude))
    (is (not (str/includes? css "@layer")) "never in a layer: a host's own colours file wins the kit's by being unlayered")))
