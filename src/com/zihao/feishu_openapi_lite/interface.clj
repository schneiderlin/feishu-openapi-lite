(ns com.zihao.feishu-openapi-lite.interface
  "Public API for the dependency-light Feishu (Lark) OpenAPI client.

  Covers tenant access tokens, IM message send/reply/resource download/upload, the
  event payload codec, and the Feishu WebSocket event client with its protobuf-style
  frame codec."
  (:require [com.zihao.feishu-openapi-lite.auth :as auth]
            [com.zihao.feishu-openapi-lite.event-codec :as event-codec]
            [com.zihao.feishu-openapi-lite.http :as http]
            [com.zihao.feishu-openapi-lite.im :as im]
            [com.zihao.feishu-openapi-lite.ws :as ws]
            [com.zihao.feishu-openapi-lite.ws-frame :as ws-frame]))

(def default-domain
  "Feishu OpenAPI origin used when a client map carries no `:domain`."
  http/default-domain)

(defn internal-tenant-access-token-request
  "Builds the HTTP request that exchanges app credentials for a tenant access token.

  Parameters:
  - `client` — client map holding the app credentials:
    - `:domain` — optional Feishu origin; defaults to `default-domain`.
    - `:app-id` — Feishu app id sent as `app_id`.
    - `:app-secret` — Feishu app secret sent as `app_secret`.

  Returns: request map for `http/request!` (`:method` `:uri` `:headers` `:body`
  `:as` `:throw` `:timeout`); nothing is sent."
  [client]
  (auth/internal-tenant-access-token-request client))

(defn normalize-tenant-access-token-response
  "Normalizes a tenant access token response body into token state.

  Parameters:
  - `body` — decoded Feishu token response body with `:code`, `:msg`, `:expire`, and
    `:tenant_access_token`.
  - `now-ms` — optional epoch milliseconds the expiry is computed from; defaults to
    the current time in the 1-arity form.

  Returns: token state map with `:tenant-access-token`, `:expire-seconds`, and
  `:expires-at-ms`.

  Throws `ex-info` (`:feishu-openapi-lite/api-error`) carrying `:code` and `:message`
  when the body's `:code` is not zero."
  ([body]
   (auth/normalize-tenant-access-token-response body))
  ([body now-ms]
   (auth/normalize-tenant-access-token-response body now-ms)))

(defn fetch-tenant-access-token!
  "Fetches a fresh tenant access token from Feishu, ignoring any cached token.

  Parameters:
  - `client` — client map with the app credentials and optional `:domain`,
    `:request!`, and `:timeout-ms`.

  Returns: token state map (`:tenant-access-token`, `:expire-seconds`,
  `:expires-at-ms`).

  Throws `ex-info` when the HTTP request fails
  (`:feishu-openapi-lite/http-error`) or Feishu answers a non-zero `:code`
  (`:feishu-openapi-lite/api-error`)."
  [client]
  (auth/fetch-tenant-access-token! client))

(defn tenant-access-token!
  "Returns a usable tenant access token, refreshing the client's cached token first
  when it is missing or inside the refresh skew.

  Parameters:
  - `client` — client map with the app credentials:
    - `:token*` — optional atom caching token state; a fresh atom is created when
      absent (and then not shared with later calls).
    - `:tenant-access-token` — optional pre-fetched token already in the client.
    - `:domain`, `:request!`, `:timeout-ms` — optional transport settings.

  Returns: the tenant access token string, freshly fetched and cached in `:token*` when
  the cached token was stale.

  Throws like `fetch-tenant-access-token!` when a refresh is needed and fails."
  [client]
  (auth/tenant-access-token! client))

(defn text-content
  "Builds the Feishu message content JSON for a text message.

  Parameters:
  - `text` — message text; stringified, so `nil` becomes `\"\"`.

  Returns: JSON content string `{\"text\": \"...\"}` ready for `:content`."
  [text]
  (im/text-content text))

(defn file-content
  "Builds the Feishu message content JSON for a file message.

  Parameters:
  - `file-key` — file key returned by `upload-file!`.

  Returns: JSON content string `{\"file_key\": \"...\"}` ready for `:content`."
  [file-key]
  (im/file-content file-key))

(defn create-message-request
  "Builds the HTTP request that posts a new message to a chat.

  Parameters:
  - `client` — client map:
    - `:domain` — optional Feishu origin; defaults to `default-domain`.
    - `:tenant-access-token` — required; token sent as a bearer credential.
  - `opts` — message options map:
    - `:chat-id` — chat id used as the receiver when `:receive-id` is absent.
    - `:receive-id` — optional explicit receiver id.
    - `:receive-id-type` — optional receiver id type; defaults to `\"chat_id\"`.
    - `:text` — text used to build `:content` for the `text` message type.
    - `:msg-type` — optional Feishu message type; defaults to `\"text\"`.
    - `:content` — optional pre-built content JSON overriding `:text`.
    - `:uuid` — optional deduplication id.

  Returns: request map for `http/request!`; nothing is sent.

  Throws `ex-info` (`:feishu-openapi-lite/missing-option`) when no receiver
  (`:receive-id`/`:chat-id`) or no `:tenant-access-token` is available."
  [client opts]
  (im/create-message-request client opts))

(defn create-message!
  "Posts a new message to a chat, fetching a tenant access token when the client has
  none.

  Parameters:
  - `client` — client map with the app credentials (`:app-id`, `:app-secret`) and
    optional `:domain`, `:tenant-access-token`, `:request!`, `:timeout-ms`.
  - `opts` — message options map:
    - `:chat-id` — chat id used as the receiver when `:receive-id` is absent.
    - `:receive-id` — optional explicit receiver id.
    - `:receive-id-type` — optional receiver id type; defaults to `\"chat_id\"`.
    - `:text` / `:msg-type` / `:content` — message body, as in `create-message-request`.
    - `:uuid` — optional deduplication id.

  Returns: normalized response map `{:ok? true :code :message :data}` with the sent
  message metadata (`:message-id`, `:root-id`, `:parent-id`, `:thread-id`,
  `:message-type`, `:create-time`, `:update-time`, `:chat-id`, `:message-app-link`, …)
  in `:data`.

  Throws `ex-info` for missing options, HTTP failures, or a non-zero Feishu `:code`."
  [client opts]
  (im/create-message! client opts))

(defn send-text!
  "Posts a text message to a chat.

  Parameters:
  - `client` — client map with the app credentials and optional transport settings.
  - `opts` — message options map as in `create-message!`; `:msg-type` is forced to
    `\"text\"`, so `:text` (or an explicit `:content`) carries the body.

  Returns: normalized response map `{:ok? true :code :message :data}` with the sent
  message metadata in `:data`.

  Throws `ex-info` for missing receiver/token, HTTP failures, or a non-zero Feishu
  `:code`."
  [client opts]
  (im/send-text! client opts))

(defn reply-message-request
  "Builds the HTTP request that replies to an existing message.

  Parameters:
  - `client` — client map:
    - `:domain` — optional Feishu origin; defaults to `default-domain`.
    - `:tenant-access-token` — required; token sent as a bearer credential.
  - `opts` — reply options map:
    - `:message-id` — required; message the reply hangs off.
    - `:text` — text used to build `:content` for the `text` message type.
    - `:msg-type` — optional Feishu message type; defaults to `\"text\"`.
    - `:content` — optional pre-built content JSON overriding `:text`.
    - `:reply-in-thread?` — optional boolean sent as `reply_in_thread`; omitted when
      absent.
    - `:uuid` — optional deduplication id.

  Returns: request map for `http/request!`; nothing is sent.

  Throws `ex-info` (`:feishu-openapi-lite/missing-option`) when `:message-id` or
  `:tenant-access-token` is missing."
  [client opts]
  (im/reply-message-request client opts))

(defn normalize-message-response
  "Normalizes a Feishu message API response body.

  Parameters:
  - `body` — decoded response body with `:code`, `:msg`, and `:data`.

  Returns: response map `{:ok? true :code :message :data}`, with `:data` holding the
  sent message metadata keys (`:message-id`, `:root-id`, `:parent-id`, `:thread-id`,
  `:message-type`, `:create-time`, `:update-time`, `:deleted?`, `:updated?`,
  `:chat-id`, `:upper-message-id`, `:message-app-link`).

  Throws `ex-info` (`:feishu-openapi-lite/api-error`) with `:code` and `:message` when
  the body's `:code` is not zero."
  [body]
  (im/normalize-message-response body))

(defn reply-message!
  "Replies to an existing message, fetching a tenant access token when needed.

  Parameters:
  - `client` — client map with app credentials and optional transport settings.
  - `opts` — reply options map as in `reply-message-request`.

  Returns: normalized response map `{:ok? true :code :message :data}` with the reply
  message metadata in `:data`.

  Throws `ex-info` for a missing `:message-id`/token, HTTP failures, or a non-zero
  Feishu `:code`."
  [client opts]
  (im/reply-message! client opts))

(defn reply-text!
  "Replies to an existing message with text.

  Parameters:
  - `client` — client map with app credentials and optional transport settings.
  - `opts` — reply options map as in `reply-message-request`; `:msg-type` is forced to
    `\"text\"`, so `:text` (or an explicit `:content`) carries the body.

  Returns: normalized response map `{:ok? true :code :message :data}` with the reply
  message metadata in `:data`.

  Throws `ex-info` for a missing `:message-id`/token, HTTP failures, or a non-zero
  Feishu `:code`."
  [client opts]
  (im/reply-text! client opts))

(defn reply-file!
  "Replies to an existing message with a file or image.

  Parameters:
  - `client` — client map with app credentials and optional transport settings.
  - `opts` — reply options map:
    - `:message-id` — required; message the reply hangs off.
    - `:file-key` — required unless `:content` is given; file to attach.
    - `:content` — optional pre-built file content JSON overriding `:file-key`.
    - `:reply-in-thread?` — optional boolean; whether the reply stays in the thread.
    - `:uuid` — optional deduplication id.

  Returns: normalized response map `{:ok? true :code :message :data}` with the reply
  message metadata in `:data`.

  Throws `ex-info` (`:feishu-openapi-lite/missing-option`) when neither `:file-key` nor
  `:content` is given, and for HTTP failures or a non-zero Feishu `:code`."
  [client opts]
  (im/reply-file! client opts))

(defn message-resource-request
  "Builds the HTTP request that downloads a resource (image/file) attached to a
  message.

  Parameters:
  - `client` — client map:
    - `:domain` — optional Feishu origin; defaults to `default-domain`.
    - `:tenant-access-token` — required; token sent as a bearer credential.
  - `opts` — resource options map:
    - `:message-id` — required; message the resource belongs to.
    - `:file-key` — required; resource key from the message content.
    - `:type` — optional resource type (for example `\"image\"` or `\"file\"`);
      defaults to `\"image\"`.

  Returns: request map for `http/request!` reading the body as bytes; nothing is sent.

  Throws `ex-info` (`:feishu-openapi-lite/missing-option`) when `:message-id`,
  `:file-key`, or `:tenant-access-token` is missing."
  [client opts]
  (im/message-resource-request client opts))

(defn download-message-resource!
  "Downloads a resource attached to a message.

  Parameters:
  - `client` — client map with app credentials and optional transport settings.
  - `opts` — resource options map as in `message-resource-request`.

  Returns: binary response map `{:ok? true :status :headers :content-type :bytes}`;
  `:bytes` is a byte array and `:content-type` comes from the response headers.

  Throws `ex-info` for missing options
  (`:feishu-openapi-lite/missing-option`), HTTP failures
  (`:feishu-openapi-lite/http-error`), or an unsupported response body."
  [client opts]
  (im/download-message-resource! client opts))

(defn upload-file-request
  "Builds the multipart HTTP request that uploads a file to Feishu.

  Parameters:
  - `client` — client map:
    - `:domain` — optional Feishu origin; defaults to `default-domain`.
    - `:tenant-access-token` — required; token sent as a bearer credential.
  - `opts` — upload options map:
    - `:file` — required unless `:file-path` is given; file object to upload.
    - `:file-path` — optional path of the file to upload.
    - `:file-name` — optional uploaded name; defaults to the file's own name.
    - `:file-type` — optional Feishu file type; defaults to `\"stream\"`.
    - `:content-type` — optional content type for the file part.

  Returns: multipart request map for `http/request!`; nothing is sent.

  Throws `ex-info` (`:feishu-openapi-lite/missing-option`) when neither `:file` nor
  `:file-path` is given, or when `:tenant-access-token` is missing."
  [client opts]
  (im/upload-file-request client opts))

(defn normalize-file-upload-response
  "Normalizes a Feishu file upload response body.

  Parameters:
  - `body` — decoded response body with `:code`, `:msg`, and `:data`.

  Returns: response map `{:ok? true :code :message :data}`, with `:data` holding
  `:file-key`, `:file-name`, and `:file-type`.

  Throws `ex-info` (`:feishu-openapi-lite/api-error`) with `:code` and `:message` when
  the body's `:code` is not zero."
  [body]
  (im/normalize-file-upload-response body))

(defn upload-file!
  "Uploads a file to Feishu and returns its file key.

  Parameters:
  - `client` — client map with app credentials and optional transport settings.
  - `opts` — upload options map as in `upload-file-request`.

  Returns: normalized response map `{:ok? true :code :message :data}` with
  `:file-key`, `:file-name`, and `:file-type` in `:data`.

  Throws `ex-info` for missing options, HTTP failures, or a non-zero Feishu `:code`."
  [client opts]
  (im/upload-file! client opts))

(defn parse-event-payload
  "Decodes a Feishu event payload into Clojure data.

  Parameters:
  - `payload` — event payload: an already-decoded map, a JSON string, or UTF-8 bytes.

  Returns: the event as a map (unchanged when the payload is already a map).

  Throws `ex-info` (`:feishu-openapi-lite/unsupported-event-payload`) for any other
  payload type, and the JSON reader's exception for malformed JSON."
  [payload]
  (event-codec/parse-event-payload payload))

(defn normalize-p2-event
  "Normalizes a Feishu v2 (p2) event into the client's event map.

  Parameters:
  - `event` — decoded Feishu event map with `:header` and `:event`.

  Returns: map with `:type :feishu/event`, `:schema`, `:event-type`, normalized
  `:header` (`:event-id`, `:event-type`, `:app-id`, `:tenant-key`, `:create-time`),
  `:sender` (`:id` with `:user-id`/`:open-id`/`:union-id`, `:type`, `:tenant-key`),
  `:event`, `:raw-event`, and — for message events — the
  normalized `:message` plus flattened keys (`:message-id`, `:chat-id`, `:chat-type`,
  `:thread-id`, `:message-type`, `:content`, `:content-raw`, `:create-time`,
  `:update-time`)."
  [event]
  (event-codec/normalize-p2-event event))

(defn message-received-event?
  "Tests whether an event is a Feishu incoming-message event.

  Parameters:
  - `event` — Feishu event map.

  Returns: true when the event type is `im.message.receive_v1`, false otherwise."
  [event]
  (event-codec/message-received-event? event))

(defn endpoint-request
  "Builds the HTTP request that asks Feishu for a WebSocket endpoint URL.

  Parameters:
  - `client` — client map:
    - `:domain` — optional Feishu origin; defaults to `default-domain`.
    - `:app-id` — Feishu app id sent as `AppID`.
    - `:app-secret` — Feishu app secret sent as `AppSecret`.
    - `:user-agent` — optional User-Agent; defaults to the library user agent.

  Returns: request map for `http/request!`; nothing is sent."
  [client]
  (ws/endpoint-request client))

(defn normalize-endpoint-response
  "Normalizes a WebSocket endpoint response body.

  Parameters:
  - `body` — decoded response body with `:code`, `:msg`, and `:data`.

  Returns: map with `:url` (the WebSocket URL to connect to) and `:client-config`
  (`:ping-interval-ms`, `:reconnect-count`, `:reconnect-interval-ms`,
  `:reconnect-nonce-ms`, converted from the server's second-based values).

  Throws `ex-info` (`:feishu-openapi-lite/api-error`) with `:code` and `:message` when
  the body's `:code` is not zero."
  [body]
  (ws/normalize-endpoint-response body))

(defn pull-endpoint!
  "Requests a WebSocket endpoint from Feishu.

  Parameters:
  - `client` — client map with app credentials and optional `:domain`, `:user-agent`,
    `:request!`, `:timeout-ms`.

  Returns: map with `:url` and `:client-config` (see `normalize-endpoint-response`).

  Throws `ex-info` for HTTP failures or a non-zero Feishu `:code`."
  [client]
  (ws/pull-endpoint! client))

(defn start-event-ws!
  "Opens the Feishu event WebSocket and starts its ping loop.

  Parameters:
  - `client` — client map:
    - `:app-id` / `:app-secret` / `:domain` — credentials and origin for the endpoint
      request.
    - `:on-event` — optional function called with each decoded event payload; its
      return value is echoed back in the frame response.
    - `:on-error` — optional function called with connection errors and event handling
      throwables.
    - `:user-agent` — optional User-Agent header.
    - `:request!` — optional HTTP transport for the endpoint request.
    - `:timeout-ms` — optional endpoint-request timeout.

  Returns: WebSocket client record (`:websocket`, `:state`, `:stop!*`, `:ping-future`)
  accepted by `event-ws-state` and `close-event-ws!`. The ping loop runs on a future
  until the client is closed.

  Throws when the endpoint request fails or the WebSocket cannot be opened; the
  failure is also reported to `:on-error` for connection errors."
  [client]
  (ws/start! client))

(defn close-event-ws!
  "Stops the ping loop and sends a normal WebSocket close frame.

  Parameters:
  - `client` — WebSocket client record returned by `start-event-ws!`.

  Returns: the same client record. The ping loop stops because `:stop!*` is set; the
  close frame is sent on the socket the record holds."
  [client]
  (ws/close! client))

(defn event-ws-state
  "Reads the WebSocket client's current state.

  Parameters:
  - `client` — WebSocket client record returned by `start-event-ws!`.

  Returns: map with `:connection` (`:connecting`, `:connected`, `:error`,
  `:closed`), `:url`, `:client-config`, plus `:error` on failures and
  `:close-status`/`:close-reason` once closed."
  [client]
  (ws/state client))

(defn encode-frame
  "Encodes a Feishu WebSocket frame as protobuf-style bytes.

  Parameters:
  - `frame` — frame map:
    - `:seq-id` — optional int frame sequence number; defaults to 0.
    - `:log-id` — optional int log id; defaults to 0.
    - `:service` — optional int service id; defaults to 0.
    - `:method` — optional int frame method (`frame-type-control` 0 or
      `frame-type-data` 1); defaults to 0.
    - `:headers` — optional seq of `{:key :value}` string pairs.
    - `:payload-encoding` — optional string payload encoding.
    - `:payload-type` — optional string payload type.
    - `:payload` — optional byte array payload.
    - `:log-id-new` — optional string log id.

  Returns: byte array holding the encoded frame."
  [frame]
  (ws-frame/encode-frame frame))

(defn decode-frame
  "Decodes Feishu WebSocket frame bytes into a frame map.

  Parameters:
  - `bs` — frame bytes: a byte array, a UTF-8 string, a number sequence, or `nil`
    (which decodes as an empty frame).

  Returns: map with `:headers` (vector of `{:key :value}` maps) plus whichever of
  `:seq-id`, `:log-id`, `:service`, `:method`, `:payload-encoding`, `:payload-type`,
  `:payload` (byte array), and `:log-id-new` the frame carried.

  Throws `ex-info` (`:feishu-openapi-lite/unsupported-wire-type`) for a protobuf wire
  type the codec does not support."
  [bs]
  (ws-frame/decode-frame bs))

(defn ping-frame
  "Builds the control frame that keeps the event WebSocket alive.

  Parameters:
  - `service-id` — numeric service id echoed from the endpoint URL.

  Returns: frame map (see `encode-frame`) with `:method` `frame-type-control`, zero
  sequence/log ids, and a `type: ping` header."
  [service-id]
  (ws-frame/ping-frame service-id))

(defn event-response-frame
  "Builds the response frame acknowledging one received event.

  Parameters:
  - `event-frame` — decoded event frame the response answers; its sequence ids and
    headers are reused.
  - `opts` — response options map:
    - `:code` — optional HTTP-style result code; defaults to 200.
    - `:data` — optional handler result; written as base64 JSON when present.
    - `:biz-rt-ms` — optional business runtime in milliseconds, sent in the `biz_rt`
      header; defaults to 0.

  Returns: frame map ready for `encode-frame`, with the `biz_rt` header appended and the
  payload replaced by the JSON response."
  [event-frame opts]
  (ws-frame/event-response-frame event-frame opts))
