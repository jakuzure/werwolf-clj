(ns werwolf.text
  "Every string the UI shows, in one place.

   German only for now, but deliberately shaped as lookups rather than literals
   scattered through the views: M4 replaces the two functions at the bottom with
   `tongue` dictionaries and a per-player locale, and no view has to change.

   `log-line` is the reason the protocol carries structured events instead of
   sentences. The server says `{:type :player/died :player 3 :cause :werwolf}`
   and each client renders it in its own language — which is what lets a German
   and an English player sit in the same room."
  (:require [clojure.string :as str]))

(def ^:private de
  {;; --- landing ---
   :app/title        "Werwölfe von Düsterwald"
   :landing/name     "Dein Name"
   :landing/create   "Neue Runde eröffnen"
   :landing/join     "Runde beitreten"
   :landing/code     "Raumcode"
   :landing/or       "oder"

   ;; --- connection ---
   :conn/connecting  "verbinde…"
   :conn/connected   "verbunden"
   :conn/disconnected "getrennt — versuche erneut zu verbinden…"

   ;; --- lobby ---
   :lobby/title      "Warteraum"
   :lobby/players    "Mitspieler"
   :lobby/start      "Spiel starten"
   :lobby/need-more  "Es fehlen noch Mitspieler"
   :lobby/share      "Teile diesen Code:"
   :lobby/host-hint  "Du bist Spielleiter"
   :lobby/leave      "Runde verlassen"

   ;; --- phases ---
   :phase/lobby      "Warteraum"
   :phase/night      "Nacht"
   :phase/day-reveal "Morgengrauen"
   :phase/discussion "Beratung"
   :phase/nomination "Anklage"
   :phase/vote       "Abstimmung"
   :phase/day-resolve "Urteil"
   :phase/reaction   "Letzte Handlung"
   :phase/game-over  "Spielende"

   ;; --- roles ---
   :role/werwolf      "Werwolf"
   :role/dorfbewohner "Dorfbewohner"
   :role/seherin      "Seherin"
   :role/hexe         "Hexe"
   :role/jaeger       "Jäger"
   :role/amor         "Amor"

   ;; What your role actually does, shown on your own card. "Unclear who is who"
   ;; is mostly this being missing: a role name alone tells a new player nothing.
   :role-desc/werwolf      "Du reißt jede Nacht mit den anderen Wölfen ein Opfer."
   :role-desc/dorfbewohner "Du hast keine Nachtaktion — nur deine Stimme am Tag."
   :role-desc/seherin      "Jede Nacht darfst du die Rolle eines Mitspielers sehen."
   :role-desc/hexe         "Einmal im Spiel heilen, einmal vergiften."
   :role-desc/jaeger       "Stirbst du, nimmst du einen Mitspieler mit."
   :role-desc/amor         "In der ersten Nacht bestimmst du zwei Verliebte."

   :role-icon/werwolf      "🐺"
   :role-icon/dorfbewohner "🏠"
   :role-icon/seherin      "🔮"
   :role-icon/hexe         "🧪"
   :role-icon/jaeger       "🎯"
   :role-icon/amor         "💘"

   ;; --- actions ---
   :action/start-game      "Spiel starten"
   :action/leave           "Verlassen"
   :action/set-locale      "Sprache"
   :action/chat            "Sagen"
   :action/amor-link       "Verliebte bestimmen"
   :action/seherin-inspect "Rolle aufdecken"
   :action/werewolf-vote   "Opfer wählen"
   :action/hexe-heal       "Heiltrank benutzen"
   :action/hexe-poison     "Gifttrank benutzen"
   :action/hexe-pass       "Keinen Trank benutzen"
   :action/jaeger-shoot    "Schuss abgeben"
   :action/jaeger-pass     "Nicht schießen"
   :action/ready           "Ich bin fertig"
   :action/nominate        "Anklagen"
   :action/nominate-pass   "Niemanden anklagen"
   :action/vote            "Verurteilen"
   :action/vote-abstain    "Enthalten"
   :action/advance-phase   "Phase weiter (SL)"
   :action/gm-force-kill   "Töten (SL)"
   :action/gm-revive       "Wiederbeleben (SL)"
   :action/gm-skip-role    "Rolle überspringen (SL)"
   :action/gm-set-phase    "Phase setzen (SL)"
   :action/gm-pause        "Zeit anhalten (SL)"
   :action/gm-resume       "Zeit weiterlaufen (SL)"
   :action/gm-add-time     "+60 s (SL)"

   ;; --- game ---
   :game/you         "du"
   :game/dead        "tot"
   :game/absent      "abwesend"
   :game/your-role   "Deine Rolle"
   :game/in-love     "Du bist verliebt in"
   :game/potions     "Tränke"
   :game/heiltrank   "Heiltrank"
   :game/gifttrank   "Gifttrank"
   :game/knows       "Aufgedeckt"
   :game/log         "Chronik"
   :game/paused      "Zeit angehalten"
   :log/show-all     "Alles zeigen"
   :log/show-less    "Nur Wichtiges"
   :log/hidden       "weitere Einträge"
   :log/times        "×"
   :game/chat        "Gespräch"
   :game/chat-hint   "Nachricht schreiben…"
   :game/waiting     "Warte auf die anderen…"
   :game/nothing-to-do "Für dich ist gerade nichts zu tun."
   :game/wolves-channel "Nur die Werwölfe hören dich"
   :game/dead-channel   "Nur die Toten hören dich"
   :game/winner-village "Das Dorf hat gewonnen"
   :game/winner-werewolf "Die Werwölfe haben gewonnen"
   :game/winner-lovers  "Die Verliebten haben gewonnen"

   ;; --- targeting ---
   :target/pick-one   "Wähle ein Ziel"
   :target/pick-two   "Wähle zwei Ziele"
   :target/chosen-one "Erster gewählt — jetzt den zweiten"
   :target/cancel     "Abbrechen"
   :target/hint       "Klicke unten auf einen Mitspieler"

   ;; --- badges ---
   :badge/accused  "angeklagt"
   :badge/votes    "Stimmen"
   :badge/vote     "Stimme"
   :badge/ready    "fertig"
   :badge/decided  "hat gewählt"
   :badge/host     "SL"
   :badge/lover    "verliebt"
   :badge/selected "gewählt"
   :badge/waiting-for "Es fehlen noch"

   ;; --- errors ---
   :error/no-such-room     "Diesen Raum gibt es nicht"
   :error/game-in-progress "Die Runde läuft schon"
   :error/room-full        "Der Raum ist voll"
   :error/name-required    "Bitte gib einen Namen ein"
   :error/not-in-room      "Du sitzt nicht in dieser Runde"
   :error/seat-gone        "Dein Platz existiert nicht mehr"
   :error/expired          "Deine Sitzung ist abgelaufen"
   :error/wrong-room        "Dieser Zugang gehört zu einer anderen Runde"
   :error/bad-signature    "Ungültiger Zugang"
   :error/malformed        "Ungültige Anfrage"
   :error/chat-not-allowed "Jetzt darfst du nicht sprechen"
   :error/not-allowed      "Das darfst du gerade nicht"
   :error/invalid-target   "Dieses Ziel ist nicht erlaubt"
   :error/already-in-room  "Du bist schon in einer Runde"
   :error/internal-error   "Etwas ist schiefgegangen"})

(defn t
  "Look up a string. Unknown keys render as the key itself rather than blank —
   a visible `:game/mystery` in the UI is a bug report; an empty span is not."
  [k]
  (get de k (str k)))

(defn phase-name [phase] (t (keyword "phase" (name (or phase :lobby)))))
(defn role-name [role] (if role (t (keyword "role" (name role))) "?"))
(defn role-desc [role] (when role (t (keyword "role-desc" (name role)))))
(defn role-icon [role] (if role (t (keyword "role-icon" (name role))) "❓"))

(defn action-name [action]
  (t (keyword "action" (str/replace (subs (str action) 1) "/" "-"))))

;; ------------------------------------------------------------------ the log ---

(defn- who [players pid]
  (or (get-in players [pid :name]) (str "#" pid)))

(def ^:private tone-by-type
  "How prominent each kind of entry is in the Chronik. A log where a death and a
   reconnect look identical is a log nobody reads."
  {:player/died      :death
   :jaeger/shot      :death
   :vote/resolved    :verdict
   :day/no-accusation :verdict
   :game/ended       :verdict
   :night/began      :night
   :night/ended      :day
   :day/began        :day
   :game/started     :night
   :role/revealed    :reveal
   :player/nominated :vote
   ;; Individual votes are bookkeeping: the tally is already on the player
   ;; cards, so a line per voter only pushes the verdict off the screen.
   :vote/cast        :muted
   :gm/forced        :gm
   :host/changed     :gm
   :phase/timed-out  :muted
   :player/ready     :muted
   :nominate/passed  :muted
   :player/joined    :muted
   :player/left      :muted
   :player/disconnected :muted
   :player/reconnected  :muted
   :jaeger/held-fire :muted
   :clock/paused     :gm
   :clock/resumed    :gm})

(defn log-tone
  "Tone for one entry. A function rather than a lookup because prominence
   sometimes depends on the payload: a tie is worth reading, while 'the vote
   resolved and nothing happened' merely repeats the :day/no-accusation line
   directly above it."
  [{:keys [type target tie?]}]
  (if (and (= :vote/resolved type) (nil? target) (not tie?))
    :muted
    (get tone-by-type type :plain)))

(def loud?
  "Entries worth showing when the Chronik is filtered down to consequences."
  (complement #{:muted}))

(declare log-line)

(defn- actor-of
  "Who an entry is about. :vote/cast says :voter, everything else :player."
  [e]
  (or (:player e) (:voter e)))

(defn- group-line
  "One line for a run of same-type entries, naming everyone in it.

   Naming them beats a bare count: '5 × P5 ist fertig' is worse than the noise
   it replaces, because it reads as the log stuttering on one player."
  [players run]
  (let [names (str/join ", " (keep #(some->> (actor-of %) (who players)) run))]
    (case (:type (first run))
      :player/ready        (str names " sind fertig")
      :nominate/passed     (str names " klagen niemanden an")
      :vote/cast           (str names " haben abgestimmt")
      :player/joined       (str names " sind beigetreten")
      :player/disconnected (str names " sind abwesend")
      :player/reconnected  (str names " sind zurück")
      ;; No group phrasing for this type: fall back to the single line plus a
      ;; count rather than inventing grammar.
      (str (count run) "× " (log-line players (first run))))))

(defn chronicle
  "Turn the raw log into display rows, newest first: {:text :tone :count}.

   Two jobs, both learned from a playtest. Runs of the same type collapse into
   one row — a quiet six-player day emits ten near-identical lines, sixteen at
   nine players, which reads as the log repeating itself. And bookkeeping is
   filtered out unless asked for, because showing every ready/pass/vote by
   default buried the one line that said somebody had died."
  [players log {:keys [show-all?]}]
  (->> log
       (map (fn [e] (assoc e ::tone (log-tone e))))
       (filter (fn [e] (or show-all? (loud? (::tone e)))))
       (partition-by :type)
       (mapcat (fn [run]
                 (if (< (count run) 2)
                   [{:tone (::tone (first run))
                     :text (log-line players (first run))}]
                   [{:tone (::tone (first run))
                     :text (group-line players run)
                     :count (count run)}])))
       reverse
       vec))

(defn log-line
  "Render one public log entry. `players` supplies names, because the log holds
   ids — a name change must not rewrite history."
  [players {:keys [type player target role winner phase host cause day voter]}]
  (case type
    :clock/paused        "Die Spielleitung hält die Zeit an"
    :clock/resumed       "Die Zeit läuft weiter"
    :player/ready        (str (who players player) " ist fertig")
    :nominate/passed     (str (who players player) " klagt niemanden an")
    :day/no-accusation   "Das Dorf klagt niemanden an — heute stirbt keiner"
    :jaeger/held-fire    (str (who players player) " senkt das Gewehr")
    :vote/cast           (if target
                           (str (who players voter) " → " (who players target))
                           (str (who players voter) " enthält sich"))
    :player/joined       (str (who players player) " ist beigetreten")
    :player/left         (str (who players player) " hat die Runde verlassen")
    :player/disconnected (str (who players player) " ist abwesend")
    :player/reconnected  (str (who players player) " ist zurück")
    :host/changed        (str (who players host) " ist jetzt Spielleiter")
    :game/started        "Die erste Nacht bricht an"
    :night/began         "Die Nacht bricht an"
    :night/ended         "Der Morgen graut"
    :day/began           "Das Dorf versammelt sich"
    :player/died         (str (who players player) " ist gestorben"
                             (case cause
                               :werwolf " — von Werwölfen gerissen"
                               :gift " — vergiftet"
                               :liebe " — aus Liebe gefolgt"
                               :lynch " — vom Dorf gerichtet"
                               :jaeger " — vom Jäger erschossen"
                               :gm " — durch die Spielleitung"
                               ""))
    :player/revived      (str (who players player) " lebt wieder")
    :role/revealed       (str (who players player) " war " (role-name role))
    :jaeger/shot         (str (who players player) " erschießt " (who players target))
    :vote/resolved       (if target
                           (str "Das Dorf richtet " (who players target))
                           "Das Dorf kann sich nicht einigen — niemand stirbt")
    :player/nominated    (str (who players target) " wird angeklagt")
    :phase/timed-out     (str "Die Zeit für " (phase-name phase) " ist abgelaufen")
    :gm/forced           "Die Spielleitung greift ein"
    :game/ended          (t (keyword "game" (str "winner-" (name (or winner :village)))))
    (str type)))
