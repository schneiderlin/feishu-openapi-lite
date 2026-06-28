(ns com.zihao.feishu-openapi-lite.http
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [com.zihao.feishu-openapi-lite.json :as json]))

(def default-domain "https://open.feishu.cn")
(def default-user-agent "synapse-feishu-openapi-lite/0.1")

(defn- trim-slash-right
  [s]
  (str/replace (str s) #"/+$" ""))

(defn api-url
  ([path]
   (api-url default-domain path))
  ([domain path]
   (str (trim-slash-right (or domain default-domain))
        (if (str/starts-with? path "/") path (str "/" path)))))

(defn json-body
  [m]
  (json/write-str m))

(defn json-request
  [{:keys [method url body headers query-params timeout-ms]
    :or {timeout-ms 15000}}]
  (cond-> {:method method
           :uri url
           :headers (merge {"Content-Type" "application/json"} headers)
           :as :string
           :throw false
           :timeout timeout-ms}
    (some? body) (assoc :body (json-body body))
    (seq query-params) (assoc :query-params query-params)))

(defn request!
  [client request]
  (let [f (or (:request! client) http/request)]
    (f request)))

(defn parse-response-body
  [response]
  (let [body (:body response)]
    (cond
      (map? body) body
      (string? body) (json/read-str body)
      (bytes? body) (json/read-bytes body)
      (nil? body) nil
      :else (throw (ex-info "Unsupported response body"
                            {:type :feishu-openapi-lite/unsupported-response-body
                             :body-class (some-> body class str)})))))

(defn ok-status?
  [status]
  (<= 200 (long (or status 0)) 299))

(defn execute-json!
  [client request]
  (let [response (request! client request)
        status (:status response)]
    (when-not (ok-status? status)
      (throw (ex-info "Feishu OpenAPI HTTP request failed"
                      {:type :feishu-openapi-lite/http-error
                       :status status
                       :body (:body response)})))
    (parse-response-body response)))
