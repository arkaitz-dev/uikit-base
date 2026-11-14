(ns dev.arkaitz.uikit-base.native-test
  "The native-image metadata this jar ships, so a host's image serves the kit's stylesheet
  without being told its path. What only a build proves — that GraalVM reads it — the host
  template's native build does; this holds the globs to the files beside them, which is
  where they drift: a stylesheet added without its glob is a 404 in the binary alone."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private metadata-path "META-INF/native-image/dev.arkaitz/uikit-base/reachability-metadata.json")

(defn- globs
  "The resource globs of the metadata on the classpath, where a jar carries it."
  []
  (some->> (io/resource metadata-path) slurp (re-seq #"\"glob\"\s*:\s*\"([^\"]+)\"") (mapv second)))

(defn- glob->regex
  "GraalVM's resource glob: `**` crosses directories, `*` stays within one. Translated in
  one pass, so the `.*` of a `**` is never rewritten by the `*` rule."
  [glob]
  (re-pattern (str/replace (java.util.regex.Pattern/quote glob) #"\*\*|\*"
                           #(if (= "**" %) "\\E.*\\Q" "\\E[^/]*\\Q"))))

(defn- shipped
  "Every file under `dir` of resources/, which build.clj copies whole into the jar."
  [dir]
  (let [root (io/file "resources")]
    (->> (file-seq (io/file root dir))
         (filter #(.isFile ^java.io.File %))
         (mapv #(str/replace (str (.relativize (.toPath root) (.toPath ^java.io.File %))) "\\" "/")))))

(deftest glob-translation-crosses-directories-only-for-a-double-star
  (is (re-matches (glob->regex "a/**") "a/b/c.css") "** crosses directories")
  (is (not (re-matches (glob->regex "a/*") "a/b/c.css")) "* stays within one")
  (is (re-matches (glob->regex "a/*") "a/c.css") "and matches a file of that one"))

(deftest every-file-the-kit-ships-is-registered-for-a-native-image--and-nothing-else
  (let [gs    (globs)
        files (shipped "dev/arkaitz/uikit_base/public")
        hit?  (fn [path] (some #(re-matches (glob->regex %) path) gs))]
    (is (seq gs) (str "the metadata is on the classpath at " metadata-path))
    (is (= 1 (count files)) (str "witness: the files the kit ships, read from resources/: " files))
    (is (= [] (remove hit? files)) "every one of them is matched by a glob")
    (is (not (hit? "dev/arkaitz/uikit_base/public"))
        "and the directory is not: a registered directory is served as a listing of its names")
    (is (= [] (remove (fn [g] (some #(re-matches (glob->regex g) %) files)) gs))
        "no glob names something the jar does not ship")))
