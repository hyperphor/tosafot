(ns tosafot.files
  "The notebook folder: its File System Access handle, kept in the extension's IndexedDB, and
  writing notes into its daily notes."
  (:require [tosafot.pkg :as pkg]))

;;; Handles can't go in chrome.storage, so they get their own small database.

(def ^:private db-name "tosafot-files")
(def ^:private store "handles")
(def ^:private graph-key "graph")

(defn- req->promise [^js r]
  (js/Promise. (fn [ok fail]
                 (set! (.-onsuccess r) #(ok (.-result r)))
                 (set! (.-onerror r) #(fail (.-error r))))))

(defn- open-db []
  (let [r (js/indexedDB.open db-name 1)]
    (set! (.-onupgradeneeded r) #(.createObjectStore (.-result r) store))
    (req->promise r)))

(defn- with-store [mode f]
  (.then (open-db) (fn [^js db] (req->promise (f (.objectStore (.transaction db store mode) store))))))

(defn graph-handle
  "Promise of the stored graph folder handle, or nil."
  []
  (with-store "readonly" #(.get ^js % graph-key)))

(defn set-graph-handle! [handle]
  (with-store "readwrite" #(if handle (.put ^js % handle graph-key) (.delete ^js % graph-key))))

(def ^:private rw #js {:mode "readwrite"})

(defn permission
  "Promise of \"granted\", \"prompt\" or \"denied\" for writing to `handle`."
  [^js handle]
  (.queryPermission handle rw))

(defn request-permission
  "Ask for write access; needs a user gesture, so only from an extension page."
  [^js handle]
  (.requestPermission handle rw))

(defn journals-dir
  "Promise of the folder notes go in: `handle`'s journals/ subfolder if it has one (a Logseq
  graph), else `handle` itself (a daily notes folder)."
  [^js handle]
  (.catch (.getDirectoryHandle handle "journals") (constantly handle)))

(defn- file-names
  "Promise of the file names in `dir`."
  [^js dir]
  (let [it (.keys dir)]
    (js/Promise.
     (fn [ok fail]
       (let [step (fn step [acc]
                    (-> (.next it)
                        (.then (fn [^js r] (if (.-done r) (ok acc) (step (conj acc (.-value r))))))
                        (.catch fail)))]
         (step []))))))

(defn- read-text [^js dir name]
  (-> (.getFileHandle dir name)
      (.then #(.getFile ^js %))
      (.then #(.text ^js %))
      (.catch (constantly nil))))

(defn- write-text! [^js dir name text]
  (-> (.getFileHandle dir name #js {:create true})
      (.then #(.createWritable ^js %))
      (.then (fn [^js w] (-> (.write w text) (.then #(.close w)))))))

(defn- today [] (let [d (js/Date.)] (pkg/journal-name (.getFullYear d) (inc (.getMonth d)) (.getDate d))))

(defn- error [code msg] (doto (js/Error. msg) (aset "code" code)))

(defn export!
  "Write `ann` into the graph: replacing its block in the journal it went to before, if it's
  still there, else at the end of the latest journal. Promise of the journal file name.
  Rejects with .code \"not-configured\" or \"permission\"."
  [ann tag]
  (let [id (str (:annotation/id ann))
        block (pkg/render [(pkg/note-block ann tag)])]
    (-> (graph-handle)
        (.then (fn [h]
                 (if-not h
                   (throw (error "not-configured" "No notebook folder is set."))
                   (.then (permission h)
                          (fn [p]
                            (if (= "granted" p)
                              (journals-dir h)
                              (throw (error "permission" "Tosafot needs permission to write to the notebook folder."))))))))
        (.then (fn [dir]
                 (let [prev (:annotation/pkg-file ann)]
                   (-> (if prev (read-text dir prev) (js/Promise.resolve nil))
                       (.then (fn [text]
                                (if (and text (pkg/contains-block? text id))
                                  [prev text]
                                  (.then (file-names dir)
                                         (fn [names]
                                           (let [name (or (pkg/latest-journal names) (today))]
                                             (.then (read-text dir name) (fn [t] [name t]))))))))
                       (.then (fn [[name text]]
                                (.then (write-text! dir name (pkg/upsert text block id))
                                       (constantly name)))))))))))
