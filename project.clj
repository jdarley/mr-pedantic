(defproject shuppet "0.7-SNAPSHOT"
  :description "Shuppet service"
  :url "http://wikis.in.nokia.com/NokiaMusicArchitecture/Shuppet"

  :dependencies [[compojure "1.1.5" :exclusions [javax.servlet/servlet-api]]
                 [ring-json-params "0.1.3"]
                 [ring-middleware-format "0.3.1"]
                 [ring/ring-jetty-adapter "1.2.0"]
                 [org.clojure/clojure "1.5.1"]
                 [org.clojure/data.json "0.2.3"]
                 [org.clojure/data.xml "0.0.7"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.slf4j/slf4j-api "1.7.5"]
                 [org.slf4j/jcl-over-slf4j "1.7.5"]
                 [org.slf4j/jul-to-slf4j "1.7.5"]
                 [org.slf4j/log4j-over-slf4j "1.7.5"]
                 [ch.qos.logback/logback-classic "1.0.13"]
                 [com.ovi.common.logging/logback-appender "0.0.45" :exclusions [commons-logging/commons-logging]]
                 [com.yammer.metrics/metrics-logback "2.2.0"]
                 [com.ovi.common.metrics/metrics-graphite "2.1.21"]
                 [clj-http "0.7.6"]
                 [cheshire "5.2.0"]
                 [clj-time "0.6.0"]
                 [environ "0.4.0"]
                 [nokia/ring-utils "1.0.1"]
                 [metrics-clojure "1.0.1"]
                 [metrics-clojure-ring "1.0.1"]
                 [commons-collections "3.2.1"]
                 [org.eclipse.jgit "3.0.0.201306101825-r"]
                 [me.raynes/conch "0.5.0"]
                 [clj-campfire "2.1.0"]]

  :profiles {:dev {:dependencies [[com.github.rest-driver/rest-client-driver "1.1.32"
                                   :exclusions [org.slf4j/slf4j-nop
                                                javax.servlet/servlet-api
                                                org.eclipse.jetty.orbit/javax.servlet]]
                                  [clj-http-fake "0.4.1"]
                                  [junit "4.11"]
                                  [midje "1.5.1"]
                                  [rest-cljer "0.1.7"]]
                   :plugins [[lein-rpm "0.0.4"]
                             [lein-midje "3.0.1"]
                             [jonase/kibit "0.0.8"]]}}

  :plugins [[lein-ring "0.8.6"]
            [lein-environ "0.4.0"]
            [lein-release "1.0.73"]]

  ;; development token values
  :env {:environment-name "Dev"
        :service-name "shuppet"
        :service-port "8080"
        :service-url "http://localhost:%s/1.x"
        :restdriver-port "8081"
        :environment-entertainment-graphite-host "graphite.brislabs.com"
        :environment-entertainment-graphite-port "8080"
        :service-graphite-post-interval "1"
        :service-graphite-post-unit "MINUTES"
        :service-graphite-enabled "ENABLED"
        :service-production "false"

        ;;to-check
        :environment-entertainment-onix-url "http://onix.brislabs.com:8080/1.x"

        ;;aws-config
        :service-aws-access-key-id "AKIAI7INFGUXMYXWWBYQ"
        :service-aws-secret-access-key "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"
        :service-aws-ec2-url "https://ec2.eu-west-1.amazonaws.com"
        :service-aws-ec2-api-version "2013-10-01"
        :service-aws-elb-url "https://elasticloadbalancing.eu-west-1.amazonaws.com"
        :service-aws-elb-version "2012-06-01"
        :service-aws-sts-url "https://sts.amazonaws.com"
        :service-aws-sts-api-version "2011-06-15"
        :service-aws-iam-url "https://iam.amazonaws.com"
        :service-aws-iam-api-version "2010-05-08"

        ;;git-config
        :service-base-git-repository-url "ssh://snc@source.nokia.com/shuppet/git/"
        :service-base-git-repository-path "/tmp/repos/"
        :service-base-git-repository-branch "dev"

        ;;campfire
        :service-campfire-api-token "acec839becb8d253b2973f1614d46ce34e640da4"
        :service-campfire-sub-domain "nokia-entertainment"
        }

  :lein-release {:release-tasks [:clean :uberjar :pom :rpm]
                 :clojars-url "clojars@clojars.brislabs.com:"}

  :ring {:handler shuppet.web/app
         :main shuppet.setup
         :port ~(Integer.  (get (System/getenv) "SERVICE_PORT" "8080"))
         :init shuppet.setup/setup
         :browser-uri "/1.x/status"
         :nrepl {:start? true}}

  :repositories {"internal-clojars"
                 "http://clojars.brislabs.com/repo"
                 "rm.brislabs.com"
                 "http://rm.brislabs.com/nexus/content/groups/all-releases"}

  :uberjar-name "shuppet.jar"

  :resource-paths ["shared"]

  :rpm {:name "shuppet"
        :summary "RPM for Shuppet service"
        :copyright "Nokia 2013"
        :preinstall {:scriptFile "scripts/rpm/preinstall.sh"}
        :postinstall {:scriptFile "scripts/rpm/postinstall.sh"}
        :preremove {:scriptFile "scripts/rpm/preremove.sh"}
        :postremove {:scriptFile "scripts/rpm/postremove.sh"}
        :requires ["jdk >= 2000:1.6.0_31-fcs"]
        :mappings [{:directory "/usr/local/jetty"
                    :filemode "444"
                    :username "jetty"
                    :groupname "jetty"
                    :sources {:source [{:location "target/shuppet.jar"}]}}
                   {:directory "/usr/local/jetty/bin"
                    :filemode "744"
                    :username "jetty"
                    :groupname "jetty"
                    :sources {:source [{:location "scripts/bin"}]}}
                   {:directory "/usr/local/deployment/shuppet/bin"
                    :filemode "744"
                    :sources {:source [{:location "scripts/dmt"}]}}
                   {:directory "/etc/rc.d/init.d"
                    :filemode "744"
                    :username "jetty"
                    :groupname "jetty"
                    :sources {:source [{:location "scripts/service/jetty"}]}}]}

  :main shuppet.setup)
