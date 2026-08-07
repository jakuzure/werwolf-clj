(ns werwolf.config
  "Where the client dials.

   `ws-url` is an escape hatch, and it is EMPTY by default in every build. It
   used to be set to ws://localhost:3000 for dev, which worked on exactly one
   device: the machine running the server. Open the game on a phone — which is
   how it is actually played — and the client dialled the phone itself, the
   socket never opened, and every tap went into the outbox instead of onto the
   wire. The buttons looked broken.

   So the endpoint is derived from the page's own origin. Whatever host you
   reached the game on is the host the socket goes to.")

(goog-define ws-url "")

(def ^:private shadow-dev-port
  "shadow-cljs' dev-http serves the page on 8080 and proxies /api to the Clojure
   server on 3000. A WebSocket upgrade through that proxy is not something we
   want to depend on, so from that one port we hop to the game server directly —
   by HOSTNAME, still never by 'localhost'."
  "8080")

(def ^:private game-server-port "3000")

(defn ws-url-for
  "Pure, so the port hop is testable without a browser."
  [{:keys [protocol hostname port]}]
  (let [proto (if (= "https:" protocol) "wss:" "ws:")
        port  (if (= shadow-dev-port port) game-server-port port)]
    (str proto "//" hostname (when (seq port) (str ":" port)) "/api/ws")))

(defn websocket-url
  "The WebSocket endpoint for this page."
  []
  (if (seq ws-url)
    ws-url
    (let [loc (.-location js/window)]
      (ws-url-for {:protocol (.-protocol loc)
                   :hostname (.-hostname loc)
                   :port     (.-port loc)}))))
