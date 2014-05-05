(ns jecode.model
  (:require
   [taoensso.carmine :as car]
   [noir.session :as session]
   [clojure.string :as s]
   [cheshire.core :refer :all :as json]
   [clj-rss.core :as rss]
   [shoreleave.middleware.rpc :refer [defremote]]))

(def server1-conn
  {:pool {} :spec {:uri "redis://localhost:6379/"}})

(defmacro wcar* [& body]
  `(car/wcar server1-conn ~@body))

;;; * Core model functions

(defn get-username-uid
  "Given a username, return the user's uid."
  [username]
  (wcar* (car/get (str "user:" username ":uid"))))

(defn get-uid-field
  "Given a uid and a field (as a string), return the field's value."
  [uid field]
  (wcar* (car/hget (str "uid:" uid) field)))

(defn get-pid-field
  "Given a pid and a field (as a string), return the field's value."
  [pid field]
  (wcar* (car/hget (str "pid:" pid) field)))

(defn get-eid-field
  "Given a eid and a field (as a string), return the field's value."
  [eid field]
  (wcar* (car/hget (str "eid:" eid) field)))

(defn get-pid-all
  "Given a pid and a field (as a string), return the field's value."
  [pid]
  (wcar* (car/hgetall (str "pid:" pid))))

(defn get-uid-all
  "Given a uid, return all field:value pairs."
  [uid]
  (wcar* (car/hgetall (str "uid:" uid))))

(defn get-eid-all
  "Given a eid and a field (as a string), return the field's value."
  [eid]
  (wcar* (car/hgetall (str "eid:" eid))))

(defn username-admin-of-pid?
  "True is username is the admin of project pid."
  [username pid]
  (= (wcar* (car/get (str "pid:" pid ":auid")))
     (get-username-uid username)))

(defn username-admin-of-eid?
  "True is username is the admin of event eid."
  [username eid]
  (= (wcar* (car/get (str "eid:" eid ":auid")))
     (get-username-uid username)))

;;; Remotes

(defmacro vec-to-kv-hmap [vec]
  `(into {}
         (for [v# (apply hash-map ~vec)]
           [(keyword (key v#)) (val v#)])))

(defremote get-initiatives
  "Return the list of initiatives.
Each initiative is represented as a hash-map."
  []
  (sort-by
   :name
   (filter
    #(not (= (:hide %) "hide"))
    (map #(assoc (vec-to-kv-hmap (wcar* (car/hgetall (str "pid:" %))))
            :pid %
            :isadmin (or (session/get :admin)
                         (username-admin-of-pid?
                          (session/get :username) %)))
         (wcar* (car/lrange "timeline" 0 -1))))))

(defremote get-initiatives-for-map
  []
  (filter #(not (or (empty? (:lat %)) (empty? (:lon %))))
          (get-initiatives)))

(defremote get-events
  "Return the list of events.
Each event is represented as a hash-map."
  []
  (sort-by
   :name
   (filter
    #(not (= (:hide %) "hide"))
    (map #(assoc (vec-to-kv-hmap (wcar* (car/hgetall (str "eid:" %))))
            :eid %
            :isadmin (or (session/get :admin)
                         (username-admin-of-eid?
                          (session/get :username) %)))
         (wcar* (car/lrange "timeline_events" 0 -1))))))

(defremote get-events-for-map
  []
  (filter #(not (or (empty? (:lat %)) (empty? (:lon %))))
          (get-events)))

;;; * RSS

(defrecord event-rss-item [title link description])

(defn event-to-rss-item
  "Given `event`, maybe export it to a rss item."
  [event]
  (let [name (:name event)
        url (:url event)
        loc (:location event)
        contact (:contact event)]
    (->event-rss-item
     (str "Événement: " name)
     url
     (format
      (s/join
       '("<p>%s organise l'événement \"%s\" !</p>"
         "<p>Début : %s<p>"
         "<p>  Fin : %s<p>"
         "<p> Lieu : %s</p>"
         "<p>Contact : %s</p>%s<p><a href=\"%s\">%s</a></p>"))
      (:orga event)
      name
      (:hdate_start event)
      (:hdate_end event) 
      (if (seq loc) loc "non précisé")
      (if (seq contact) contact "non précisé")
      (:desc event) url url))))

(defn events-rss []
  (apply rss/channel-xml
         {:title "jecode.org"
          :link "http://jecode.org"
          :description "jecode.org: apprenons à programmer ensemble !"}
         (reverse
          (filter #(seq %) (map #(event-to-rss-item %) (get-events))))))

;;; * JSON

(defn items-json [type]
  (json/generate-string
   {:source (str "jecode.org/" type "/json")
    :retrieved (java.util.Date.)
    (condp = type
      "evenements" :events
      "initiatives" :initiatives)
    (condp = type
      "evenements" (get-events-for-map)
      "initiatives" (get-initiatives-for-map))}
   {:date-format "yyyy-MM-dd HH:MM" :pretty true}))

;; Local Variables:
;; eval: (orgstruct-mode 1)
;; orgstruct-heading-prefix-regexp: ";;; "
;; End:
