(ns werwolf.previews.github
  "The open PRs and their preview images, in one GraphQL query per round.

   CI sets a commit status `preview` on every pushed commit it built an image
   for, with the image reference as its description; the status' creation time
   is the PR's last activity."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def query
  "query($owner: String!, $name: String!) {
     repository(owner: $owner, name: $name) {
       pullRequests(states: OPEN, first: 100) {
         pageInfo { hasNextPage }
         nodes {
           number
           headRefOid
           isCrossRepository
           commits(last: 1) {
             nodes { commit { status { context(name: \"preview\") {
               state description createdAt } } } }
           }
         }
       }
     }
   }")

(def ^:private image-ref
  "Exactly a nix-snapshotter reference to a store path tarball. The description
   goes into a pod spec, so nothing else gets through. (Even a forged one could
   only name a path signed with the Cachix key: the nix daemon substitutes
   nothing else.)"
  #"nix:0/nix/store/[0-9a-z]{32}-[A-Za-z0-9+._?=-]+\.tar")

(defn- preview [{:keys [state description createdAt]}]
  (when (and (= "SUCCESS" state)
             (string? description)
             (re-matches image-ref description))
    {:image description :last-activity createdAt}))

(defn parse-prs
  "The GraphQL response as [{:number :sha :image :last-activity}], with `:image`
   and `:last-activity` nil for a PR whose head commit has no usable preview.

   Throws on anything short of a complete answer: an empty list would read as
   every PR closed, and delete every preview."
  [{:keys [data errors]}]
  (when (seq errors)
    (throw (ex-info "GitHub answered with errors" {:errors errors})))
  (let [prs (get-in data [:repository :pullRequests])]
    (when-not (sequential? (:nodes prs))
      (throw (ex-info "GitHub answered without a PR list" {:data data})))
    (when (get-in prs [:pageInfo :hasNextPage])
      (throw (ex-info "more than 100 open PRs, refusing to act on a partial list" {})))
    (->> (:nodes prs)
         (remove :isCrossRepository)
         (mapv (fn [{:keys [number headRefOid commits]}]
                 (let [ctx (get-in commits [:nodes 0 :commit :status :context])]
                   (merge {:number number :sha headRefOid
                           :image nil :last-activity nil}
                          (preview ctx))))))))

(defn fetch-prs
  "Ask GitHub. Throws on any failure, see `parse-prs`."
  [{:keys [graphql-url repo github-token]}]
  (let [[owner name] (str/split repo #"/" 2)
        res (http/post graphql-url
                       {:headers {"Authorization" (str "bearer " github-token)
                                  "Content-Type" "application/json"}
                        :body (json/generate-string
                               {:query query
                                :variables {:owner owner :name name}})
                        :timeout 30000
                        :throw false})]
    (when-not (= 200 (:status res))
      (throw (ex-info (str "GitHub answered " (:status res)) {:body (:body res)})))
    (parse-prs (json/parse-string (:body res) true))))
