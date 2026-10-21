(ns werwolf.i18n.de
  "German. The canonical Düsterwald terminology: Seherin, Hexe, Jäger, Amor,
   Verliebte, Spielleiter.

   Every log entry is a WHOLE sentence rather than a stem plus a suffix. German
   and English put the pieces in different orders — 'wurde von den Werwölfen
   gerissen' against 'was torn apart by the werewolves' — so composing them from
   fragments produces something that is grammatical in exactly one language.")

(def dict
  {;; ------------------------------------------------------------- shell ---
   :app/title         "Werwölfe von Finsterbach"

   :conn/connecting   "verbinde…"
   :conn/connected    "verbunden"
   :conn/disconnected "getrennt — versuche erneut zu verbinden…"

   :locale/de         "Deutsch"
   :locale/en         "English"

   ;; ----------------------------------------------------------- landing ---
   :landing/name      "Dein Name"
   :guide/title       "Neu hier? So wird gespielt"
   :guide/intro       "Unter die Bewohner von Finsterbach haben sich Werwölfe gemischt. Jede Nacht reißen sie ein Opfer, jeden Tag versucht das Dorf, sie zu entlarven. Alle bekommen eine geheime Rolle — nur du kennst deine. Wer Werwolf ist, weiß, wer noch zum Rudel gehört; alle anderen müssen es herausfinden."
   :guide/night-title "Die Nacht"
   :guide/night       "Alle schlafen. Die Rollen werden nacheinander gerufen, und wer gerufen wird, handelt heimlich auf dem eigenen Bildschirm: die Seherin schaut sich eine Karte an, die Werwölfe einigen sich auf ein Opfer, die Hexe darf es retten."
   :guide/day-title   "Der Tag"
   :guide/day         "Am Morgen erfahrt ihr, wer die Nacht nicht überlebt hat, und seht seine Karte. Am ersten Tag wählt ihr zuerst einen Bürgermeister. Dann redet ihr und stimmt zugleich ab, wer verurteilt wird — jeden Tag, den ersten eingeschlossen. Dafür braucht es eine Mehrheit der Lebenden; bis alle abgestimmt haben, könnt ihr umstimmen."
   :guide/win-title   "Wer gewinnt"
   :guide/win         "Das Dorf gewinnt, sobald kein Werwolf mehr lebt. Die Werwölfe gewinnen, wenn außer ihnen niemand mehr übrig ist. Zwei Verliebte gewinnen gemeinsam, wenn nur noch sie leben — egal, zu wem sie gehören. Tote reden nur noch mit Toten."
   :guide/roles-title "Die Rollen"
   :guide/team-werewolf "Die Werwölfe"
   :guide/team-village "Das Dorf"
   :guide/mayor-title "Bürgermeister"
   :guide/mayor       "Keine Karte, sondern ein Amt: am ersten Tag gewählt, zählt seine Stimme doppelt. Stirbt er, bestimmt er seinen Nachfolger."
   :guide/table       "Ab drei Spielern geht es los, richtig Spaß macht es ab fünf. Wer das Spiel eröffnet, ist der Spielleiter: Er sieht alle Karten und spielt nicht mit — oder er spielt mit, dann läuft das Spiel mit Zeitlimit von selbst. Redet über den Sprachchat oder am selben Tisch."
   :landing/create    "Neue Runde eröffnen"
   :landing/join      "Runde beitreten"
   :landing/invited "Du bist eingeladen in Runde"
   :landing/not-invited "Lieber eine andere Runde"
   :landing/invite-not-open "Diese Runde ist gerade nicht offen — sie läuft schon oder ist vorbei."
   :landing/join-anyway "Trotzdem versuchen"
   :notice/left "Du hast die Runde verlassen."
   :landing/code      "Raumcode"
   :landing/or        "oder"
   ;; Die offenen Runden stehen über dem Codefeld: einen Code abtippen ist jetzt
   ;; der Notausgang für eine Runde, die die Liste nicht zeigen kann.
   :landing/open-rooms "Offene Runden"
   :landing/no-rooms   "Gerade läuft keine offene Runde"
   :landing/refresh    "Neu laden"
   ;; Das Passwort wird am Tisch vorgelesen wie der Raumcode, also ein normales
   ;; Textfeld: wer tippt, soll sehen, was er tippt.
   :landing/password      "Passwort (optional)"
   :landing/join-password "Passwort der Runde"
   :landing/locked        "Mit Passwort"

   ;; ------------------------------------------------------------- lobby ---
   :lobby/title       "Warteraum"
   :lobby/players     "Mitspieler"
   :lobby/count       (fn [n mx mn] (str n " / " mx " Mitspieler" (when (< n mn) (str " · am meisten Spaß macht es ab " mn " Mitspielern"))))
   :lobby/start       "Spiel starten"
   :lobby/need-more   "Es fehlen noch Mitspieler"
   ;; Die Spielleitung darf unter der empfohlenen Größe starten. Ein Hinweis,
   ;; keine Sperre: vier Leute, die jetzt spielen wollen, sollen nicht an einer
   ;; Zahl scheitern, die für einen größeren Abend gesetzt wurde.
   :lobby/below-recommended (fn [n] (str "Ihr könnt starten — schöner wird es ab "
                                         n " Mitspielern"))
   :lobby/share       "Teile diesen Code"
   :lobby/leave       "Runde verlassen"
   :lobby/leave-confirm "Wirklich verlassen?"
   :lobby/leave-cancel  "Doch bleiben"
   ;; Der Spielleiter. Er sitzt im Raum, spielt aber nicht mit — der Warteraum
   ;; muss das sagen, sonst zählt jeder ihn als Mitspieler und wundert sich über
   ;; die Kartenzahl.
   :lobby/mode               "Spielleiter"
   :lobby/mode-narrated      "Der Spielleiter bekommt keine Karte, sieht alle Rollen und führt durchs Spiel."
   :lobby/mode-players       "Der Spielleiter spielt mit; seine Schalter laufen nebenher."
   :lobby/narrator-is        (fn [n] (str "Spielleiter: " n))
   :lobby/you-narrate        "Du bist der Spielleiter — du bekommst keine Rolle."
   :lobby/narrate            "Ich leite das Spiel"
   :lobby/play-along         "Ich spiele mit"
   :lobby/timer              "Zeitlimit"
   :lobby/timer-switch-on    "Zeitlimit einschalten"
   :lobby/timer-switch-off   "Zeitlimit ausschalten"
   :lobby/password           "Passwort"
   :lobby/mode-short-narrated "spielt nicht mit"
   :lobby/mode-short-players "spielt mit"
   :lobby/timer-short-on "an"
   :lobby/timer-short-off "aus"
   :lobby/password-short-on "gesetzt"
   :lobby/password-short-off "keins"
   :lobby/invite-text (fn [code] (str "Spiel mit uns Werwolf — Raum " code))
   :lobby/link-copied "Link kopiert"
   :lobby/share-link "Link teilen"
   :lobby/qr "QR-Code zum Beitreten"
   :lobby/you-are-in "Du bist dabei"
   :lobby/host-starts (fn [n] (str n " startet das Spiel, sobald alle da sind."))
   :lobby/fix-deck "Die Rollen passen noch nicht zum Tisch — siehe Rollen."
   :lobby/password-new       "Neues Passwort"
   :lobby/password-set       "Passwort setzen"
   :lobby/password-change    "Passwort ändern"
   :lobby/password-clear     "Passwort entfernen"
   :lobby/make-narrator      "Zum Spielleiter machen"
   :lobby/make-host          "Zum Spielleiter machen"
   :lobby/seat-menu "Optionen für diesen Platz"

   ;; Die Rollen. Gewählt werden nur die besonderen Karten — die Dorfbewohner
   ;; füllen auf, was übrig bleibt, und sind darum keine Entscheidung.
   :deck/title          "Rollen"
   :deck/auto           "Automatisch"
   :deck/custom         "Eigene Auswahl"
   :deck/auto-hint      "Die Rollen richten sich nach der Zahl der Mitspieler."
   :deck/custom-hint    "Der Spielleiter hat die Rollen gewählt."
   :deck/custom-hint-own "Du hast die Rollen gewählt."
   :deck/fill           "füllen die übrigen Plätze"
   :deck/centre         "Mitte (für den Dieb)"
   :deck/in-game        "Im Spiel"
   :deck/more           (fn [r] (str r " hinzufügen"))
   :deck/less           (fn [r] (str r " entfernen"))
   :deck/only-villagers "nur Dorfbewohner"
   :deck/problem-too-many-roles (fn [roles n] (str "Zu viele Rollen (" roles ") für " n " Spieler"))
   :deck/problem-no-werewolf    "Mindestens ein Werwolf muss mitspielen"
   :deck/problem-no-villager    "Mindestens eine Karte muss zum Dorf gehören"
   :deck/problem-too-many-werewolves "Die Werwölfe müssen weniger sein als alle anderen zusammen"
   :setup/problem               (fn [why] (str "So kann die Nacht nicht beginnen: " why))

   ;; ------------------------------------------------------------ phases ---
   :phase/lobby       "Warteraum"
   :phase/setup       "Rollenvergabe"
   :phase/night       "Nacht"
   :phase/election    "Bürgermeisterwahl"
   :phase/day-reveal  "Morgengrauen"
   :phase/discussion  "Beratung"
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

   :role-desc/werwolf      "Du reißt mit den anderen Wölfen jede Nacht ein Opfer."
   :role-desc/dorfbewohner "Du hast keine Nachtaktion — nur deine Stimme am Tag."
   :role-desc/seherin      "Jede Nacht darfst du die Rolle eines Mitspielers sehen."
   :role-desc/hexe         "Du erfährst nachts, wen die Werwölfe gewählt haben. Einmal im Spiel darfst du heilen, einmal vergiften — auch beides in einer Nacht."
   :role-desc/jaeger       "Stirbst du, nimmst du einen Mitspieler mit."
   :role-desc/weisser-werwolf "Du jagst mit den Wölfen — und jede zweite Nacht reißt du einen von ihnen. Du gewinnst nur allein."
   :role-desc/beschuetzer  "Jede Nacht schützt du einen Mitspieler vor den Werwölfen — nie zweimal hintereinander denselben."
   :role-desc/dieb         "In der ersten Nacht siehst du zwei übrige Karten und darfst eine davon nehmen — oder Dieb bleiben."
   :role-desc/amor         "In der ersten Nacht bestimmst du zwei Verliebte."

   ;; ----------------------------------------------------------- actions ---
   :action/start-game      "Spiel starten"
   :action/play-again      "Nochmal mit allen"
   :action/leave           "Verlassen"
   :action/set-locale      "Sprache"
   :action/chat            "Sagen"
   :action/ready           "Ich bin fertig"
   :action/amor-link       "Verliebte bestimmen"
   :action/seherin-inspect "Rolle ansehen"
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
   :action/vote            "Verurteilen"
   :action/vote-abstain    "Enthalten"
   :action/begin-night     "Erste Nacht beginnen"
   :action/advance-phase   "Phase weiter"
   :action/claim-host      "Spielleiter werden"
   :action/gm-toggle-narrated "Spielleiter-Modus"
   :action/gm-toggle-timer "Zeitlimit an/aus"
   :action/gm-transfer-host "Spielleiter-Rolle übergeben"
   :action/gm-set-deck     "Rollen wählen"
   :action/gm-auto-deck    "Rollen automatisch"
   :action/gm-force-kill   "Töten"
   :action/gm-revive       "Wiederbeleben"
   :action/gm-skip-role    "Rolle überspringen"
   :action/gm-set-phase    "Phase setzen"
   :action/gm-reassign-role "Rolle neu vergeben"
   :action/gm-pause        "Zeit anhalten"
   :action/gm-resume       "Zeit weiterlaufen"
   :action/gm-add-time     "+60 s"

   ;; ------------------------------------------------------- entfernen ---
   ;; Der Host nimmt jemanden aus der Lobby: ein Geisterplatz, ein Tippfehler,
   ;; ein abgelöster Host. Zwei Tipps, wie beim Verlassen.
   :action/gm-kick    "Entfernen"
   :action/gm-add-bot "Bot hinzufügen"
   :kick/button       "Entfernen"
   :kick/confirm      "Wirklich entfernen?"
   :log/kicked        (fn [n] (str n " wurde aus der Runde entfernt"))
   :error/kicked      "Du wurdest aus der Runde entfernt"

   ;; ---------------------------------------------------- Spielleitung ---
   :gm/panel          "Spielleiter"
   :gm/who "Wen?"
   :gm/which-role "Welche Rolle?"
   :gm/which-phase "Welche Phase?"
   :gm/panel-hint     "Nur du siehst diese Schalter. Jeder Eingriff steht in der Chronik."
   :gm/open           "Öffnen"
   :gm/close          "Schließen"
   :gm/pick-player    "Mitspieler"
   :gm/pick-role      "Rolle"
   :gm/pick-phase     "Phase"
   :gm/confirm        "Wirklich?"
   :gm/clock          "Zeit"
   :gm/nothing        "Gerade nichts zu steuern."
   :gm/timer-on       "Zeitlimit einschalten"
   :gm/timer-off      "Zeitlimit ausschalten"

   ;; ------------------------------------------------------- Spielleiter ---
   :narrator/title    "Spielleiter"
   :narrator/desc     "Du spielst nicht mit. Du siehst alle Rollen, rufst die Nacht aus und schaltest die Phasen weiter."
   :narrator/owing        "Wartet noch auf"
   :narrator/owing-none   "Niemand muss gerade etwas tun."
   :narrator/skip-role    (fn [role] (str role " überspringen"))
   :narrator/advance      "Weiter, ohne zu warten"
   :narrator/advance-free "Weiter"
   :narrator/really       (fn [n] (str "Wirklich? " n " " (if (= 1 n) "fehlt" "fehlen") " noch"))
   :narrator/keep-waiting "Doch warten"
   :narrator/night    "Nachtgeschehen"
   :narrator/wolf-votes "Wolfsstimmen"
   :narrator/dying    "Stirbt heute Nacht"
   :narrator/protected "Beschützt"
   :narrator/lovers   "Verliebte"
   :narrator/centre   "Mitte (Dieb)"
   :narrator/nobody   "—"
   :narrator/new      "Neuer Spielleiter"
   :setup/title       "Rollenvergabe"
   :setup/hint        "Alle haben ihre Karte. Du kannst jede Rolle noch ändern — dann beginne die erste Nacht. Bis dahin steht nichts davon in der Chronik."
   :setup/waiting     "Der Spielleiter verteilt die Rollen …"
   :setup/your-card   "Deine Karte kann sich bis zur ersten Nacht noch ändern."

   ;; -------------------------------------------------------------- game ---
   :game/dead          "tot"
   :game/absent        "abwesend"
   :game/left          "gegangen"

   ;; ---------------------------------------------------------- absence ---
   ;;
   ;; Die Spielleitung ist länger weg als die Schonfrist. Zwei Auswege, je
   ;; nachdem, was sicher ist: jemand übernimmt den Platz, oder die Uhr
   ;; übernimmt das Tempo.
   :absence/title      (fn [n] (str "Der Spielleiter (" n ") ist abwesend"))
   :absence/claim-hint "Jemand am Tisch kann Spielleiter werden."
   :absence/clock-hint "Die Uhr hat übernommen: Jede Phase endet mit ihrem Zeitlimit, bis der Spielleiter zurück ist."
   :game/your-role     "Deine Rolle"
   :bar/menu           "Menü"
   :bar/room           "Raum"
   :tab/now            "Jetzt"
   :tab/table          "Mitspieler"
   :game/in-love       "Du bist verliebt in"
   :game/heiltrank     "Heiltrank"
   :game/gifttrank     "Gifttrank"
   :game/knows         "Aufgedeckt"
   :game/log           "Chronik"
   :game/chat          "Gespräch"
   :game/chat-hint     "Nachricht schreiben…"
   :game/chat-closed   "Gerade kannst du nicht schreiben"
   :game/waiting       "Warte auf die anderen…"
   :game/wolves-channel "Nur die Werwölfe hören dich"
   :game/dead-channel   "Nur die Toten hören dich"
   :game/narrator-channel "Alle hören dich — du bist der Spielleiter"
   :chat/tag-wolves "Wölfe"
   :chat/tag-dead "Tote"
   :chat/tag-narrator "SL"
   :game/paused         "Zeit angehalten"
   :game/new-round      "Zurück zum Start"
   :game/play-again-wait "Der Spielleiter kann gleich noch ein Spiel mit euch allen starten."
   :game/dead-hint      "Du bist tot — du schaust nur noch zu."

   ;; ------------------------------------------------------------- turn ---
   ;;
   ;; The banner that says whether the game is waiting for YOU. Its own group
   ;; because "warte auf die anderen" already exists as :game/waiting and these
   ;; are the other half of that sentence.
   :turn/yours   "Du bist dran"
   :turn/hint    "Wähle, was du tust"
   :turn/urgent  "Alle warten auf dich"
   :turn/calling (fn [role] (str "Der Spielleiter ruft: " role))
   ;; Der Spielleiter ist nie "dran" und wartet nie — er führt.
   :turn/narrating "Du leitest das Spiel"

   ;; ------------------------------------------------------------ stage ---
   ;;
   ;; Ein Satz pro Phase, der sagt, was gerade geschieht — für alle, die zum
   ;; ersten Mal mitspielen. Bewusst ohne „Das Dorf" und „Werwölfe": diese
   ;; Wörter gehören den Ergebnissen, und ein Satz, der sie immer enthält,
   ;; macht sie auf dem Bildschirm wertlos.
   :stage/round       (fn [n] (str "Runde " n))
   :stage/called-label "Die Nacht ruft"
   :stage/call-label "Rufe auf, der Reihe nach"
   :turn/asleep "Du schläfst"
   :turn/asleep-next (fn [role] (str "Als Nächstes: " role))
   :board/show (fn [n] (str "Spielbrett zeigen (" n ")"))
   :stage/latest      "Zuletzt"
   :stage/progress    "Ablauf der Runde"
   :stage/interrupted "unterbrochen"
   :sound/turn-off    "Signalton bei Nacht und Tag ausschalten"
   :sound/turn-on     "Signalton bei Nacht und Tag einschalten"
   :explain/setup       "Die Karten sind verteilt. Schau dir deine Rolle an — gleich beginnt die Nacht."
   :explain/setup-narrator "Die Karten sind verteilt. Prüfe die Verteilung, dann beginne die Nacht."
   :explain/night       "Alle schlafen. Wer gerufen wird, wacht auf und handelt, ohne sich zu verraten."
   :explain/day-reveal  "Der Morgen graut. Gleich zeigt sich, was die Nacht angerichtet hat."
   :explain/election    "Wählt einen Bürgermeister. Seine Stimme zählt bei jeder Abstimmung doppelt."
   :explain/discussion  "Redet miteinander: Wem traut ihr nicht? Wer genug geredet hat, tippt auf „Ich bin fertig“."
   :explain/trial       (fn [_] "Redet miteinander und stimmt ab, wer verurteilt wird. Ihr könnt umstimmen, bis alle abgestimmt haben.")
   :explain/first-discussion "Heute stimmt noch niemand ab. Sprecht über die Nacht und darüber, wem ihr traut. Wer genug geredet hat, tippt auf „Ich bin fertig“ – sind alle so weit, bricht die Nacht herein."
   :explain/day-resolve "Das Urteil fällt."
   :explain/reaction    "Ein Toter hat noch eine letzte Handlung. Alle warten auf ihn."
   :explain/reaction-jaeger "Der Jäger ist gestorben und legt noch einmal an. Alle warten auf seinen Schuss."
   :explain/reaction-mayor "Der Bürgermeister ist gestorben und bestimmt, wer sein Amt übernimmt."
   :step/night       "Nacht"
   :step/day-reveal  "Morgen"
   :step/election    "Wahl"
   :step/discussion  "Beratung"
   :step/day-resolve "Urteil"

   ;; ------------------------------------------------------------ voice ---
   ;;
   ;; Joining is an explicit tap: browsers refuse a microphone without a user
   ;; gesture, and opening one because somebody loaded a page is the wrong
   ;; default anyway. `:voice/everyone-hears` is deliberately plain about the
   ;; rule — the server enforces no phase muting, so the UI must not imply it.
   :voice/in-call        "im Gespräch"
   :voice/in-call-list   "Im Gespräch"
   :voice/nobody-in-call "Noch niemand im Gespräch."
   :voice/title          "Sprachchat"
   :voice/join           "🎧 Sprachchat beitreten"
   :voice/joining        "Verbinde…"
   :voice/leave          "Verlassen"
   :voice/mute           "Stumm schalten"
   :voice/unmute         "Stummschaltung aufheben"
   :voice/everyone-hears "Alle am Tisch hören dich — auch nachts, auch als Toter."

   ;; --------------------------------------------------------- targeting ---
   :target/pick-one   "Wähle ein Ziel"
   :target/pick-two   "Wähle zwei Ziele"
   :target/chosen-one "Erster gewählt — jetzt den zweiten"
   :target/confirm (fn [action who] (str action ": " who "?"))
   :target/repick "Anders wählen"
   :target/yes "Ja, sicher"
   :target/cancel     "Abbrechen"
   :target/hint       "Klicke unten auf einen Mitspieler"
   :target/direct (fn [action who] (str action ": " who))
   :target/tap "Oder tipp einfach auf einen Mitspieler"

   ;; ------------------------------------------------------------ badges ---
   :dawn/title "Der Morgen graut"
   :dawn/ok "Weiter"
   :record/title (fn [n] (str "Diese Runde, nach " n " Spielen"))
   :record/won "gewonnen"
   :hexe/victim (fn [who] (str "🐺 Die Werwölfe haben " who " gewählt."))
   :hexe/victim-you "🐺 Die Werwölfe haben dich gewählt."
   :hexe/no-victim "🐺 Heute Nacht stirbt niemand durch die Werwölfe."
   :hexe/healed (fn [who] (str "✓ Du hast " who " gerettet."))
   :hexe/healed-you "✓ Du hast dich selbst gerettet."
   :hexe/done "Fertig"
   :ballot/title     "Abstimmung"
   :ballot/needed    (fn [n] (str "Verurteilt ab " n " Stimmen"))
   :ballot/none      "Noch hat niemand eine Stimme bekommen."
   :ballot/mayor     "Die Stimme des Bürgermeisters zählt doppelt"
   :ballot/abstained (fn [n] (str n (if (= 1 n) " Enthaltung" " Enthaltungen")))
   :badge/votes       "Stimmen"
   :badge/vote        "Stimme"
   :badge/your-vote   "deine Stimme"
   :badge/ready       "fertig"
   :badge/decided     "hat gewählt"
   :badge/mayor       "Bürgermeister"
   :badge/host        "Spielleiter"
   :badge/lover       "verliebt"
   :badge/waiting-for "Es fehlen noch"

   ;; --------------------------------------------------------------- log ---
   :log/show-all      "Alles zeigen"
   :log/show-less     "Nur Wichtiges"
   :log/open          (fn [n] (str "Chronik anzeigen (" n ")"))
   :log/close         "Chronik ausblenden"
   :log/hidden        "weitere Einträge"

   :log/joined        (fn [n] (str n " ist beigetreten"))
   :log/left          (fn [n] (str n " hat die Runde verlassen"))
   :log/disconnected  (fn [n] (str n " ist abwesend"))
   :log/reconnected   (fn [n] (str n " ist zurück"))
   :log/host-changed  (fn [n] (str n " ist jetzt Spielleiter"))
   :log/host-claimed  (fn [n] (str n " wird Spielleiter"))
   :log/host-absent   (fn [n] (str "Der Spielleiter (" n ") ist abwesend"))
   :log/host-returned (fn [n] (str n " ist zurück und leitet wieder"))
   :log/game-started  "Die erste Nacht bricht an"
   :log/game-started-setup "Die Karten sind verteilt — der Spielleiter bereitet die erste Nacht vor"
   :log/narrated-on   "Der Spielleiter spielt nicht mit und führt durchs Spiel"
   :log/narrated-off  "Der Spielleiter spielt mit"
   :log/timer-on      "Die Uhr läuft — jede Phase hat ein Zeitlimit"
   :log/timer-off     "Die Uhr ist aus — der Spielleiter bestimmt das Tempo"
   :log/timer-on-absent "Die Uhr übernimmt — ohne Spielleiter endet jede Phase mit ihrem Zeitlimit"
   ;; Dass sich das Schloss geändert hat, nie der Schlüssel.
   :log/room-locked   "Die Runde ist jetzt mit einem Passwort geschützt"
   :log/room-unlocked "Die Runde braucht kein Passwort mehr"
   :log/deck-set      (fn [d] (str "Der Spielleiter wählt die Rollen: " d))
   :log/deck-auto     "Die Rollen richten sich wieder nach der Zahl der Mitspieler"
   :log/amor-linked   "Amor hat zwei Verliebte bestimmt"
   :log/night-began   "Die Nacht bricht an"
   :log/night-quiet   "In dieser Nacht ist niemand gestorben"
   :log/night-ended   "Der Morgen graut"
   :log/day-began     "Das Dorf versammelt sich"
   :log/ready         (fn [n] (str n " ist fertig"))
   :log/nominate-passed (fn [n] (str n " klagt niemanden an"))
   :log/no-accusation "Das Dorf klagt niemanden an — heute stirbt keiner"
   :log/no-trial      "Am ersten Tag wird noch niemand angeklagt"
   :log/jaeger-held-fire (fn [n] (str n " senkt das Gewehr"))
   :log/vote-cast     (fn [v t] (str v " → " t))
   :log/vote-abstained (fn [v] (str v " enthält sich"))

   :log/died-werwolf  (fn [n] (str n " wurde von den Werwölfen gerissen"))
   :log/died-gift     (fn [n] (str n " wurde vergiftet"))
   :log/died-liebe    (fn [n] (str n " folgt der großen Liebe in den Tod"))
   :log/died-lynch    (fn [n] (str n " wurde vom Dorf gerichtet"))
   :log/died-jaeger   (fn [n] (str n " wurde vom Jäger erschossen"))
   :log/died-weisser  (fn [n] (str n " wurde vom Weißen Werwolf gerissen"))
   :log/died-gm       (fn [n] (str n " wurde vom Spielleiter aus dem Spiel genommen"))
   :log/died-left (fn [n] (str n " hat das Spiel verlassen und scheidet aus"))
   :log/died          (fn [n] (str n " ist gestorben"))

   :log/revived       (fn [n] (str n " lebt wieder"))
   :log/role-revealed (fn [n r] (str n " war " r))
   :log/jaeger-shot   (fn [n t] (str n " erschießt " t))
   :log/lynched       (fn [n] (str "Das Dorf richtet " n))
   :log/tie           "Das Dorf kann sich nicht einigen — niemand stirbt"
   :vote/yours        (fn [n] (str "Deine Stimme: " n " — du kannst noch umstimmen"))
   :vote/yours-abstained "Du hast dich enthalten — du kannst noch umstimmen"
   :log/no-majority   "Keine Mehrheit — das Dorf verurteilt niemanden"
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
   :log/gm-forced     "Der Spielleiter greift ein"
   :log/gm-skip-role  (fn [r] (str "Der Spielleiter überspringt " r))
   :log/gm-set-phase  (fn [p] (str "Der Spielleiter setzt die Phase auf " p))
   :log/gm-reassign-role (fn [n] (str n " hat vom Spielleiter eine neue Rolle bekommen"))
   :log/gm-advance-phase (fn [p] (str "Der Spielleiter beendet: " p))
   :log/clock-paused  "Der Spielleiter hält die Zeit an"
   :log/clock-resumed "Die Zeit läuft weiter"
   :log/win-village   "Das Dorf hat gewonnen"
   :log/win-werewolf  "Die Werwölfe haben gewonnen"
   :log/win-lovers    "Die Verliebten haben gewonnen"
   :log/win-weisser   "Der Weiße Werwolf hat allein gewonnen"
   :log/game-abandoned "Niemand ist zurückgekommen — die Runde ist beendet"
   :log/game-reset     "Noch eine Runde — die Karten werden neu gemischt"

   ;; Plural roll-ups for the Chronik. Separate entries because the verb agrees
   ;; with the subject, which a count spliced into the singular form cannot do.
   :log/group-ready        (fn [names] (str names " sind fertig"))
   :log/group-passed       (fn [names] (str names " klagen niemanden an"))
   :log/group-voted        (fn [names] (str names " haben abgestimmt"))
   :log/group-joined       (fn [names] (str names " sind beigetreten"))
   :log/group-disconnected (fn [names] (str names " sind abwesend"))
   :log/group-reconnected  (fn [names] (str names " sind zurück"))

   ;; ------------------------------------------------------------ recap ---
   ;; Der Rückblick am Spielende: was wirklich geschah. Ganze Sätze wie in der
   ;; Chronik; wo die Chronik schon einen Satz hat (Tod, Schuss, Urteil), wird
   ;; der wiederverwendet — siehe werwolf.i18n.recap.
   :recap/title        "Rückblick"
   :recap/hint         "Jetzt ist alles offen: wer wer war und was jede Nacht wirklich geschah."
   :recap/cast         "Wer war wer"
   :recap/night        (fn [n] (str n ". Nacht"))
   :recap/day          (fn [n] (str n ". Tag"))
   :recap/survived     "überlebt"
   :recap/cause-werwolf "von den Werwölfen gerissen"
   :recap/cause-gift   "vergiftet"
   :recap/cause-liebe  "aus Liebe gestorben"
   :recap/cause-lynch  "vom Dorf gerichtet"
   :recap/cause-jaeger "vom Jäger erschossen"
   :recap/cause-weisser "vom Weißen Werwolf gerissen"
   :recap/cause-gm     "vom Spielleiter aus dem Spiel genommen"
   :recap/cause-left "hat das Spiel verlassen"
   :recap/cause-other  "gestorben"
   :recap/dealt        (fn [r] (str "anfangs " r))
   :recap/lover        "verliebt"
   :recap/mayor        "Bürgermeister"
   :recap/dieb-took    (fn [n r] (str n " nimmt sich die Karte " r))
   :recap/dieb-kept    (fn [n] (str n " bleibt Dieb"))
   :recap/amor-linked  (fn [a b] (str "Amor verliebt " a " und " b))
   :recap/seherin      (fn [n t r] (str n " sieht nach: " t " ist " r))
   :recap/beschuetzer  (fn [n t] (str n " beschützt " t))
   :recap/wolves-chose (fn [t] (str "Die Werwölfe wählen " t))
   :recap/wolves-guarded (fn [t] (str "Die Werwölfe wählen " t " — doch der Beschützer wacht"))
   :recap/wolves-none  "Die Werwölfe einigen sich auf kein Opfer"
   :recap/weisser-killed (fn [n t] (str n " reißt " t " aus dem eigenen Rudel"))
   :recap/weisser-passed (fn [n] (str n " verschont das Rudel"))
   :recap/hexe-healed  (fn [n t] (str n " rettet " t " mit dem Heiltrank"))
   :recap/hexe-poisoned (fn [n t] (str n " vergiftet " t))
   :recap/nominated    (fn [by t] (str by " klagt " t " an"))
   :recap/gm-reassigned (fn [n from to] (str "Der Spielleiter tauscht die Karte von " n ": " from " → " to))
   :recap/votes        (fn [v] (str "Stimmen: " v))
   :recap/abandoned    "Niemand ist zurückgekommen — die Runde wurde ohne Sieger beendet"

   ;; ------------------------------------------------------------ errors ---
   ;; Voice failures. A refused microphone gets its own line because it is fixed
   ;; in browser settings, not by tapping again — and it is by far the common one.
   :error/mic-denied       "Kein Zugriff aufs Mikrofon — bitte in den Browser-Einstellungen erlauben"
   ;; Kein Fehler, den die App beheben kann: ohne HTTPS gibt der Browser gar kein
   ;; Mikrofon her. Betrifft vor allem Handys, die den Rechner über die IP öffnen.
   :error/insecure-context "Der Sprachchat braucht HTTPS — über eine IP-Adresse ohne Zertifikat gibt der Browser kein Mikrofon frei"
   :error/join-failed      "Sprachverbindung fehlgeschlagen"
   :error/no-such-room     "Diesen Raum gibt es nicht"
   :error/game-in-progress "Die Runde läuft schon"
   :error/already-joined   "Du sitzt schon in dieser Runde"
   :error/room-full        "Der Raum ist voll"
   :error/name-required    "Bitte gib einen Namen ein"
   :error/name-taken       "Diesen Namen gibt es in der Runde schon — nimm einen anderen"
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
   :error/not-host         "Nur der Spielleiter darf das"
   :error/password-required "Diese Runde hat ein Passwort — bitte eingeben"
   :error/wrong-password    "Falsches Passwort"
   :error/too-many-attempts "Zu viele Versuche — bitte warte eine Minute"
   :error/already-dead     "Dieser Mitspieler ist schon tot"
   :error/already-alive    "Dieser Mitspieler lebt noch"
   :error/already-paused   "Die Zeit ist schon angehalten"
   :error/not-paused       "Die Zeit läuft bereits"
   :error/no-deadline      "Es läuft gerade keine Zeit"
   :error/not-night        "Das geht nur nachts"
   :error/cannot-advance   "Diese Phase lässt sich nicht weiterschalten"
   :error/no-game-in-progress "Dafür muss das Spiel erst laufen"
   :error/already-that-role "Diese Rolle hat der Mitspieler schon"
   :error/invalid-choice   "Diese Wahl ist nicht erlaubt"
   :error/no-such-player   "Diesen Mitspieler gibt es nicht"
   :error/unknown-role     "Diese Rolle gibt es nicht"
   :error/invalid-deck     "Ungültige Rollenauswahl"
   :error/invalid-count    "Ungültige Anzahl"
   :error/too-many-of-role "So viele Karten dieser Rolle gibt es nicht"
   :error/deck-too-large   "So viele Karten passen an keinen Tisch"
   :error/deck-unplayable  "Mit diesen Rollen kann das Spiel nicht beginnen"
   :error/deal-unplayable  "Mit dieser Verteilung kann die Nacht nicht beginnen"
   :error/distinct-targets-required "Bitte zwei verschiedene Mitspieler"
   :error/unsupported-locale "Diese Sprache gibt es nicht"
   :error/unknown-command  "Unbekannter Befehl"
   :error/internal-error   "Etwas ist schiefgegangen"})
