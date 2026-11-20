(ns sprites
  "Turn the pixel sprites in art/sprites.edn into SVGs under
   resources/public/img/sprites/. Run with `bb sprites`.

   Drawn as text rather than in an editor so a sprite is reviewable in a diff
   and needs no tool beyond this one. Each row is one line of characters, each
   character a colour from the palette, `.` transparent."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def src "art/sprites.edn")
(def out "resources/public/img/sprites")

(defn- runs
  "A row as [start length char] runs, transparent ones left out, so a sprite
   is a few dozen rects instead of one per pixel."
  [row]
  (->> (partition-by identity row)
       (reduce (fn [[x acc] run]
                 [(+ x (count run))
                  (if (= \. (first run)) acc (conj acc [x (count run) (first run)]))])
               [0 []])
       second))

(defn svg
  "One sprite as an SVG whose viewBox is its grid, so it scales to any size
   and stays sharp."
  [palette title rows]
  (let [w (count (first rows))
        h (count rows)]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " w " " h "\""
         " shape-rendering=\"crispEdges\" role=\"img\"><title>" title "</title>"
         (str/join
          (for [[y row] (map-indexed vector rows)
                [x n c] (runs row)]
            (str "<rect x=\"" x "\" y=\"" y "\" width=\"" n "\" height=\"1\" fill=\""
                 (or (palette c) (throw (ex-info (str title ": no colour for " c) {})))
                 "\"/>")))
         "</svg>\n")))

(defn- check!
  "Every row as wide as the first: a row one character short shifts every
   pixel after it, and that is easy to miss in text."
  [id rows]
  (let [w (count (first rows))]
    (doseq [[y row] (map-indexed vector rows)
            :when (not= w (count row))]
      (throw (ex-info (str id " row " y " is " (count row) " wide, not " w) {:row row})))))

(defn -main [& _]
  (let [{:keys [palette sprites]} (edn/read-string (slurp src))]
    (fs/create-dirs out)
    (doseq [[id {:keys [title rows]}] sprites]
      (check! id rows)
      (spit (str out "/" (name id) ".svg") (svg palette (or title (name id)) rows)))
    (println "wrote" (count sprites) "sprites to" out)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
