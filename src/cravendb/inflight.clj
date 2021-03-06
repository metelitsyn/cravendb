(ns cravendb.inflight
  (:require [cravendb.vclock :as v]
            [cravendb.documents :as docs]
            [clojure.core.async :refer [chan go mult put!]]
            [clojure.core.incubator :refer [dissoc-in]]
            [cravendb.storage :as s]))

(defn create [db server-id]
  (let [out (chan)
        events (mult out)] {
        :server-id server-id 
        :db db 
        :out out
        :events events
        :tx-count (atom 0) 
        :in-flight (atom {})}))
    
(defn open 
  ([handle] (open handle :client))
  ([{:keys [in-flight db tx-count]} source]
  (let [txid (swap! tx-count inc)] 
    (swap! in-flight #(assoc-in %1 [:transactions txid] 
                      { :tx (s/ensure-transaction db)
                        :ops {}
                        :source source }))
    txid)))

(defn history-in-db [db id]
  (get (docs/load-document-metadata db id) :history))

(defn history-in-flight [in-flight id]
  (get-in in-flight [:documents id :history]))

(defmulti conflict-status (fn [tx current supplied] (:source tx)))
(defmethod conflict-status :client
  [tx current supplied]
  (cond
    (nil? current) :write ;; First time seeing this document ever
    (v/same? supplied current) :write ;; Isn't this nice, good little client
    (v/descends? supplied current) :write ;; Client specified, knows the future 
    :else :conflict)) ;; Who knows, conflict!

(defmethod conflict-status :replication
  [tx current candidate]
  (cond
    (nil? current) :write
    (v/same? candidate current) :skip
    (v/descends? candidate current) :write
    (v/descends? current candidate) :skip
    :else :conflict))

(defn last-known-history [in-flight db doc-id]
   (or (get-in in-flight [:documents doc-id :current-history]) 
       (history-in-db db doc-id)))

(defn check-against-existing [in-flight {:keys [db]} txid doc-id]
  (assoc-in in-flight
    [:transactions txid :ops doc-id :status] 
    (conflict-status (get-in in-flight [:transactions txid])
                     (last-known-history in-flight db doc-id)
                     (get-in in-flight [:transactions txid :ops doc-id :metadata :history]))))

(defn ensure-history
  [in-flight {:keys [db]} txid id]
  (assoc-in in-flight [:transactions txid :ops id :metadata :history] 
            (or (get-in in-flight [:transactions txid :ops id :metadata :history])
                (history-in-db db id)
                (v/new))))



(defmulti update-written-metadata 
  (fn [in-flight handle txid id]
    (get-in in-flight [:transactions txid :source])))

(defmethod update-written-metadata :client
  [in-flight {:keys [server-id db]} txid id]
  (-> in-flight
    (update-in [:transactions txid :ops id :metadata :history]
              #(v/next (str server-id txid) %1))
    (assoc-in [:transactions txid :ops id :metadata :synctag] (s/next-synctag db))))

(defmethod update-written-metadata :replication
  [in-flight {:keys [server-id db]} txid id]
  (assoc-in in-flight [:transactions txid :ops id :metadata :synctag] (s/next-synctag db)))

(defn update-log
  [in-flight handle txid id]
  (-> 
    (if (and (not (get-in in-flight [:documents id]))
          (= :write (get-in in-flight [:transactions txid :ops id :status])))
      (assoc-in in-flight [:documents id :current-history]
        (get-in in-flight [:transactions txid :ops id :metadata :history]))
    in-flight)
    (update-in [:documents id :refs] conj txid)))

(defn write-request [handle txid {:keys [metadata id] :as item}]
  (fn [in-flight]
    (-> in-flight
      (assoc-in [:transactions txid :ops id] item)
      (ensure-history handle txid id)
      (check-against-existing handle txid id)
      (update-written-metadata handle txid id)
      (update-log handle txid id))))

(defn finish-with-document [txid]
  (fn [in-flight doc-id] (dissoc-in in-flight [:documents doc-id])))

(defn clean-up [txid]
  (fn [in-flight]
    (-> (reduce (finish-with-document txid) 
            in-flight 
            (map (comp :id val) (get-in in-flight [:transactions txid :ops])))
      (dissoc-in [:transactions txid]))))

(defn is-registered? 
  [{:keys [in-flight]} id]
  (boolean (get-in @in-flight [:documents id])))

(defn is-txid?
  [{:keys [in-flight]} txid]
  (boolean (get-in @in-flight [:transactions txid])))

(defn add-document 
  [{:keys [in-flight db] :as handle} txid id document metadata]
  (swap! in-flight (write-request handle txid {:request :doc-add 
                                           :id id 
                                           :document document 
                                           :metadata metadata })))
(defn delete-document 
  [{:keys [in-flight db] :as handle} txid id metadata]
  (swap! in-flight (write-request handle txid {:request :doc-delete
                                           :id id 
                                           :metadata metadata })))
 
(defn write-operation [{:keys [tx out] :as acc} {:keys [status request id document metadata]}] 
  (case [status request]
    [:skip :doc-add] tx
    [:skip :doc-delete] tx
    [:write :doc-add] 
    (do
      (go (put! out :doc-added))
      (assoc acc :tx (docs/store-document tx id document metadata)))
    [:write :doc-delete] 
    (do
      (go (put! out :doc-deleted))
      (assoc acc :tx (docs/delete-document tx id metadata)))
    [:conflict :doc-add] 
    (do
      (go (put! out :doc-added))
      (assoc acc :tx (docs/store-conflict tx id document metadata)))
    [:conflict :doc-delete] 
    (do
      (go (put! out :doc-deleted))
      (assoc acc :tx (docs/store-conflict tx id :deleted metadata)))))

;; For calling once a transaction is actually committed
(defn complete! [{:keys [in-flight out]} txid]
  (let [{:keys [tx ops]} (get-in @in-flight [:transactions txid])] 
    (s/commit! (:tx (reduce write-operation {:tx tx :out out} (map val ops)))))
  (swap! in-flight (clean-up txid))
  (go (put! out :commited)))
