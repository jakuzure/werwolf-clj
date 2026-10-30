(ns werwolf.http-test
  "Caching headers on the static files. The bug these guard against: nix stamps
   every file 1970-01-01, ring sent that as Last-Modified, and browsers cached
   the bundle for years, so players kept the previous version after a deploy."
  (:require [clojure.test :refer [deftest is testing]]
            [werwolf.http :as http]))

(def ^:private app (http/handler {}))

(defn- GET
  ([uri] (GET uri {}))
  ([uri headers] (app {:request-method :get :uri uri :headers headers})))

(defn- slurp-body [resp]
  (let [b (:body resp)] (when b (slurp b))))

(deftest the-page-is-revalidated-not-dated
  (let [resp (GET "/")]
    (slurp-body resp)
    (is (= 200 (:status resp)))
    (is (= "no-cache" (get-in resp [:headers "Cache-Control"]))
        "the browser must ask before reusing it")
    (is (re-matches #"\"[0-9a-f]{32}\"" (get-in resp [:headers "ETag"] ""))
        "a quoted content fingerprint")
    (is (not-any? #{"Last-Modified" "last-modified"} (keys (:headers resp)))
        "no modification date, which under nix is always 1970")))

(deftest an-unchanged-file-costs-only-a-304
  (let [tag  (get-in (doto (GET "/") slurp-body) [:headers "ETag"])
        resp (GET "/" {"if-none-match" tag})]
    (is (= 304 (:status resp)))
    (is (nil? (:body resp)))
    (is (= tag (get-in resp [:headers "ETag"])))
    (testing "a stale fingerprint gets the full file"
      (let [resp (GET "/" {"if-none-match" "\"0000\""})]
        (is (= 200 (:status resp)))
        (is (re-find #"<html" (slurp-body resp)))))))

(deftest static-files-share-the-rule
  (testing "the resource handler path, not just the index route"
    (let [resp (GET "/index.html")]
      (slurp-body resp)
      (is (= 200 (:status resp)))
      (is (= "no-cache" (get-in resp [:headers "Cache-Control"])))
      (is (= (get-in (doto (GET "/") slurp-body) [:headers "ETag"])
             (get-in resp [:headers "ETag"]))
          "same bytes, same fingerprint, whichever route served them")))
  (testing "a miss is left alone"
    (let [resp (GET "/no-such-file.js")]
      (is (not= 200 (:status resp)))
      (is (nil? (get-in resp [:headers "ETag"]))))))

(deftest the-install-files-have-their-real-types
  (testing "ring knows no .webmanifest, so the handler has to say it"
    (let [resp (GET "/manifest.webmanifest")]
      (slurp-body resp)
      (is (= 200 (:status resp)))
      (is (= "application/manifest+json" (get-in resp [:headers "Content-Type"])))
      (is (= "no-cache" (get-in resp [:headers "Cache-Control"])))))
  (doseq [[uri type] [["/icons/icon.svg" "image/svg+xml"]
                      ["/icons/icon-192.png" "image/png"]
                      ["/icons/apple-touch-icon.png" "image/png"]]]
    (let [resp (GET uri)]
      (slurp-body resp)
      (is (= 200 (:status resp)) uri)
      (is (= type (get-in resp [:headers "Content-Type"])) uri))))

(deftest a-connection-is-counted-against-the-client-behind-the-proxy
  (testing "http-kit has already put X-Forwarded-For into :remote-addr"
    (is (= "203.0.113.7" (http/client-address {:remote-addr "203.0.113.7"}))))
  (testing "this machine is not a client to count"
    (doseq [local ["127.0.0.1" "127.0.1.1" "::1" "0:0:0:0:0:0:0:1"]]
      (is (nil? (http/client-address {:remote-addr local :headers {}})) local)))
  (testing "but writing 127.0.0.1 into the header does not make a client local"
    (is (= "127.0.0.1" (http/client-address {:remote-addr "127.0.0.1"
                                             :headers {"x-forwarded-for" "127.0.0.1"}})))))

(deftest the-server-says-which-build-it-is
  (testing "the start page asks: the bundle carries no commit"
    (let [resp (GET "/api/build")
          body (:body resp)]
      (is (= 200 (:status resp)))
      (is (= "application/json" (get-in resp [:headers "content-type"])))
      (is (re-matches #"\{\"version\":\"[0-9][^\"]*\",\"commit\":\"[0-9a-z]+\"\}" body) body))))
