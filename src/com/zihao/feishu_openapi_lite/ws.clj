(ns com.zihao.feishu-openapi-lite.ws
  (:require [clojure.string :as str]
            [com.zihao.feishu-openapi-lite.event-codec :as event-codec]
            [com.zihao.feishu-openapi-lite.http :as http]
            [com.zihao.feishu-openapi-lite.json :as json]
            [com.zihao.feishu-openapi-lite.ws-frame :as frame])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.nio ByteBuffer]
           [java.util.concurrent CompletableFuture]))

(def endpoint-path "/callback/ws/endpoint")

(defn endpoint-request
  [{:keys [domain app-id app-secret user-agent]}]
  (http/json-request
   {:method :post
    :url (http/api-url domain endpoint-path)
    :headers {"locale" "zh"
              "User-Agent" (or user-agent http/default-user-agent)}
    :body {"AppID" app-id
           "AppSecret" app-secret}}))

(defn normalize-client-config
  [m]
  (when (map? m)
    {:ping-interval-ms (* 1000 (long (or (:PingInterval m) 120)))
     :reconnect-count (long (or (:ReconnectCount m) -1))
     :reconnect-interval-ms (* 1000 (long (or (:ReconnectInterval m) 120)))
     :reconnect-nonce-ms (* 1000 (long (or (:ReconnectNonce m) 30)))}))

(defn normalize-endpoint-response
  [body]
  (let [code (:code body)]
    (if (zero? (long (or code 0)))
      (let [data (:data body)]
        {:url (:URL data)
         :client-config (normalize-client-config (:ClientConfig data))})
      (throw (ex-info "Feishu WebSocket endpoint request failed"
                      {:type :feishu-openapi-lite/api-error
                       :code code
                       :message (:msg body)})))))

(defn pull-endpoint!
  [client]
  (-> (http/execute-json! client (endpoint-request client))
      normalize-endpoint-response))

(defn- parse-query
  [url]
  (let [query (.getQuery (URI/create url))]
    (into {}
          (keep (fn [pair]
                  (let [[k v] (str/split pair #"=" 2)]
                    (when k [k v]))))
          (str/split (or query "") #"&"))))

(defn endpoint-service-id
  [url]
  (some-> (parse-query url) (get "service_id") Long/parseLong))

(defn- byte-buffer->bytes
  [^ByteBuffer buffer]
  (let [dup (.slice buffer)
        out (byte-array (.remaining dup))]
    (.get dup out)
    out))

(defn- concat-bytes
  [chunks]
  (let [total (reduce + (map alength chunks))
        out (byte-array total)]
    (loop [offset 0
           chunks (seq chunks)]
      (if-let [chunk (first chunks)]
        (do
          (System/arraycopy chunk 0 out offset (alength ^bytes chunk))
          (recur (+ offset (alength ^bytes chunk)) (next chunks)))
        out))))

(defn merge-fragment!
  [cache* {:keys [message-id sum seq trace-id payload]}]
  (let [sum (long (or sum 1))
        seq (long (or seq 0))]
    (if (<= sum 1)
      payload
      (let [entry (swap! cache*
                         (fn [cache]
                           (let [current (or (get cache message-id)
                                             {:trace-id trace-id
                                              :created-at-ms (System/currentTimeMillis)
                                              :parts {}})]
                             (assoc cache message-id
                                    (assoc-in current [:parts seq] payload)))))
            parts (get-in entry [message-id :parts])]
        (when (= sum (count parts))
          (swap! cache* dissoc message-id)
          (concat-bytes (map parts (range sum))))))))

(defn event-payload-from-frame!
  [cache* ws-frame]
  (let [headers (frame/headers->map (:headers ws-frame))
        message-type (get headers frame/header-type)]
    (when (= frame/message-type-event message-type)
      (merge-fragment!
       cache*
       {:message-id (get headers frame/header-message-id)
        :sum (some-> (get headers frame/header-sum) Long/parseLong)
        :seq (some-> (get headers frame/header-seq) Long/parseLong)
        :trace-id (get headers frame/header-trace-id)
        :payload (:payload ws-frame)}))))

(defn- send-frame!
  [^WebSocket websocket ws-frame]
  (.sendBinary websocket (ByteBuffer/wrap (frame/encode-frame ws-frame)) true))

(defn- completed
  []
  (CompletableFuture/completedFuture nil))

(defrecord EventWsClient [websocket state stop!* ping-future])

(defn close!
  [^EventWsClient client]
  (reset! (:stop!* client) true)
  (when-let [ws (:websocket client)]
    (.sendClose ^WebSocket ws WebSocket/NORMAL_CLOSURE "closed"))
  client)

(defn start!
  [{:keys [on-event on-error] :as client}]
  (let [{:keys [url client-config]} (pull-endpoint! client)
        service-id (or (endpoint-service-id url) 0)
        ws-config (merge {:ping-interval-ms 120000} client-config)
        state* (atom {:connection :connecting
                      :url url
                      :client-config ws-config})
        stop!* (atom false)
        fragment-cache* (atom {})
        partial* (atom [])
        websocket* (atom nil)
        listener (reify WebSocket$Listener
                   (onOpen [_ websocket]
                     (reset! websocket* websocket)
                     (swap! state* assoc :connection :connected)
                     (.request websocket 1))
                   (onBinary [_ websocket data last?]
                     (let [chunk (byte-buffer->bytes data)
                           chunks (swap! partial* conj chunk)]
                       (when last?
                         (reset! partial* [])
                         (let [raw (concat-bytes chunks)]
                           (try
                             (let [ws-frame (frame/decode-frame raw)]
                               (case (:method ws-frame)
                                 0 (let [headers (frame/headers->map (:headers ws-frame))
                                         payload (:payload ws-frame)]
                                     (when (and (= frame/message-type-pong (get headers frame/header-type))
                                                (seq payload))
                                       (swap! state* assoc :client-config
                                              (merge (:client-config @state*)
                                                     (normalize-client-config
                                                      (json/read-bytes payload))))))
                                 1 (when-let [payload (event-payload-from-frame! fragment-cache* ws-frame)]
                                     (let [start-ms (System/currentTimeMillis)
                                           result (try
                                                    (when on-event
                                                      (on-event (event-codec/parse-event-payload payload)))
                                                    (catch Throwable t
                                                      (when on-error (on-error t))
                                                      ::failed))
                                           code (if (= ::failed result) 500 200)]
                                       (send-frame! websocket
                                                    (frame/event-response-frame
                                                     ws-frame
                                                     {:code code
                                                      :data (when-not (= ::failed result) result)
                                                      :biz-rt-ms (- (System/currentTimeMillis) start-ms)}))))
                                 nil))
                             (catch Throwable t
                               (when on-error (on-error t)))))))
                     (.request websocket 1)
                     (completed))
                   (onError [_ _websocket error]
                     (swap! state* assoc :connection :error :error error)
                     (when on-error (on-error error)))
                   (onClose [_ _websocket status reason]
                     (swap! state* assoc :connection :closed
                            :close-status status
                            :close-reason reason)
                     (completed)))
        websocket (-> (HttpClient/newHttpClient)
                      (.newWebSocketBuilder)
                      (.header "User-Agent" (or (:user-agent client) http/default-user-agent))
                      (.buildAsync (URI/create url) listener)
                      (.join))
        ping-future (future
                      (while (not @stop!*)
                        (Thread/sleep (long (or (:ping-interval-ms (:client-config @state*))
                                                120000)))
                        (when (and (not @stop!*) @websocket*)
                          (send-frame! @websocket*
                                       (frame/ping-frame service-id)))))]
    (->EventWsClient websocket state* stop!* ping-future)))

(defn state
  [^EventWsClient client]
  @(:state client))
