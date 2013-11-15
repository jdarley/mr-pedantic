(ns shuppet.core
  (:require [shuppet
             [core-shuppet :as shuppet]
             [git :as git]
             [campfire :as cf]]
            [clojure.string :refer [lower-case]]
            [clj-http.client :as client]
            [environ.core :refer [env]]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import [shuppet.core_shuppet LocalConfig]
           [shuppet.core_shuppet LocalAppNames]))

(deftype OnixAppNames [^String url]
           shuppet/ApplicationNames
           (list-names
             [_]
             (let [url (str url "/applications")
                   response (client/get url {:as :json
                                             :throw-exceptions false})
                   status (:status response)]
               (if (= 200 status)
                 (get-in response [:body :applications])
                 (cf/error {:title "Failed to get application list from Onix."
                            :url url
                            :status status
                            :message (:body response)})))))

(deftype GitConfig []
           shuppet/Configuration
           (as-string
             [_ env filename]
             (git/get-data (lower-case env) (lower-case filename))))

(defmacro with-ent-bindings
  "Specific Entertainment bindings"
  [env & body]
  `(let [local?# (= "local" ~env)]
     (binding [shuppet/*application-names* (if local?#
                                             (shuppet/LocalAppNames.)
                                             (OnixAppNames. (env :environment-entertainment-onix-url)))
               shuppet/*configuration* (if local?#
                                         (shuppet/LocalConfig.)
                                         (GitConfig.))]
       ~@body)))

(defn apply-config
  ([env & [app-name]]
     (with-ent-bindings env
       (shuppet/apply-config env app-name))))

(defn get-config
  [env & [app-name]]
  (with-ent-bindings env
    (shuppet/load-config env app-name)))

(defn clean-config
  [environment app-name]
  (when-not (env :service-delete-allowed)
    (throw+ {:type ::wrong-environment}))
  (with-ent-bindings environment
    (shuppet/clean-config environment app-name)))

(defn app-names [env]
  (with-ent-bindings env
    (shuppet/app-names)))

(defn update-configs
  [env]
  (with-ent-bindings env
    (shuppet/update-configs)))
