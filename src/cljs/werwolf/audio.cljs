(ns werwolf.audio
  "The voice effects, and the code split that keeps the SDK out of first paint.

   livekit-client is around half the compiled application — it roughly doubled the
   release bundle when it was required directly. This game is played on phones,
   often on mobile data, and not every table uses voice, so paying for the SDK on
   first paint is the wrong trade: the download is spent before anyone has decided
   whether they want it.

   So everything that touches LiveKit lives in `werwolf.audio-impl`, and this
   namespace reaches it through `shadow.lazy`. The module is fetched the first time
   a player taps to join. Nothing on the loading path may require `audio-impl`
   directly — a single stray require anywhere in the first-paint graph puts the SDK
   back in the main bundle, and the only symptom is a bundle that is quietly twice
   the size again. `bb build:cljs` prints the module sizes; that is the check.

   The effects stay HERE rather than in the impl, because re-frame must know about
   `::connect` at registration time — before the module exists. Each one loads the
   module if needed and then applies the call, so callers dispatch effects exactly
   as they would if nothing were lazy."
  (:require [re-frame.core :as rf]
            [shadow.lazy :as lazy]))

(def ^:private impl (lazy/loadable werwolf.audio-impl/api))

;; Whether the module has ever been fetched. Used to keep `::disconnect` from
;; DOWNLOADING half a megabyte in order to discover there is nothing to hang up:
;; `::leave` fires it on every exit, including for the majority of players who
;; never joined a call at all.
(defonce ^:private loaded (atom nil))

(defn- with-impl!
  "Run `(f api)`, fetching the module first if this is the first time."
  [f]
  (if-let [api @loaded]
    (f api)
    (-> (lazy/load impl)
        (.then (fn [api]
                 (reset! loaded api)
                 (f api)))
        (.catch (fn [e]
                  ;; A failed chunk fetch is a network problem, not a refused
                  ;; microphone, and it must not look like one. Reported through
                  ;; the same event as any other join failure so it renders in the
                  ;; player's language.
                  (js/console.error "audio: could not load the voice module" e)
                  (rf/dispatch [:werwolf.events/audio-failed :join-failed]))))))

(rf/reg-fx ::connect (fn [creds] (with-impl! (fn [api] ((:connect! api) creds)))))

(rf/reg-fx
 ::disconnect
 (fn [_]
   ;; Only if the module is already here. Not merely an optimisation: without the
   ;; guard, leaving a room would trigger the SDK download for every player who
   ;; never touched voice.
   (when-let [api @loaded] ((:disconnect! api)))))

(rf/reg-fx
 ::set-muted
 (fn [muted?]
   ;; Same reasoning — there is no microphone to mute until there is a module.
   (when-let [api @loaded] ((:set-muted! api) muted?))))
