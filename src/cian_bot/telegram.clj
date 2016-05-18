(ns cian-bot.core
  (:use [environ.core :refer [env]])
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.core.async :refer
             [>! <! >!! <!! go go-loop chan buffer close! thread
              alts! alts!! timeout]]))

;------------------- TELEGRAM BOT API -------------------
(def bot-token (env :telegram-bot-token))
(def bot-url (format "https://api.telegram.org/bot%s/" bot-token))

(defn send-message [text chat-id]
  "Sends message to telegram chat"
  (client/get (str bot-url "sendMessage")
              {:query-params
               {:text    text
                :chat_id chat-id}}))

(defn get-updates []
  "Get all updates"
  (client/get (str bot-url "getUpdates") {:accept :json}))


(defn get-user-message [update]
  "Get user message from update json object"
  ((comp :text :message) update))

(defn get-date [update]
  "Get date from update json object"
  ((comp :date :message) update))

(defn get-chat-id [update]
  "Get chat id from update json object"
  ((comp :id :chat :message) update))

(defn parse-updates [updates-str]
  "Parses telegram update object. https://core.telegram.org/bots/api#update"
  (:result (json/read-str (:body updates-str))))

(defn new-updates-chan
  "When user subscribes to some cian feed new channel is created.
  Then stream of new updates feeds that channel"
  ([updates-from pause]
   (new-updates-chan updates-from pause -1))
  ([updates-from pause last-update-id]
   (let [ch (chan)]
     (go-loop [last-id last-update-id]
       (let [updates (->> (get-updates)
                          parse-updates
                          (filter #(> (get-date %) updates-from))
                          (filter #(> (:update_id %) last-id)))]
         (doseq [update updates]
           (>! ch update))
         (Thread/sleep pause)
         (recur (reduce max last-id (map :update_id updates)))))
     ch)))