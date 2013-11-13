(ns shuppet.core
  (:require [clojure.string :refer [lower-case]]
            [clojure.data.json :refer [json-str]]
            [shuppet.util :refer :all]
            [clojure.string :refer [join]]
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

(def ^:const ^:private onix-url (env :environment-entertainment-onix-url))
(def ^:private default-info-room (env :service-campfire-default-info-room))
(def ^:private default-error-rooms
  (conj [default-info-room] (env :service-campfire-default-error-room)))

(defprotocol ApplicationNames
  (list-names
    [apps]
    "Gets a list of the application names"))

(defrecord ^:private FromService [^String url]
           ApplicationNames
           (list-names
             [this]
             (let [url (str url "/applications")
                   response (client/get url {:as :json
                                             :throw-exceptions false})
                   status (:status response)]
               (if (= 200 status)
                 (get-in response [:body :applications])
                 (cf/error {:title "Failed to get application list from onix."
                            :url url
                            :status status
                            :message (:body response)} default-error-rooms)))))

(defrecord ^:private LocalName [^String path]
           ApplicationNames
           (list-names
             [this]
             ["localtest"]))


(defprotocol Configuration
  (contents
    [this]
    "Gets the configuration file contents for evaluation"))

(defrecord ^:private FromGit [^String env ^String filename]
           Configuration
           (contents
             [this]
             (git/get-data (lower-case env) (lower-case filename))))

(defrecord ^:private FromFile [^String env ^String name]
           Configuration
           (contents
             [this]
             (let [f-name (if (= env name)
                            (str (lower-case env) ".clj")
                            (str (lower-case name) ".clj"))
                   resource (str  "test/shuppet/resources/" f-name)]
               (-> resource
                   (clojure.java.io/as-file)
                   (slurp)))))

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

(defn- app-name-provider
  [env]
  (if (= "local" env)
    (LocalName. "irrelevant-path")
    (FromService. onix-url)))

(defn get-app-names
  [env]
  (list-names (app-name-provider env)))

(defn- get-configuration [env name]
  (if (= "local" env)
    (FromFile. env name)
    (FromGit. env name)))

(defn- load-config
  [env app-name]
  (let [environment (contents (get-configuration env env))]
    (if app-name
      (let [application (contents (get-configuration env app-name))]
        (execute-string (str environment "\n" application) app-name))
      (execute-string environment))))

(defn- load-config-with-error-handling
  [env app-name]
  (try+
   (load-config env app-name)
   (catch clojure.lang.Compiler$CompilerException e
     (cf/error {:env env :app-name app-name :title "I cannot read this config" :message (.getMessage e)}
               default-error-rooms)
     (throw+ e))))

(defn apply-config
  ([env & [app-name]]
     (let [config (load-config-with-error-handling env app-name)]
       (binding [cf/*info-rooms* (conj (to-vec (get-in config [:Campfire :Info]))
                                       default-info-room)
                 cf/*error-rooms* (reduce conj
                                          (to-vec (get-in config [:Campfire :Error]))
                                          default-error-rooms)]
         (try+
          (doto config
            ensure-sgs
            ensure-elb
            ensure-iam
            ensure-s3s
            ensure-ddbs)
          (catch [:type :shuppet.util/aws] e
            (cf/error (merge {:env env :app-name app-name} e))
            (throw+ e)))))))

(defn get-config
  ([env & [app-name]]
     (load-config env app-name)))


(defn clean-config [environment app-name]
  (when-not (= "poke" (lower-case (env :environment-name)))
    (throw+ {:type ::wrong-environment}))
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
  (let [names (list-names (FromService. onix-url))]
    (map #(apply-config env %) names)))
