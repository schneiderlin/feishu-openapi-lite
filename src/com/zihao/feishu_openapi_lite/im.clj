(ns com.zihao.feishu-openapi-lite.im
  (:require [clojure.string :as str]
            [com.zihao.feishu-openapi-lite.auth :as auth]
            [com.zihao.feishu-openapi-lite.http :as http]
            [com.zihao.feishu-openapi-lite.json :as json]))

(defn text-content
  [text]
  (json/write-str {:text (str text)}))

(defn- nonblank
  [x]
  (when-not (str/blank? (str (or x "")))
    (str x)))

(defn- kget
  [m k]
  (or (get m k)
      (get m (keyword (str/replace (name k) "-" "_")))
      (get m (name k))
      (get m (str/replace (name k) "-" "_"))))

(defn- sent-message->map
  [message]
  (when (map? message)
    (cond-> {}
      (kget message :message-id) (assoc :message-id (kget message :message-id))
      (kget message :root-id) (assoc :root-id (kget message :root-id))
      (kget message :parent-id) (assoc :parent-id (kget message :parent-id))
      (kget message :thread-id) (assoc :thread-id (kget message :thread-id))
      (kget message :msg-type) (assoc :message-type (kget message :msg-type))
      (kget message :message-type) (assoc :message-type (kget message :message-type))
      (kget message :create-time) (assoc :create-time (kget message :create-time))
      (kget message :update-time) (assoc :update-time (kget message :update-time))
      (some? (kget message :deleted)) (assoc :deleted? (kget message :deleted))
      (some? (kget message :updated)) (assoc :updated? (kget message :updated))
      (kget message :chat-id) (assoc :chat-id (kget message :chat-id))
      (kget message :upper-message-id) (assoc :upper-message-id (kget message :upper-message-id))
      (kget message :message-app-link) (assoc :message-app-link (kget message :message-app-link)))))

(defn create-message-request
  [{:keys [domain tenant-access-token]} {:keys [chat-id receive-id receive-id-type text msg-type content uuid]
                                         :or {receive-id-type "chat_id"}}]
  (let [receive-id (or (nonblank receive-id)
                       (nonblank chat-id)
                       (throw (ex-info "receive-id is required"
                                       {:type :feishu-openapi-lite/missing-option
                                        :required-any-of [:receive-id :chat-id]})))
        msg-type (or (nonblank msg-type) "text")
        content (or content
                    (when (= "text" msg-type)
                      (text-content (or text ""))))
        token (or tenant-access-token
                  (throw (ex-info "tenant-access-token is required"
                                  {:type :feishu-openapi-lite/missing-option
                                   :option :tenant-access-token})))]
    (http/json-request
     {:method :post
      :url (http/api-url domain "/open-apis/im/v1/messages")
      :query-params {"receive_id_type" receive-id-type}
      :headers {"Authorization" (str "Bearer " token)}
      :body (cond-> {:receive_id receive-id
                     :msg_type msg-type
                     :content content}
              (nonblank uuid) (assoc :uuid uuid))})))

(defn reply-message-request
  [{:keys [domain tenant-access-token]} {:keys [message-id text msg-type content reply-in-thread? uuid]}]
  (let [message-id (or (nonblank message-id)
                       (throw (ex-info "message-id is required"
                                       {:type :feishu-openapi-lite/missing-option
                                        :option :message-id})))
        msg-type (or (nonblank msg-type) "text")
        content (or content
                    (when (= "text" msg-type)
                      (text-content (or text ""))))
        token (or tenant-access-token
                  (throw (ex-info "tenant-access-token is required"
                                  {:type :feishu-openapi-lite/missing-option
                                   :option :tenant-access-token})))]
    (http/json-request
     {:method :post
      :url (http/api-url domain (str "/open-apis/im/v1/messages/" message-id "/reply"))
      :headers {"Authorization" (str "Bearer " token)}
      :body (cond-> {:msg_type msg-type
                     :content content}
              (some? reply-in-thread?) (assoc :reply_in_thread (boolean reply-in-thread?))
              (nonblank uuid) (assoc :uuid uuid))})))

(defn normalize-message-response
  [body]
  (let [code (:code body)]
    (if (zero? (long (or code 0)))
      {:ok? true
       :code code
       :message (:msg body)
       :data (sent-message->map (:data body))}
      (throw (ex-info "Feishu message API request failed"
                      {:type :feishu-openapi-lite/api-error
                       :code code
                       :message (:msg body)})))))

(defn create-message!
  [client opts]
  (let [token (or (:tenant-access-token client)
                  (auth/tenant-access-token! client))
        request (create-message-request (assoc client :tenant-access-token token) opts)]
    (-> (http/execute-json! client request)
        normalize-message-response)))

(defn send-text!
  [client opts]
  (create-message! client (assoc opts :msg-type "text")))

(defn reply-message!
  [client opts]
  (let [token (or (:tenant-access-token client)
                  (auth/tenant-access-token! client))
        request (reply-message-request (assoc client :tenant-access-token token) opts)]
    (-> (http/execute-json! client request)
        normalize-message-response)))

(defn reply-text!
  [client opts]
  (reply-message! client (assoc opts :msg-type "text")))
