(ns xi.ext.clj-socket
  "Synchronous TCP sockets for the clj sandbox — the `socket` SCI namespace.

   SCI evals run synchronously on the room's worker thread, but JS sockets
   are async-only. Each (socket/connect host port) spawns a nested
   worker_threads Worker from the same bundle (dispatched by the workerData
   :role in xi.cli/main → bridge-install!) that owns the async net.Socket.
   Received bytes stream through a SharedArrayBuffer ring buffer; the eval
   thread blocks on it with Atomics.wait — abortable like process/wait, so a
   turn-end (ESC) wakes a blocked read. Writes are postMessage'd to the
   bridge, whose event loop applies them to the socket.

   Loopback only: connect refuses non-loopback hosts at runtime (so a
   dynamically built host string can't bypass it) — raw TCP to remote hosts
   stays out of the sandbox; (curl …) covers remote http(s).

   Sockets persist across evals like the rest of the room's REPL state
   ((def sock (socket/connect …)) once, reuse later). They die with the
   room's worker (nested workers terminate with their parent thread) or via
   (socket/close s)."
  (:require [clojure.string :as str]
            ["node:net" :as net]
            ["node:worker_threads" :as wt]))

;; ── Shared layout ────────────────────────────────────────────────────────────
;; ctrl Int32Array slots (over a 16-byte SharedArrayBuffer):

(def ^:private ST 0)    ;; status: see ST-* below
(def ^:private HEAD 1)  ;; ring write index (bridge-owned)
(def ^:private TAIL 2)  ;; ring read index (parent-owned)
(def ^:private ERR 3)   ;; UTF-8 byte length of the error text in the err sab

(def ^:private ST-CONNECTING 0)
(def ^:private ST-OPEN 1)
(def ^:private ST-CLOSED 2)
(def ^:private ST-ERROR 3)

(def ^:private RING-CAP (* 1024 1024))
(def ^:private ERR-CAP 512)

(defn ring-used
  "Bytes currently readable in the ring: distance from tail to head, wrapped.
   The ring holds at most (dec cap) so head == tail is unambiguously empty."
  ([head tail] (ring-used head tail RING-CAP))
  ([head tail cap] (mod (- head tail) cap)))

;; ── Bridge side (nested worker) ──────────────────────────────────────────────

(defn bridge-install!
  "Entry when this bundle is loaded as a socket-bridge worker (see the
   workerData :role dispatch in xi.cli/main): connect to workerData's
   host:port, pump received socket bytes into the shared ring buffer, apply
   write/close ops posted by the parent."
  []
  (let [^js wd wt/workerData
        ctrl  (js/Int32Array. (.-ctrl wd))
        data  (js/Uint8Array. (.-data wd))
        errb  (js/Uint8Array. (.-err wd))
        sock  (net/connect #js {:host (.-host wd) :port (.-port wd)})
        queue #js []
        ;; Status only ever advances (connecting < open < closed < error-wins
        ;; is not quite ordered — error (3) must survive the close event that
        ;; follows it, and (< 2 3) makes that hold).
        status! (fn [s]
                  (when (< (js/Atomics.load ctrl ST) s)
                    (js/Atomics.store ctrl ST s)
                    (js/Atomics.notify ctrl ST))
                  ;; wake a parent blocked on HEAD so it re-checks status
                  (js/Atomics.notify ctrl HEAD))
        drain!  (fn drain! []
                  (loop []
                    (if-let [^js chunk (aget queue 0)]
                      (let [head  (js/Atomics.load ctrl HEAD)
                            used  (ring-used head (js/Atomics.load ctrl TAIL))
                            space (- (dec RING-CAP) used)
                            n     (min space (.-length chunk))]
                        (if (zero? n)
                          ;; Ring full: pause the socket and poll for the
                          ;; parent consuming (it updates TAIL without
                          ;; notifying — the bridge can't block on Atomics).
                          (do (.pause sock)
                              (js/setTimeout drain! 10))
                          (let [len1 (min n (- RING-CAP head))]
                            (.set data (.subarray chunk 0 len1) head)
                            (when (< len1 n)
                              (.set data (.subarray chunk len1 n) 0))
                            (if (< n (.-length chunk))
                              (aset queue 0 (.subarray chunk n))
                              (.shift queue))
                            (js/Atomics.store ctrl HEAD (mod (+ head n) RING-CAP))
                            (js/Atomics.notify ctrl HEAD)
                            (recur))))
                      (.resume sock))))]
    (.on sock "connect" (fn [] (status! ST-OPEN)))
    (.on sock "data" (fn [^js buf] (.push queue buf) (drain!)))
    (.on sock "error"
         (fn [^js e]
           (let [bytes (.encode (js/TextEncoder.) (str (.-message e)))
                 n     (min (.-length bytes) ERR-CAP)]
             (.set errb (.subarray bytes 0 n) 0)
             (js/Atomics.store ctrl ERR n))
           (status! ST-ERROR)))
    (.on sock "close" (fn [_] (status! ST-CLOSED)))
    (when-let [^js port wt/parentPort]
      (.on port "message"
           (fn [^js m]
             (case (.-op m)
               "write" (.write sock (js/Buffer.from (.-bytes m)))
               "close" (do (.destroy sock)
                           (status! ST-CLOSED)
                           (js/process.exit 0))
               nil))))))

;; ── Parent side (eval thread) ────────────────────────────────────────────────

(defonce ^:private sockets (atom {}))  ;; id → entry (worker-local, like procs)
(defonce ^:private next-id (atom 0))

(defn loopback-host?
  "True for hosts the sandbox may open raw TCP to: localhost, 127.x.x.x, ::1."
  [host]
  (let [h (str/lower-case (str/trim (str host)))]
    (boolean
     (or (contains? #{"localhost" "::1" "0:0:0:0:0:0:0:1" "::ffff:127.0.0.1"} h)
         (re-matches #"127(\.\d{1,3}){3}" h)))))

(defn- check-abort!
  "Throw when the eval's abort flag was flipped (turn ended) so a blocked
   read/connect unwinds instead of outliving its turn."
  [opts]
  (when-let [^js arr (:abort-arr @opts)]
    (when-not (zero? (js/Atomics.load arr 0))
      (throw (ex-info "clj: aborted (turn ended)" {:aborted true})))))

(defn- err-text [ctrl ^js errb fallback]
  (let [n (js/Atomics.load ctrl ERR)]
    (if (pos? n)
      ;; .slice copies out of the SharedArrayBuffer — TextDecoder refuses
      ;; SAB-backed views.
      (.decode (js/TextDecoder.) (.slice errb 0 n))
      fallback)))

(defn- entry-of [s]
  (let [id (if (map? s) (:id s) s)]
    (or (get @sockets id)
        (throw (ex-info (str "socket: unknown or closed socket " (pr-str s)
                             " — (socket/connect host port) first")
                        {:socket s})))))

(defn- pull-ring!
  "Move all currently readable ring bytes into the entry's local byte buffer
   (a plain JS array), advancing TAIL. Buffering locally lets :until/:n reads
   stop exactly at their boundary without losing follow-on bytes."
  [{:keys [ctrl ^js data ^js buf]}]
  (let [head (js/Atomics.load ctrl HEAD)
        tail (js/Atomics.load ctrl TAIL)
        n    (ring-used head tail)]
    (when (pos? n)
      (dotimes [i n]
        (.push buf (aget data (mod (+ tail i) RING-CAP))))
      (js/Atomics.store ctrl TAIL (mod (+ tail n) RING-CAP)))
    n))

(defn index-of-subseq
  "Index of the byte sequence `delim` (indexable) inside the JS array `buf`,
   or -1."
  [^js buf ^js delim]
  (let [dl (.-length delim)
        bl (.-length buf)]
    (loop [i 0]
      (cond
        (> (+ i dl) bl) -1
        (loop [j 0]
          (cond (= j dl) true
                (= (aget buf (+ i j)) (aget delim j)) (recur (inc j))
                :else false)) i
        :else (recur (inc i))))))

(defn- take-buf!
  "Remove and return the first `k` bytes of the local buffer as a Uint8Array."
  [^js buf k]
  (let [out (js/Uint8Array. k)]
    (dotimes [i k] (aset out i (aget buf i)))
    (.splice buf 0 k)
    out))

(def ^:private CONNECT-TIMEOUT 10000)
(def ^:private READ-TIMEOUT 30000)

(defn- connect!
  "Open a TCP connection to a loopback host — spawn the bridge worker, block
   until it reports open/error. Returns the socket handle map."
  [opts host port & [{:keys [timeout-ms]}]]
  (when-not (loopback-host? host)
    (throw (ex-info (str "socket: only loopback hosts are allowed "
                         "(localhost / 127.x.x.x / ::1) — got "
                         (pr-str (str host))
                         ". Use (curl …) for remote http(s).")
                    {:host (str host)})))
  (when-not (and (number? port) (pos? port) (< port 65536))
    (throw (ex-info (str "socket: invalid port " (pr-str port)) {})))
  (let [ctrl-sab (js/SharedArrayBuffer. 16)
        data-sab (js/SharedArrayBuffer. RING-CAP)
        err-sab  (js/SharedArrayBuffer. ERR-CAP)
        ctrl     (js/Int32Array. ctrl-sab)
        errb     (js/Uint8Array. err-sab)
        worker   (wt/Worker. (aget js/process.argv 1)
                             #js {:workerData #js {:role "xi-socket-bridge"
                                                   :host (str host)
                                                   :port port
                                                   :ctrl ctrl-sab
                                                   :data data-sab
                                                   :err  err-sab}})
        deadline (+ (.now js/Date) (or timeout-ms CONNECT-TIMEOUT))]
    (try
      (loop []
        (let [st (js/Atomics.load ctrl ST)]
          (cond
            (= st ST-OPEN)
            (let [id (swap! next-id inc)]
              (swap! sockets assoc id
                     {:id id :host (str host) :port port :worker worker
                      :ctrl ctrl :data (js/Uint8Array. data-sab) :errb errb
                      :buf #js []})
              {:id id :host (str host) :port port})

            (or (= st ST-ERROR) (= st ST-CLOSED))
            (throw (ex-info (str "socket: connect to " host ":" port
                                 " failed — "
                                 (err-text ctrl errb "connection closed"))
                            {:host (str host) :port port}))

            (> (.now js/Date) deadline)
            (throw (ex-info (str "socket: connect to " host ":" port
                                 " timed out")
                            {:host (str host) :port port}))

            :else
            (do (js/Atomics.wait ctrl ST ST-CONNECTING 100)
                (check-abort! opts)
                (recur)))))
      (catch :default e
        (try (.terminate worker) (catch :default _))
        (throw e)))))

(defn- read*
  "Blocking read. With no opts: block until ≥1 byte is available and return
   everything buffered. {:n k} returns exactly k bytes; {:until s} returns the
   bytes before the delimiter (delimiter consumed, not returned). Strings by
   default; {:bytes? true} → vector of ints. nil on clean EOF (default read
   only). Throws on error, timeout, or EOF mid-:n/:until."
  [opts s {:keys [n until timeout-ms bytes?]}]
  (let [{:keys [ctrl ^js buf] :as entry} (entry-of s)
        delim    (when until (.encode (js/TextEncoder.) (str until)))
        timeout  (or timeout-ms READ-TIMEOUT)
        deadline (+ (.now js/Date) timeout)
        decode   (fn [^js u8]
                   (if bytes?
                     (vec (js/Array.from u8))
                     (.decode (js/TextDecoder.) u8)))
        take-satisfied!
        (fn []
          (cond
            n     (when (>= (.-length buf) n) (take-buf! buf n))
            delim (let [i (index-of-subseq buf delim)]
                    (when (>= i 0)
                      (let [head (take-buf! buf i)]
                        (.splice buf 0 (.-length delim))
                        head)))
            :else (when (pos? (.-length buf))
                    (take-buf! buf (.-length buf)))))]
    (loop []
      (pull-ring! entry)
      (if-let [u8 (take-satisfied!)]
        (decode u8)
        (let [st (js/Atomics.load ctrl ST)]
          (cond
            (= st ST-ERROR)
            (throw (ex-info (str "socket: error — "
                                 (err-text ctrl (:errb entry) "unknown error"))
                            {:socket s}))

            (= st ST-CLOSED)
            (if (and (nil? n) (nil? delim))
              nil ;; clean EOF (buffer is empty, else take-satisfied! hit)
              (throw (ex-info (str "socket: closed before the read completed ("
                                   (.-length buf) " bytes buffered)")
                              {:socket s :buffered (.-length buf)})))

            (> (.now js/Date) deadline)
            (throw (ex-info (str "socket: read timed out after " timeout "ms ("
                                 (.-length buf) " bytes buffered)")
                            {:socket s :buffered (.-length buf)}))

            :else
            (do (js/Atomics.wait ctrl HEAD (js/Atomics.load ctrl HEAD) 100)
                (check-abort! opts)
                (recur))))))))

(defn- ->u8 [data]
  (cond
    (string? data)                  (.encode (js/TextEncoder.) data)
    (instance? js/Uint8Array data)  data
    (sequential? data)              (js/Uint8Array.from
                                     (into-array (map #(bit-and (int %) 0xff) data)))
    :else (throw (ex-info "socket: write expects a string or a seq of bytes"
                          {:data data}))))

(defn- write!
  "Send data (string → UTF-8, or seq of ints 0–255) → byte count sent."
  [_opts s data]
  (let [{:keys [^js worker ctrl]} (entry-of s)]
    (when (>= (js/Atomics.load ctrl ST) ST-CLOSED)
      (throw (ex-info "socket: socket is closed" {:socket s})))
    (let [u8 (->u8 data)]
      (.postMessage worker #js {:op "write" :bytes u8})
      (.-length u8))))

(defn- close! [s]
  (let [id (if (map? s) (:id s) s)]
    (when-let [{:keys [^js worker]} (get @sockets id)]
      (swap! sockets dissoc id)
      ;; The bridge destroys the socket and exits itself on the close op;
      ;; terminate is the fallback if it never processes the message.
      (try (.postMessage worker #js {:op "close"}) (catch :default _))
      (js/setTimeout (fn [] (try (.terminate worker) (catch :default _))) 250))
    nil))

(defn- open?* [s]
  (boolean
   (when-let [{:keys [ctrl]} (get @sockets (if (map? s) (:id s) s))]
     (= ST-OPEN (js/Atomics.load ctrl ST)))))

(defn- list-socks []
  (->> (vals @sockets)
       (sort-by :id)
       (mapv (fn [{:keys [id host port ctrl ^js buf]}]
               {:id id :host host :port port
                :status   (case (js/Atomics.load ctrl ST)
                            0 :connecting 1 :open 2 :closed 3 :error)
                :buffered (.-length buf)}))))

(defn sci-namespace
  "The `socket` namespace injected into the SCI ctx (see xi.ext.clj/make-ctx).
   `opts` is the room runtime's opts atom — carries :abort-arr for the
   current eval so blocked reads wake on turn-end."
  [opts]
  {'connect (fn [host port & [opt]] (connect! opts host port opt))
   'write   (fn [s data] (write! opts s data))
   'read    (fn [s & [opt]] (read* opts s opt))
   'close   (fn [s] (close! s))
   'open?   (fn [s] (open?* s))
   'list    (fn [] (list-socks))})
