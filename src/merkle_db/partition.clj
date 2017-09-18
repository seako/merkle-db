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
      [graph :as graph]
      [key :as key]
      [patch :as patch]
      [record :as record]
      [tablet :as tablet :refer [tablet?]])))


(def ^:const data-type
  "Value of `:data/type` that indicates a partition node."
  :merkle-db/partition)


(def default-limit
  "The default number of records to build partitions up to."
  10000)


(defn partition?
  "Determines whether the value is partition node data."
  [x]
  (and (map? x) (= data-type (:data/type x))))


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
    (s/keys :req [::tablets
                  ::membership
                  ::record/count
                  ::record/families
                  ::record/first-key
                  ::record/last-key])
    partition?))



;; ## Construction Functions

; TODO: make this private and eager
(defn ^:no-doc partition-limited
  "Returns a sequence of partitions of the elements of `coll` such that:
  - No partition has more than `limit` elements
  - The minimum number of partitions is returned
  - Partitions are approximately equal in size

  Note that this counts (and hence realizes) the input collection."
  [limit coll]
  (let [cnt (count coll)
        n (min (int (Math/ceil (/ cnt limit))) cnt)]
    (when (pos? cnt)
      (letfn [(split [i] (int (* (/ i n) cnt)))
              (take-parts
                [i xs]
                (when (seq xs)
                  (lazy-seq
                    (let [length (- (split (inc i)) (split i))
                          [head tail] (split-at length xs)]
                      (cons head (take-parts (inc i) tail))))))]
        (take-parts 0 coll)))))


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
       (partition-limited (::limit params default-limit))
       (mapv #(from-records store params %))))



;; ## Read Functions

(defn- get-tablet
  "Return the tablet data for the given family key."
  [store part family-key]
  (graph/get-link! store part (get (::tablets part) family-key)))


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
    (if (seq fields)
      (->>
        records
        (map (juxt first #(select-keys (second %) fields)))
        (remove (comp empty? second)))
      records)))


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

; TODO: find better pattern than passing limit and half-full around :\

(defn- check-partition
  "Check a partition for validity under the current parameters. Returns a tuple
  containing output valid partitions and a new virtual tablet, if needed."
  [store params limit half-full part]
  (cond
    (< (::record/count part) half-full)
      [nil (tablet/from-records (read-all store part nil))]

    (< limit (::record/count part))
      [(partition-records store params (read-all store part nil)) nil]

    :else
      [[part] nil]))


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


(defn- emit-parts
  "Chop up some pending records in a virtual tablet into full valid partitions.
  Returns a tuple containing a vector of serialized partitions and a virtual
  tablet containing any remaining records."
  [store params threshold part-size pending]
  (loop [result []
         records (tablet/read-all pending)]
    (if (<= threshold (count records))
      ; Serialize a full partition using the pending records.
      (let [[output remnant] (split-at part-size records)
            part (from-records store params output)]
        (recur (conj result part) remnant))
      ; Not enough to guarantee validity, so continue.
      [result (tablet/from-records records)])))


(defn- finish-update
  "Finalize an update by ensuring any remaining pending records are emitted.
  Returns an updated result vector with serialized partition data, or a
  virtual tablet if not enough record are left."
  [store params half-full result pending]
  (cond
    ; No pending data to handle, we're done.
    (nil? pending)
      result

    ; Not enough records left to create a valid partition!
    (< (count (tablet/read-all pending)) half-full)
      (if-let [prev (peek result)]
        ; Load last result link and redistribute into two valid partitions.
        (->> (tablet/read-all pending)
             (concat (read-all store prev nil))
             (partition-records store params)
             (into (pop result)))
        ; No siblings, so return virtual tablet for carrying.
        pending)

    ; Enough records to make one or more valid partitions.
    :else
      (->> (tablet/read-all pending)
           (partition-records store params)
           (into result))))


(defn update-partitions!
  "Apply patch changes to a sequence of partitions and redistribute the results
  to create a new sequence of valid, updated partitions. Each input tuple
  should have the form `[partition changes]`, and `carry` may be a
  forward-carried partition or virtual tablet.

  Returns a sequence of updated valid stored partitions, or a virtual tablet if
  there were not enough records in the result to create a valid partition. The
  sequence of partitions may be empty if all records were removed."
  [store params carry inputs]
  (let [limit (::limit params default-limit)
        half-full (int (Math/ceil (/ limit 2)))
        emit-threshold (+ limit half-full)
        emit-size limit]
    (loop [result []
           pending (when (tablet? carry) carry)
           inputs (if (partition? carry)
                    (cons [carry nil] inputs)
                    inputs)]
      (if (seq inputs)
        ; Process next partition in the sequence.
        (let [[part changes] (first inputs)]
          (if (and (nil? pending) (empty? changes))
            ; No pending records or changes, so use "pass through" logic.
            (let [[parts pending] (check-partition store params limit half-full part)]
              (recur (into result parts) pending (next inputs)))

            ; Load partition data or use virtual tablet records.
            (let [tablet (tablet/from-records (read-all store part nil))
                  tablet' (tablet/join pending (apply-patch tablet changes))]
              (cond
                ; All data was removed from the partition.
                (empty? (tablet/read-all tablet'))
                  (recur result nil (next inputs))

                ; Original partition data was unchanged by updates.
                (= tablet tablet')
                  ; Original linked partition remains unchanged by update.
                  (let [[parts pending] (check-partition store params limit half-full part)]
                    (recur (into result parts) pending (next inputs)))

                ; Accumulated enough records to output full partitions.
                (<= (+ limit half-full) (count (tablet/read-all tablet')))
                  (let [[parts pending] (emit-parts store params emit-threshold emit-size tablet')]
                    (recur (into result parts) pending (next inputs)))

                ; Not enough data to output a partition yet, keep pending.
                :else
                  (recur result tablet' (next inputs))))))

        ; No more partitions to process.
        (finish-update store params half-full result pending)))))


(defn update-root!
  "Apply changes to a partition root node, returning a sequence of
  updated partitions."
  [store params part changes]
  (let [result (update-partitions! store params [[part changes]])]
    (if (sequential? result)
      result
      (when result
        [(from-records store params (tablet/read-all result))]))))


; XXX: In the carry-backward case with a virtual tablet, we need to load the
; last partition, borrow records to fill out the tablet, and write out two
; partitions. In the carry-backward case with a partition, we just append it
; to the list of child partitions. (probably make this a separate function)
