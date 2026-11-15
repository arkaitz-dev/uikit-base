(ns dev.arkaitz.uikit-base.gallery-test
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dev.arkaitz.uikit-base.gallery :as gallery]
            [ring.mock.request :as mock]))

(def ^:private source (slurp (io/resource "dev/arkaitz/uikit_base/gallery.clj")))

(defn- components
  "Every public function of the components' namespace, by name."
  []
  (require 'dev.arkaitz.uikit-base)
  (set (for [[n v] (ns-publics 'dev.arkaitz.uikit-base) :when (fn? @v)] (name n))))

(deftest every-public-component-is-in-the-gallery
  (let [all  (components)
        used (set (map second (re-seq #"\(ui/([a-z][a-z-]*)" source)))]
    (is (< 30 (count all)) "witness: the components were found")
    (is (= #{} (set/difference all used)) "components the gallery never draws")
    (is (= #{} (set/difference used all)) "names the gallery calls that are no public component")))

(defn- served [lang]
  ((gallery/handler) (-> (mock/request :get "/") (mock/header "accept-language" lang))))

(deftest the-gallery-is-served-under-the-templates-policy-and-breaks-none-of-it
  (doseq [lang ["en" "es"]]
    (let [{:keys [status headers body]} (served lang)
          policy (get headers "Content-Security-Policy")
          nonce  (second (re-find #"'nonce-([^']+)'" (str policy)))]
      (is (= 200 status) lang)
      (is (some? nonce) "witness: the policy carries this request's nonce")
      (is (= gallery/csp (str/replace policy (str "nonce-" nonce) "nonce-{nonce}")) "the template's policy, verbatim")
      (is (nil? (re-find #"\sstyle=" body)) "no inline style, which style-src 'self' refuses")
      (is (nil? (re-find #"\son[a-z]+=" body)) "no inline handler, which script-src refuses")
      (is (every? #(str/includes? % (str "nonce=\"" nonce "\"")) (re-seq #"<script[^>]*>" body)) "every script under the nonce")
      (doseq [id ["buttons" "forms" "feedback" "lists" "tables" "navigation" "cards" "overlays" "motion" "text"]]
        (is (str/includes? body (str "<section class=\"ui-stack\" id=\"" id "\">")) (str "the " id " section"))))))

(deftest the-gallery-in-spanish-says-the-kits-words-in-spanish
  (let [body (:body (served "es"))]
    (doseq [w ["Cerrar" "Cargando…" "Páginas" "Ruta de navegación" "Vence el" "Diapositiva siguiente" "carrusel"]]
      (is (str/includes? body w) w))))

(defn- defined-classes
  "Every class a selector of the kit's stylesheets names."
  []
  (set (for [f ["kit.css" "ui-utilities.css" "ui-colors.css"]
             [_ c] (re-seq #"\.(-?[A-Za-z_][\w-]*)" (slurp (io/resource (str "dev/arkaitz/uikit_base/public/" f))))]
         c)))

(deftest every-class-the-gallery-draws-is-one-the-kit-defines
  (let [body  (:body (served "en"))
        drawn (set (for [[_ v] (re-seq #"class=\"([^\"]*)\"" body) c (str/split v #"\s+") :when (str/starts-with? c "ui-")] c))]
    (is (< 60 (count drawn)) "witness: the page draws the kit's classes")
    (is (= #{} (set/difference drawn (defined-classes))) "classes drawn that no stylesheet of the kit defines")))
