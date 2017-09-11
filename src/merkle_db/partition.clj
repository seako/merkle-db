(ns merkle-db.partition
  "Partitions contain non-overlapping ranges of the records witin a table.
  Partition nodes contain metadata about the contained records and links to the
  tablets where the data for each field family is stored.

  Some functions in this namespace use the term 'virtual tablet' to mean a
  tablet map in memory which contains the full record data for a partition.
  They are used as temporary ways to represent unserialized record data."
  (:require
    [clojure.future :refer [pos-int?]]
    [clojure.spec :as s]
    (merkledag
      [core :as mdag]
      [node :as node])
    (merkle-db
      [bloom :as bloom]
      [key :as key]
      [patch :as patch]
      [record :as record]
      [tablet :as tablet]
      [validate :as validate])))


;; ## Specs

(def ^:const data-type
  "Value of `:data/type` that indicates a partition node."
  :merkle-db/partition)

(def default-limit
  "The default number of records to build partitions up to."
  10000)

;; Maximum number of records to allow in each partition.
(s/def ::limit pos-int?)

;; Map of family keys (and `:base`) to links to the corresponding tablets.
(s/def ::tablets (s/map-of keyword? mdag/link?))

;; Bloom filter providing probable membership testing for record keys contained
;; in the partition.
(s/def ::membership bloom/filter?)

;; Partition node.
(s/def ::node-data
  (s/and
    (s/keys :req [::limit
                  ::tablets
                  ::membership
                  ::record/count
                  ::record/families
                  ::record/first-key
                  ::record/last-key])
    #(= data-type (:data/type %))))


(defn validate
  [part params]
  (when (validate/check :data/type
          (= data-type (:data/type part))
          "Node has expected data type.")
    (validate/check ::spec
      (s/valid? ::node-data part)
      (s/explain-str ::node-data part))
    ; TODO: warn when partition limit or families don't match params
    (when (<= (::limit part) (::record/count params))
      (validate/check ::underflow
        (<= (Math/ceil (/ (::limit part) 2)) (::record/count part))
        "Partition is at least half full if tree has at least :merkle-db.partition/limit records"))
    (validate/check ::overflow
      (<= (::record/count part) (::limit part))
      "Partition has at most :merkle-db.partition/limit records")
    ; TODO: first-key matches actual first record key
    ; TODO: last-key matches actual last record key
    ; TODO: record/count is accurate
    ; TODO: every key present tests true against membership filter
    (when (::record/first-key params)
      (validate/check ::record/first-key
        (not (key/before? (::record/first-key part) (::record/first-key params)))
        "First key in partition is within the subtree boundary"))
    (when (::record/last-key params)
      (validate/check ::record/last-key
        (not (key/after? (::record/last-key part) (::record/last-key params)))
        "Last key in partition is within the subtree boundary"))
    (validate/check ::base-tablet
      (:base (::tablets part))
      "Partition contains a base tablet")
    (doseq [[tablet-family link] (::tablets part)]
      (validate/check-next!
        link
        tablet/validate
        (assoc params
               ::record/families (::record/families part)
               ::record/family-key tablet-family)))))



;; ## Tablet Utilities

(defn- choose-tablets
  "Selects a list of tablet names to query over, given a mapping of tablet
  names to sets of the contained fields and the desired set of field data. If
  selected-fields is empty, returns all tablets."
  [tablet-fields selected]
  (if (seq selected)
    ; Use field selection to filter tablets to load.
    (-> (dissoc tablet-fields :base)
        (->> (keep #(when (some selected (val %)) (key %))))
        (set)
        (as-> chosen
          (if (seq (apply disj selected (mapcat tablet-fields chosen)))
            (conj chosen :base)
            chosen)))
    ; No selection provided, return all field data.
    (-> tablet-fields keys set (conj :base))))


(defn- get-tablet
  "Return the tablet data for the given family key."
  [store part family-key]
  (mdag/get-data store (get (::tablets part) family-key)))


(defn- store-tablet!
  "Store the given tablet data. Returns a tuple of the family key and named
  link to the new node."
  [store family-key tablet]
  (let [tablet (cond-> tablet
                 (not= family-key :base)
                 (tablet/prune))]
    (when (seq (tablet/read-all tablet))
      [family-key
       (mdag/link
         (if (namespace family-key)
           (str (namespace family-key) ":" (name family-key))
           (name family-key))
         (mdag/store-node! store nil tablet))])))


(defn- apply-patch
  "Performs an update on the tablet by applying the patch changes. Returns an
  updated tablet, or nil if the result was empty."
  [tablet changes]
  (if (seq changes)
    (let [deletion? (comp patch/tombstone? second)
          additions (remove deletion? changes)
          deleted-keys (set (map first (filter deletion? changes)))]
      (tablet/update-records tablet additions deleted-keys))
    tablet))



;; ## Read Functions

(defn- record-seq
  "Combines lazy sequences of partial records into a single lazy sequence
  containing key/data tuples."
  [field-seqs]
  (lazy-seq
    (when-let [next-key (some->> (seq (keep ffirst field-seqs))
                                 (apply key/min))] ; TODO: key/max for reverse
      (let [has-next? #(= next-key (ffirst %))
            next-data (->> field-seqs
                           (filter has-next?)
                           (map (comp second first))
                           (apply merge))
            next-seqs (keep #(if (has-next? %) (next %) (seq %))
                            field-seqs)]
        (cons [next-key next-data] (record-seq next-seqs))))))


(defn- read-tablets
  "Performs a read across the tablets in the partition by selecting based on
  the desired fields. The reader function is called on each selected tablet
  along with any extra args, producing a collection of lazy record sequences
  which are combined into a single sequence of key/record pairs."
  [store part fields read-fn & args]
  ; OPTIMIZE: use transducer instead of intermediate sequences.
  (let [tablets (choose-tablets (::record/families part) (set fields))
        field-seqs (map #(apply read-fn (get-tablet store part %) args) tablets)
        records (record-seq field-seqs)]
    (cond->> records
      (seq fields)
        (map (juxt first #(select-keys (second %) fields))))))


(defn read-all
  "Read a lazy sequence of key/map tuples which contain the requested field data
  for every record in the partition."
  [store part fields]
  (read-tablets store part fields tablet/read-all))


(defn read-batch
  "Read a lazy sequence of key/map tuples which contain the requested field
  data for the records whose keys are in the given collection."
  [store part fields record-keys]
  ; OPTIMIZE: use the membership filter to weed out keys which are definitely not present.
  (read-tablets store part fields tablet/read-batch record-keys))


(defn read-range
  "Read a lazy sequence of key/map tuples which contain the field data for the
  records whose keys lie in the given range, inclusive. A nil boundary includes
  all records in that range direction."
  [store part fields start-key end-key]
  (read-tablets store part fields tablet/read-range start-key end-key))



;; ## Update Functions

(defn- load-tablet
  "Loads data from the given value into a tablet."
  [store x]
  (let [x (if (mdag/link? x)
            (mdag/get-data store x)
            x)]
    (condp = (:data/type x)
      tablet/data-type x
      data-type (tablet/from-records (read-all store x nil))
      nil nil)))


(defn- join-tablets
  "Convert the values to virtual tablets and join them into a single tablet."
  [store a b]
  (if (and a b)
    (tablet/join (load-tablet store a)
                 (load-tablet store b))
    (or a b)))


(defn from-records
  "Constructs a new partition from the given map of record data. The records
  will be split into tablets matching the given families, if provided. Returns
  the node data for the persisted partition."
  [store params records]
  (let [records (vec (sort-by first (patch/remove-tombstones records))) ; TODO: don't sort?
        limit (or (::limit params) default-limit)
        families (or (::record/families params) {})]
    (when (< limit (count records))
      (throw (ex-info
               (format "Cannot construct a partition from %d records overflowing limit %d"
                       (count records) limit)
               {::record/count (count records)
                ::limit limit})))
    (when (seq records)
      (->>
        {:data/type data-type
         ::limit limit
         ::tablets (into {}
                         (map #(store-tablet! store (key %) (tablet/from-records (val %))))
                         (record/split-data families records))
         ::membership (into (bloom/create limit)
                            (map first)
                            records)
         ::record/count (count records)
         ::record/families families
         ::record/first-key (first (first records))
         ::record/last-key (first (last records))}
        (mdag/store-node! store nil)
        (::node/data)))))


(defn partition-records
  "Divides the given records into one or more partitions. Returns a sequence of
  node data for the persisted partitions."
  [store params records]
  (->> records
       (patch/remove-tombstones)
       (record/partition-limited (or (::limit params) default-limit))
       (mapv #(from-records store params %))))


(defn update-partitions!
  "Consume a sequence of tuples containing partitions and associated patch
  changes to return an updated sequence of partitions. Each tuple has the form
  `[partition-data changes]`. The partition data may be either a link
  to an existing node or a virtual tablet which was carried into the inputs.

  The result of the update is one of:

  - `nil` if all partitions were removed by the changes.
  - A virtual tablet if there are not enough records in the result to create a
    valid half-full partition.
  - A sequence of data for the serialized partitions."
  [store params inputs]
  (let [limit (or (::limit params) default-limit)
        half-full (int (Math/ceil (/ limit 2)))
        valid? #(or (mdag/link? %) (<= half-full (::record/count %) limit))]
    (loop [result []
           pending nil
           inputs inputs]
      (if (seq inputs)
        ; Process next partition in the sequence.
        (let [[part changes] (first inputs)]
          (if (and (nil? pending) (empty? changes))
            ; No pending records or changes, so use "pass through" logic.
            (if (mdag/link? part)
              ; No changes to the stored partition, add directly to result.
              (recur (conj result (mdag/get-data store part))
                     nil
                     (next inputs))
              ; No changes to virtual tablet, recur with pending records.
              (recur result part (next inputs)))
            ; Load partition data or use virtual tablet records.
            (let [tablet (load-tablet store part)
                  tablet' (join-tablets store pending (apply-patch tablet changes))]
              (cond
                ; All data was removed from the partition.
                (empty? (tablet/read-all tablet'))
                  (recur result nil (next inputs))

                ; Original partition data was unchanged by updates.
                (= tablet tablet')
                  (if (mdag/link? part)
                    ; Original linked partition remains unchanged by update.
                    (recur (conj result (mdag/get-data store part))
                           nil
                           (next inputs))
                    ; Original pending data hasn't changed
                    (recur result tablet (next inputs)))

                ; Accumulated enough records to output full partitions.
                (<= (+ limit half-full) (count (tablet/read-all tablet')))
                  (let [[result' remnant]
                        (loop [result result
                               records (tablet/read-all tablet')]
                          (if (<= (+ limit half-full) (count records))
                            ; Serialize a full partition using the pending records.
                            (let [[output remnant] (split-at limit records)
                                  part' (from-records store params output)]
                              (recur (conj result part') remnant))
                            ; Not enough to guarantee validity, so continue.
                            [result records]))]
                    (recur result' (tablet/from-records remnant) (next inputs)))

                ; Not enough data to output a partition yet, keep pending.
                :else
                  (recur result tablet' (next inputs))))))

        ; No more partitions to process.
        (cond
          ; No pending data to handle, we're done.
          (nil? pending)
            result

          ; Not enough records left to create a valid partition!
          (< (count (tablet/read-all pending)) half-full)
            (if-let [prev (peek result)]
              ; Load last result link and redistribute into two valid partitions.
              (->> (join-tablets store prev pending)
                   (tablet/read-all)
                   (partition-records store params)
                   (into result))
              ; No siblings, so return virtual tablet for carrying.
              pending)

          ; Enough records to make one or more valid partitions.
          :else
            (->> (tablet/read-all pending)
                 (partition-records store params)
                 (into result)))))))


(defn update-partition-root
  "Apply changes to a partition root node, returning a sequence of
  updated partition links."
  [store params part changes]
  (let [result (update-partitions! store params [[part changes]])]
    (if (sequential? result)
      result
      (when result
        [(from-records store params (tablet/read-all result))]))))
