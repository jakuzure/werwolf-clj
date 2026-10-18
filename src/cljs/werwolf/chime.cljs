(ns werwolf.chime
  "A short sound on every phone when the night falls, the day breaks, and the
   game ends — so a table that is talking, not staring at its screens, notices
   the turn anyway.

   ## Why this is not in werwolf.audio-impl

   That module carries the voice-call earcons, and it would be the obvious home
   for more sounds. It is the wrong one: it is fetched only when somebody taps to
   join the call, and most tables never do. The phase chime is for everybody, so
   it lives in the main bundle — and must therefore stay small and must never
   require anything from the voice side. It needs nothing but WebAudio.

   ## Why the sounds are the shape they are

   The voice earcons are single sine blips, a tenth of a second each, and they
   mean 'somebody came or went'. These have to be told apart from those in the
   middle of a conversation, and from each other, without anybody looking:

   * **Night** — three soft bells falling down a minor chord, E4 C4 A3. Each is a
     sine with a quiet inharmonic partial above it (2.76× — what makes a bell a
     bell rather than an organ) and a slow exponential decay. Low, round, closing.
   * **Day** — a quick rising D-major arpeggio, D5 F♯5 A5 D6, on triangle waves
     that ring into each other. Bright, opening, a morning motif.
   * **End** — two short pickups on G4 and a resolved C-major chord, on a
     filtered sawtooth so it sounds faintly brassy: a little fanfare, and the
     only one of the three with an edge.

   Each stays under about a second and a half and well below the level of
   speech. A chime that makes somebody jump gets switched off, and then it is
   not there for the night it would have mattered.

   ## Unlocking

   Browsers keep an AudioContext suspended until the page has seen a user
   gesture, and a context created without one is born suspended. A night that
   falls while nobody is touching their phone is the entire use case, so waiting
   for a gesture at chime time would be useless. `unlock!` is hooked to the
   first pointerdown or keydown anywhere — tapping create or join counts — and
   creates or resumes the context there; every chime after that plays without
   interaction.

   A page that was reloaded mid-game and not touched since has no context, and
   stays silent until it is touched. That is the browser's rule, not ours.

   Everything here is wrapped: a missing AudioContext (Node, which runs the test
   suite; an old browser) or a context that refuses is a silent no-op. A chime is
   a nicety, and must never be the thing that throws."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [werwolf.subs :as sub]))

;; ----------------------------------------------------------- the decision ---

(defn cue-for
  "Which chime the step from view `old` to view `new` rings: :night, :day, :end
   or nil. Pure, so the decision is testable without any audio at all.

   * No previous view, no chime. The first view after a welcome — joining,
     reloading, a socket reconnecting — describes a phase that began who knows
     when, and announcing it now would be announcing the past. Welcome clears
     `:view`, which is what makes `old` nil there.

   * **Day** is LEAVING the night. Not entering :day-reveal: the server settles a
     whole commit before it broadcasts, and :day-reveal is an instant narrator
     step, so a client normally goes straight from :night to the election, the
     discussion — or :reaction, when a Jäger died overnight and has a shot owed.
     The day sub-phases after that are one day, and ring nothing.

   * **Night** is entering it, with one exception: an interrupt (:reaction) that
     RESUMES the night it paused. The night counter tells the two apart — it
     only moves when a night really begins, as it does when a Jäger lynched at
     dusk takes his shot and the next night falls straight after.

   * **End** is entering :game-over, from anywhere."
  [old new]
  (let [was (:phase old)
        is  (:phase new)]
    (when (and old (not= was is))
      (cond
        (= :game-over is) :end
        (= :night is)     (when (or (not= :reaction was)
                                    (not= (:night old) (:night new)))
                            :night)
        (= :night was)    (when (= :day (sub/ambience is)) :day)))))

;; ----------------------------------------------------------- the switch ---

(def ^:private storage-key "werwolf/chime-on")

(defn stored-on?
  "The player's last word on the chime, defaulting to ON. Storage can be missing
   (Node, a private window) or refuse outright; either way the default applies."
  []
  (try
    (not= "false" (.getItem js/localStorage storage-key))
    (catch :default _ true)))

(rf/reg-fx
 ::store-on
 (fn [on?]
   (try (.setItem js/localStorage storage-key (str on?))
        (catch :default _ nil))))

;; ------------------------------------------------------------ the context ---

(defonce ^:private ctx* (atom nil))

(defn- audio-context-class
  "The constructor, or nil. Looked up by string through goog.object so the
   advanced build cannot rename it, and so Node — which has no such global —
   gets nil rather than a ReferenceError."
  []
  (or (gobj/get goog/global "AudioContext")
      (gobj/get goog/global "webkitAudioContext")))

(defn unlock!
  "Create the context, or wake it, from inside a user gesture.

   Hooked to every pointerdown and keydown, not only the first: iOS suspends a
   running context again when the page goes to the background, and the next tap
   is the only chance to bring it back. Once it is running this is one property
   read per tap.

   The silent one-sample buffer is for older Safari, which only counts a context
   as unlocked once something has actually been STARTED inside the gesture."
  []
  (try
    (if-let [^js ctx @ctx*]
      (when (not= "running" (.-state ctx))
        (.resume ctx))
      (when-let [C (audio-context-class)]
        (let [^js ctx (new C)
              ^js src (.createBufferSource ctx)]
          (set! (.-buffer src) (.createBuffer ctx 1 1 22050))
          (.connect src (.-destination ctx))
          (.start src 0)
          (reset! ctx* ctx))))
    (catch :default _ nil))
  nil)

(defonce ^:private installed? (atom false))

(defn install!
  "Listen for the gestures that unlock audio. Capturing, so a component that
   stops propagation cannot swallow the one tap that mattered; passive, because
   it never prevents anything. Idempotent across hot reloads."
  []
  (try
    (when (compare-and-set! installed? false true)
      (doseq [ev ["pointerdown" "keydown"]]
        (.addEventListener js/document ev unlock! #js {:capture true :passive true})))
    (catch :default _ nil)))

;; ------------------------------------------------------------- the sounds ---

(defn- voice!
  "One note into `out`. `at` and `dur` are seconds from now; `peak` is its gain.

   The envelope is a short linear attack and an exponential decay to near
   silence, then the oscillator stops a moment later. A bare gate on any
   waveform is a click — and a click reads as a fault, not a chime.
   `cutoff`, when given, puts a lowpass in the path, which is what turns a raw
   sawtooth into something closer to a horn than to a buzzer."
  [^js ctx ^js out {:keys [type freq at dur peak attack detune cutoff]
                    :or   {type "sine" attack 0.01 detune 0}}]
  (let [t0        (+ (.-currentTime ctx) at)
        ^js osc   (.createOscillator ctx)
        ^js gain  (.createGain ctx)]
    (set! (.-type osc) type)
    (.setValueAtTime (.-frequency osc) freq t0)
    (.setValueAtTime (.-detune osc) detune t0)
    (.setValueAtTime (.-gain gain) 0 t0)
    (.linearRampToValueAtTime (.-gain gain) peak (+ t0 attack))
    ;; Exponential ramps cannot reach zero; a ten-thousandth is inaudible.
    (.exponentialRampToValueAtTime (.-gain gain) 0.0001 (+ t0 dur))
    (if cutoff
      (let [^js lp (.createBiquadFilter ctx)]
        (set! (.-type lp) "lowpass")
        (.setValueAtTime (.-frequency lp) cutoff t0)
        (.connect osc lp)
        (.connect lp gain))
      (.connect osc gain))
    (.connect gain out)
    (.start osc t0)
    (.stop osc (+ t0 dur 0.05))))

(defn- bell!
  "A soft bell: the fundamental, plus the inharmonic partial a struck bell has
   and an organ pipe does not, quieter and dying faster. Two slightly detuned
   fundamentals give it the slow beat of real metal."
  [ctx out freq at dur peak]
  (voice! ctx out {:freq freq :at at :dur dur :peak (* peak 0.6) :attack 0.008 :detune -4})
  (voice! ctx out {:freq freq :at at :dur dur :peak (* peak 0.6) :attack 0.008 :detune 4})
  (voice! ctx out {:freq (* freq 2.76) :at at :dur (* dur 0.45) :peak (* peak 0.25) :attack 0.004}))

(def ^:private notes
  {:a3 220.00 :c4 261.63 :e4 329.63 :g4 392.00
   :c5 523.25 :d5 587.33 :e5 659.25 :fs5 739.99 :g5 783.99 :a5 880.00 :d6 1174.66 :e6 1318.51})

(defn- score!
  "Lay out one of the three chimes on `out`."
  [ctx out what]
  (case what
    ;; Falling, minor, slow. The last bell rings longest, so the phrase closes.
    :night (do (bell! ctx out (:e4 notes) 0.00 0.90 0.10)
               (bell! ctx out (:c4 notes) 0.24 0.90 0.10)
               (bell! ctx out (:a3 notes) 0.48 1.00 0.12))
    ;; Two quick taps on the shoulder, a fifth apart: it is you. Short and
    ;; high, so it cannot be mistaken for the phase chimes, which are chords.
    :turn  (do (voice! ctx out {:type "sine" :freq (:a5 notes) :at 0.00 :dur 0.16 :peak 0.09 :attack 0.004})
               (voice! ctx out {:type "sine" :freq (:e6 notes) :at 0.13 :dur 0.30 :peak 0.09 :attack 0.004}))
    ;; Rising, major, quick, each note ringing under the next.
    :day   (doseq [[i k] (map-indexed vector [:d5 :fs5 :a5 :d6])]
             (voice! ctx out {:type "triangle" :freq (k notes) :at (* i 0.11)
                              :dur (if (= 3 i) 0.75 0.45) :peak 0.08 :attack 0.006}))
    ;; Da-da — DAAH. Filtered sawtooth, the chord held a little.
    :end   (let [brass {:type "sawtooth" :cutoff 1800 :attack 0.015}]
             (voice! ctx out (merge brass {:freq (:g4 notes) :at 0.00 :dur 0.13 :peak 0.05}))
             (voice! ctx out (merge brass {:freq (:g4 notes) :at 0.16 :dur 0.13 :peak 0.05}))
             (doseq [k [:c4 :c5 :e5 :g5]]
               (voice! ctx out (merge brass {:freq (k notes) :at 0.32 :dur 1.05
                                             :peak 0.035 :attack 0.03}))))
    nil))

(defn play!
  "Ring `what` — :night, :day or :end — if this page can.

   Never creates a context: one made outside a gesture is born suspended, and the
   browser says so in the console. No context, or one still suspended, means the
   page has not been touched yet; that is silence, not an error."
  [what]
  (try
    (when-let [^js ctx @ctx*]
      (when (= "running" (.-state ctx))
        ;; One bus per chime, so the whole phrase can be levelled in one place.
        (let [^js out (.createGain ctx)]
          (.setValueAtTime (.-gain out) 1 (.-currentTime ctx))
          (.connect out (.-destination ctx))
          (score! ctx out what))))
    (catch :default _ nil))
  nil)

(rf/reg-fx ::play play!)

(defn buzz!
  "A short double buzz, where the device can. Android browsers can; Safari on
   the iPhone has no vibration at all, so there the turn chime has to do."
  []
  (try
    (when (gobj/get js/navigator "vibrate")
      (.vibrate js/navigator #js [70 60 70]))
    (catch :default _ nil))
  nil)

(rf/reg-fx ::buzz (fn [_] (buzz!)))

(rf/reg-fx
 ::title
 ;; The tab's name, so a player in another tab sees that it is their turn.
 (fn [title] (try (set! (.-title js/document) title) (catch :default _ nil))))
