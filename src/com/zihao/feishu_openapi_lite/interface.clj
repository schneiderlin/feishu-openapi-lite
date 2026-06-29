(ns com.zihao.feishu-openapi-lite.interface
  (:require [com.zihao.feishu-openapi-lite.auth :as auth]
            [com.zihao.feishu-openapi-lite.event-codec :as event-codec]
            [com.zihao.feishu-openapi-lite.http :as http]
            [com.zihao.feishu-openapi-lite.im :as im]
            [com.zihao.feishu-openapi-lite.ws :as ws]
            [com.zihao.feishu-openapi-lite.ws-frame :as ws-frame]))

(def default-domain http/default-domain)

(def internal-tenant-access-token-request auth/internal-tenant-access-token-request)
(def normalize-tenant-access-token-response auth/normalize-tenant-access-token-response)
(def fetch-tenant-access-token! auth/fetch-tenant-access-token!)
(def tenant-access-token! auth/tenant-access-token!)

(def text-content im/text-content)
(def file-content im/file-content)
(def create-message-request im/create-message-request)
(def create-message! im/create-message!)
(def send-text! im/send-text!)
(def reply-message-request im/reply-message-request)
(def normalize-message-response im/normalize-message-response)
(def reply-message! im/reply-message!)
(def reply-text! im/reply-text!)
(def reply-file! im/reply-file!)
(def message-resource-request im/message-resource-request)
(def download-message-resource! im/download-message-resource!)
(def upload-file-request im/upload-file-request)
(def normalize-file-upload-response im/normalize-file-upload-response)
(def upload-file! im/upload-file!)

(def parse-event-payload event-codec/parse-event-payload)
(def normalize-p2-event event-codec/normalize-p2-event)
(def message-received-event? event-codec/message-received-event?)

(def endpoint-request ws/endpoint-request)
(def normalize-endpoint-response ws/normalize-endpoint-response)
(def pull-endpoint! ws/pull-endpoint!)
(def start-event-ws! ws/start!)
(def close-event-ws! ws/close!)
(def event-ws-state ws/state)

(def encode-frame ws-frame/encode-frame)
(def decode-frame ws-frame/decode-frame)
(def ping-frame ws-frame/ping-frame)
(def event-response-frame ws-frame/event-response-frame)
