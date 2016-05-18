(ns cian-bot.core
  (:require [clj-http.client :as client]
            [net.cgrand.enlive-html :as html]
            [clojure.data.json :as json]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  (:import (java.io StringReader)
           (java.util Date)))

(html/html-resource (StringReader. (:body
                                     (client/get
                                       "http://www.cian.ru/cat.php?"
                                       {:accept       :json
                                        :query-params {"currency" 2 "deal_type" "rent"}}))))

; ---------------------- CIAN SHIT END----------------------


(defn cian-request [query-params]
  (->> (client/get (str "http://www.cian.ru/cat.php?" query-params))
       :body
       (StringReader.)
       html/html-resource))

(defn get-flat-links
  ([query-params]
   (get-flat-links query-params 1))
  ([query-params page]
   (try
     (let [html-response (cian-request (str query-params "&p=" page))]
       (map (comp :href :attrs)
            (html/select html-response
                         [:span.objects_item_link [:a (html/attr-contains :href "cian.ru/rent/flat")]])))
     (catch Exception e
       []))))

(defn flat-id [flat-id-pattern flat-link]
  (Long/parseLong (re-find flat-id-pattern flat-link)))

(defn get-flats [query-params]
  (let [flat-id-pattern (re-pattern "\\d+")]
    (loop [page 1 flats {}]
      (let [page-flats (reduce #(assoc %1 (flat-id flat-id-pattern %2) %2)
                               {}
                               (get-flat-links query-params page))
            next-flats (merge flats page-flats)]
        (println "Page" page " flats: " page-flats)
        (if (= (count next-flats) (count flats))
          flats
          (recur (inc page) next-flats))))))

(defn new-cian-flats-chan [pause query-params]
  (let [flat-ch (chan)]
    (go-loop [flats (get-flats query-params)]
             (let [flat-ids (into #{} (keys flats))]
               (println (Date.) ":collected flats ->" (count flats))
               (Thread/sleep pause)
               (let [next-flats (get-flats query-params)
                     new-flats (into {} (filter #(not (contains? flat-ids (key %))) next-flats))]
                 (doseq [new-flat new-flats]
                   (do
                     (println "new flat " new-flat)
                     (>! flat-ch new-flat)))
                 (recur (merge flats next-flats)))))
    flat-ch))

(defn start-cian-subscription [query-params update]
  (let [chat-id (get-chat-id update)
        update-id (:update_id update)
        chat-updates-chan (new-updates-chan (/ (System/currentTimeMillis) 1000) 5000 update-id)
        cian-flats-chan (new-cian-flats-chan 60000 query-params)]
    (go-loop []
             (let [[value ch] (alts! [chat-updates-chan cian-flats-chan])]
               (if (= ch cian-flats-chan)
                 (send-message (val value) chat-id)
                 (when (= (get-user-message value) "/stop")
                   (send-message "Your query stopped" chat-id)))
               (recur)))))

(defn handle-update [update]
  (let [user-msg (get-user-message update)
        chat-id (get-chat-id update)]

    (when (.startsWith user-msg "/start")
      (if (> (.length user-msg) 7)
        (let [query-params (.substring user-msg 7)]
          (send-message (str "Your query started. Query is '" query-params "'") chat-id)
          (start-cian-subscription query-params update))
        (send-message "Start what? Give me query params, like shit: /start minprice=40000&offer_type=flat"
                      chat-id)))

    (when (.startsWith user-msg "/test-query")
      (if (> (.length user-msg) 12)
        (let [query-params (.substring user-msg 12)]
          (send-message (str "Your test started. Query is '" query-params "'") chat-id)
          (send-message (str "Take your flat: \n" (first (get-flat-links [query-params]))) chat-id))
        (send-message "Test what? Give me query params, like this : /test-query minprice=40000&offer_type=flat"
                      chat-id)))))

(defn subscribe [times]
  "The main way to subscribe on the flat feed.
  Specify number of times you want to get an update."
  (let [updates (new-updates-chan (/ (System/currentTimeMillis) 1000) 5000)]
    (go-loop [n times]
             (let [update (<! updates)]
               (when (pos? n)
                 (do
                   (handle-update update)
                   (recur (dec n))))))))


