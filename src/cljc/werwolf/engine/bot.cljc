(ns werwolf.engine.bot
  "Seats the server plays.

   A bot is an ordinary seat with nobody behind it, there so one person can
   play and test a whole game alone. Its moves live here, in .cljc, because
   they are tested on both platforms like the rest of the engine, and because
   a bot decides from the same redacted view a browser gets — never from state.
   For now: the names. The moves follow."
  (:require [clojure.string :as str]))

(def names
  "German first names for bot seats, in the order they are handed out. Twenty,
   so a full table of eighteen never runs dry. First names rather than 'Bot 1':
   the chronicle and the recap read like a table, not like a log."
  ["Agnes" "Bruno" "Clara" "Dieter" "Elke" "Falk" "Greta" "Hanno" "Ida" "Jonas"
   "Käthe" "Lutz" "Mia" "Nils" "Ottilie" "Paul" "Rosa" "Sven" "Thea" "Ulf"])

(def ^:private suffix
  "Same word in both languages, so the name needs no translation and the
   chronicle says who is a bot without any client knowing."
  " (Bot)")

(defn next-name
  "The first bot name not at the table, or nil when all twenty are.

   `taken` holds the names already seated, lower-cased and trimmed, which is
   exactly how the join rule compares them — so a human who called themselves
   'agnes (bot)' pushes the bot to the next name rather than tripping that rule."
  [taken]
  (first (remove #(contains? taken (str/lower-case %))
                 (map #(str % suffix) names))))
