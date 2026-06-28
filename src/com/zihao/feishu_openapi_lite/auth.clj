(ns com.zihao.feishu-openapi-lite.auth
  (:require [com.zihao.feishu-openapi-lite.http :as http]))

(def token-refresh-skew-ms 60000)

(defn internal-tenant-access-token-request
  [{:keys [domain app-id app-secret]}]
  (http/json-request
   {:method :post
    :url (http/api-url domain "/open-apis/auth/v3/tenant_access_token/internal")
    :body {:app_id app-id
           :app_secret app-secret}}))

(defn normalize-tenant-access-token-response
  ([body]
   (normalize-tenant-access-token-response body (System/currentTimeMillis)))
  ([body now-ms]
   (let [code (:code body)]
     (if (zero? (long (or code 0)))
       (let [expire-seconds (long (or (:expire body) 0))]
         {:tenant-access-token (:tenant_access_token body)
          :expire-seconds expire-seconds
          :expires-at-ms (+ now-ms (* expire-seconds 1000))})
       (throw (ex-info "Feishu tenant access token request failed"
                       {:type :feishu-openapi-lite/api-error
                        :code code
                        :message (:msg body)}))))))

(defn fetch-tenant-access-token!
  [client]
  (-> (internal-tenant-access-token-request client)
      (http/execute-json! client)
      (normalize-tenant-access-token-response)))

(defn token-fresh?
  ([token-state]
   (token-fresh? token-state (System/currentTimeMillis)))
  ([token-state now-ms]
   (and (not-empty (:tenant-access-token token-state))
        (< (+ now-ms token-refresh-skew-ms)
           (long (or (:expires-at-ms token-state) 0))))))

(defn tenant-access-token!
  [{:keys [token*] :as client}]
  (let [token* (or token* (atom nil))
        current @token*]
    (if (token-fresh? current)
      (:tenant-access-token current)
      (:tenant-access-token (reset! token* (fetch-tenant-access-token! client))))))
