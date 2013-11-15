(ns shuppet.core-shuppet
  (:require [clojure.string :refer [lower-case]]
            [clojure.data.json :refer [json-str]]
            [shuppet.util :refer :all]
            [clojure.string :refer [join split]]
            [clojure.java.io :refer [as-file file resource]]
            [shuppet
             [git :as git]
             [securitygroups :refer [ensure-sgs delete-sgs]]
             [elb :refer [ensure-elb delete-elb]]
             [iam :refer [ensure-iam delete-role]]
             [s3 :refer [ensure-s3s delete-s3s]]
             [ddb :refer [ensure-ddbs delete-ddbs]]
             [campfire :as cf]
             [util :refer [to-vec]]]
            [clj-http.client :as client]
            [environ.core :refer [env]]
            [slingshot.slingshot :refer [try+ throw+]]))

(defprotocol ApplicationNames
  (list-names
    [this]
    "Gets a list of the application names"))

(deftype LocalAppNames []
  ApplicationNames
  (list-names
    [_]
    (split (env :service-local-app-names) #",")))

(defprotocol Configuration
  (as-string
    [this env filename readonly]
    "Gets the configuration file as string")
  (configure
    [this app-name]
    "Sets up the configuration file for the application"))

(deftype LocalConfig []
  Configuration
  (as-string
    [_ environment name readonly]
    (let [filename (str (lower-case name) ".clj")
          path (str (env :service-local-config-path) "/" environment "/" filename)]
      (-> path
          (as-file)
          (slurp))))
  (configure
    [_ name]
    (let [dest-path (str (env :service-local-config-path) "/local/" name ".clj")]
      (spit dest-path (slurp (resource "default.clj")))
      {:message (str "Created new configuration file " dest-path)})))

(def ^:dynamic *application-names* (LocalAppNames.))
(def ^:dynamic *configuration* (LocalConfig.))

(defn- with-vars [vars clojure-string]
  (str (join (map (fn [[k v]]
                    (str "(def " (name k) " " (if (string? v) (str "\""v "\"")  v) ")\n"))
                  vars))
       clojure-string))

(defn- execute-string
  [clojure-string & [app-name]]
  (let [ns (-> (java.util.UUID/randomUUID) (str) (symbol) (create-ns))]
    (binding [*ns* ns]
      (refer 'clojure.core)
      (let [config (load-string (with-vars {:$app-name app-name}
                                  clojure-string))]
        (remove-ns (symbol (ns-name ns)))
        config))))

(defn app-names
  []
  (list-names *application-names*))

(defn create-config
  [app-name]
  (configure *configuration* app-name))

(defn- configuration [env name readonly]
  (as-string *configuration* env name readonly))

(defn load-config
  [env & [app-name readonly]]
  (let [environment (configuration env env false)]
    (if app-name
      (let [application (configuration env app-name readonly)]
        (execute-string (str environment "\n" application) app-name))
      (execute-string environment))))


(defn apply-config
  ([env app-name]
     (let [config (cf/with-messages {:env env :app-name app-name}
                    (load-config env app-name false))]
       (cf/with-messages {:env env :app-name app-name :config config}
         (doto config
           ensure-sgs
           ensure-elb
           ensure-iam
           ensure-s3s
           ensure-ddbs)))))

(defn clean-config [environment app-name]
  (let [config (load-config environment app-name)]
    (delete-elb config)
    (Thread/sleep 6000)
    (doto config
      delete-sgs
      delete-role
      delete-s3s
      delete-ddbs)))

(defn update-configs
  [env]
  (let [names (app-names env)]
    (map #(apply-config env %) names)))