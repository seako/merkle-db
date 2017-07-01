(ns merkle-db.table
  "Tables are named top-level containers of records. Generally, a single table
  corresponds to a certain 'kind' of data. Tables also contain configuration
  determining how keys are encoded and records are stored."
  (:require
    [clojure.future :refer [pos-int?]]
    [clojure.spec :as s]
    [merkledag.link :as link]
    [merkledag.node :as node]
    [merkle-db.data :as data]
    [merkle-db.index :as index]
    [merkle-db.key :as key]
    [merkle-db.partition :as part])
  (:import
    java.time.Instant))


;; ## Data Specifications

;; Table names are non-empty strings.
;; TODO: disallow certain characters like '/'
(s/def ::name (s/and string? #(<= 1 (count %) 127)))

;; Table data is a link to the root of the data tree.
(s/def ::data link/merkle-link?)

;; Tables may have a patch tablet containing recent unmerged data.
(s/def ::patch link/merkle-link?)

;; Table root node.
(s/def ::node-data
  (s/keys :req [::data/count
                ::index/branching-factor
                ::part/limit]
          :opt [::data
                ::patch
                ::data/families
                ::key/lexicoder
                :time/updated-at]))

(def info-keys
  #{::node/id ::name ::data/size})

(s/def ::table-info
  (s/keys :req [::node/id
                ::name
                ::version
                ::committed-at]))




;; ## Table API

(defprotocol ITable
  "Protocol for an immutable table of record data."

  ;; Records

  (scan
    [table opts]
    "...")

  (get-records
    [table primary-keys opts]
    "...")

  (write
    [table records]
    "...")

  (delete
    [table primary-keys]
    "...")

  ;; Partitions

  (list-partitions
    [table]
    "...")

  (read-partition
    [table partition-id opts]
    "..."))



;; ## Utility Functions

(defn root-data
  "Construct a map for a new table root node."
  [opts]
  (merge {::index/branching-factor 256
          ::part/limit 100000}
          opts
          {::data/count 0
           :time/updated-at (Instant/now)}))



;; ## Table Type

;; Tables are implementad as a custom type so they can behave similarly to
;; natural Clojure values.
;;
;; - `store` reference to the merkledag node store backing the database.
;; - `table-info` map of higher-level table properties such as table-name,
;;   node-id, and recursive size.
;; - `root-data` map of data stored in the table root.
;; - `patch-data` sorted map of loaded patch record data.
;; - `dirty?` flag to indicate whether the table data has been changed since
;;   the node was loaded.
(deftype Table
  [store
   table-info
   root-data
   patch-data
   dirty?
   _meta]

  Object

  (toString
    [this]
    (format "table:%s %s"
            (::name table-info "?")
            (hash root-data)))


  (equals
    [this that]
    (boolean
      (or (identical? this that)
          (when (identical? (class this) (class that))
            (and (= root-data (.root-data ^Table that))
                 (= patch-data (.patch-data ^Table that)))))))


  (hashCode
    [this]
    (hash-combine (hash root-data) (hash patch-data)))


  clojure.lang.IObj

  (meta
    [this]
    _meta)


  (withMeta
    [this meta-map]
    (Table. store table-info root-data patch-data dirty? meta-map))


  clojure.lang.ILookup

  (valAt
    [this k]
    (.valAt this k nil))


  (valAt
    [this k not-found]
    (if (contains? info-keys k)
      (get table-info k not-found)
      ; TODO: if val here is a link, auto-resolve it
      (get root-data k not-found)))


  clojure.lang.IPersistentMap

  (count
    [this]
    (+ (count root-data) (count table-info)))


  (empty
    [this]
    (Table.
      store
      (dissoc table-info ::node/id)
      (root-data nil)
      nil
      true
      _meta))


  (cons
    [this element]
    (cond
      (instance? java.util.Map$Entry element)
        (let [^java.util.Map$Entry entry element]
          (.assoc this (.getKey entry) (.getValue entry)))
      (vector? element)
        (.assoc this (first element) (second element))
      :else
        (loop [result this
               entries element]
          (if (seq entries)
            (let [^java.util.Map$Entry entry (first entries)]
              (recur (.assoc result (.getKey entry) (.getValue entry))
                     (rest entries)))
            result))))


  (equiv
    [this that]
    (.equals this that))


  (containsKey
    [this k]
    (not (identical? this (.valAt this k this))))


  (entryAt
    [this k]
    (let [v (.valAt this k this)]
      (when-not (identical? this v)
        (clojure.lang.MapEntry. k v))))


  (seq
    [this]
    (seq (concat (seq table-info) (seq root-data))))


  (iterator
    [this]
    (clojure.lang.RT/iter (seq this)))


  (assoc
    [this k v]
    (if (contains? info-keys k)
      (throw (IllegalArgumentException.
               (str "Cannot change table info field " k)))
      (let [root' (assoc root-data k v)]
        (if (= root-data root')
          this
          (Table. store table-info root' patch-data true _meta)))))


  (without
    [this k]
    (if (contains? info-keys k)
      (throw (IllegalArgumentException.
               (str "Cannot remove table info field " k)))
      (let [root' (not-empty (dissoc root-data k))]
        (if (= root-data root')
          this
          (Table. store table-info root' patch-data true _meta))))))


(alter-meta! #'->Table assoc :private true)


; TODO: constructor functions



;; ## Protocol Implementation

(extend-type Table

  ITable

  ;; Records

  (scan
    [this opts]
    (throw (UnsupportedOperationException. "NYI")))


  (get-records
    [this primary-keys opts]
    (throw (UnsupportedOperationException. "NYI")))


  (write
    [this records]
    (throw (UnsupportedOperationException. "NYI")))


  (delete
    [this primary-keys]
    (throw (UnsupportedOperationException. "NYI")))


  ;; Partitions

  (list-partitions
    [this]
    (throw (UnsupportedOperationException. "NYI")))


  (read-partition
    [this partition-id opts]
    (throw (UnsupportedOperationException. "NYI"))))
