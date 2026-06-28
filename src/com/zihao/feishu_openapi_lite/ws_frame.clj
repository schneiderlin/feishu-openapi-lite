(ns com.zihao.feishu-openapi-lite.ws-frame
  (:require [com.zihao.feishu-openapi-lite.json :as json])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def frame-type-control 0)
(def frame-type-data 1)
(def message-type-event "event")
(def message-type-card "card")
(def message-type-ping "ping")
(def message-type-pong "pong")
(def header-type "type")
(def header-message-id "message_id")
(def header-sum "sum")
(def header-seq "seq")
(def header-trace-id "trace_id")
(def header-biz-rt "biz_rt")

(defn- as-bytes
  [x]
  (cond
    (nil? x) (byte-array 0)
    (bytes? x) x
    (string? x) (.getBytes ^String x StandardCharsets/UTF_8)
    :else (byte-array x)))

(defn- write-byte!
  [^ByteArrayOutputStream out n]
  (.write out (bit-and n 0xff)))

(defn- write-varint!
  [^ByteArrayOutputStream out value]
  (loop [n (long (or value 0))]
    (if (< n 128)
      (write-byte! out n)
      (do
        (write-byte! out (bit-or 0x80 (bit-and n 0x7f)))
        (recur (unsigned-bit-shift-right n 7))))))

(defn- write-bytes!
  [^ByteArrayOutputStream out bs]
  (let [bs (as-bytes bs)]
    (write-varint! out (alength ^bytes bs))
    (.write out ^bytes bs 0 (alength ^bytes bs))))

(defn- write-string!
  [^ByteArrayOutputStream out s]
  (write-bytes! out (.getBytes (str s) StandardCharsets/UTF_8)))

(defn- encode-field-varint!
  [out field-number value]
  (write-varint! out (bit-or (bit-shift-left field-number 3) 0))
  (write-varint! out value))

(defn- encode-field-bytes!
  [out field-number value]
  (write-varint! out (bit-or (bit-shift-left field-number 3) 2))
  (write-bytes! out value))

(defn- encode-field-string!
  [out field-number value]
  (write-varint! out (bit-or (bit-shift-left field-number 3) 2))
  (write-string! out value))

(defn encode-header
  [{:keys [key value]}]
  (let [out (ByteArrayOutputStream.)]
    (encode-field-string! out 1 (or key ""))
    (encode-field-string! out 2 (or value ""))
    (.toByteArray out)))

(defn encode-frame
  [{:keys [seq-id log-id service method headers payload-encoding payload-type payload log-id-new]}]
  (let [out (ByteArrayOutputStream.)]
    (encode-field-varint! out 1 (or seq-id 0))
    (encode-field-varint! out 2 (or log-id 0))
    (encode-field-varint! out 3 (or service 0))
    (encode-field-varint! out 4 (or method 0))
    (doseq [header headers]
      (encode-field-bytes! out 5 (encode-header header)))
    (when (some? payload-encoding)
      (encode-field-string! out 6 payload-encoding))
    (when (some? payload-type)
      (encode-field-string! out 7 payload-type))
    (when (some? payload)
      (encode-field-bytes! out 8 payload))
    (when (some? log-id-new)
      (encode-field-string! out 9 log-id-new))
    (.toByteArray out)))

(defn- read-varint
  [^bytes bs pos]
  (loop [pos pos
         shift 0
         acc 0]
    (let [b (bit-and (aget bs pos) 0xff)
          acc (bit-or acc (bit-shift-left (bit-and b 0x7f) shift))
          pos (inc pos)]
      (if (zero? (bit-and b 0x80))
        [acc pos]
        (recur pos (+ shift 7) acc)))))

(defn- read-len-bytes
  [^bytes bs pos]
  (let [[len pos] (read-varint bs pos)
        end (+ pos len)
        out (byte-array len)]
    (System/arraycopy bs pos out 0 len)
    [out end]))

(defn- bytes->string
  [bs]
  (String. ^bytes bs StandardCharsets/UTF_8))

(defn- skip-field
  [^bytes bs pos wire-type]
  (case wire-type
    0 (second (read-varint bs pos))
    2 (let [[len pos] (read-varint bs pos)]
        (+ pos len))
    (throw (ex-info "Unsupported protobuf wire type"
                    {:type :feishu-openapi-lite/unsupported-wire-type
                     :wire-type wire-type}))))

(defn decode-header
  [bs]
  (let [bs (as-bytes bs)
        end (alength ^bytes bs)]
    (loop [pos 0
           result {}]
      (if (>= pos end)
        result
        (let [[tag pos] (read-varint bs pos)
              field (unsigned-bit-shift-right tag 3)
              wire (bit-and tag 7)]
          (case field
            1 (let [[v pos] (read-len-bytes bs pos)]
                (recur pos (assoc result :key (bytes->string v))))
            2 (let [[v pos] (read-len-bytes bs pos)]
                (recur pos (assoc result :value (bytes->string v))))
            (recur (skip-field bs pos wire) result)))))))

(defn decode-frame
  [bs]
  (let [bs (as-bytes bs)
        end (alength ^bytes bs)]
    (loop [pos 0
           result {:headers []}]
      (if (>= pos end)
        result
        (let [[tag pos] (read-varint bs pos)
              field (unsigned-bit-shift-right tag 3)
              wire (bit-and tag 7)]
          (case field
            1 (let [[v pos] (read-varint bs pos)]
                (recur pos (assoc result :seq-id v)))
            2 (let [[v pos] (read-varint bs pos)]
                (recur pos (assoc result :log-id v)))
            3 (let [[v pos] (read-varint bs pos)]
                (recur pos (assoc result :service v)))
            4 (let [[v pos] (read-varint bs pos)]
                (recur pos (assoc result :method v)))
            5 (let [[v pos] (read-len-bytes bs pos)]
                (recur pos (update result :headers conj (decode-header v))))
            6 (let [[v pos] (read-len-bytes bs pos)]
                (recur pos (assoc result :payload-encoding (bytes->string v))))
            7 (let [[v pos] (read-len-bytes bs pos)]
                (recur pos (assoc result :payload-type (bytes->string v))))
            8 (let [[v pos] (read-len-bytes bs pos)]
                (recur pos (assoc result :payload v)))
            9 (let [[v pos] (read-len-bytes bs pos)]
                (recur pos (assoc result :log-id-new (bytes->string v))))
            (recur (skip-field bs pos wire) result)))))))

(defn headers->map
  [headers]
  (into {} (map (juxt :key :value)) headers))

(defn ping-frame
  [service-id]
  {:seq-id 0
   :log-id 0
   :service (long service-id)
   :method frame-type-control
   :headers [{:key header-type
              :value message-type-ping}]})

(defn response-payload
  [{:keys [code data]}]
  (json/write-bytes
   (cond-> {:code (or code 200)}
     (some? data)
     (assoc :data (.encodeToString (Base64/getEncoder)
                                   (json/write-bytes data))))))

(defn event-response-frame
  [event-frame {:keys [code data biz-rt-ms]}]
  (-> event-frame
      (update :headers conj {:key header-biz-rt
                             :value (str (or biz-rt-ms 0))})
      (assoc :payload (response-payload {:code (or code 200)
                                         :data data}))))
