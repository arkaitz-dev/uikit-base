(ns dev.arkaitz.uikit-base.utilities-test
  "The utilities file this library ships: the generator's output, committed. What the
  classes do in the cascade is kit_test's, which reads the file as a host's browser would."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.uikit-base.utilities :as utilities])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private committed-path "resources/dev/arkaitz/uikit_base/public/ui-utilities.css")

(deftest the-utilities-this-library-ships-are-what-the-generator-writes
  ;; A drift test, not a correctness one (kit_test is): it reds when the generator or the
  ;; file changed without the other.
  (let [committed (slurp (io/file committed-path))]
    (is (str/includes? committed ".ui-layout{") "witness: the utilities' file")
    (is (= (utilities/css) committed)
        "regenerate with (dev.arkaitz.uikit-base.utilities/write! \"resources/dev/arkaitz/uikit_base/public/ui-utilities.css\")")))

(deftest write-writes-exactly-the-file
  (let [out (io/file (str (Files/createTempDirectory "uikit-utilities" (make-array FileAttribute 0))) "a" "ui-utilities.css")]
    (is (= (str out) (utilities/write! (str out))) "answers the path, its directories made")
    (is (= (utilities/css) (slurp out)))))

(defn- gzipped-size [^bytes b]
  (let [o (java.io.ByteArrayOutputStream.)]
    (with-open [z (java.util.zip.GZIPOutputStream. o)] (.write z b))
    (.size o)))

(def ^:private measured
  "The utilities file at 0.6.0, measured on 2026-11-15: 79908 bytes, 10132 gzipped (Java's
  GZIPOutputStream, its default level). Bootstrap 5.3.8's utilities and grid, which it
  replaces, are 107938 and 70323 bytes."
  {:raw 79908 :gzip 10132})

(deftest the-utilities-stay-within-their-size-budget
  ;; Declared policy, not a derived bound: the measured size above plus a tenth (87898 raw,
  ;; 11145 gzipped). Growth past that is a decision to make, and to write here with its new
  ;; measurement — never a number raised to make this green.
  (let [b      (java.nio.file.Files/readAllBytes (.toPath (io/file committed-path)))
        budget (fn [k] (long (* 1.1 (measured k))))]
    (is (< (alength b) 107938) "witness: smaller than Bootstrap's utilities alone")
    (is (<= (alength b) (budget :raw)) (str "raw bytes: " (alength b)))
    (is (<= (gzipped-size b) (budget :gzip)) (str "gzipped bytes: " (gzipped-size b)))))
