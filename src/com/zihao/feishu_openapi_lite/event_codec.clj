(ns com.zihao.feishu-openapi-lite.event-codec
  (:require [clojure.string :as str]
            [com.zihao.feishu-openapi-lite.json :as json]))

(defn- kget
  [m k]
  (or (get m k)
      (get m (keyword (str/replace (name k) "-" "_")))
      (get m (name k))
      (get m (str/replace (name k) "-" "_"))))

(defn- parse-json-content
  [content]
  (when-not (str/blank? (str (or content "")))
    (try
      (json/read-str content)
      (catch Exception _
        nil))))

(defn event-type
  [event]
  (or (kget (kget event :header) :event-type)
      (kget event :type)))

(defn schema
  [event]
  (kget event :schema))

(defn p2-event?
  [event]
  (boolean (kget event :header)))

(defn message-received-event?
  [event]
  (= "im.message.receive_v1" (event-type event)))

(defn user-id->map
  [m]
  (when (map? m)
    (cond-> {}
      (kget m :user-id) (assoc :user-id (kget m :user-id))
      (kget m :open-id) (assoc :open-id (kget m :open-id))
      (kget m :union-id) (assoc :union-id (kget m :union-id)))))

(defn mention->map
  [m]
  (when (map? m)
    (cond-> {:id (user-id->map (kget m :id))}
      (kget m :key) (assoc :key (kget m :key))
      (kget m :mentioned-type) (assoc :mentioned-type (kget m :mentioned-type))
      (kget m :name) (assoc :name (kget m :name))
      (kget m :tenant-key) (assoc :tenant-key (kget m :tenant-key)))))

(defn sender->map
  [sender]
  (when (map? sender)
    (cond-> {:id (user-id->map (kget sender :sender-id))}
      (kget sender :sender-type) (assoc :type (kget sender :sender-type))
      (kget sender :tenant-key) (assoc :tenant-key (kget sender :tenant-key)))))

(defn message->map
  [message]
  (when (map? message)
    (let [content-raw (kget message :content)
          content (parse-json-content content-raw)]
      (cond-> {:message-id (kget message :message-id)
               :root-id (kget message :root-id)
               :parent-id (kget message :parent-id)
               :create-time (kget message :create-time)
               :update-time (kget message :update-time)
               :chat-id (kget message :chat-id)
               :thread-id (kget message :thread-id)
               :chat-type (kget message :chat-type)
               :message-type (kget message :message-type)
               :content content
               :content-raw content-raw
               :mentions (mapv mention->map (or (kget message :mentions) []))}
        (nil? content) (assoc :content-parse-failed? (some? content-raw))))))

(defn header->map
  [header]
  (when (map? header)
    (cond-> {}
      (kget header :event-id) (assoc :event-id (kget header :event-id))
      (kget header :event-type) (assoc :event-type (kget header :event-type))
      (kget header :app-id) (assoc :app-id (kget header :app-id))
      (kget header :tenant-key) (assoc :tenant-key (kget header :tenant-key))
      (kget header :create-time) (assoc :create-time (kget header :create-time)))))

(defn normalize-p2-event
  [event]
  (let [event-data (kget event :event)
        sender (sender->map (kget event-data :sender))
        message (message->map (kget event-data :message))
        header (header->map (kget event :header))]
    (merge {:type :feishu/event
            :schema (schema event)
            :event-type (event-type event)
            :header header
            :sender sender
            :event event-data
            :raw-event event}
           (when message
             {:message message})
           (select-keys message [:message-id
                                 :chat-id
                                 :chat-type
                                 :thread-id
                                 :message-type
                                 :content
                                 :content-raw
                                 :create-time
                                 :update-time]))))

(defn parse-event-payload
  [payload]
  (cond
    (map? payload) payload
    (string? payload) (json/read-str payload)
    (bytes? payload) (json/read-bytes payload)
    :else (throw (ex-info "Unsupported event payload"
                          {:type :feishu-openapi-lite/unsupported-event-payload
                           :payload-class (some-> payload class str)}))))
