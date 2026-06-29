(ns com.zihao.feishu-openapi-lite.im
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.zihao.feishu-openapi-lite.auth :as auth]
            [com.zihao.feishu-openapi-lite.http :as http]
            [com.zihao.feishu-openapi-lite.json :as json])
  (:import [java.net URLEncoder]))

(defn text-content
  [text]
  (json/write-str {:text (str text)}))

(defn file-content
  [file-key]
  (json/write-str {:file_key (str file-key)}))

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

(defn- path-segment
  [value]
  (str/replace (URLEncoder/encode (str value) "UTF-8") "+" "%20"))

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

(defn- file-upload->map
  [file]
  (when (map? file)
    (cond-> {}
      (kget file :file-key) (assoc :file-key (kget file :file-key))
      (kget file :file-name) (assoc :file-name (kget file :file-name))
      (kget file :file-type) (assoc :file-type (kget file :file-type)))))

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

(defn reply-file!
  [client {:keys [file-key content] :as opts}]
  (let [content (or content
                    (file-content
                     (or (nonblank file-key)
                         (throw (ex-info "file-key is required"
                                         {:type :feishu-openapi-lite/missing-option
                                          :option :file-key})))))]
    (reply-message! client
                    (assoc opts
                           :msg-type "file"
                           :content content))))

(defn message-resource-request
  [{:keys [domain tenant-access-token]} {:keys [message-id file-key type]}]
  (let [message-id (or (nonblank message-id)
                       (throw (ex-info "message-id is required"
                                       {:type :feishu-openapi-lite/missing-option
                                        :option :message-id})))
        file-key (or (nonblank file-key)
                     (throw (ex-info "file-key is required"
                                     {:type :feishu-openapi-lite/missing-option
                                      :option :file-key})))
        resource-type (or (nonblank type) "image")
        token (or tenant-access-token
                  (throw (ex-info "tenant-access-token is required"
                                  {:type :feishu-openapi-lite/missing-option
                                   :option :tenant-access-token})))]
    (http/binary-request
     {:method :get
      :url (http/api-url domain
                         (str "/open-apis/im/v1/messages/"
                              (path-segment message-id)
                              "/resources/"
                              (path-segment file-key)))
      :query-params {"type" resource-type}
      :headers {"Authorization" (str "Bearer " token)}})))

(defn download-message-resource!
  [client opts]
  (let [token (or (:tenant-access-token client)
                  (auth/tenant-access-token! client))
        request (message-resource-request (assoc client :tenant-access-token token) opts)]
    (http/execute-binary! client request)))

(defn upload-file-request
  [{:keys [domain tenant-access-token]} {:keys [file file-path file-name file-type content-type]}]
  (let [file (or file
                 (when-let [path (nonblank file-path)]
                   (io/file path))
                 (throw (ex-info "file or file-path is required"
                                 {:type :feishu-openapi-lite/missing-option
                                  :required-any-of [:file :file-path]})))
        file-name (or (nonblank file-name)
                      (.getName (io/file file)))
        file-type (or (nonblank file-type) "stream")
        token (or tenant-access-token
                  (throw (ex-info "tenant-access-token is required"
                                  {:type :feishu-openapi-lite/missing-option
                                   :option :tenant-access-token})))]
    (http/multipart-request
     {:method :post
      :url (http/api-url domain "/open-apis/im/v1/files")
      :headers {"Authorization" (str "Bearer " token)}
      :multipart [{:name "file_type"
                   :content file-type}
                  {:name "file_name"
                   :content file-name}
                  (cond-> {:name "file"
                           :content file
                           :file-name file-name}
                    (nonblank content-type)
                    (assoc :content-type content-type))]})))

(defn normalize-file-upload-response
  [body]
  (let [code (:code body)]
    (if (zero? (long (or code 0)))
      {:ok? true
       :code code
       :message (:msg body)
       :data (file-upload->map (:data body))}
      (throw (ex-info "Feishu file upload API request failed"
                      {:type :feishu-openapi-lite/api-error
                       :code code
                       :message (:msg body)})))))

(defn upload-file!
  [client opts]
  (let [token (or (:tenant-access-token client)
                  (auth/tenant-access-token! client))
        request (upload-file-request (assoc client :tenant-access-token token) opts)]
    (-> (http/execute-json! client request)
        normalize-file-upload-response)))
