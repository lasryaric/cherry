(ns cherry.core
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :as async :refer [<! >! take! put! chan timeout close!]]
            [clojure.string :as string]
            [cherry.integration.mopidy :as mopidy]
            [cherry.integration.hipchat :as hipchat]
            [cherry.integration.wit :as wit]
            [cherry.util :as util])
  (:require-macros [cljs.core.async.macros :refer [go go-loop alt!]]))

(def util (js/require "util"))
(def fs (js/require "fs"))
(def path (js/require "path"))
(def child_process (js/require "child_process"))

(nodejs/enable-util-print!)

(let [re #"^[^./]+$"]
  (defn npm? [p]
    (boolean (re-find re p))))

(let [re #"\.js$"]
  (defn js? [p]
    (boolean (re-find re p))))

(let [re #"^[^/]+\.[^/]+$"]
  (defn cljs? [p]
    (boolean (re-find re p))))

(defn load-js-module! [m paths ch]
  (loop [ps paths]
    (if-let [p (first ps)]
      (let [full (str p "/" m)
            f (try
                (js/require full)
                (catch :default e nil))]
        (or f (recur (next ps))))
      (throw (str "could not find " m " in " (.inspect util (clj->js paths)))))))

(defn load-cljs-module! [m paths ch]
  (aget (js/eval m) "init"))

(defn load-module! [config firehose m paths ch]
  (let [f (if (cljs? m)
            (load-cljs-module! m paths ch)
            (load-js-module! m paths ch))]
    (f #js {:consume (fn [h]
                       (go-loop []
                         (let [[sender msg] (<! ch)]
                           (when (and (not (nil? msg))
                                      (not (= m sender)))
                             (h (clj->js msg)))
                           (recur))))
            :config config
            :produce (fn [x]
                       (println ">" (str m ":") x)
                       (put! firehose [m x]))})))

(defn load-modules!
  "Load modules from a config file and hook them to the firehose"
  [config paths firehose]
  (println "> Plugins paths:" paths)
  (let [modules (.-plugins config)
        mult (async/mult firehose)]
    (doseq [m modules]
      (println "> Loading" m)
      (let [ch (chan)]
        (async/tap mult ch)
        (load-module! config firehose m paths ch)))))

(defn load-config! [p]
  (-> (.readFileSync fs p) (js/JSON.parse)))

(defn extract-node-paths! [np]
  (when-not (string/blank? np)
    (string/split np #":")))

(defn extract-node-exec! []
  (let [out (chan)
        proc (.spawn child_process "which" #js ["node"])]
    (.stdout.on proc "data" (fn [data]
                              (put! out (.toString data))))

    (.stderr.on proc "data" (fn [data]
                              (println "ERR:" (.toString data))))
    (.stdin.end proc)

    (.on proc "exit" (fn [code]
                       (when (not (= 0 code))
                         (println "exited with" code)
                         (close! out))))

    (go (let [p (<! out)]
          (.normalize path (str p "../../../lib/node_modules"))))))

(defn find-paths! [config-path]
  (go (let [dirname (.dirname path config-path)
            config-dir (->> (.join path (.cwd js/process) dirname)
                            (.normalize path))
            node-paths (extract-node-paths! (.-env.NODE_PATH js/process))
            node-exec (<! (extract-node-exec!))]
        (vec (set (concat [config-dir "/usr/local/lib/node_modules"]
                          node-paths
                          [node-exec]))))))

(defn usage! []
  (println "usage:" (first (.-argv js/process)) "<path/to/config.json>")
  (js/process.exit 1))

(defn -main [& [p args]]
  (aset js/process "title" "cherry")
  (cond (not p)
        (usage!)

        :else
        (do
          (go (let [conf (load-config! p)
                    paths (<! (find-paths! p))
                    port (or (.-port conf) 4433)
                    firehose (chan)]
                (println "cherry starting up on port" port)
                (load-modules! conf paths firehose)))
            (js/setTimeout (fn []) 10000))))

(set! *main-cli-fn* -main)
