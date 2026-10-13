(ns werwolf.previews.plan
  "What the cluster should look like, as a pure function of three snapshots:
   the open PRs on GitHub, the preview objects in k3s, and lain's memory.

   Everything with a side effect lives around this, so every decision the
   controller makes is testable without GitHub or a cluster.")

(defn- candidates
  "The previews that should exist, one per open PR that has one.

   A PR whose head commit has a preview image wants that image. A PR whose head
   has none yet (its build is still running) keeps the preview it already has:
   a push must not take the old preview down before the new one exists."
  [prs deployments]
  (keep (fn [{:keys [number image sha last-activity]}]
          (if image
            {:pr number :image image :sha sha :last-activity last-activity}
            (when-let [d (get deployments number)]
              (assoc (select-keys d [:image :sha :last-activity]) :pr number))))
        prs))

(defn- by-priority
  "People's PRs before Renovate's, then newest first. A dependency bump is
   worth a look, but never at the cost of a preview somebody is working on:
   Renovate opens several PRs at once, and as the newest they would otherwise
   put every other preview to sleep."
  [renovate previews]
  (sort-by (juxt (comp not renovate :pr) :last-activity :pr) #(compare %2 %1) previews))

(defn- slots
  "How many previews fit: what is free once the running previews are counted
   back in (they are part of the plan, not competing with it), minus the
   reserve, in pod-sized pieces. Swap is deliberately not counted."
  [{:keys [available usage]} {:keys [max-previews memory-reserve pod-memory]}]
  (let [free (+ available (reduce + 0 (vals usage)))]
    (-> (quot (- free memory-reserve) pod-memory)
        (max 0)
        (min max-previews))))

(defn desired-state
  "The actions for one round.

   Returns
     :delete          PR numbers whose objects all go (the PR is not open)
     :create-secrets  PR numbers that need their TOKEN_SECRET created
     :previews        [{:pr :image :sha :last-activity :replicas}], to apply
     :hosts           PR numbers Caddy may get a certificate for
     :short-rounds    rounds in a row with fewer slots than running previews

   A preview is only put to sleep for lack of memory after `:grace-rounds`
   such rounds in a row, so MemAvailable wobbling around a boundary does not
   restart a JVM every minute. A newer preview taking an older one's slot is
   not memory noise and happens at once."
  [{:keys [prs k8s memory config short-rounds]}]
  (let [{:keys [deployments secrets present]} k8s
        open      (set (map :number prs))
        renovate  (into #{} (comp (filter :renovate?) (map :number)) prs)
        wanted    (by-priority renovate (candidates prs deployments))
        running   (count (filter #(= 1 (:replicas (get deployments (:pr %)))) wanted))
        n         (slots memory config)
        short     (if (< n running) (inc short-rounds) 0)
        effective (if (and (pos? short) (< short (:grace-rounds config)))
                    (min running (:max-previews config))
                    n)
        target    (set (map :pr (take effective wanted)))]
    {:delete         (into #{} (remove open) present)
     :create-secrets (into #{} (comp (map :pr) (remove secrets)) wanted)
     :previews       (mapv #(assoc % :replicas (if (target (:pr %)) 1 0)) wanted)
     :hosts          (set (map :pr wanted))
     :short-rounds   short}))
