(ns dev.arkaitz.uikit-base.build-test
  "Generating a host's colours: the brand file refused unless it is one primary hex, a
  palette that breaks the audit refused with nothing written, and the colours this library
  ships equal to what its default brand generates — the drift test every host copies."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dev.arkaitz.uikit-base.brand :as brand]
            [dev.arkaitz.uikit-base.build :as build])
  (:import [clojure.lang ExceptionInfo]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir [] (str (Files/createTempDirectory "uikit-build" (make-array FileAttribute 0))))

(defn- brand-file [content]
  (let [f (io/file (temp-dir) "brand.edn")] (spit f content) (str f)))

(deftest the-brand-file-is-one-primary-hex-or-it-is-refused
  (is (= {:primary "#2563eb"} (build/read-brand (brand-file "{:primary \"#2563EB\"}"))) "control: read, lower-cased")
  (let [missing (str (io/file (temp-dir) "no-such.edn"))]
    (is (= {:path missing} (try (build/read-brand missing) nil (catch ExceptionInfo e (ex-data e))))
        "a path with no file is refused, naming it"))
  (doseq [content ["{:primary \"#2563eb\" :x 1}" "{}" "{:primary \"#25e\"}" "[1]" "{:primary \"2563eb\"}" "{:primary" ""]]
    (testing (pr-str content)
      (let [path (brand-file content)
            e    (try (build/read-brand path) nil (catch ExceptionInfo e e))]
        (is (= (str "uikit-base: " path " must hold {:primary \"#rrggbb\"} and nothing else") (ex-message e)))
        (is (= {:path path} (ex-data e)))))))

(deftest a-palette-that-breaks-the-audit-is-refused-and-nothing-is-written
  (let [theme brand/theme
        out   (io/file (temp-dir) "never" "ui-colors.css")
        path  (brand-file "{:primary \"#2563eb\"}")]
    (is (not (.exists out)) "witness: nothing there before")
    (with-redefs [brand/theme (fn [b] (let [t (theme b)] (assoc-in t [:on-primary 0] (get-in t [:primary 0]))))]
      (let [e (try (build/write! path (str out)) nil (catch ExceptionInfo e e))]
        (is (str/starts-with? (str (ex-message e)) "uikit-base: the palette of #2563eb breaks 3 audited pairs: ") (ex-message e))
        (is (= #{[[:primary :on-primary] :light] [[:primary-hover :on-primary] :light] [[:primary-active :on-primary] :light]}
               (set (map (juxt :pair :scheme) (:failures (ex-data e))))))))
    (is (not (.exists (.getParentFile out))) "and nothing written, not even its directory")
    (with-redefs [brand/cvd-ok? (constantly false)]
      (is (= "uikit-base: the status colours no longer tell apart under every colour vision"
             (try (build/expected path) nil (catch ExceptionInfo e (ex-message e))))))))

(deftest write-writes-exactly-what-is-expected
  (let [path (brand-file "{:primary \"#2563eb\"}")
        out  (io/file (temp-dir) "a" "b" "ui-colors.css")]
    (is (= (str out) (build/write! path (str out))) "answers the path, its directories made")
    (is (= (build/expected path) (slurp out)))))

(deftest the-colours-this-library-ships-are-what-its-default-brand-generates
  ;; A drift test, not a correctness one (emit_test is): it reds when the brand, the
  ;; engine or the file changed without the others.
  (let [committed (slurp (io/file "resources/dev/arkaitz/uikit_base/public/ui-colors.css"))]
    (is (str/includes? committed "#2563eb") "witness: the default brand's file")
    (is (= (build/expected "default-brand.edn") committed) "regenerate with (build/write! \"default-brand.edn\" …)")))
