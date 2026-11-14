(ns dev.arkaitz.uikit-base.structure-test
  "What the runtime namespaces may load. A host commits its generated colours, so the
  colour engine — `color`, `brand`, `audit`, `emit`, `build` — is never needed at run time,
  and the components and the plugin must never load it: not in their ns forms, not through
  a helper, not by a `require` outside the ns form. Only a fresh JVM can see that; this
  one has loaded whatever the other tests needed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private accepted-runtime
  "Every namespace of this library a running host loads, by name, so a new one reds until
  it is listed here with its reason."
  '#{dev.arkaitz.uikit-base       ; the components
     dev.arkaitz.uikit-base.web}) ; the plugin

(def ^:private engine
  '#{dev.arkaitz.uikit-base.color dev.arkaitz.uikit-base.brand dev.arkaitz.uikit-base.audit
     dev.arkaitz.uikit-base.emit dev.arkaitz.uikit-base.build})

(defn- loaded-after
  "The namespaces of this library loaded in a fresh JVM after requiring `nss`, or a
  failure naming why the JVM gave no answer."
  [nss]
  (let [form (pr-str `(do (apply require '~nss)
                          (pr (into (sorted-set)
                                    (filter #(str/starts-with? (str %) "dev.arkaitz.uikit-base"))
                                    (map ns-name (all-ns))))))
        java (str (System/getProperty "java.home") "/bin/java")
        proc (.start (ProcessBuilder. ^java.util.List
                       [java "-cp" (System/getProperty "java.class.path") "clojure.main"
                        "-e" (str "(require 'clojure.string) " form)]))]
    (if-not (.waitFor proc 60 TimeUnit/SECONDS)
      (do (.destroyForcibly proc) {:failure "the fresh JVM did not answer within 60 s"})
      (let [out (slurp (.getInputStream proc)) err (slurp (.getErrorStream proc))]
        (if (zero? (.exitValue proc))
          {:loaded (edn/read-string out)}
          {:failure (str "the fresh JVM exited " (.exitValue proc) ": " err)})))))

(deftest the-harness-sees-a-namespace-loaded-outside-an-ns-form
  (let [{:keys [loaded failure]} (loaded-after '[dev.arkaitz.uikit-base.control.outer])]
    (is (nil? failure) failure)
    (is (= '#{dev.arkaitz.uikit-base.control.inner dev.arkaitz.uikit-base.control.outer} loaded)
        "the control's own namespace and the one it loads by a bare require")))

(deftest the-runtime-namespaces-load-nothing-else-of-this-library
  (let [{:keys [loaded failure]} (loaded-after (vec (sort accepted-runtime)))]
    (is (nil? failure) failure)
    (is (= accepted-runtime loaded) (str "loaded beyond the accepted: " (vec (remove accepted-runtime loaded))))))

(deftest every-source-namespace-is-runtime-or-engine
  (let [root  (io/file "src")
        nss   (->> (file-seq root)
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
                   (map #(second (edn/read-string (slurp %))))
                   set)]
    (is (= accepted-runtime (into #{} (filter accepted-runtime) nss)) "witness: the scan read the runtime namespaces")
    (is (= [] (filter accepted-runtime engine)) "no engine namespace is accepted at run time")
    (is (= [] (remove (some-fn accepted-runtime engine) nss))
        "a source namespace that is neither reds here, so it is classified before it ships")))
