(ns com.zihao.feishu-openapi-lite.json
  (:require [cheshire.core :as json])
  (:import [java.nio.charset StandardCharsets]))

(defn write-str
  [x]
  (json/generate-string x))

(defn read-str
  [s]
  (json/parse-string s true))

(defn bytes->str
  [bytes]
  (String. ^bytes bytes StandardCharsets/UTF_8))

(defn str->bytes
  [s]
  (.getBytes (str s) StandardCharsets/UTF_8))

(defn read-bytes
  [bytes]
  (read-str (bytes->str bytes)))

(defn write-bytes
  [x]
  (str->bytes (write-str x)))
