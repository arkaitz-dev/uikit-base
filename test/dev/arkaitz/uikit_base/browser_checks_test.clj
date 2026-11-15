(ns dev.arkaitz.uikit-base.browser-checks-test
  "What a browser saw of the kit, held to the bytes it saw. test/browser/gallery-check.mjs runs
  the gallery in real engines and test/resources/browser-checks.edn records each check; this
  holds that every recorded check passed, and that the stylesheets, the script and the checks
  themselves are still the bytes they were run against — a change to any reds here until the
  checks are run again, since no JVM test can see what they see."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.security MessageDigest)))

(def ^:private record (edn/read-string (slurp (io/resource "browser-checks.edn"))))

(def ^:private files
  {"kit.css"           "resources/dev/arkaitz/uikit_base/public/kit.css"
   "ui-utilities.css"  "resources/dev/arkaitz/uikit_base/public/ui-utilities.css"
   "ui.js"             "resources/dev/arkaitz/uikit_base/public/ui.js"
   "gallery-check.mjs" "test/browser/gallery-check.mjs"})

(defn- sha256 [path]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.readAllBytes (io/input-stream (io/file path))))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(deftest the-browser-checks-were-run-against-these-bytes
  (is (= (set (keys files)) (set (keys (:files record)))) "witness: the record names the files it was run against")
  (doseq [[f path] files]
    (is (= (get-in record [:files f]) (sha256 path))
        (str f " changed since the browser checks ran: run test/browser/gallery-check.mjs in each engine and regenerate browser-checks.edn"))))

(deftest every-recorded-browser-check-passed
  (let [runs (:runs record)]
    (is (= #{"webkit" "chromium"} (set (map :engine runs))) "witness: both engines were run")
    (doseq [{:keys [engine modes]} runs]
      (is (every? (set (keys modes)) ["native" "older-floor"]) (str "witness: " engine " ran natively and with the floor's gaps forced"))
      (doseq [[mode checks] modes]
        (is (<= 12 (count checks)) (str "witness: " engine " " mode " recorded its checks, " (count checks)))
        (is (= [] (sort (keep (fn [[check pass]] (when-not (true? pass) check)) checks)))
            (str "checks that failed in " engine " " mode))))))
