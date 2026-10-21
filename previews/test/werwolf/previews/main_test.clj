(ns werwolf.previews.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.previews.main :as main]))

(def domain "werwolf-chrz.duckdns.org")

(deftest caddy-may-only-get-certificates-for-real-previews
  (let [ask #(main/allowed? domain #{42} %)]
    (is (ask "pr-42.werwolf-chrz.duckdns.org"))
    (testing "a PR without a preview, and anything that is not one"
      (is (not (ask "pr-43.werwolf-chrz.duckdns.org")))
      (is (not (ask "werwolf-chrz.duckdns.org")))
      (is (not (ask "pr-42.evil.example")))
      (is (not (ask "x.pr-42.werwolf-chrz.duckdns.org")))
      (is (not (ask "pr-042.werwolf-chrz.duckdns.org")))
      (is (not (ask nil))))))

(deftest the-ask-endpoint-answers-caddy-and-the-browser
  (let [ask (#'main/ask-handler domain (atom #{42}))
        answer #(ask {:query-string (when % (str "domain=" %))})]
    (is (= 200 (:status (answer "pr-42.werwolf-chrz.duckdns.org"))))
    (testing "forward_auth copies a 404 to the browser: give it a line to read"
      (let [{:keys [status body]} (answer "pr-43.werwolf-chrz.duckdns.org")]
        (is (= 404 status))
        (is (= "No such preview.\n" body))))
    (is (= 404 (:status (answer nil))))))

(deftest config-from-the-environment
  (let [cfg (main/config {"PREVIEWS_REPO" "jakuzure/werwolf-clj"
                          "PREVIEWS_DOMAIN" domain
                          "POD_MEMORY" "384Mi"
                          "MEMORY_RESERVE" "1Gi"})]
    (is (= (* 384 1024 1024) (:pod-memory cfg)))
    (is (= "384Mi" (:pod-memory-quantity cfg)))
    (is (= (* 1024 1024 1024) (:memory-reserve cfg)))
    (is (= 4 (:max-previews cfg)))
    (is (= 3 (:grace-rounds cfg)))
    (is (= "https://api.github.com/graphql" (:graphql-url cfg)))
    (is (= "werwolf-previews" (:namespace cfg)))))

(deftest the-preview-livekit-key-is-one-line-of-the-key-file
  (testing "LiveKit's key file holds production's key too; take only ours"
    (is (= {:key "werwolf-preview" :secret "s3cret"}
           (main/livekit-key "werwolf: prod\nwerwolf-preview:   s3cret\n"
                             "werwolf-preview")))
    (is (thrown? Exception (main/livekit-key "werwolf: prod\n" "werwolf-preview")))))
