(ns shuppet.s3
  (:require
   [shuppet
    [report :as report]
    [signature :refer [get-signed-request]]
    [util :refer :all]
    [campfire :as cf]]
   [environ.core :refer [env]]
   [slingshot.slingshot :refer [try+ throw+]]
   [clojure.string :refer [split join upper-case trim lower-case]]
   [clj-http.client :as client]
   [clojure.data.zip.xml :refer [xml1-> text]]
   [clojure.xml :as xml]
   [clojure.zip :as zip]
   [clojure.data.json :refer [write-str read-str]]
   [clojure.data.xml :refer [element sexp-as-element emit-str]]
   [clojure.walk :refer [keywordize-keys]]
   [clojure.tools.logging :as log])
  (:import
   [java.net URL]))

;Need to supply this when we use temporary creadentials via IAM roles
(def ^:dynamic *session-token* nil)

(defn- xml-to-map [xml-string]
  (zip/xml-zip (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-string)))))

(def ^:private s3-url (env :service-aws-s3-url))
(def ^:const ^:private s3-valid-locations #{:eu :eu-west-1 :eu-west-2 :ap-southeast-1
                                            :ap-southeast-2 :ap-northeast-1 :sa-east-1})

(def ^:private location
  (delay (let [host (-> s3-url
                        URL.
                        .getHost)
               location (first (split (subs host 0 (.indexOf  host ".amazonaws.com")) #"[.]"))
               s3-location (if (= "s3" location) "" (lower-case (subs location 3)))]
           (if (contains? s3-valid-locations (keyword s3-location))
             s3-location
             ""))))

(defn- create-bucket-body []
  (emit-str (element :CreateBucketConfiguration {}
                     (element :LocationConstraint {} @location))))

(defn- delete-request
  [url date]
  (let [request (get-signed-request "s3" {:url url
                                          :headers (without-nils {"x-amz-date" date
                                                                  "x-amz-security-token" *session-token*})})]
    (client/delete (request :url) {:headers  (request :headers)
                                   :as :xml
                                   :throw-exceptions false})))

(defn- put-request
  [url date body & [content-type]]
  (let [content-type (if content-type content-type "application/xml")
        type (keyword (second (split content-type  #"\/")))
        request (get-signed-request "s3" {:url url
                                          :method :put
                                          :body body
                                          :content-type content-type
                                          :headers (without-nils {"x-amz-date" date
                                                                  "x-amz-security-token" *session-token*})})
        response (client/put (request :url) {:headers  (request :headers)
                                             :as type
                                             :content-type content-type
                                             :body (request :body)
                                             :throw-exceptions false})
           status (:status response)]
       (log/info "S3 put request: " url)
       (when (and (not= 204 status)
                  (not= 200 status))
         (throw-aws-exception "S3" "PUT" url status (xml-to-map (:body response))))))

(defn- get-request
  [url date]
  (let [request (get-signed-request "s3" {:url url
                                          :headers (without-nils {"x-amz-date" date
                                                                  "x-amz-security-token" *session-token*})})
        response (client/get (request :url) {:headers  (request :headers)
                                  :throw-exceptions false} )
        status (:status response)
        body (:body response)
        content-type (get-in response [:headers "content-type"])]
    (log/info "S3 get request: " url)
    (condp = status
      200 body
      404 nil
      301 nil ;tocheck
      (throw-aws-exception "S3" "GET" url status (xml-to-map (:body response))))))

(defn- process
  ([action url body]
     (let [date (rfc2616-time)]
       (condp = (keyword action)
         :CreateBucket  (put-request url date body)
         :ListBucket (get-request url date)
         :DeleteBucket (delete-request url date)
         :GetBucketPolicy (get-request url date)
         :CreateBucketPolicy (put-request url date body "application/json")
         :DeleteBucketPolicy (delete-request url date)
         :GetBucketAcl (get-request url date)
         :CreateBucketAcl (put-request url date body))))
  ([action url]
     (process action url nil)))

(defn- create-policy-stmt
  [opts]
  (join-policies (map create-policy opts)))

(defn- vec-to-string
  [[key val]]
  {key (if (and (vector? val) (= 1 (count val))) (first val) val)})

(defn- to-amazon-format
  [opts]
  (let [item (into {} (map vec-to-string opts))]
    (assoc item :Principal (into {} (map vec-to-string (:Principal item))))))

(defn- get-remote-policy
  [url]
  (let [p-response (process :GetBucketPolicy url)]
    (if-not (empty? p-response)
      (get (keywordize-keys (read-str p-response)) :Statement)
      [])))

(defn- create-id-grant
  [display-name permission id]
  (element :Grant {}
           (element :Grantee {:xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
                              :xsi:type "CanonicalUser"}
                    (element :ID {} id)
                    (element :DisplayName {} display-name))
           (element :Permission {} permission)))

(defn- create-uri-grant
  [permission uri]
  (element :Grant {}
           (element :Grantee {:xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"
                              :xsi:type "Group"}
                    (element :URI {} uri))
           (element :Permission {} permission)))

(defn- create-email-grant
  [permission email]
  (element :Grant {}
           (element :Grantee {:xsi:type "AmazonCustomerByEmail"
                              :xmlns:xsi "http://www.w3.org/2001/XMLSchema-instance"}
                    (element :EmailAddress {} email))
           (element :Permission {} permission)))

(defn- create-grants
  [{:keys [DisplayName Permission] :as opts}]
  (cond
   (not (empty? (:ID opts))) (create-id-grant DisplayName Permission (:ID opts))
   (not (empty? (:URI opts))) (create-uri-grant Permission (:URI opts))
   (not (empty? (:EmailAddress opts))) (create-email-grant Permission (:EmailAddress opts))))

(defn- owner-xml
  [{:keys [ID DisplayName]}]
  (element :Owner {}
           (element :ID {} ID)
           (element :DisplayName {} DisplayName)))

(defn- put-acl-body
  [owner acls]
  (element :AccessControlPolicy {:xmlns "http://s3.amazonaws.com/doc/2006-03-01/"}
           owner
           (element :AccessControlList {}
                    acls)))

(defn- put-acl
  [{:keys [Owner]} acls url]
  (let [owner (owner-xml Owner)
        acls-xml (map create-grants acls)
        body (put-acl-body owner acls-xml)]
    (process :CreateBucketAcl url (emit-str body))))

(defn- local-acls
  [{:keys [Owner AccessControlList]}]
  (reduce concat (map #(create-acl (:DisplayName Owner) %) AccessControlList)))

(defn- ensure-acl
  [{:keys [BucketName AccessControlPolicy]}]
  (Thread/sleep 1000);Bucket creation can be slow
  (let [url (str s3-url "/" BucketName "/?acl")
        ; get-response (process :GetBucketAcl url)
        local-config (local-acls AccessControlPolicy)]
        ;Not doing the comparison here as is not a requirement now.
    (put-acl AccessControlPolicy local-config url)
    (report/add :CreateBucketAcl  (str "I've succesfully applied the acl for '" BucketName "'"))
    (cf/info (str "I've succesfully applied the acl for '" BucketName "'"))))

(defn- ensure-policy
  [{:keys [BucketName Id Statement]}]
  (Thread/sleep 1000) ;Bucket creation can be slow
  (let [url (str s3-url  "/" BucketName "/?policy")
        l-config (create-policy-stmt Statement)
        remote (get-remote-policy url)
        local (vec (map to-amazon-format (get l-config :Statement)))
        [r l] (compare-config local remote)]
    (when-not (empty? l)
      (process :CreateBucketPolicy url (write-str (without-nils (merge l-config {:Id Id}))))
      (report/add :CreateBucketPolicy  (str "I've succesfully created a bucket policy for '" BucketName "'"))
      (cf/info (str "I've succesfully created a bucket policy for '" BucketName "'")))
    (when-not (empty? r)
      (when (empty? l)
        (process :DeleteBucketPolicy url)
        (report/add :DeleteBucketPolicy (str "I've succesfully deleted the bucket policy for '" BucketName "'"))
        (cf/info (str "I've succesfully deleted the bucket policy for '" BucketName "'"))))))

(defn- ensure-s3
  [{:keys [BucketName] :as opts}]
  (let [url (str s3-url  "/" BucketName)
        get-response (process :ListBucket url)]
    (when (empty? get-response)
      (process :CreateBucket url (create-bucket-body))
      (report/add :CreateBucket (str "I've created a new S3 bucket called '" BucketName "'"))
      (cf/info (str "I've created a new S3 bucket called '" BucketName "'")))
    (when (:Statement opts)
      (ensure-policy opts))
    (when (:AccessControlPolicy opts)
      (ensure-acl opts))))

(defn ensure-s3s
  [{:keys [S3]}]
  (doseq [s3 S3]
    (ensure-s3 s3)))

(defn- delete-s3
  [{:keys [BucketName]}]
  (let [url (str s3-url "/" BucketName)]
    (process :DeleteBucket url)))

(defn delete-s3s
  [{:keys [S3]}]
  (doseq [s3 S3]
    (delete-s3 s3)))
