(ns werwolf.wake
  "Keep this phone's screen on while a game is running on it, with the Screen
   Wake Lock API.

   ## Why

   A phone that locks drops its WebSocket. One phone going dark is a reconnect;
   a whole table putting their phones down for the discussion is every socket
   gone at once — which, from the server, looks exactly like a game everybody
   walked out on. Its idle handling had to grow around that: `:game-idle-ms` in
   resources/config.edn is half an hour where a lobby gets five minutes, and
   `:last-activity` in werwolf.room is written to disk so that even a reboot can
   tell how long a table has really been gone. Keeping the screen on while a
   game runs removes the common cause instead of living with its consequences:
   the socket stays up, the night's chime rings, and nobody comes back from a
   lock screen to a reconnect.

   ## When

   The lock follows the seat and the phase: wanted while this page holds a seat
   in a room whose game is under way — any phase but the lobby and the end — and
   let go at game over, on leaving the room, on being removed from it, and when
   the session is thrown away. The lobby does without: a phone going dark before
   the deal costs nothing but a reconnect, and a lobby can wait. The phase is
   the server's, read off the view as sent; nothing is worked out here.

   The decision is a LEVEL, not an edge like the chime's: every accepted view
   says again whether the lock is wanted, and the effect acts only on a change.
   That is what re-takes the lock after a reload or a reconnect, where there is
   no transition to notice — the first view after a welcome already describes a
   running game.

   A socket that merely drops keeps the lock. The reconnect is exactly the
   moment a sleeping phone would miss, so letting go there would bring back the
   problem this exists to remove. The lock goes with the seat, not with the
   socket; a game nobody comes back to is the server's idle sweep's to retire.

   ## What the browser does with it

   The browser lets go of the lock by itself whenever the tab is hidden — the
   phone locked anyway, the player switched apps — and a request only succeeds
   from a visible page. So the sentinel's `release` event forgets it, and a
   `visibilitychange` back to visible asks again while the game still runs. A
   request may also be refused outright (a low battery, a browser setting); that
   counts as nothing happened, and the next view asks again.

   Everything here is wrapped. `navigator.wakeLock` is missing on desktop
   Firefox, older Safari, and in Node, which runs the test suite; a missing or
   refusing API is silence, never an error and never a line in the console —
   the same rule as werwolf.chime, for the same reason: this is a nicety around
   the game, not the game."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]))

;; ----------------------------------------------------------- the decision ---

(defn wanted?
  "Should this page hold the screen: does it have a seat in a game under way?
   Pure, and the only game-shaped question in this namespace — answered from
   the server's own phase."
  [db]
  (boolean (and (:me db)
                (when-let [phase (get-in db [:view :phase])]
                  (not (#{:lobby :game-over} phase))))))

;; -------------------------------------------------------------- the lock ---

;; The fx layer's last word, so a tab coming back from the background knows
;; whether to ask again.
(defonce ^:private wanted* (atom false))
;; The sentinel while this page holds one; nil once the browser or we let go.
(defonce ^:private held* (atom nil))
;; A request on its way to the browser. A second one meanwhile would hold two,
;; and let go of one.
(defonce ^:private asking* (atom false))

(defn- wake-lock
  "navigator.wakeLock, or nil. Looked up by string through goog.object so the
   advanced build cannot rename it, and so a runtime without a navigator gets
   nil rather than a ReferenceError."
  []
  (gobj/getValueByKeys goog/global "navigator" "wakeLock"))

(defn- held?
  "Is the lock still ours? The browser releases it when the tab is hidden and
   says so with an event, but the sentinel's own flag is the final word."
  []
  (when-let [^js s @held*]
    (if (true? (.-released s))
      (do (reset! held* nil) false)
      true)))

(defn- release! []
  (when-let [^js s @held*]
    (reset! held* nil)
    (some-> (.release s) (.catch (fn [_] nil)))))

(defn- request!
  "Ask the browser, if the lock is wanted and neither held nor being asked for.
   The answer comes later: by then the game may be over, and a lock granted to
   a page that no longer wants it is let go at once."
  []
  (when (and @wanted* (not (held?)) (not @asking*))
    (when-let [^js wl (wake-lock)]
      (reset! asking* true)
      (try
        (-> (.request wl "screen")
            (.then (fn [^js sentinel]
                     (reset! asking* false)
                     (.addEventListener sentinel "release"
                                        (fn [_] (when (identical? sentinel @held*)
                                                  (reset! held* nil))))
                     (if @wanted*
                       (reset! held* sentinel)
                       (some-> (.release sentinel) (.catch (fn [_] nil))))))
            (.catch (fn [_] (reset! asking* false))))
        (catch :default _ (reset! asking* false))))))

(defn hold!
  "The effect: `wanted?` is the fx layer's word on whether a game runs here.
   Said on every view; acted on when it changes."
  [wanted?]
  (try
    (reset! wanted* (boolean wanted?))
    (if wanted? (request!) (release!))
    (catch :default _ nil))
  nil)

(rf/reg-fx ::hold hold!)

;; ------------------------------------------------------------- the return ---

(defn- on-visibility!
  "Back in the foreground: the browser let go while the tab was hidden, so ask
   again if the game is still running. `request!` knows whether the lock is
   wanted, held or being asked for, so this is one call."
  [_]
  (try
    (when (= "visible" (.-visibilityState js/document))
      (request!))
    (catch :default _ nil)))

(defonce ^:private installed? (atom false))

(defn install!
  "Listen for the tab coming back. Idempotent across hot reloads, and marked
   installed only once the listener is really there: Node has no document, and
   a test that gives it one later must still be able to install."
  []
  (try
    (when-not @installed?
      (.addEventListener js/document "visibilitychange" on-visibility!)
      (reset! installed? true))
    (catch :default _ nil))
  nil)
