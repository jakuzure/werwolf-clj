(ns werwolf.i18n.de
  "German. The canonical Düsterwald terminology: Seherin, Hexe, Jäger, Amor,
   Verliebte, Spielleitung.

   Every log entry is a WHOLE sentence rather than a stem plus a suffix. German
   and English put the pieces in different orders — 'wurde von den Werwölfen
   gerissen' against 'was torn apart by the werewolves' — so composing them from
   fragments produces something that is grammatical in exactly one language.")

(def dict
  {;; ------------------------------------------------------------- shell ---
   :app/title         "Werwölfe von Düsterwald"

   :conn/connecting   "verbinde…"
   :conn/connected    "verbunden"
   :conn/disconnected "getrennt — versuche erneut zu verbinden…"

   :locale/de         "Deutsch"
   :locale/en         "English"

   ;; ----------------------------------------------------------- landing ---
   :landing/name      "Dein Name"
   :landing/create    "Neue Runde eröffnen"
   :landing/join      "Runde beitreten"
   :landing/code      "Raumcode"
   :landing/or        "oder"
   ;; Die offenen Runden stehen über dem Codefeld: einen Code abtippen ist jetzt
   ;; der Notausgang für eine Runde, die die Liste nicht zeigen kann.
   :landing/open-rooms "Offene Runden"
   :landing/no-rooms   "Gerade läuft keine offene Runde"
   :landing/refresh    "Neu laden"

   ;; ------------------------------------------------------------- lobby ---
   :lobby/title       "Warteraum"
   :lobby/players     "Mitspieler"
   :lobby/start       "Spiel starten"
   :lobby/need-more   "Es fehlen noch Mitspieler"
   ;; Die Spielleitung darf unter der empfohlenen Größe starten. Ein Hinweis,
   ;; keine Sperre: vier Leute, die jetzt spielen wollen, sollen nicht an einer
   ;; Zahl scheitern, die für einen größeren Abend gesetzt wurde.
   :lobby/below-recommended (fn [n] (str "Ihr könnt starten — schöner wird es ab "
                                         n " Mitspielern"))
   :lobby/share       "Teile diesen Code"
   :lobby/leave       "Runde verlassen"

   ;; ------------------------------------------------------------ phases ---
   :phase/lobby       "Warteraum"
   :phase/night       "Nacht"
   :phase/election    "Bürgermeisterwahl"
   :phase/day-reveal  "Morgengrauen"
   :phase/discussion  "Beratung"
   :phase/nomination  "Anklage"
   :phase/vote        "Abstimmung"
   :phase/day-resolve "Urteil"
   :phase/reaction    "Letzte Handlung"
   :phase/game-over   "Spielende"

   ;; ------------------------------------------------------------- roles ---
   :role/werwolf      "Werwolf"
   :role/dorfbewohner "Dorfbewohner"
   :role/seherin      "Seherin"
   :role/hexe         "Hexe"
   :role/jaeger       "Jäger"
   :role/weisser-werwolf "Weißer Werwolf"
   :role/beschuetzer  "Beschützer"
   :role/dieb         "Dieb"
   :role/amor         "Amor"

   :role-desc/werwolf      "Du reißt mit den anderen Wölfen jede Nacht ein Opfer — ab der zweiten."
   :role-desc/dorfbewohner "Du hast keine Nachtaktion — nur deine Stimme am Tag."
   :role-desc/seherin      "Jede Nacht darfst du die Rolle eines Mitspielers sehen."
   :role-desc/hexe         "Einmal im Spiel heilen, einmal vergiften."
   :role-desc/jaeger       "Stirbst du, nimmst du einen Mitspieler mit."
   :role-desc/weisser-werwolf "Du jagst mit den Wölfen — und jede zweite Nacht reißt du einen von ihnen. Du gewinnst nur allein."
   :role-desc/beschuetzer  "Jede Nacht schützt du einen Mitspieler vor den Werwölfen — nie zweimal hintereinander denselben."
   :role-desc/dieb         "In der ersten Nacht siehst du zwei übrige Karten und darfst eine davon nehmen — oder Dieb bleiben."
   :role-desc/amor         "In der ersten Nacht bestimmst du zwei Verliebte."

   ;; ----------------------------------------------------------- actions ---
   :action/start-game      "Spiel starten"
   :action/leave           "Verlassen"
   :action/set-locale      "Sprache"
   :action/chat            "Sagen"
   :action/ready           "Ich bin fertig"
   :action/amor-link       "Verliebte bestimmen"
   :action/seherin-inspect "Rolle aufdecken"
   :action/werewolf-vote   "Opfer wählen"
   :action/beschuetzer-protect "Schützen"
   :action/weisser-kill    "Einen Wolf reißen"
   :action/weisser-pass    "Heute keinen Wolf reißen"
   :action/dieb-take       "Karte nehmen"
   :action/dieb-keep       "Eigene Karte behalten"
   :action/hexe-heal       "Heiltrank benutzen"
   :action/hexe-poison     "Gifttrank benutzen"
   :action/hexe-pass       "Keinen Trank benutzen"
   :action/jaeger-shoot    "Schuss abgeben"
   :action/jaeger-pass     "Nicht schießen"
   :action/elect           "Zum Bürgermeister wählen"
   :action/elect-abstain   "Enthalten"
   :action/mayor-appoint   "Nachfolge bestimmen"
   :action/mayor-decline   "Niemanden bestimmen"
   :action/nominate        "Anklagen"
   :action/nominate-pass   "Niemanden anklagen"
   :action/vote            "Verurteilen"
   :action/vote-abstain    "Enthalten"
   :action/advance-phase   "Phase weiter (SL)"
   :action/gm-force-kill   "Töten (SL)"
   :action/gm-revive       "Wiederbeleben (SL)"
   :action/gm-skip-role    "Rolle überspringen (SL)"
   :action/gm-set-phase    "Phase setzen (SL)"
   :action/gm-reassign-role "Rolle neu vergeben (SL)"
   :action/gm-pause        "Zeit anhalten (SL)"
   :action/gm-resume       "Zeit weiterlaufen (SL)"
   :action/gm-add-time     "+60 s (SL)"

   ;; ---------------------------------------------------- Spielleitung ---
   :gm/panel          "Spielleitung"
   :gm/panel-hint     "Nur du siehst diese Schalter. Jeder Eingriff steht in der Chronik."
   :gm/open           "Öffnen"
   :gm/close          "Schließen"
   :gm/pick-player    "Mitspieler"
   :gm/pick-role      "Rolle"
   :gm/pick-phase     "Phase"
   :gm/confirm        "Wirklich?"
   :gm/clock          "Zeit"
   :gm/nothing        "Gerade nichts zu steuern."

   ;; -------------------------------------------------------------- game ---
   :game/you           "du"
   :game/dead          "tot"
   :game/absent        "abwesend"
   :game/your-role     "Deine Rolle"
   :game/in-love       "Du bist verliebt in"
   :game/heiltrank     "Heiltrank"
   :game/gifttrank     "Gifttrank"
   :game/knows         "Aufgedeckt"
   :game/log           "Chronik"
   :game/chat          "Gespräch"
   :game/chat-hint     "Nachricht schreiben…"
   :game/waiting       "Warte auf die anderen…"
   :game/nothing-to-do "Für dich ist gerade nichts zu tun."
   :game/wolves-channel "Nur die Werwölfe hören dich"
   :game/dead-channel   "Nur die Toten hören dich"
   :game/paused         "Zeit angehalten"
   :game/new-round      "Zurück — neue Runde"
   :game/over-hint      "Alle Rollen sind jetzt aufgedeckt."
   :game/dead-hint      "Du bist tot — du schaust nur noch zu."

   ;; ------------------------------------------------------------- turn ---
   ;;
   ;; The banner that says whether the game is waiting for YOU. Its own group
   ;; because "warte auf die anderen" already exists as :game/waiting and these
   ;; are the other half of that sentence.
   :turn/yours   "Du bist dran"
   :turn/hint    "Wähle unten eine Aktion"
   :turn/urgent  "Alle warten auf dich"
   :turn/calling (fn [role] (str "Der Spielleiter ruft: " role))

   ;; ------------------------------------------------------------ voice ---
   ;;
   ;; Joining is an explicit tap: browsers refuse a microphone without a user
   ;; gesture, and opening one because somebody loaded a page is the wrong
   ;; default anyway. `:voice/everyone-hears` is deliberately plain about the
   ;; rule — the server enforces no phase muting, so the UI must not imply it.
   :voice/title          "Sprache"
   :voice/join           "🎧 Sprache beitreten"
   :voice/joining        "Verbinde…"
   :voice/leave          "Verlassen"
   :voice/mute           "Stumm schalten"
   :voice/unmute         "Stummschaltung aufheben"
   :voice/everyone-hears "Alle am Tisch hören dich — auch nachts, auch als Toter."

   ;; --------------------------------------------------------- targeting ---
   :target/pick-one   "Wähle ein Ziel"
   :target/pick-two   "Wähle zwei Ziele"
   :target/chosen-one "Erster gewählt — jetzt den zweiten"
   :target/cancel     "Abbrechen"
   :target/hint       "Klicke unten auf einen Mitspieler"

   ;; ------------------------------------------------------------ badges ---
   :badge/accused     "angeklagt"
   :badge/votes       "Stimmen"
   :badge/vote        "Stimme"
   :badge/ready       "fertig"
   :badge/decided     "hat gewählt"
   :badge/mayor       "BM"
   :badge/host        "SL"
   :badge/lover       "verliebt"
   :badge/waiting-for "Es fehlen noch"

   ;; --------------------------------------------------------------- log ---
   :log/show-all      "Alles zeigen"
   :log/show-less     "Nur Wichtiges"
   :log/hidden        "weitere Einträge"

   :log/joined        (fn [n] (str n " ist beigetreten"))
   :log/left          (fn [n] (str n " hat die Runde verlassen"))
   :log/disconnected  (fn [n] (str n " ist abwesend"))
   :log/reconnected   (fn [n] (str n " ist zurück"))
   :log/host-changed  (fn [n] (str n " ist jetzt Spielleitung"))
   :log/game-started  "Die erste Nacht bricht an"
   :log/amor-linked   "Amor hat zwei Verliebte bestimmt"
   :log/night-began   "Die Nacht bricht an"
   :log/night-quiet-first "Die erste Nacht bleibt ruhig — niemand stirbt"
   :log/night-quiet   "In dieser Nacht ist niemand gestorben"
   :log/night-ended   "Der Morgen graut"
   :log/day-began     "Das Dorf versammelt sich"
   :log/ready         (fn [n] (str n " ist fertig"))
   :log/nominate-passed (fn [n] (str n " klagt niemanden an"))
   :log/no-accusation "Das Dorf klagt niemanden an — heute stirbt keiner"
   :log/jaeger-held-fire (fn [n] (str n " senkt das Gewehr"))
   :log/vote-cast     (fn [v t] (str v " → " t))
   :log/vote-abstained (fn [v] (str v " enthält sich"))

   :log/died-werwolf  (fn [n] (str n " wurde von den Werwölfen gerissen"))
   :log/died-gift     (fn [n] (str n " wurde vergiftet"))
   :log/died-liebe    (fn [n] (str n " folgt der großen Liebe in den Tod"))
   :log/died-lynch    (fn [n] (str n " wurde vom Dorf gerichtet"))
   :log/died-jaeger   (fn [n] (str n " wurde vom Jäger erschossen"))
   :log/died-weisser  (fn [n] (str n " wurde vom Weißen Werwolf gerissen"))
   :log/died-gm       (fn [n] (str n " wurde von der Spielleitung aus dem Spiel genommen"))
   :log/died          (fn [n] (str n " ist gestorben"))

   :log/revived       (fn [n] (str n " lebt wieder"))
   :log/role-revealed (fn [n r] (str n " war " r))
   :log/jaeger-shot   (fn [n t] (str n " erschießt " t))
   :log/lynched       (fn [n] (str "Das Dorf richtet " n))
   :log/tie           "Das Dorf kann sich nicht einigen — niemand stirbt"
   ;; Die Wahl. Öffentlich, denn der ganze Sinn des Amtes ist, dass der Tisch
   ;; weiß, wessen Stimme doppelt zählt.
   :log/mayor-elected   (fn [n] (str n " wurde zum Bürgermeister gewählt"))
   :log/mayor-none      "Das Dorf konnte sich auf keinen Bürgermeister einigen"
   :log/mayor-appointed (fn [n] (str n " übernimmt das Amt des Bürgermeisters"))
   :log/mayor-vacated   "Das Amt des Bürgermeisters bleibt unbesetzt"
   :log/nominated     (fn [n] (str n " wird angeklagt"))
   :log/timed-out     (fn [p] (str "Die Zeit für " p " ist abgelaufen"))
   ;; Was die Spielleitung getan hat. Nur für die Eingriffe, die sonst spurlos
   ;; blieben: Töten und Wiederbeleben schreiben ohnehin ihre eigene Zeile, und
   ;; zweimal dasselbe zu sagen liest sich wie ein Fehler.
   ;;
   ;; Die neue Rolle steht bewusst NICHT hier. Die Chronik liest der ganze
   ;; Tisch — dass eingegriffen wurde, geht alle an; was auf der Karte steht,
   ;; nur den einen. Siehe `handle :gm/reassign-role`.
   :log/gm-forced     "Die Spielleitung greift ein"
   :log/gm-skip-role  (fn [r] (str "Die Spielleitung überspringt " r))
   :log/gm-set-phase  (fn [p] (str "Die Spielleitung setzt die Phase auf " p))
   :log/gm-reassign-role (fn [n] (str n " hat von der Spielleitung eine neue Rolle bekommen"))
   :log/clock-paused  "Die Spielleitung hält die Zeit an"
   :log/clock-resumed "Die Zeit läuft weiter"
   :log/win-village   "Das Dorf hat gewonnen"
   :log/win-werewolf  "Die Werwölfe haben gewonnen"
   :log/win-lovers    "Die Verliebten haben gewonnen"
   :log/win-weisser   "Der Weiße Werwolf hat allein gewonnen"

   ;; Plural roll-ups for the Chronik. Separate entries because the verb agrees
   ;; with the subject, which a count spliced into the singular form cannot do.
   :log/group-ready        (fn [names] (str names " sind fertig"))
   :log/group-passed       (fn [names] (str names " klagen niemanden an"))
   :log/group-voted        (fn [names] (str names " haben abgestimmt"))
   :log/group-joined       (fn [names] (str names " sind beigetreten"))
   :log/group-disconnected (fn [names] (str names " sind abwesend"))
   :log/group-reconnected  (fn [names] (str names " sind zurück"))

   ;; ------------------------------------------------------------ errors ---
   ;; Voice failures. A refused microphone gets its own line because it is fixed
   ;; in browser settings, not by tapping again — and it is by far the common one.
   :error/mic-denied       "Kein Zugriff aufs Mikrofon — bitte in den Browser-Einstellungen erlauben"
   ;; Kein Fehler, den die App beheben kann: ohne HTTPS gibt der Browser gar kein
   ;; Mikrofon her. Betrifft vor allem Handys, die den Rechner über die IP öffnen.
   :error/insecure-context "Sprache braucht HTTPS — über eine IP-Adresse ohne Zertifikat gibt der Browser kein Mikrofon frei"
   :error/join-failed      "Sprachverbindung fehlgeschlagen"
   :error/no-such-room     "Diesen Raum gibt es nicht"
   :error/game-in-progress "Die Runde läuft schon"
   :error/already-joined   "Du sitzt schon in dieser Runde"
   :error/room-full        "Der Raum ist voll"
   :error/name-required    "Bitte gib einen Namen ein"
   :error/code-required    "Bitte gib den Raumcode ein"
   :error/not-in-room      "Du sitzt nicht in dieser Runde"
   :error/unknown-player   "Diesen Platz gibt es nicht"
   :error/seat-gone        "Dein Platz existiert nicht mehr"
   :error/expired          "Deine Sitzung ist abgelaufen"
   :error/wrong-room       "Dieser Zugang gehört zu einer anderen Runde"
   :error/bad-signature    "Ungültiger Zugang"
   :error/malformed        "Ungültige Anfrage"
   :error/chat-not-allowed "Jetzt darfst du nicht sprechen"
   :error/empty-message    "Die Nachricht ist leer"
   :error/not-allowed      "Das darfst du gerade nicht"
   :error/invalid-target   "Dieses Ziel ist nicht erlaubt"
   :error/already-in-room  "Du bist schon in einer Runde"
   :error/not-host         "Nur die Spielleitung darf das"
   :error/already-dead     "Dieser Mitspieler ist schon tot"
   :error/already-alive    "Dieser Mitspieler lebt noch"
   :error/already-paused   "Die Zeit ist schon angehalten"
   :error/not-paused       "Die Zeit läuft bereits"
   :error/no-deadline      "Es läuft gerade keine Zeit"
   :error/not-night        "Das geht nur nachts"
   :error/cannot-advance   "Diese Phase lässt sich nicht weiterschalten"
   :error/distinct-targets-required "Bitte zwei verschiedene Mitspieler"
   :error/unsupported-locale "Diese Sprache gibt es nicht"
   :error/unknown-command  "Unbekannter Befehl"
   :error/internal-error   "Etwas ist schiefgegangen"})
