(ns thesis.background.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [goog.string :as gstring]
            [goog.string.format]
            [cljs.core.async :refer [<! chan]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.support :refer-macros [oget]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols :refer [post-message! get-sender]]
            [chromex.ext.tabs :as tabs]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.web-request :as web-request]
            [chromex.ext.browser-action :as browser-action]
            [chromex.ext.storage :as storage]
            [thesis.background.storage :refer [process-request! setup-storage! get-and-store-psl!]]
            [thesis.background.location :as location]))

(enable-console-print!)
(def clients (atom []))
(def location (atom nil))

; -- clients manipulation ---------------------------------------------------------------------------------------------------

(defn add-client! [client]
  (log "BACKGROUND: client connected" (get-sender client))
  (swap! clients conj client))

(defn remove-client! [client]
  (log "BACKGROUND: client disconnected" (get-sender client))
  (let [remove-item (fn [coll item] (remove #(identical? item %) coll))]
    (swap! clients remove-item client)))

; -- client event loop ------------------------------------------------------------------------------------------------------

(defn run-client-message-loop! [client]
  (go-loop []
    (when-let [message (<! client)]
      (log "BACKGROUND: got client message:" message "from tab: " (.. (get-sender client) -tab -id))
      (recur))
    (remove-client! client)))

; -- event handlers ---------------------------------------------------------------------------------------------------------

(defn handle-client-connection! [client]
  (add-client! client)
  (post-message! client "hello from BACKGROUND PAGE!")
  (run-client-message-loop! client))

(defn tell-clients-about-new-tab! []
  (doseq [client @clients]
    (post-message! client "a new tab was created")))

(defn tell-client-about-click! [id]
  (post-message!
    (first (filter #(= (.. (get-sender %) -tab -id) id) @clients))
    "launch"))

; -- main event loop --------------------------------------------------------------------------------------------------------

(defn process-chrome-event [event-num event]
  (let [[event-id event-args] event]
    ;(log (gstring/format "BACKGROUND: got chrome event (%05d)" event-num) (str event-id))
    (case event-id
      ::browser-action/on-clicked (tell-client-about-click! (oget (first event-args) "id"))
      ::storage/on-changed (.. js/chrome -storage -local (get #(reset! location %)))
      ::web-request/on-before-request (-> event-args (first) (process-request! @location))
      ::runtime/on-connect (apply handle-client-connection! event-args)
      ::tabs/on-created (tell-clients-about-new-tab!)
      nil)))

(defn run-chrome-event-loop! [chrome-event-channel]
  (log "BACKGROUND: starting main event loop...")
  (go-loop [event-num 1]
    (when-let [event (<! chrome-event-channel)]
      (process-chrome-event event-num event)
      (recur (inc event-num)))
    (log "BACKGROUND: leaving main event loop")))

(defn boot-chrome-event-loop! []
  (let [chrome-event-channel (make-chrome-event-channel (chan))]
    (tabs/tap-all-events chrome-event-channel)
    (runtime/tap-all-events chrome-event-channel)
    (storage/tap-all-events chrome-event-channel)
    (browser-action/tap-on-clicked-events chrome-event-channel)
    (web-request/tap-on-before-request-events chrome-event-channel (clj->js {:urls ["http://*/*", "https://*/*"]}))
    (run-chrome-event-loop! chrome-event-channel)))

; -- main entry point -------------------------------------------------------------------------------------------------------

(defn init! []
  (log "BACKGROUND: init")
  (location/watch-location!)
  (setup-storage!)
  (get-and-store-psl!)
  (boot-chrome-event-loop!))
