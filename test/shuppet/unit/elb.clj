(ns shuppet.unit.elb
  (:require [cheshire.core :as json]
            [clojure.xml :as xml]
            [shuppet.aws :refer [elb-request]]
            [clojure.zip :as zip :refer [children]]
            [clojure.data.zip.xml :refer [xml1-> xml->]])
  (:import (java.io ByteArrayInputStream))
  (:use [shuppet.elb]
        [midje.sweet]))

(def subnet1 "subnet-24df904c")
(def subnet2 "subnet-bdc08fd5")

(def config {:LoadBalancerName "elb-for-test"
             :Listeners [{:LoadBalancerPort 8080
                          :InstancePort 8080
                          :Protocol "http"
                          :InstanceProtocol "http"}
                         {:LoadBalancerPort 80
                          :InstancePort 8080
                          :Protocol "http"
                          :InstanceProtocol "http"}]
             :SecurityGroups ["elb-for-test"]
             :Subnets [subnet1 subnet2]
             :Scheme "internal"
             :HealthCheck {:Target "HTTP:8080/1.x/ping"
                           :HealthyThreshold 2
                           :UnhealthyThreshold "2"
                           :Interval 6
                           :Timeout 5}})

(def to-aws-format @#'shuppet.elb/to-aws-format)
(def update-elb @#'shuppet.elb/update-elb)
(def ensure-subnets @#'shuppet.elb/ensure-subnets)
(def check-fixed-values @#'shuppet.elb/check-fixed-values)
(def ensure-security-groups @#'shuppet.elb/ensure-security-groups)
(def ensure-health-check @#'shuppet.elb/ensure-health-check)
(def ensure-listeners @#'shuppet.elb/ensure-listeners)
(def sg-names-to-ids @#'shuppet.elb/sg-names-to-ids)
(def check-string-value @#'shuppet.elb/check-string-value)

(def xml (->  (slurp "test/shuppet/unit/resources/DescribeLoadBalancersResponse.xml")
              (.getBytes)
              (ByteArrayInputStream.)
              (xml/parse)
              (zip/xml-zip)))

(fact-group :unit

            (fact "correctly convert to aws format"
                  (let [converted-config { "LoadBalancerName" "elb-for-test"
                                           "Listeners.member.1.LoadBalancerPort" "8080"
                                           "Listeners.member.1.InstancePort"   "8080"
                                           "Listeners.member.1.Protocol" "http"
                                           "Listeners.member.1.InstanceProtocol" "http"
                                           "Listeners.member.2.LoadBalancerPort" "80"
                                           "Listeners.member.2.InstancePort"   "8080"
                                           "Listeners.member.2.Protocol" "http"
                                           "Listeners.member.2.InstanceProtocol" "http"
                                           "Subnets.member.1" subnet1
                                           "Subnets.member.2" subnet2
                                           "SecurityGroups.member.1" "elb-for-test"
                                           "Scheme" "internal"
                                           "HealthCheck.Target" "HTTP:8080/1.x/ping"
                                           "HealthCheck.HealthyThreshold" "2"
                                           "HealthCheck.UnhealthyThreshold" "2"
                                           "HealthCheck.Interval" "6"
                                           "HealthCheck.Timeout" "5"}]

                    (to-aws-format config) => converted-config))

            (fact "same text value returns nil"
                  (check-string-value xml :Scheme "internal") => nil)

            (fact "different text value fails"
                  (check-string-value xml :Scheme "wrong") =>  (throws clojure.lang.ExceptionInfo))

            (fact "missing text value fails"
                  (check-string-value xml :missing "value") =>  (throws clojure.lang.ExceptionInfo))

            (fact "error when fixed value changed"
                  (let [config (assoc config :LoadBalancerName "wrong")])
                  (check-fixed-values {:local config :remote xml}) => (throws clojure.lang.ExceptionInfo))

            (fact "health check is not created when identical"
                  (ensure-health-check {:local config :remote xml}) => {:local config :remote xml}
                  (provided
                   (create-healthcheck config) => nil :times 0))

            (fact "health check is created when configs are different"
                  (let [config (assoc-in config [:HealthCheck :Target] "different")]
                    (ensure-health-check {:local config :remote xml}) => {:local config :remote xml}
                    (provided
                     (create-healthcheck config) => nil)))

            (fact "Security groups not are created when configs are identical"
                  (let [config (assoc config :SecurityGroups ["same-as-xml"])]
                    (ensure-security-groups {:local config :remote xml})
                    => {:local config :remote xml}
                    (provided
                     (elb-request anything) => nil :times 0)))

            (fact  "local security groups are applied when configs are different"
                   (ensure-security-groups {:local config :remote xml}) => {:local config :remote xml}
                   (provided
                    (elb-request anything) => nil))

            (fact "nothing done when subnets are identical"
                  (ensure-subnets {:local config :remote xml})=> {:local config :remote xml}
                  (provided
                   (elb-request anything) => nil :times 0))

            (fact "missing subnets are added and extra removed"
                  (let [config (assoc config :Subnets ["different"])]
                    (ensure-subnets {:local config :remote xml})=> {:local config :remote xml}
                    (provided
                     (elb-request anything) => nil :times 2)))

            (fact "nothing done when listeners are identical"
                  (ensure-listeners {:local config :remote xml})=> {:local config :remote xml}
                  (provided
                   (elb-request anything) => nil :times 0))

            (fact "missing listeners are added and extra removed"
                  (let [config (assoc config :Listeners [{:something "different"}])]
                    (ensure-listeners {:local config :remote xml})=> {:local config :remote xml}
                    (provided
                     (elb-request anything) => nil :times 2)))

            (fact "update elb with a list of strings"
                  (update-elb "elb-name" :action :prefix ["v1" "v2"]) => anything
                  (provided
                   (elb-request {"Action" "action"
                                 "LoadBalancerName" "elb-name"
                                 "prefix.member.1" "v1"
                                 "prefix.member.2" "v2"}) => anything))

            (fact "update elb with a list of maps"
                  (update-elb "elb-name" :action :prefix [{:k1 "v1"} {:k2 "v2"}]) => anything
                  (provided
                   (elb-request {"Action" "action"
                                 "LoadBalancerName" "elb-name"
                                 "prefix.member.1.k1" "v1"
                                 "prefix.member.2.k2" "v2"}) => anything)))
