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

(def ^:private default-max-sockets-per-address 32)
(def ^:private default-unseated-grace-ms 30000)

(defn client-address
  "Who opened this connection, for counting sockets per client; nil for this
   machine, which is not counted that way.

   http-kit has already read X-Forwarded-For into :remote-addr (its first
   entry), so behind Caddy this is the client, not Caddy: Caddy replaces
   whatever a client wrote in that header with the address it saw. Every way
   this server is deployed puts Caddy in front — nix/module.nix binds the app
   to loopback, deploy/ publishes no port of its own, a preview is reached
   only through the host's Caddy — so nobody else gets to write it. Loopback
   without that header is the operator's own machine, a test or a load run;
   with it, it is somebody who wrote 127.0.0.1 there, and counted as such.
   Compared as text: the value may be anything a client wrote, and InetAddress
   would look a name up in DNS."
  [{:keys [remote-addr headers]}]
  (when-not (and (or (nil? remote-addr)
                     (str/starts-with? remote-addr "127.")
                     (#{"::1" "0:0:0:0:0:0:0:1"} remote-addr))
                 (not (contains? headers "x-forwarded-for")))
    remote-addr))

(defn- crowd
  "The open sockets a new one from `addr` would be one too many among, and the
   refusal that is, or nil when there is room for it."
  [open addr {:keys [max-sockets max-sockets-per-address]}]
  (let [mine (when addr (filterv #(= addr (:addr (val %))) open))]
    (cond (and mine (>= (count mine) max-sockets-per-address)) [:too-many-from-address mine]
          (and max-sockets (>= (count open) max-sockets))       [:too-many-sockets open])))

(defn- admit
  "`open` with `sock` in it, or unchanged when there is no room for it. When a
   cap is reached, room is made by closing the longest-open socket that had its
   chance to sit down and did not: that is what holding sockets looks like, and
   a page that is still being used is only asked to reconnect."
  [open sock addr now limits idle?]
  (let [[_ among] (crowd open addr limits)
        victim (some->> (seq (filter idle? among)) (apply min-key (comp :at val)) key)]
    (cond (nil? among) (assoc open sock {:addr addr :at now})
          victim       (-> open (dissoc victim) (assoc sock {:addr addr :at now}))
          :else        open)))

(defn ws-handler
  "Upgrade to a WebSocket and hand every frame to the protocol layer.

   `read-transit` returns nil on malformed input and `dispatch` answers nil with
   an error, so a client cannot kill its own read loop — let alone the server —
   by sending garbage.

   `max-sockets` in `deps` caps the open sockets, and `max-sockets-per-address`
   those of one client (see `client-address`); past either an upgrade is
   refused with a 503, which the client's backoff turns into a retry. The
   global cap alone was the denial it meant to prevent: one client holding
   idle sockets locked every player out, seated ones whose phone reconnected
   included. Per address it has to fit a whole table on one wifi, and
   max-players is 18.

   When a cap is full, a socket open longer than `unseated-grace-ms` without a
   seat is closed to make room for the newcomer. Not on a timer of its own: the landing page asks for the room list on an unseated
   socket for as long as somebody reads it, and closing that every 30 s would
   flash the connection banner at them for nothing.

   The sockets are the one exception to the no-per-connection-map rule above:
   the count has to be known BEFORE a socket exists, when the registry has
   nothing to index it by. A map by socket rather than a counter, because
   `on-error` and `on-close` can both fire for one socket and a counter would
   drift down for ever. The upgrade looks first, but `on-open` decides, in one
   swap: when the upgrade decided, a burst of handshakes all passed before any
   of them was counted. Nothing is reserved at upgrade time, since http-kit may
   still refuse the handshake and never call `on-open`.

   Each socket may send `frame-burst` frames at once and `frames-per-sec` after
   that; a socket that sends faster is closed. Every frame is work on the pool
   that all rooms share, and most commands are a commit with two SQLite
   writes: in the security review ten sockets sending :set-locale as fast as
   they could made every other table's create time out after 20 s. Checked
   before the frame is even decoded, so junk is bounded too. Closed rather
   than answered, since an answer per excess frame would be more of the same
   work, and no person tapping a phone comes near the limit. The bucket lives
   in the socket's own listener, so it needs no map either."
  [{:keys [max-sockets max-sockets-per-address unseated-grace-ms
           frames-per-sec frame-burst] :as deps}]
  (let [open    (atom {})
        limits  {:max-sockets max-sockets
                 :max-sockets-per-address (or max-sockets-per-address
                                              default-max-sockets-per-address)}
        grace   (or unseated-grace-ms default-unseated-grace-ms)
        idle?   (fn [now [sock {:keys [at]}]]
                  (and (> (- now at) grace) (not (protocol/seated? deps sock))))
        per-sec (or frames-per-sec default-frames-per-sec)
        burst   (or frame-burst default-frame-burst)]
    (fn [req]
      (let [addr (client-address req)
            now  (System/currentTimeMillis)
            seen @open]
        (cond
          (not (ws/upgrade-request? req))
          {:status 400 :body "expected a websocket upgrade request"}

          (not (contains? (admit seen ::look addr now limits (partial idle? now)) ::look))
          (do (metrics/refused! (first (crowd seen addr limits)))
              {:status 503
               :headers {"content-type" "text/plain"}
               :body "too many connections"})

          :else
          ;; Only ever touched by this socket's frames, which http-kit hands
          ;; over one at a time.
          (let [bucket (atom {:tokens burst :at now})]
            (ws/websocket-response
             {:on-open    (fn [sock]
                            (let [now (System/currentTimeMillis)
                                  [before after] (swap-vals! open admit sock addr now limits
                                                             (partial idle? now))]
                              (if (contains? after sock)
                                (do (doseq [s (keys before) :when (not (contains? after s))]
                                      (ws/close! s 1013 "made room"))
                                    (protocol/on-open deps sock))
                                (do (metrics/refused! (or (first (crowd before addr limits))
                                                          :too-many-sockets))
                                    (ws/close! sock 1013 "too many connections")))))
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
                            (swap! open dissoc sock)
                            (protocol/on-close deps sock))
              :on-error   (fn [sock e]
                            (log/warn "websocket error:" (ex-message e))
                            (swap! open dissoc sock)
                            (protocol/on-close deps sock))})))))))

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
