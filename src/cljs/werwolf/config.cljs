(ns werwolf.config
  "Build-time configuration. `ws-url` is a closure-define so the dev build can
   point straight at the Clojure server on :3000 while the release build uses a
   same-origin relative URL.")

(goog-define ws-url "")

(defn websocket-url
  "Resolve the WebSocket endpoint. In release builds ws-url is empty and we
   derive it from the page origin, so the app works on any host without a rebuild."
  []
  (if (seq ws-url)
    ws-url
    (let [loc   (.-location js/window)
          proto (if (= "https:" (.-protocol loc)) "wss:" "ws:")]
      (str proto "//" (.-host loc) "/api/ws"))))
