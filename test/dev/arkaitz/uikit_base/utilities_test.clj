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
