(ns werwolf.previews.github-test
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.previews.github :as github]))

(def image "nix:0/nix/store/0123456789abcdfghijklmnpqrsvwxyz-nix-image-werwolf.tar")

(defn- node [n & {:keys [cross? state description created]
                  :or {state "SUCCESS" description image
                       created "2026-10-12T10:00:00Z"}}]
  {:number n
   :headRefOid (str "sha" n)
   :isCrossRepository (boolean cross?)
   :commits {:nodes [{:commit {:status (when state
                                         {:context {:state state
                                                    :description description
                                                    :createdAt created}})}}]}})

(defn- response [nodes & {:keys [next-page? errors]}]
  (cond-> {:data {:repository {:pullRequests {:pageInfo {:hasNextPage (boolean next-page?)}
                                              :nodes nodes}}}}
    errors (assoc :errors errors)))

(deftest a-successful-preview-status-is-a-preview
  (is (= [{:number 42 :sha "sha42" :image image
           :last-activity "2026-10-12T10:00:00Z"}]
         (github/parse-prs (response [(node 42)])))))

(deftest an-open-pr-without-a-usable-status-has-no-image
  (testing "it is still open, so its preview must not be deleted"
    (doseq [n [(node 1 :state nil)
               (node 1 :state "PENDING")
               (node 1 :state "FAILURE")]]
      (is (= [{:number 1 :sha "sha1" :image nil :last-activity nil}]
             (github/parse-prs (response [n])))))))

(deftest a-description-that-is-not-a-nix-image-is-ignored
  (testing "it ends up in a pod spec, so only exactly a store path image counts"
    (doseq [d ["nginx:latest"
               "nix:0/nix/store/short-nix-image-werwolf.tar"
               (str image " ; rm -rf /")
               "nix:0/nix/store/0123456789abcdfghijklmnpqrsvwxyz-x/../../etc.tar"]]
      (is (nil? (:image (first (github/parse-prs (response [(node 1 :description d)])))))
          d))))

(deftest a-pr-from-a-fork-is-not-ours
  (is (= [] (github/parse-prs (response [(node 1 :cross? true)])))))

(deftest an-incomplete-answer-is-an-error-not-an-empty-list
  (testing "an empty list would read as every PR closed, and delete everything"
    (is (thrown? Exception (github/parse-prs (response [(node 1)] :errors [{:message "x"}]))))
    (is (thrown? Exception (github/parse-prs (response [(node 1)] :next-page? true))))
    (is (thrown? Exception (github/parse-prs {:data {:repository nil}})))
    (is (thrown? Exception (github/parse-prs {})))))
