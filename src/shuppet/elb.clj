(ns shuppet.elb
  (:require
   [shuppet.aws :refer [elb-request]]
   [clj-http.client :as client]
   [clojure.string :refer [join]]
   [clojure.tools.logging :as log]
   [clojure.xml :as xml]
   [clojure.data :refer [diff]]
   [clojure.zip :as zip]
   [slingshot.slingshot :refer [throw+ try+]]
   [clojure.data.zip.xml :refer [xml1-> text]]))

(defn get-listeners
  "returns a list of maps, very specific"
  [xml]
  )

(defn get-subnets
  "returns a list of strings, can be generalised eg get-string-list name"
  [xml]
  )

(defn compare-lists
  "use indexof to see what is missing, returns map :a :b with lists, can be used for maps or strings"
  [a b]

  )

(defn get-security-groups
  "returns a list of strings, same as subnet"
  [xml])

(defn map-to-dot [prefix m]
  (map (fn [[k v]] [(str prefix "." (name k)) (str v)])
       m))

(defn to-member [prefix i]
  (str prefix ".member." i))

(defn list-to-member [prefix list]
  (flatten (map (fn [i v]
                  (cond
                   (map? v) (map-to-dot (to-member prefix i) v)
                   :else [(to-member prefix i) (str v)]))
                (iterate inc 1)
                list)))

(defn to-aws-format
  "Transforms shuppet config to aws config format"
  [config]
  (apply hash-map (flatten (map (fn [[k v]]
                                  (let [k (name k)]
                                    (cond (sequential? v) (list-to-member k v)
                                          (map? v) (map-to-dot k v)
                                          :else [k (str v)])))
                                config))))

(defn create-elb [config]
  (let [elb-config (dissoc config :HealthCheck)
        health-check-config (select-keys config [:LoadBalancerName :HealthCheck])]
    (elb-request (merge {"Action" "CreateLoadBalancer"} (to-aws-format elb-config)))
    (elb-request (merge {"Action" "ConfigureHealthCheck"} (to-aws-format health-check-config)))))

(defn find-elb [name]
  (try+
   (elb-request {"Action" "DescribeLoadBalancers"
                 "LoadBalancerNames.member.1" name})
   (catch [:code "LoadBalancerNotFound"] _
       nil)))

(defn check-string-value [remote k v]
  (let [remote-value (xml1->
                      remote
                      :DescribeLoadBalancersResult :LoadBalancerDescriptions :member k text)]
    (cond
     (nil? remote-value) (throw+ {:type ::missing-value :key k })
     (not (= remote-value (str v))) (throw+ {:type ::wrong-value :key k
                                           :local-value v
                                           :remote-value remote-value}))))

(defn check-fixed-values [{:keys [local remote] :as config}]
  (dorun (map (fn [[k v]]
                (cond
                 (string? v) (check-string-value remote k v)))
              local))
  config)

(defn create-listeners [config]
  (elb-request (merge {"Action" "CreateLoadBalancerListeners"} (to-aws-format config))))

(defn delete-listener [elb-name listener-port]
  (elb-request {"Action" "DeleteLoadBalancerListeners"
                "LoadBalancerName" elb-name
                "LoadBalancerPorts.member.1" listener-port}))

(defn delete-elb [elb-name]
  (elb-request {"Action" "DeleteLoadBalancer"
                "LoadBalancerName" elb-name}))

(defn ensure-health-check [{:keys [local remote] :as config}]
config
  )

(defn ensure-config [local]
  (if-let [remote (find-elb (:LoadBalancerName local))]
    (-> {:local local :remote remote}
        (check-fixed-values)
        (ensure-health-check))
    (create-elb local))

        ;    (ensure-security-groups)
 ;   (ensure-listener)
                                        ;    (ensure-subnet)
  )