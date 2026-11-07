(ns werwolf.lint)

;; The tests' with-ctx, with-deps, with-db and with-server bind their first
;; symbol to a fresh fixture and clean up after. The rest of the vector is
;; arguments, used, and the body sees the symbol bound.
(defmacro with-binding [[sym & args] & body]
  `(let [~sym ~(vec args)] ~@body))
