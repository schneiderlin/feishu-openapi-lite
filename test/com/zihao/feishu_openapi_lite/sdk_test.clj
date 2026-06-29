(ns com.zihao.feishu-openapi-lite.sdk-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.zihao.feishu-openapi-lite.interface :as sut]
            [com.zihao.feishu-openapi-lite.json :as json]
            [com.zihao.feishu-openapi-lite.ws-frame :as frame]))

(defn- payload-text
  [decoded-frame]
  (some-> (:payload decoded-frame) json/bytes->str))

(deftest auth-request-and-response-shapes
  (testing "internal tenant token requests use Feishu's expected path and body"
    (let [request (sut/internal-tenant-access-token-request
                   {:domain "https://open.feishu.cn/"
                    :app-id "cli_123"
                    :app-secret "secret"})]
      (is (= :post (:method request)))
      (is (= "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
             (:uri request)))
      (is (= {:app_id "cli_123"
              :app_secret "secret"}
             (json/read-str (:body request))))))
  (testing "successful token responses keep expiry data explicit"
    (is (= {:tenant-access-token "t-1"
            :expire-seconds 7200
            :expires-at-ms 7201000}
           (sut/normalize-tenant-access-token-response
            {:code 0
             :tenant_access_token "t-1"
             :expire 7200}
            1000))))
  (testing "fetching tenant tokens sends the token request through the configured requester"
    (let [requests (atom [])
          result (sut/fetch-tenant-access-token!
                  {:app-id "cli_123"
                   :app-secret "secret"
                   :request! (fn [request]
                               (swap! requests conj request)
                               {:status 200
                                :body (json/write-str
                                       {:code 0
                                        :tenant_access_token "t-1"
                                        :expire 7200})})})]
      (is (= "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
             (:uri (first @requests))))
      (is (= "t-1" (:tenant-access-token result))))))

(deftest reply-text-request-shape
  (testing "chat-id text sends serialize Feishu's message.create payload"
    (let [request (sut/create-message-request
                   {:domain "https://open.feishu.cn"
                    :tenant-access-token "tenant-token"}
                   {:chat-id "oc_1"
                    :text "hello back"
                    :uuid "uuid-0"})
          body (json/read-str (:body request))]
      (is (= :post (:method request)))
      (is (= "https://open.feishu.cn/open-apis/im/v1/messages"
             (:uri request)))
      (is (= {"receive_id_type" "chat_id"}
             (:query-params request)))
      (is (= "Bearer tenant-token"
             (get-in request [:headers "Authorization"])))
      (is (= {:receive_id "oc_1"
              :msg_type "text"
              :content "{\"text\":\"hello back\"}"
              :uuid "uuid-0"}
             body))))
  (testing "text replies serialize the nested Feishu content payload"
    (let [request (sut/reply-message-request
                   {:domain "https://open.feishu.cn"
                    :tenant-access-token "tenant-token"}
                   {:message-id "om_1"
                    :text "hello"
                    :reply-in-thread? true
                    :uuid "uuid-1"})
          body (json/read-str (:body request))]
      (is (= :post (:method request)))
      (is (= "https://open.feishu.cn/open-apis/im/v1/messages/om_1/reply"
             (:uri request)))
      (is (= "Bearer tenant-token"
             (get-in request [:headers "Authorization"])))
      (is (= {:msg_type "text"
              :content "{\"text\":\"hello\"}"
              :reply_in_thread true
              :uuid "uuid-1"}
             body))))
  (testing "message API responses expose kebab-case message data"
    (is (= {:ok? true
            :code 0
            :message "success"
            :data {:message-id "om_sent"
                   :thread-id "omt_1"
                   :message-type "text"
                   :chat-id "oc_1"
                   :message-app-link "https://example.invalid/message"}}
           (sut/normalize-message-response
            {:code 0
             :msg "success"
             :data {:message_id "om_sent"
                    :thread_id "omt_1"
                    :msg_type "text"
                    :chat_id "oc_1"
                    :message_app_link "https://example.invalid/message"}})))))

(deftest file-upload-and-reply-request-shape
  (testing "file uploads use Feishu's multipart file endpoint"
    (let [file (java.io.File. "/tmp/report.pdf")
          request (sut/upload-file-request
                   {:domain "https://open.feishu.cn"
                    :tenant-access-token "tenant-token"}
                   {:file file
                    :file-name "report.pdf"
                    :file-type "pdf"})
          parts (:multipart request)]
      (is (= :post (:method request)))
      (is (= "https://open.feishu.cn/open-apis/im/v1/files"
             (:uri request)))
      (is (= "Bearer tenant-token"
             (get-in request [:headers "Authorization"])))
      (is (= :string (:as request)))
      (is (= [{:name "file_type" :content "pdf"}
              {:name "file_name" :content "report.pdf"}
              {:name "file"
               :content file
               :file-name "report.pdf"}]
             parts))))
  (testing "file replies serialize file_key as Feishu message content"
    (let [request (sut/reply-message-request
                   {:domain "https://open.feishu.cn"
                    :tenant-access-token "tenant-token"}
                   {:message-id "om_1"
                    :msg-type "file"
                    :content (sut/file-content "file_v2_1")
                    :reply-in-thread? true
                    :uuid "uuid-file"})
          body (json/read-str (:body request))]
      (is (= {:msg_type "file"
              :content "{\"file_key\":\"file_v2_1\"}"
              :reply_in_thread true
              :uuid "uuid-file"}
             body))))
  (testing "successful file upload responses expose the file key"
    (is (= {:ok? true
            :code 0
            :message "success"
            :data {:file-key "file_v2_1"}}
           (sut/normalize-file-upload-response
            {:code 0
             :msg "success"
             :data {:file_key "file_v2_1"}})))))

(deftest message-resource-request-and-download-shape
  (testing "message resource downloads use message id, file key, and resource type"
    (let [request (sut/message-resource-request
                   {:domain "https://open.feishu.cn"
                    :tenant-access-token "tenant-token"}
                   {:message-id "om_1"
                    :file-key "img_v3_1"
                    :type "image"})]
      (is (= :get (:method request)))
      (is (= "https://open.feishu.cn/open-apis/im/v1/messages/om_1/resources/img_v3_1"
             (:uri request)))
      (is (= {"type" "image"}
             (:query-params request)))
      (is (= :bytes (:as request)))
      (is (= "Bearer tenant-token"
             (get-in request [:headers "Authorization"])))))
  (testing "download-message-resource! returns binary bytes and content type"
    (let [seen-requests (atom [])
          result (sut/download-message-resource!
                  {:tenant-access-token "tenant-token"
                   :request! (fn [request]
                               (swap! seen-requests conj request)
                               {:status 200
                                :headers {"content-type" "image/jpeg"}
                                :body (byte-array [1 2 3])})}
                  {:message-id "om_1"
                   :file-key "img_v3_1"
                   :type "image"})]
      (is (= 1 (count @seen-requests)))
      (is (= "image/jpeg" (:content-type result)))
      (is (= [1 2 3] (vec (:bytes result)))))))

(deftest p2-message-event-normalization
  (testing "raw p2 message events become small stable message maps"
    (let [event {:schema "2.0"
                 :header {:event_id "ev_1"
                          :event_type "im.message.receive_v1"
                          :app_id "cli_1"
                          :tenant_key "tenant_1"
                          :create_time "1780000000000"}
                 :event {:sender {:sender_id {:user_id "u_sender"
                                               :open_id "ou_sender"
                                               :union_id "on_sender"}
                                  :sender_type "user"
                                  :tenant_key "tenant_1"}
                         :message {:message_id "om_1"
                                   :root_id ""
                                   :parent_id ""
                                   :create_time "1780000000001"
                                   :update_time "1780000000002"
                                   :chat_id "oc_1"
                                   :thread_id "omt_1"
                                   :chat_type "group"
                                   :message_type "text"
                                   :content "{\"text\":\"ls again\"}"
                                   :mentions []}}}
          normalized (sut/normalize-p2-event event)]
      (is (true? (sut/message-received-event? event)))
      (is (= "im.message.receive_v1" (:event-type normalized)))
      (is (= {:id {:user-id "u_sender"
                   :open-id "ou_sender"
                   :union-id "on_sender"}
              :type "user"
              :tenant-key "tenant_1"}
             (:sender normalized)))
      (is (= {:message-id "om_1"
              :root-id ""
              :parent-id ""
              :create-time "1780000000001"
              :update-time "1780000000002"
              :chat-id "oc_1"
              :thread-id "omt_1"
              :chat-type "group"
              :message-type "text"
              :content {:text "ls again"}
              :content-raw "{\"text\":\"ls again\"}"
              :mentions []}
             (:message normalized))))))

(deftest websocket-frame-roundtrip
  (testing "the minimal pbbp2 frame codec round-trips data frames"
    (let [source {:seq-id 1
                  :log-id 2
                  :service 7
                  :method frame/frame-type-data
                  :headers [{:key frame/header-type
                             :value frame/message-type-event}
                            {:key frame/header-message-id
                             :value "msg-1"}]
                  :payload (json/str->bytes "{\"ok\":true}")}
          decoded (sut/decode-frame (sut/encode-frame source))]
      (is (= (select-keys source [:seq-id :log-id :service :method :headers])
             (select-keys decoded [:seq-id :log-id :service :method :headers])))
      (is (= "{\"ok\":true}" (payload-text decoded)))))
  (testing "event response frames preserve headers and return a JSON response payload"
    (let [event-frame {:seq-id 1
                       :log-id 2
                       :service 7
                       :method frame/frame-type-data
                       :headers [{:key frame/header-type
                                  :value frame/message-type-event}]
                       :payload (json/str->bytes "{}")}
          response (sut/event-response-frame event-frame
                                             {:code 200
                                              :data {:challenge "ok"}
                                              :biz-rt-ms 12})
          decoded (sut/decode-frame (sut/encode-frame response))
          payload (json/read-str (payload-text decoded))]
      (is (= [{:key frame/header-type
               :value frame/message-type-event}
              {:key frame/header-biz-rt
               :value "12"}]
             (:headers decoded)))
      (is (= 200 (:code payload)))
      (is (string? (:data payload))))))

(deftest websocket-endpoint-response-shape
  (testing "endpoint responses expose the websocket URL and millisecond config"
    (is (= {:url "wss://example.invalid/ws?device_id=d1&service_id=9"
            :client-config {:ping-interval-ms 10000
                            :reconnect-count 3
                            :reconnect-interval-ms 4000
                            :reconnect-nonce-ms 5000}}
           (sut/normalize-endpoint-response
            {:code 0
	             :data {:URL "wss://example.invalid/ws?device_id=d1&service_id=9"
	                    :ClientConfig {:PingInterval 10
	                                   :ReconnectCount 3
	                                   :ReconnectInterval 4
	                                   :ReconnectNonce 5}}}))))
  (testing "pull-endpoint! sends the endpoint request through the configured requester"
    (let [requests (atom [])
          result (sut/pull-endpoint!
                  {:app-id "cli_123"
                   :app-secret "secret"
                   :request! (fn [request]
                               (swap! requests conj request)
                               {:status 200
                                :body (json/write-str
                                       {:code 0
                                        :data {:URL "wss://example.invalid/ws?device_id=d1&service_id=9"
                                               :ClientConfig {:PingInterval 10}}})})})]
      (is (= "https://open.feishu.cn/callback/ws/endpoint"
             (:uri (first @requests))))
      (is (= "wss://example.invalid/ws?device_id=d1&service_id=9"
             (:url result))))))
