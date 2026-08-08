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
  (:require ["livekit-client" :refer [Room RoomEvent]]
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

;; ------------------------------------------------------------------ cues ---
;;
;; Synthesised rather than loaded from a file: two short sine notes need no asset,
;; no fetch and no decoding, and cannot be the thing that fails on a slow phone.
;;
;; Deliberately quiet (gain 0.04). People testing voice are wearing headphones,
;; and a cue that makes anyone flinch gets the feature turned off.

(defonce ^:private cue-ctx (atom nil))

(defn- cue-context
  "Created lazily and reused. The first cue always follows a tap — joining — so
   the context is unlocked by a user gesture, which is what lets later cues (a
   friend arriving) play with no interaction at all."
  []
  (or @cue-ctx
      (try (reset! cue-ctx (js/AudioContext.))
           (catch :default _ nil))))

(defn- tone!
  "One sine note. `at` is an offset in seconds, so a caller can sequence a pair
   without timers."
  [^js ctx freq at dur]
  (let [^js osc  (.createOscillator ctx)
        ^js gain (.createGain ctx)
        t0       (+ (.-currentTime ctx) at)]
    (set! (.-type osc) "sine")
    (.setValueAtTime (.-frequency osc) freq t0)
    ;; A tiny fade in and out. A bare gate on a sine is a click, which sounds
    ;; like a fault rather than a cue.
    (.setValueAtTime (.-gain gain) 0 t0)
    (.linearRampToValueAtTime (.-gain gain) 0.04 (+ t0 0.01))
    (.linearRampToValueAtTime (.-gain gain) 0 (+ t0 dur))
    (.connect osc gain)
    (.connect gain (.-destination ctx))
    (.start osc t0)
    (.stop osc (+ t0 dur 0.02))))

(defn cue!
  "A short earcon. `:joined` when you enter the call, `:arrived` when somebody
   else does — rising for both, but the other person is one quieter note, since it
   happens without you doing anything and should not interrupt a sentence."
  [what]
  (when-let [^js ctx (cue-context)]
    (case what
      :joined  (do (tone! ctx 523.25 0    0.10)   ; C5
                   (tone! ctx 783.99 0.10 0.14))  ; G5
      :arrived (tone! ctx 659.25 0 0.09)          ; E5
      nil)))

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
         ;; Compared against the literal wire value, NOT against the SDK's enum.
         ;;
         ;; This line read `(.-Audio Track)` and the enum is actually
         ;; `Track.Kind.Audio`, so it evaluated to `undefined`, never equalled
         ;; "audio", and no remote track was ever attached. Nothing failed: the SDK
         ;; connected, published and subscribed exactly as it should, and the game
         ;; was simply silent. A mistyped property path on a JS object is `undefined`
         ;; rather than an error, which is the whole hazard of interop.
         ;;
         ;; The string cannot silently become undefined, and under :advanced a
         ;; nested enum access is one more thing that has to survive renaming.
         (when (= "audio" (.-kind track))
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
  ;; Somebody else joined the call. Worth hearing: on a phone the player list is
  ;; often off-screen, and "did Bob get in?" is otherwise answered by asking.
  (.on room (.-ParticipantConnected RoomEvent)
       (fn [_] (cue! :arrived)))
  (.on room (.-Disconnected RoomEvent)
       (fn [_]
         (detach-all!)
         (rf/dispatch [:werwolf.events/audio-left])))
  room)

(defn connect!
  "Join the call. `on-error` receives a keyword, because the UI renders through
   the dictionaries and must not put a browser's English exception on screen."
  [{:keys [url token]}]
  (cond
    ;; No microphone exists at all outside a secure context: browsers do not
    ;; merely refuse permission, they delete `navigator.mediaDevices`. The failure
    ;; is otherwise "Cannot read properties of undefined (reading getUserMedia)",
    ;; which sends the reader looking for a bug in the SDK.
    ;;
    ;; This is what a phone hits at http://192.168.x.x:3000. Nothing in the app
    ;; can fix it — it needs HTTPS, or localhost.
    (or (not js/isSecureContext) (nil? (.-mediaDevices js/navigator)))
    (rf/dispatch [:werwolf.events/audio-failed :insecure-context])

    @room* nil ; idempotent: a second tap must not open a second microphone

    :else
    ;; adaptiveStream and dynacast are both VIDEO optimisations — quality laddering
    ;; by element visibility, and pausing unwatched simulcast layers. This app
    ;; publishes audio and nothing else, so they buy nothing and only add behaviour
    ;; between a published track and a heard one.
    (let [^js room (Room. #js {:adaptiveStream false :dynacast false})]
      (reset! room* room)
      (wire! room)
      (-> (.connect room url token)
          (.then (fn [_] (.setMicrophoneEnabled ^js (.-localParticipant room) true)))
          (.then (fn [_]
                   ;; After setMicrophoneEnabled, so the cue means "you are in and
                   ;; publishing" rather than "the socket opened".
                   (cue! :joined)
                   (rf/dispatch [:werwolf.events/audio-joined])))
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
  "Mute locally.

   `setMicrophoneEnabled false` MUTES the publication; it does not unpublish it.
   An earlier version of this docstring claimed the opposite, and the browser test
   disproved it: after muting, the far side still holds the track and simply sees
   it disabled. Worth stating accurately, because \"the track is gone\" and \"the
   track is muted\" are different guarantees — the second is the one being made.

   It is still enforced at the source rather than by not rendering something:
   the microphone stops feeding the publication, so a muted player cannot be
   heard by accident."
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
