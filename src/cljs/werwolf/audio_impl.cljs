(ns werwolf.audio-impl
  "Everything that touches livekit-client, isolated so it can be LOADED ON DEMAND.

   This namespace is deliberately the only one that requires the SDK. It is
   reached exclusively through `werwolf.audio`, which pulls it in the first time a
   player taps to join — see the split explained there. Nothing here may be
   required from a namespace that loads on first paint, or the split silently
   collapses and the SDK is back in the main bundle.

   It registers no effects and knows nothing about re-frame's registry; it exports
   plain functions in `api` and dispatches events outward.

   Shaped after `werwolf.ws` and for the same reason: a LiveKit `Room` is a
   long-lived stateful object with a microphone attached, so it cannot live in
   app-db. app-db holds a small summary of it — connected, muted, who is talking —
   and every mutation happens through an effect. Components then render from
   subscriptions and never touch the SDK.

   ## Joining is an explicit act

   There is no auto-join, and that is not timidity. Browsers refuse
   `getUserMedia` without a user gesture, so an automatic attempt fails on the
   first load of every session; and opening someone's microphone because they
   loaded a web page is the wrong default even where it is technically allowed.
   So: a button.

   ## Audio elements, and why they are not in the view

   A remote track has to be attached to a DOM element to be audible, and reagent
   must not own those elements — a re-render that replaced them would cut the
   audio mid-sentence. They are created imperatively, parented to a container
   outside the reagent tree, and removed when the track goes away. This is the one
   place in the client where manual DOM manipulation is the correct answer.

   ## What this namespace does NOT decide

   Who may speak. Every player can talk, always — see `werwolf.media` — so there
   are no permissions to interpret here. Muting is local and voluntary: it is the
   player's own microphone, not a rule. If a permission table is ever added, the
   server will enforce it on the SFU and this namespace will not need to be
   trusted to cooperate."
  (:require ["livekit-client" :refer [Room RoomEvent Track]]
            [re-frame.core :as rf]))

;; Every JS object below carries a `^js` hint, and that is load-bearing rather
;; than decorative. The release build is `:advanced`, so Closure renames property
;; accesses it cannot prove are external — `.attach`, `.setMicrophoneEnabled`,
;; `.-identity` — and the failure mode is audio that works in development and is
;; silently dead in production. `:infer-externs :auto` protects what it can infer;
;; the hints are what let it infer. `bb test:cljs` must stay at zero warnings for
;; that reason: here the warnings ARE the bug report.

(defonce ^:private room* (atom nil))

;; Remote <audio> elements, keyed by track sid so they can be detached again.
(defonce ^:private els (atom {}))

(def ^:private container-id "werwolf-audio-sink")

(defn- container
  "A div outside the reagent tree to hang <audio> elements on. Created on demand
   so nothing has to guarantee ordering against the app's first render."
  []
  (or (.getElementById js/document container-id)
      (let [d (.createElement js/document "div")]
        (set! (.-id d) container-id)
        ;; Not display:none — a hidden audio element is still played, but some
        ;; browsers have historically treated fully hidden media as inert, and
        ;; the cost of being wrong is silence that looks like a bug.
        (set! (.. d -style -position) "absolute")
        (set! (.. d -style -width) "0")
        (set! (.. d -style -height) "0")
        (set! (.. d -style -overflow) "hidden")
        (.appendChild (.-body js/document) d)
        d)))

(defn- attach! [^js track sid]
  (let [^js el (.attach track)]
    (set! (.-autoplay el) true)
    (.appendChild (container) el)
    (swap! els assoc sid el)))

(defn- detach! [sid]
  (when-let [^js el (get @els sid)]
    (.remove el)
    (swap! els dissoc sid)))

(defn- detach-all! []
  (doseq [sid (keys @els)] (detach! sid))
  (reset! els {}))

(defn- speaking-identities [speakers]
  (set (keep (fn [^js p] (.-identity p)) speakers)))

(defn- track-key [^js participant ^js track]
  (str (.-identity participant) "/" (.-sid track)))

(defn- wire! [^js room]
  (.on room (.-TrackSubscribed RoomEvent)
       (fn [^js track _pub ^js participant]
         (when (= (.-Audio Track) (.-kind track))
           (attach! track (track-key participant track)))))
  (.on room (.-TrackUnsubscribed RoomEvent)
       (fn [^js track _pub ^js participant]
         (detach! (track-key participant track))))
  ;; Who is talking, for the UI. Everyone is in one room and nobody is muted by
  ;; the server, so this leaks nothing — worth stating because it would NOT be
  ;; safe under the plan's permission table, where absence from the main room
  ;; identifies the wolves.
  (.on room (.-ActiveSpeakersChanged RoomEvent)
       (fn [speakers]
         (rf/dispatch [:werwolf.events/audio-speakers
                       (speaking-identities speakers)])))
  (.on room (.-Disconnected RoomEvent)
       (fn [_]
         (detach-all!)
         (rf/dispatch [:werwolf.events/audio-left])))
  room)

(defn connect!
  "Join the call. `on-error` receives a keyword, because the UI renders through
   the dictionaries and must not put a browser's English exception on screen."
  [{:keys [url token]}]
  (when-not @room* ; idempotent: a second tap must not open a second microphone
    (let [^js room (Room. #js {:adaptiveStream true :dynacast true})]
      (reset! room* room)
      (wire! room)
      (-> (.connect room url token)
          (.then (fn [_] (.setMicrophoneEnabled ^js (.-localParticipant room) true)))
          (.then (fn [_] (rf/dispatch [:werwolf.events/audio-joined])))
          (.catch (fn [^js e]
                    (js/console.error "audio: join failed" e)
                    (reset! room* nil)
                    (rf/dispatch [:werwolf.events/audio-failed
                                  ;; A refused microphone is the common case and
                                  ;; deserves its own message: it is fixed in
                                  ;; browser settings, not by retrying.
                                  (if (re-find #"(?i)permission|denied|notallowed"
                                               (str (.-message e)))
                                    :mic-denied
                                    :join-failed)])))))))

(defn disconnect! []
  (when-let [^js room @room*]
    (reset! room* nil)
    (detach-all!)
    (.disconnect room)))

(defn set-muted!
  "Mute locally. `setMicrophoneEnabled false` stops publishing rather than
   sending silence, so a muted player costs the SFU nothing and — more to the
   point — cannot be heard by accident."
  [muted?]
  (when-let [^js room @room*]
    (.setMicrophoneEnabled ^js (.-localParticipant room) (not muted?))))

(def api
  "The impl's whole surface, as data.

   One loadable rather than three: `shadow.lazy` resolves a symbol at load time,
   and a single map means the split has exactly one entry point to keep in step
   with the caller."
  {:connect! connect!
   :disconnect! disconnect!
   :set-muted! set-muted!})
