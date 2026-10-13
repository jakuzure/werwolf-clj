(ns werwolf.previews.plan-test
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.previews.plan :as plan]))

(def Mi (* 1024 1024))
(def Gi (* 1024 Mi))

(def config {:max-previews 4
             :memory-reserve Gi
             :pod-memory (* 384 Mi)
             :grace-rounds 3})

(defn- pr [n minute & {:as more}]
  (merge {:number n
          :sha (str "sha" n)
          :image (str "nix:0/nix/store/" n "-nix-image-werwolf.tar")
          :last-activity (format "2026-10-12T10:%02d:00Z" minute)}
         more))

(defn- deployment [n minute replicas]
  {:image (str "nix:0/nix/store/" n "-nix-image-werwolf.tar")
   :sha (str "sha" n)
   :last-activity (format "2026-10-12T10:%02d:00Z" minute)
   :replicas replicas})

(defn- run
  "desired-state with plenty of memory unless the test says otherwise."
  [{:keys [prs k8s available usage short-rounds]
    :or {k8s {} available (* 8 Gi) usage {} short-rounds 0}}]
  (plan/desired-state {:prs prs
                       :k8s (merge {:deployments {} :secrets #{} :present #{}} k8s)
                       :memory {:available available :usage usage}
                       :config config
                       :short-rounds short-rounds}))

(defn- replicas [out]
  (into {} (map (juxt :pr :replicas)) (:previews out)))

(deftest a-new-pr-gets-a-secret-and-a-running-preview
  (let [out (run {:prs [(pr 42 0)]})]
    (is (= #{42} (:create-secrets out)))
    (is (= {42 1} (replicas out)))
    (is (= (:image (pr 42 0)) (-> out :previews first :image)))
    (is (= #{} (:delete out)))
    (is (= #{42} (:hosts out)))))

(deftest an-existing-secret-is-never-recreated
  (testing "TOKEN_SECRET is random: recreating it would log everybody out"
    (let [out (run {:prs [(pr 42 0)]
                    :k8s {:deployments {42 (deployment 42 0 1)}
                          :secrets #{42} :present #{42}}})]
      (is (= #{} (:create-secrets out))))))

(deftest a-closed-pr-is-deleted-with-everything
  (let [out (run {:prs [(pr 1 0)]
                  :k8s {:deployments {1 (deployment 1 0 1) 7 (deployment 7 0 1)}
                        :secrets #{1 7} :present #{1 7 9}}})]
    (testing "a PR no longer open, including one with only leftovers (9)"
      (is (= #{7 9} (:delete out))))
    (is (= #{1} (set (map :pr (:previews out)))))))

(deftest a-push-still-building-keeps-the-old-preview
  (testing "the head commit has no preview status yet: keep what runs"
    (let [out (run {:prs [(pr 42 5 :image nil :last-activity nil :sha "new")]
                    :k8s {:deployments {42 (deployment 42 0 1)}
                          :secrets #{42} :present #{42}}})]
      (is (= #{} (:delete out)))
      (is (= [{:pr 42 :replicas 1 :image (:image (deployment 42 0 1))
               :sha "sha42" :last-activity "2026-10-12T10:00:00Z"}]
             (:previews out))))))

(deftest an-open-pr-without-any-preview-gets-nothing
  (let [out (run {:prs [(pr 42 0 :image nil :last-activity nil)]})]
    (is (empty? (:previews out)))
    (is (= #{} (:create-secrets out)))
    (is (= #{} (:hosts out)))))

(deftest at-most-max-previews-run-newest-first
  (let [out (run {:prs (map #(pr % %) (range 1 6))})]
    (is (= {1 0 2 1 3 1 4 1 5 1} (replicas out)))
    (testing "the sleeping one still has a host, so its page can say so"
      (is (= #{1 2 3 4 5} (:hosts out))))))

(deftest free-memory-decides-how-many-run
  (testing "1 GiB reserve + 2 × 384 MiB fits, a third does not"
    (let [out (run {:prs (map #(pr % %) (range 1 4))
                    :available (+ Gi (* 2 384 Mi) (* 100 Mi))})]
      (is (= {1 0 2 1 3 1} (replicas out)))))
  (testing "what running previews use now counts as free: they are in the plan"
    (let [out (run {:prs (map #(pr % %) (range 1 4))
                    :k8s {:deployments {1 (deployment 1 1 1) 2 (deployment 2 2 1)
                                        3 (deployment 3 3 1)}
                          :secrets #{1 2 3} :present #{1 2 3}}
                    :available Gi
                    :usage {1 (* 384 Mi) 2 (* 384 Mi) 3 (* 384 Mi)}})]
      (is (= {1 1 2 1 3 1} (replicas out))))))

(deftest no-memory-means-no-slots-not-negative-ones
  (let [out (run {:prs [(pr 1 1)] :available (* 100 Mi)})]
    (is (= {1 0} (replicas out)))))

(deftest a-shrinking-budget-waits-before-scaling-down
  (let [k8s {:deployments {1 (deployment 1 1 1) 2 (deployment 2 2 1)}
             :secrets #{1 2} :present #{1 2}}
        ;; one slot: 1 GiB reserve + 384 MiB + a bit, nothing in use
        tight (+ Gi (* 400 Mi))
        round (fn [short] (run {:prs [(pr 1 1) (pr 2 2)] :k8s k8s
                                :available tight :short-rounds short}))]
    (testing "rounds one and two: memory noise, both keep running"
      (is (= {1 1 2 1} (replicas (round 0))))
      (is (= 1 (:short-rounds (round 0))))
      (is (= {1 1 2 1} (replicas (round 1))))
      (is (= 2 (:short-rounds (round 1)))))
    (testing "round three: the oldest goes to sleep"
      (is (= {1 0 2 1} (replicas (round 2))))
      (is (= 3 (:short-rounds (round 2)))))
    (testing "enough memory again resets the count"
      (is (= 0 (:short-rounds (run {:prs [(pr 1 1) (pr 2 2)] :k8s k8s
                                    :short-rounds 2})))))))

(deftest a-newer-preview-displaces-the-oldest-at-once
  (testing "not memory noise: the slot count did not change, priority did"
    (let [out (run {:prs [(pr 1 1) (pr 2 2) (pr 3 3)]
                    :k8s {:deployments {1 (deployment 1 1 1) 2 (deployment 2 2 1)}
                          :secrets #{1 2} :present #{1 2}}
                    :available (+ Gi (* 2 384 Mi) (* 10 Mi))
                    :usage {1 0 2 0}})]
      (is (= {1 0 2 1 3 1} (replicas out)))
      (is (= 0 (:short-rounds out))))))

(deftest a-sleeping-preview-wakes-when-a-slot-frees
  (let [out (run {:prs [(pr 1 1) (pr 2 2)]
                  :k8s {:deployments {1 (deployment 1 1 0) 2 (deployment 2 2 1)}
                        :secrets #{1 2} :present #{1 2}}})]
    (is (= {1 1 2 1} (replicas out)))))

(deftest renovate-never-takes-a-slot-from-a-person
  (testing "two slots, three PRs: Renovate's newer ones queue behind a person's"
    (let [out (run {:prs [(pr 1 1) (pr 2 5 :renovate? true) (pr 3 6 :renovate? true)]
                    :available (+ Gi (* 2 384 Mi) (* 10 Mi))})]
      (is (= {1 1 2 0 3 1} (replicas out)))))
  (testing "with room for all, Renovate's run too"
    (let [out (run {:prs [(pr 1 1) (pr 2 5 :renovate? true)]})]
      (is (= {1 1 2 1} (replicas out))))))
