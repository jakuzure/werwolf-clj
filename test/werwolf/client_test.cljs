(ns werwolf.client-test
  "ClojureScript-only: the client's state reducer.

   Light on purpose — the rules live in the shared engine and are covered there.
   What is worth testing here is exactly what the client decides for itself, and
   that is a short list: which message replaces which state, and when a session
   is worth throwing away.

   `handle-server` is pure, so none of this needs a browser, a socket or
   localStorage."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [werwolf.config :as cfg]
            [werwolf.events :as ev]
            [werwolf.i18n.core :as i18n]
            [werwolf.proto :as proto]
            [werwolf.subs :as sub]
            ;; Required for its side effect of being COMPILED. Nothing here
            ;; calls into it — rendering needs a DOM — but the test build
            ;; otherwise never sees the view layer at all, and a client that
            ;; does not compile passed `bb test:all` green. It was a local
            ;; binding named `set!`, which is a special form.
            [werwolf.views]))

(def db ev/initial-db)

(defn- apply* [db & msgs]
  (reduce ev/handle-server db msgs))

(deftest a-welcome-seats-the-player
  (let [db' (apply* db {:type :server/welcome :room-id "ABCD" :player-id 2
                        :host? false :token "t"})]
    (is (= :room (:screen db')))
    (is (= {:room-id "ABCD" :player-id 2 :host? false} (:me db')))
    (testing "the token is NOT kept in app-db — it lives in localStorage,
              because app-db is what re-frame-10x happily prints to the console"
      (is (not (contains? (:me db') :token))))))

(def seated
  "A db holding a seat, which is the precondition for a view meaning anything."
  (apply* db {:type :server/welcome :room-id "ABCD" :player-id 0
              :host? true :token "t"}))

(deftest views-replace-state-but-never-go-backwards
  (let [v1 {:type :room/view :seq 5 :view {:phase :night}}
        v2 {:type :room/view :seq 9 :view {:phase :discussion}}]
    (is (= :discussion (get-in (apply* seated v1 v2) [:view :phase])))
    (testing "an older view arriving late must not overwrite a newer one —
              the room broadcasts outside a single atomic step, so this happens"
      (is (= :discussion (get-in (apply* seated v2 v1) [:view :phase]))))))

(deftest a-view-without-a-seat-is-not-a-room

  (testing "the server drops a socket from a room's :sinks when it CLOSES, not
            when the player leaves, so the commit recording :player/left still
            broadcasts one last view to the player who just left. A view used to
            set :screen :room unconditionally, which put them straight back into
            the room they had walked out of — leaving took two clicks, and the
            second only worked because by then there was nothing left to send."
    (let [after-leave (assoc db :screen :landing :me nil)
          trailing    {:type :room/view :seq 99 :view {:phase :discussion}}
          db'         (apply* after-leave trailing)]
      (is (= :landing (:screen db')) "a trailing view must not re-enter the room")
      (is (nil? (:view db')) "nor install state for a room we are not in"))))

(deftest a-fresh-room-is-not-judged-by-the-last-rooms-sequence
  (testing "every room numbers its views from its own beginning, so a high-water
            mark carried across would silently reject the new room's views and
            leave you on an empty room screen"
    (let [long-game (apply* seated {:type :room/view :seq 400 :view {:phase :night}})
          rejoined  (apply* long-game
                            {:type :server/welcome :room-id "WXYZ" :player-id 1
                             :host? false :token "t2"}
                            {:type :room/view :seq 2 :view {:phase :lobby}})]
      (is (= "WXYZ" (:room-id (:me rejoined))))
      (is (= :lobby (get-in rejoined [:view :phase]))
          "the new room's view was accepted despite a lower seq")
      (is (= 2 (:view/seq rejoined))))))

(deftest leaving-clears-the-sequence-too
  (testing "otherwise the reset is incomplete and the next room inherits it"
    (let [played (apply* seated {:type :room/view :seq 77 :view {:phase :vote}})
          out    (:db (ev/leave-fx {:db played} nil))]
      (is (= :landing (:screen out)))
      (is (nil? (:me out)))
      (is (nil? (:view out)))
      (is (nil? (:view/seq out))))))

(deftest chat-history-replaces-and-live-chat-appends
  (let [db' (apply* db {:type :room/chat-history
                        :messages [{:from 0 :text "alt"}]})]
    (is (= 1 (count (:chat db'))))
    (let [db'' (apply* db' {:type :room/chat :from 1 :text "neu"})]
      (is (= ["alt" "neu"] (mapv :text (:chat db'')))))))

(deftest the-chat-buffer-is-bounded
  (testing "a long game must not turn into an unbounded array"
    (let [db' (apply* db (assoc {:type :room/chat-history} :messages []))
          filled (reduce (fn [d i] (ev/handle-server d {:type :room/chat :text (str i)}))
                         db' (range 400))]
      (is (= 300 (count (:chat filled))))
      (is (= "399" (:text (last (:chat filled)))) "the newest survives"))))

(deftest a-fatal-error-throws-the-session-away
  (doseq [code ev/fatal-errors]
    (let [db' (apply* db
                      {:type :server/welcome :room-id "ABCD" :player-id 0 :host? true}
                      {:type :room/view :seq 1 :view {:phase :night}}
                      {:type :server/error :code code})]
      (is (= :landing (:screen db')) (str code " should return to the landing screen"))
      (is (nil? (:me db')))
      (is (nil? (:view db')) "and the stale game must not stay on screen"))))

(deftest an-ordinary-rejection-keeps-you-in-the-game
  (testing "an illegal move is a message, not a logout"
    (let [db' (apply* db
                      {:type :server/welcome :room-id "ABCD" :player-id 0 :host? true}
                      {:type :room/view :seq 1 :view {:phase :vote}}
                      {:type :server/error :code :invalid-target})]
      (is (= :room (:screen db')))
      (is (= :invalid-target (:error db')))
      (is (some? (:view db'))))))

(deftest an-unknown-message-is-ignored-not-fatal
  (testing "a newer server may say things this client has never heard of"
    (is (= db (apply* db {:type :server/something-from-the-future :payload 42})))))

;; ------------------------------------------------------- the armed action ---

(deftest an-empty-form-field-does-not-look-like-an-armed-action
  (testing "the phantom target dialog: ::form-field defaults to \"\" so it can
            back controlled inputs, and \"\" is TRUTHY in ClojureScript. Reading
            the armed action through it made `when-let` fire on every render, so
            the amber 'choose a target' bar appeared before anyone had clicked."
    (is (nil? (sub/armed-action (:form ev/initial-db)))
        "a fresh form arms nothing")
    (is (nil? (sub/armed-action {}))
        "and neither does a missing key")
    (testing "the exact value that broke it"
      (is (nil? (sub/armed-action {:pending ""})))
      (is (some? "") "\"\" is truthy in ClojureScript — that WAS the bug"))
    (testing "and any other wrong shape"
      (is (nil? (sub/armed-action {:pending :vote})))
      (is (nil? (sub/armed-action {:pending [:vote]}))))
    (testing "while a real armed action comes through untouched"
      (is (= {:action :vote :picks []}
             (sub/armed-action {:pending {:action :vote :picks []}}))))))

;; ------------------------------------------------------- the leave confirm ---

(deftest leaving-asks-twice-and-forgets-that-it-asked
  (let [lobby {:phase :lobby}]
    (is (not (sub/leave-armed? (:form ev/initial-db) lobby)) "a fresh form asks nothing")
    (is (not (sub/leave-armed? {:leave ""} lobby)) "\"\" is truthy, still not armed")
    (is (sub/leave-armed? {:leave :lobby} lobby))
    (testing "armed in one phase means nothing in the next — the game starting
              is a screen change, and must not carry a 'really?' along"
      (is (not (sub/leave-armed? {:leave :lobby} {:phase :night})))))
  (let [armed (-> seated
                  (assoc-in [:form :leave] :lobby)
                  (assoc-in [:form :name] "Anna"))]
    (testing "leaving disarms it, or the next room's button opens on 'really?'"
      (is (not (contains? (:form (:db (ev/leave-fx {:db armed} nil))) :leave))))
    (testing "and so does sitting down anywhere, since a fatal error drops the
              session without passing through leave-fx"
      (let [db' (apply* armed {:type :server/welcome :room-id "WXYZ" :player-id 1
                               :host? false :token "t2"})]
        (is (not (contains? (:form db') :leave)))
        (is (= "Anna" (get-in db' [:form :name])) "and nothing else in the form")))))

;; -------------------------------------------------------------- room list ---

(deftest the-room-list-replaces-wholesale
  (testing "a list that merged would keep showing a game that has started"
    (let [db' (apply* db {:type :server/rooms
                          :rooms [{:room-id "ABCD" :players 2 :max-players 18
                                   :host "Anna"}]})]
      (is (= 1 (count (:rooms db'))))
      (is (= [] (:rooms (apply* db' {:type :server/rooms :rooms []})))))))

(deftest joining-from-the-list-still-needs-a-name
  (testing "the same guard as typing the code, or the tap silently does nothing"
    (is (= :name-required (:error (:db (ev/join-listed-fx {:db db} [nil "ABCD"])))))
    (let [named (assoc-in db [:form :name] "Anna")
          out (ev/join-listed-fx {:db named} [nil "ABCD"])]
      (is (= "ABCD" (:room-id (:werwolf.ws/send out))))
      (is (= "Anna" (:name (:werwolf.ws/send out))))
      (is (= "ABCD" (get-in out [:db :form :code]))
          "the code field follows the tap, so the screen agrees with itself"))))

(deftest the-room-list-keeps-itself-current-on-the-landing-screen
  (testing "it was asked for on connect, after leaving and on a tap of refresh —
            so a room opened a minute after you arrived was simply not there,
            which reads as 'the others cannot be found'"
    (let [landing (assoc db :conn/status :connected :now 100000)
          out (ev/poll-rooms-fx {:db landing} nil)]
      (is (= {:type :client/rooms} (:werwolf.ws/send out)))
      (testing "and not again until the interval has passed — it rides a tick
                that fires twice a second"
        (let [db' (:db out)]
          (is (nil? (ev/poll-rooms-fx {:db (assoc db' :now (+ 100000 1000))} nil)))
          (is (some? (:werwolf.ws/send
                      (ev/poll-rooms-fx {:db (assoc db' :now (+ 100000 ev/rooms-poll-ms))}
                                        nil)))))))
    (testing "not while seated: the list is for choosing a room, and an answer
              nobody looks at is a message for nothing"
      (is (nil? (ev/poll-rooms-fx {:db (assoc seated :conn/status :connected
                                              :now 100000)} nil))))
    (testing "and not while disconnected, where every ask would pile up in the
              outbox and all go out at once on reconnect"
      (is (nil? (ev/poll-rooms-fx {:db (assoc db :conn/status :disconnected
                                              :now 100000)} nil))))))

;; ------------------------------------------------------- joining robustly ---

(def named (assoc-in db [:form :name] "Anna"))

(deftest a-second-tap-on-create-or-join-sends-nothing
  (testing "two taps on a slow connection sent two requests; the first seated
            you and the second came back :already-in-room"
    (let [once (ev/create-room-fx {:db named} nil)]
      (is (some? (:werwolf.ws/send once)))
      (is (nil? (:werwolf.ws/send (ev/create-room-fx {:db (:db once)} nil))))
      (is (nil? (:werwolf.ws/send (ev/join-listed-fx {:db (:db once)} [nil "ABCD"])))
          "nor does a different button while the first is in flight"))
    (let [coded (assoc-in named [:form :code] "abcd")
          once (ev/join-room-fx {:db coded} nil)]
      (is (some? (:werwolf.ws/send once)))
      (is (nil? (:werwolf.ws/send (ev/join-room-fx {:db (:db once)} nil)))))
    (testing "the answer, either way, frees the buttons again"
      (let [pending (:db (ev/create-room-fx {:db named} nil))]
        (is (not (:seat/pending? (apply* pending {:type :server/welcome :room-id "ABCD"
                                                  :player-id 0 :host? true}))))
        (is (not (:seat/pending? (apply* pending {:type :server/error
                                                  :code :room-full}))))
        (testing "as does losing the socket, or a request lost with it would
                  leave them locked until a reload"
          (is (not (:seat/pending? (ev/disconnected-db pending)))))))))

(deftest already-in-room-is-not-an-error-when-you-are-in-the-room
  (testing "a join queued while reconnecting goes out after the stored seat's
            resume, which seats you first — the refusal that follows is about a
            seat you have, and a banner over it said the opposite"
    (let [db' (apply* seated {:type :server/error :code :already-in-room})]
      (is (nil? (:error db')))
      (is (= :room (:screen db'))))
    (testing "while on the landing screen it still says so"
      (is (= :already-in-room
             (:error (apply* db {:type :server/error :code :already-in-room})))))))

(deftest being-released-from-another-tab-lands-you-on-the-landing-screen
  (testing "leaving in one tab releases every tab at that seat; the other one
            used to sit on a lobby that no longer answered"
    (let [db' (apply* seated {:type :room/view :seq 3 :view {:phase :lobby}}
                      {:type :server/left :room-id "ABCD"})]
      (is (= :landing (:screen db')))
      (is (nil? (:me db')))
      (is (nil? (:view db')))
      (is (nil? (:view/seq db'))))
    (testing "and forgets the seat, hangs up and asks what else is open"
      (let [out (ev/server-message-fx {:db seated}
                                      [nil {:type :server/left :room-id "ABCD"}])]
        (is (true? (:werwolf.ws/forget-seat out)))
        (is (true? (:werwolf.audio/disconnect out)))
        (is (= [::ev/refresh-rooms] (:dispatch out)))))
    (testing "but one about a room you are not in changes nothing — it is the
              echo of your own leave arriving after you sat down elsewhere"
      (let [elsewhere (apply* db {:type :server/welcome :room-id "WXYZ" :player-id 1
                                  :host? false :token "t"})]
        (is (= elsewhere (apply* elsewhere {:type :server/left :room-id "ABCD"})))
        (is (nil? (:werwolf.ws/forget-seat
                   (ev/server-message-fx {:db elsewhere}
                                         [nil {:type :server/left :room-id "ABCD"}]))))))))

;; ------------------------------------------------------- typing on a phone ---

(deftest typing-does-not-bump-a-field-generation-but-writing-to-it-does
  (testing "A text input keeps a LOCAL draft while you type, because rf/dispatch
            is asynchronous and a fully controlled :value lags the DOM by a tick.
            React writes that stale value back, and on Android it lands inside an
            active IME composition: accepting a keyboard suggestion appended
            instead of replacing, so typing 'Greta' and tapping the suggestion
            produced 'GrGrGreta'.

            The generation counter is how the draft learns to step aside. Typing
            must NOT bump it — the element already shows the character, app-db is
            merely catching up — and a programmatic write MUST, or the draft
            outranks it forever."
    (let [typed (ev/form-put db :text "")          ; establish a baseline
          gen0  (get-in typed [:form/gen :text])]
      (testing "typing leaves the generation alone"
        (let [after (reduce (fn [d ch] (assoc-in d [:form :text] ch))
                            typed ["G" "Gr" "Gre" "Gret" "Greta"])]
          (is (= gen0 (get-in after [:form/gen :text])))))
      (testing "a programmatic write bumps it, so the input adopts the new value"
        (is (= (inc gen0) (get-in (ev/form-put typed :text "") [:form/gen :text]))))
      (testing "and each write bumps it again — a second clear is still a signal"
        (is (= (+ gen0 2) (-> typed
                              (ev/form-put :text "")
                              (ev/form-put :text "")
                              (get-in [:form/gen :text]))))))))

(deftest sending-a-message-clears-the-box-even-though-it-has-focus
  (testing "the chat input normally HAS focus when Enter fires, so its local
            draft outranks app-db. Clearing with a plain assoc left the message
            you just sent sitting in the box."
    (let [typed (assoc-in db [:form :text] "wer war das?")
          out   (ev/send-chat-fx {:db typed} nil)]
      (is (= "wer war das?" (:text (:werwolf.ws/send out))) "it was sent")
      (is (= "" (get-in out [:db :form :text])) "and the field was cleared")
      (is (pos? (get-in out [:db :form/gen :text]))
          "with a generation bump, which is what makes the draft yield"))))

(deftest a-blank-message-is-not-sent-and-clears-nothing
  (is (nil? (ev/send-chat-fx {:db (assoc-in db [:form :text] "   ")} nil))))

;; -------------------------------------------------------------------- voice ---

(deftest audio-off-is-an-answer-not-a-failure
  (testing "a text-only server is a supported configuration, so the client records
            that audio is unavailable and the UI omits the panel — rather than
            showing a button that cannot work"
    (let [db' (apply* seated {:type :server/media :enabled? false})]
      (is (false? (get-in db' [:audio :available?])))
      (is (nil? (:error db')) "and it is not surfaced as an error"))))

(deftest credentials-are-stored-but-nothing-is-joined-yet
  (testing "joining opens a microphone, so it waits for a tap: browsers refuse
            getUserMedia without a user gesture, and opening someone's mic
            because they loaded a page is the wrong default regardless"
    (let [db' (apply* seated {:type :server/media :enabled? true
                              :url "ws://localhost:7880" :room "werwolf-abcd"
                              :token "eyJhbGciOi"})]
      (is (true? (get-in db' [:audio :available?])))
      (is (= "werwolf-abcd" (get-in db' [:audio :room])))
      (is (= :idle (get-in db' [:audio :state])) "idle until asked")
      (is (false? (get-in db' [:audio :muted?]))))))

(deftest leaving-hangs-up
  (testing "without this the microphone stays published into a room the player
            has walked out of — audible to a table they are no longer at, which
            is the worst available bug in a game about who knows what"
    (let [joined (-> seated
                     (apply* {:type :server/media :enabled? true
                              :url "ws://x" :room "werwolf-abcd" :token "t"})
                     (assoc-in [:audio :state] :joined))
          out (ev/leave-fx {:db joined} nil)]
      (is (true? (:werwolf.audio/disconnect out)) "the effect is requested")
      (is (nil? (get-in out [:db :audio]))
          "and the credential does not survive into the next room"))))

(deftest speaking-is-matched-by-seat-not-by-name
  (testing "the SFU reports identities of the form werwolf-<code>-p<seat>, and
            seat 1 must not light up because seat 11 is talking"
    (let [speakers #{"werwolf-abcd-p1" "werwolf-abcd-p11"}]
      (is (true? (sub/speaking? speakers 1)))
      (is (true? (sub/speaking? speakers 11)))
      (is (false? (sub/speaking? speakers 2))))
    (testing "and nothing is speaking when nobody is"
      (is (false? (sub/speaking? #{} 0)))
      (is (false? (sub/speaking? #{"werwolf-abcd-p0"} nil))))))

;; ------------------------------------------------------------ where we dial ---

(deftest the-socket-follows-the-page-never-localhost
  (testing "the dev build pinned ws://localhost:3000, which works on exactly the
            machine running the server. On a phone the client dialled the phone,
            the socket never opened, and every tap went into the outbox instead
            of onto the wire — the buttons looked broken"
    (is (= "ws://192.168.1.40:3000/api/ws"
           (cfg/ws-url-for {:protocol "http:" :hostname "192.168.1.40" :port "3000"})))
    (testing "https means wss, or a browser refuses the upgrade outright"
      (is (= "wss://werwolf.example.com/api/ws"
             (cfg/ws-url-for {:protocol "https:" :hostname "werwolf.example.com"
                              :port ""}))))
    (testing "shadow's dev-http serves on 8080 and proxies /api, so the socket
              hops to the game server — by hostname, still not by localhost"
      (is (= "ws://192.168.1.40:3000/api/ws"
             (cfg/ws-url-for {:protocol "http:" :hostname "192.168.1.40" :port "8080"}))))
    (testing "and no host reachable from another device ever resolves to this one"
      (doseq [host ["192.168.1.40" "werwolf.example.com" "mac-mini.local"]]
        (is (not (str/includes? (cfg/ws-url-for {:protocol "http:" :hostname host
                                                 :port "8080"})
                                "localhost")))))))

(deftest an-empty-field-says-so-instead-of-doing-nothing
  (testing "tapping a button that silently drops the click is indistinguishable
            from a broken button, which is how it was reported"
    (is (= :name-required (:error (:db (ev/create-room-fx {:db db} nil)))))
    (is (= :name-required (:error (:db (ev/join-room-fx {:db db} nil)))))
    (testing "a name but no code is the other half of the same trap"
      (let [named (assoc-in db [:form :name] "Anna")]
        (is (= :code-required (:error (:db (ev/join-room-fx {:db named} nil)))))
        (testing "and with both, it actually sends"
          (let [ready (assoc-in named [:form :code] "abcd")
                out (ev/join-room-fx {:db ready} nil)]
            (is (nil? (:error (:db out))))
            (is (= "ABCD" (:room-id (:werwolf.ws/send out)))
                "upper-cased, because nobody types a code the way it was printed")))))
    (testing "and a real name creates a room rather than complaining"
      (let [out (ev/create-room-fx {:db (assoc-in db [:form :name] "Anna")} nil)]
        (is (nil? (:error (:db out))))
        (is (= "Anna" (:name (:werwolf.ws/send out))))
        (is (= :de (:locale (:werwolf.ws/send out))) "German by default")))))

;; ---------------------------------------------------------------- GM console ---

(deftest the-gm-console-guards-its-scratch-state-too
  (testing "same trap as the armed action, one panel over: \"\" is truthy, and
            the GM console reads maps out of the same form map"
    (is (= {} (sub/gm-console (:form ev/initial-db))))
    (is (= {} (sub/gm-console {:gm ""})))
    (is (= {} (sub/gm-console {:gm :open})))
    (is (= {:open? true} (sub/gm-console {:gm {:open? true}})))))

(deftest an-overrides-choice-travels-under-the-key-the-protocol-names
  (testing ":target means a player id everywhere. The overrides that name a
            phase or a role need a different key, and the client must not be
            the place that decides which — it reads proto/choice-key"
    (is (= :phase (proto/choice-key :gm/set-phase)))
    (is (= :role (proto/choice-key :gm/skip-role)))
    (is (= :role (proto/choice-key :gm/reassign-role)))
    (testing "and an override that names only a seat has no choice key at all"
      (is (nil? (proto/choice-key :gm/force-kill)))
      (is (nil? (proto/choice-key :gm/revive))))
    (testing "every key it names is one the wire schema actually carries"
      (doseq [[action k] proto/choice-key]
        (is (contains? proto/command-types action) (str action " is not a command"))
        (is (proto/valid-client-message?
             {:type :client/command :cmd {:type action k (if (= :phase k) :vote :hexe)}})
            (str action " cannot be expressed with " k))))))

;; ------------------------------------------------------------- the Chronik ---

(def ^:private quiet-day
  "What a real six-player day with no accusation actually emits. Taken from a
   dump of the engine's log, which is where the 'duplicated history' complaint
   came from: ten near-identical lines around one that matters."
  [{:type :night/ended :night 1}
   {:type :player/died :player 3 :cause :werwolf :role :dorfbewohner}
   {:type :day/began :day 1}
   {:type :player/ready :player 0} {:type :player/ready :player 1}
   {:type :player/ready :player 2} {:type :player/ready :player 4}
   {:type :player/ready :player 5}
   {:type :nominate/passed :player 0} {:type :nominate/passed :player 1}
   {:type :nominate/passed :player 2} {:type :nominate/passed :player 4}
   {:type :nominate/passed :player 5}
   {:type :day/no-accusation :day 1}
   {:type :vote/resolved :target nil :tie? false}
   {:type :night/began :night 2}])

(def ^:private names
  (into {} (map (fn [i] [i {:name (str "P" i)}])) (range 6)))

(deftest the-chronicle-hides-bookkeeping-by-default
  (let [rows (i18n/chronicle :de names quiet-day {})
        texts (mapv :text rows)]
    (is (not-any? #(re-find #"ist fertig|sind fertig" %) texts)
        "ten ready/pass lines must not bury the one death")
    ;; Asserted by tone and name rather than by a German word: the death lines
    ;; are whole sentences per cause, so the wording is the dictionary's business
    ;; and this test's business is that the death survived the filter.
    (is (some #(and (= :death (:tone %)) (re-find #"P3" (:text %))) rows)
        "the one death is still there")
    (testing "and the redundant verdict is demoted — :day/no-accusation already
              said nobody died, so :vote/resolved with no target repeats it"
      (is (= 1 (count (filter #(re-find #"niemanden an|keiner" %) texts)))))))

(deftest the-chronicle-collapses-runs-and-names-them
  (let [rows (i18n/chronicle :de names quiet-day {:show-all? true})
        ready (first (filter #(re-find #"sind fertig" (:text %)) rows))]
    (is (some? ready) "the run became one row")
    (is (= 5 (:count ready)))
    (is (= "P0, P1, P2, P4, P5 sind fertig" (:text ready))
        "named rather than counted: '5 × P5 ist fertig' reads as a stutter")
    (testing "and nothing is lost — every entry is still reachable"
      (is (some #(re-find #"klagen niemanden an" (:text %)) rows)))))

(deftest the-chronicle-is-newest-first
  (let [rows (i18n/chronicle :de names quiet-day {})]
    (is (re-find #"Nacht" (:text (first rows))))))

(deftest a-tie-is-loud-but-an-empty-vote-is-not
  (testing "a tie is a real outcome the table needs to see; 'nobody voted' is not"
    (is (= :verdict (i18n/log-tone {:type :vote/resolved :target nil :tie? true})))
    (is (= :verdict (i18n/log-tone {:type :vote/resolved :target 2 :tie? false})))
    (is (= :muted (i18n/log-tone {:type :vote/resolved :target nil :tie? false})))))

;; The dictionaries themselves — completeness, both languages, and coverage of
;; every event a real game logs — are tested in werwolf.i18n-test, which is .cljc
;; and therefore runs here too. Nothing about them is client-specific, so
;; restating a weaker version of those checks in this file would only give two
;; places to update.

(deftest names-are-data-not-language
  (testing "the log holds ids, so renaming yourself must not rewrite history —
            and an unnamed id still has to be identifiable"
    (is (= "#7 ist beigetreten"
           (i18n/log-line :de {} {:type :player/joined :player 7})))
    (is (= "#7 joined"
           (i18n/log-line :en {} {:type :player/joined :player 7})))))

;; ------------------------------------------------------- errors expire ---

(deftest an-error-retires-itself
  (testing "a banner from two minutes ago is just occupying the top of a phone
            screen — these are all transient and none needs acknowledging"
    (let [tick (fn [db] (ev/tick-db db))
          shown (tick (assoc db :error :name-required))]
      (is (= :name-required (:error shown)) "still readable on the tick that stamps it")
      (is (some? (:error/at shown)) "and its clock has started")
      (testing "and it is gone once it has been up long enough"
        (let [old (assoc shown :error/at (- (:error/at shown) (inc ev/error-ttl-ms)))]
          (is (nil? (:error (tick old)))))))))

(deftest a-newer-error-gets-its-own-full-time
  (testing "otherwise a second failure inherits the remains of the first one's
            clock and can vanish before it is read"
    (let [tick (fn [db] (ev/tick-db db))
          first-err (tick (assoc db :error :name-required))
          aged (assoc first-err :error/at (- (:error/at first-err) (dec ev/error-ttl-ms)))
          second-err (tick (assoc aged :error :code-required))]
      (is (= :code-required (:error second-err)))
      (is (= :code-required (:error/shown second-err)) "restamped, not inherited")
      (is (> (:error/at second-err) (:error/at aged))))))

(deftest dismissing-clears-the-bookkeeping
  (testing "or the next identical error is treated as still-showing and retires early"
    (let [db' (ev/dismiss-db (assoc db :error :not-allowed :error/shown :not-allowed
                                    :error/at 1))]
      (is (nil? (:error db')))
      (is (nil? (:error/shown db')))
      (is (nil? (:error/at db'))))))

(deftest the-narrator-is-never-waited-for
  (testing "he has a seat and is never killed, so he looks like a living player
            who has not voted — and a narrated day would read as one ballot
            short forever"
    (let [view {:phase :vote
                :narrator 0
                :players {0 {:name "SL" :alive? true}
                          1 {:name "Anna" :alive? true}
                          2 {:name "Bob" :alive? true}}
                :votes {1 2}}]
      (is (= ["Bob"] (sub/waiting-names view)))
      (testing "while in a room the host plays in, the host is waited for like anyone"
        (is (= ["Bob" "SL"] (sub/waiting-names (dissoc view :narrator))))))))

(deftest the-round-strip-lights-the-current-step
  (let [states #(into {} (map (juxt :step :state)) (sub/round-steps %))]
    (testing "the election is on the first day only"
      (is (contains? (states {:phase :discussion :night 1}) :election))
      (is (not (contains? (states {:phase :discussion :night 2}) :election))))
    (testing "steps before the current one are done, after it to come"
      (is (= {:night :done :day-reveal :done :discussion :done :nomination :current
              :vote :todo :day-resolve :todo}
             (states {:phase :nomination :night 3}))))
    (testing "a reaction lights the step it interrupted, read off the log"
      (is (= :current (:day-reveal (states {:phase :reaction :night 2
                                            :log [{:type :night/began} {:type :night/ended}]}))))
      (is (= :current (:day-resolve (states {:phase :reaction :night 2
                                             :log [{:type :night/ended} {:type :day/began}]}))))
      (is (:interrupted? (first (filter #(= :current (:state %))
                                        (sub/round-steps {:phase :reaction :night 2 :log []}))))))
    (testing "no strip before the first night or after the end"
      (is (nil? (sub/round-steps {:phase :setup :night 0})))
      (is (nil? (sub/round-steps {:phase :game-over :night 4}))))))

(deftest the-room-light-follows-the-phase
  (is (= :night (sub/ambience :night)))
  (is (= :day (sub/ambience :vote)))
  (is (= :day (sub/ambience :reaction)))
  (is (= :end (sub/ambience :game-over)))
  (is (= :neutral (sub/ambience :setup))))

(deftest the-chronik-starts-folded
  (testing "closed by default, narrated or not, until this player opens it"
    (is (false? (sub/chronik-open? nil)))
    (is (true? (sub/chronik-open? true)))))

(deftest the-latest-news-is-what-mattered
  (let [died  {:type :player/died :player 1 :cause :werwolf}
        quiet {:type :night/quiet :night 2}
        noise {:type :player/ready :player 2}]
    (testing "the newest run of deaths and verdicts, oldest first"
      (is (= [died died] (sub/latest-news [{:type :night/began} died died {:type :day/began}]))))
    (testing "bookkeeping after it does not bury it"
      (is (= [quiet] (sub/latest-news [died {:type :night/began} quiet noise noise]))))
    (testing "nothing to report is nil, not an empty line"
      (is (nil? (sub/latest-news [{:type :game/started} noise]))))))

(deftest the-table-hears-when-you-join-the-call
  (let [seated (assoc db :me {:room-id "ABCD" :player-id 1} :audio {:state :joining})]
    (testing "joining is reported while seated"
      (is (= {:type :client/voice :joined? true}
             (:werwolf.ws/send (ev/audio-joined-fx {:db seated} nil)))))
    (testing "a dropped call is reported, a call never joined is not"
      (is (= {:type :client/voice :joined? false}
             (:werwolf.ws/send (ev/audio-left-fx {:db (assoc-in seated [:audio :state] :joined)} nil))))
      (is (nil? (:werwolf.ws/send (ev/audio-left-fx {:db seated} nil)))))
    (testing "hanging up on the way OUT of a room reports nothing — there is no
              seat left to report for, and the error would greet the landing page"
      (is (nil? (:werwolf.ws/send (ev/audio-left-fx {:db (assoc db :audio {:state :joined})} nil)))))))

(deftest a-reconnect-keeps-a-live-call
  (testing "the welcome after a socket drop asks for credentials again, and that
            used to reset a call that never dropped to 'join'"
    (let [in-call (assoc db :audio {:available? true :state :joined :muted? true :speakers #{2}})
          db' (ev/media-fx in-call {:enabled? true :url "u" :token "t2" :room "r"})]
      (is (= :joined (get-in db' [:audio :state])))
      (is (true? (get-in db' [:audio :muted?])))
      (is (= "t2" (get-in db' [:audio :token])) "while the credential is refreshed")))
  (testing "and the new socket is told again that this page is in the call"
    (let [in-call (assoc db :audio {:state :joined})
          fx (ev/server-message-fx {:db in-call} [nil {:type :server/welcome :room-id "ABCD"
                                                       :player-id 1 :token "x"}])]
      (is (= {:type :client/voice :joined? true} (:werwolf.ws/send fx))))))

(deftest the-absent-host-banner-says-only-what-the-view-says
  (let [base {:host 0 :players {0 {:name "Anna"} 1 {:name "Bob"}}
              :phase :night :narrated? true :actions #{:chat}}]
    (is (nil? (sub/host-absence base)) "nothing to say while he is here")
    (testing "a narrated game under way: the clock took over, nobody may claim"
      (is (= {:name "Anna" :clock? true :claim? false}
             (sub/host-absence (assoc base :host-absent? true)))))
    (testing "a lobby: the seat is offered, and the button follows :actions"
      (is (= {:name "Anna" :clock? false :claim? true}
             (sub/host-absence (assoc base :host-absent? true :phase :lobby
                                      :actions #{:chat :claim-host})))))
    (testing "a game the host played: no clock line, even with a clock running"
      (is (false? (:clock? (sub/host-absence (assoc base :host-absent? true
                                                    :narrated? false :timer? true))))))))

