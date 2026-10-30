(ns werwolf.http
  "HTTP + WebSocket routing. Thin by design: this namespace knows about frames
   and bytes, and nothing at all about Werwolf.

   The socket object is passed straight through to the protocol layer as the
   player's 'sink'. There is deliberately no per-connection map here — the room
   registry's index already knows which player a socket belongs to, and a second
   copy of that fact is a second thing that can be wrong."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [reitit.ring :as ring]
            [werwolf.build-info :refer [package-version running-commit]]
            [werwolf.metrics :as metrics]
            [werwolf.protocol :as protocol]
            [werwolf.ws :as ws]))

(defn health [_req]
  {:status 200
   :headers {"content-type" "text/plain"}
   :body "ok"})

(defn build
  "Which build this server is, for the start page's footer. Asked of the
   server rather than compiled into the bundle, because the bundle no longer
   knows its commit: see werwolf.build-info/running-commit."
  [_req]
  {:status 200
   :headers {"content-type" "application/json"
             "cache-control" "no-cache"}
   ;; Both are [0-9a-z.+-] by construction; pr-str quotes them as JSON does.
   :body (str "{\"version\":" (pr-str (package-version))
              ",\"commit\":" (pr-str (running-commit)) "}")})

(defn index [_req]
  (if-let [r (io/resource "public/index.html")]
    {:status 200 :headers {"content-type" "text/html; charset=utf-8"} :body (io/input-stream r)}
    {:status 404 :body "index.html not built — run: bb build:cljs"}))

;; ------------------------------------------------------------- caching ---
;;
;; The page, the bundle and the stylesheet are served with "revalidate every
;; time" plus a content fingerprint, never with a modification date.
;;
;; The date was the bug. Nix sets every file timestamp to 1970-01-01 for
;; reproducibility, the jar carries it, and ring sent it as Last-Modified. A
;; browser given no Cache-Control guesses freshness from a file's age, and a
;; file unchanged since 1970 is fresh for years: after a deploy, players kept
;; running the previous bundle until they hard-reloaded. An ordinary reload does
;; not help, since browsers only revalidate the page itself, not its scripts.
;;
;; The filenames are fixed (/js/main.js), so a fingerprint is what tells the
;; browser a file changed. An unchanged file still costs only a 304.

(def ^:private etags
  "Fingerprint per resource, keyed by URL, modification time and size, so a dev
   rebuild (real timestamps) gets a fresh fingerprint while a jar's immutable
   entries are hashed once."
  (atom {}))

(defn- sha256-hex [^java.io.InputStream in]
  (let [md  (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (loop []
      (let [n (.read in buf)]
        (when (pos? n) (.update md buf 0 n) (recur))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn- etag-for [^java.net.URL url]
  (let [conn (.openConnection url)
        k    [(str url) (.getLastModified conn) (.getContentLengthLong conn)]]
    (or (get @etags k)
        (let [tag (str "\"" (subs (with-open [in (.getInputStream conn)] (sha256-hex in)) 0 32) "\"")]
          (swap! etags assoc k tag)
          tag))))

(defn- resource-for
  "The classpath resource a successful static response came from. Only called
   for a 200, i.e. a path the resource handler already accepted, so this is the
   same lookup and not a new way to reach files."
  [uri]
  (io/resource (str "public" (if (or (= uri "/") (str/blank? uri)) "/index.html" uri))))

(defn- close-body [{:keys [body]}]
  (when (instance? java.io.Closeable body) (.close ^java.io.Closeable body)))

(defn wrap-revalidate [handler]
  (fn [req]
    (let [resp (handler req)
          url  (when (= 200 (:status resp)) (resource-for (:uri req)))]
      (if-not url
        resp
        (let [tag  (etag-for url)
              resp (-> resp
                       (update :headers #(apply dissoc % ["Last-Modified" "last-modified"]))
                       (assoc-in [:headers "Cache-Control"] "no-cache")
                       (assoc-in [:headers "ETag"] tag))]
          (if (= tag (get-in req [:headers "if-none-match"]))
            (do (close-body resp)
                {:status 304 :headers (select-keys (:headers resp) ["Cache-Control" "ETag"]) :body nil})
            resp))))))

(def ^:private default-frames-per-sec 10)
(def ^:private default-frame-burst 30)

(defn- spend
  "A socket's token bucket after one more frame at `now`, or nil when it is
   empty: `burst` frames at once, refilled at `per-sec`."
  [{:keys [tokens at]} now per-sec burst]
  (let [tokens (min burst (+ tokens (* per-sec (/ (- now at) 1000.0))))]
    (when (>= tokens 1.0)
      {:tokens (dec tokens) :at now})))

(defn ws-handler
  "Upgrade to a WebSocket and hand every frame to the protocol layer.

   `read-transit` returns nil on malformed input and `dispatch` answers nil with
   an error, so a client cannot kill its own read loop — let alone the server —
   by sending garbage.

   `max-sockets` in `deps` caps the open sockets; past it an upgrade is refused
   with a 503, which the client's backoff turns into a retry. The count is the
   one exception to the no-per-connection-map rule above: it has to be known
   BEFORE the socket exists, when the registry has nothing to index it by. A
   set rather than a counter, because `on-error` and `on-close` can both fire
   for one socket and a counter would drift down for ever.

   Each socket may send `frame-burst` frames at once and `frames-per-sec` after
   that; a socket that sends faster is closed. Every frame is work on the pool
   that all rooms share, and most commands are a commit with two SQLite
   writes: in the security review ten sockets sending :set-locale as fast as
   they could made every other table's create time out after 20 s. Checked
   before the frame is even decoded, so junk is bounded too. Closed rather
   than answered, since an answer per excess frame would be more of the same
   work, and no person tapping a phone comes near the limit. The bucket lives
   in the socket's own listener, so it needs no map either."
  [{:keys [max-sockets frames-per-sec frame-burst] :as deps}]
  (let [open    (atom #{})
        per-sec (or frames-per-sec default-frames-per-sec)
        burst   (or frame-burst default-frame-burst)]
    (fn [req]
      (cond
        (not (ws/upgrade-request? req))
        {:status 400 :body "expected a websocket upgrade request"}

        (and max-sockets (>= (count @open) max-sockets))
        (do (metrics/refused! :too-many-sockets)
            {:status 503
             :headers {"content-type" "text/plain"}
             :body "too many connections"})

        :else
        ;; Only ever touched by this socket's frames, which http-kit hands
        ;; over one at a time.
        (let [bucket (atom {:tokens burst :at (System/currentTimeMillis)})]
          (ws/websocket-response
           {:on-open    (fn [sock]
                          (swap! open conj sock)
                          (protocol/on-open deps sock))
            :on-message (fn [sock raw]
                          (when-not (:closed? @bucket)
                            (if-let [b (spend @bucket (System/currentTimeMillis) per-sec burst)]
                              (let [_        (reset! bucket b)
                                    t0       (System/nanoTime)
                                    accepted (protocol/dispatch deps sock (ws/read-transit raw))]
                                (metrics/message! accepted (- (System/nanoTime) t0)))
                              (do (reset! bucket {:closed? true})
                                  (metrics/refused! :rate-limited)
                                  (ws/close! sock 1008 "too many messages")))))
            :on-close   (fn [sock _code _reason]
                          (swap! open disj sock)
                          (protocol/on-close deps sock))
            :on-error   (fn [sock e]
                          (log/warn "websocket error:" (ex-message e))
                          (swap! open disj sock)
                          (protocol/on-close deps sock))}))))))

(defn router [deps]
  (ring/router
   [["/health" {:get health}]
    ["/api/build" {:get build}]
    ["/api/ws" {:get (ws-handler deps)}]
    ["/" {:get (wrap-revalidate index)}]]))

(defn handler [deps]
  (ring/ring-handler
   (router deps)
   (ring/routes
    ;; ring's mime table predates web app manifests and would send this one
    ;; with no type at all. Chrome is lenient about that, but the spec names
    ;; the type and nothing obliges every browser to guess. The manifest and
    ;; icons go through wrap-revalidate like everything else: they are small, a
    ;; 304 costs nothing, and a changed icon or name then reaches phones on the
    ;; next launch instead of whenever a heuristic cache next looks.
    (wrap-revalidate (ring/create-resource-handler
                      {:path "/" :root "public"
                       :mime-types {"webmanifest" "application/manifest+json"}}))
    (ring/create-default-handler))))
