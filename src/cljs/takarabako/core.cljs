(ns takarabako.core
  (:require [konserve.memory :refer [new-mem-store]]
            [replikativ.peer :refer [client-peer]]
            [replikativ.stage :refer [create-stage! connect! subscribe-crdts!]]
            [hasch.core :refer [uuid]]
            [replikativ.crdt.ormap.realize :refer [stream-into-identity!]]
            [replikativ.crdt.ormap.stage :as s]
            [cljs.core.async :refer [>! chan timeout]]
            [superv.async :refer [S] :as sasync]
            [om.next :as om :refer-macros [defui] :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]])
  (:require-macros [superv.async :refer [go-try <? go-loop-try]]
                   [cljs.core.async.macros :refer [go-loop]]))


(def user "mail:alice@replikativ.io")
(def ormap-id #uuid "7d274663-9396-4247-910b-409ae35fe98d")
(def uri (str "ws://"
             (if (= (.. js/window -location -hostname) "localhost")
               "127.0.0.1"
               "46.101.216.210")
             ":31777"))


(enable-console-print!)


                                        ; HELPERS

(defn pprint [s & args]
  (.log js/console (apply pr-str s args)))


(defn target-val [e]
  (.. e -target -value))

                                        ; REPLIKATIV

(def stream-eval-fns
  {'assoc (fn [a new]
            (swap! a assoc-in [:transactions (uuid new)] new)
            a)
   'dissoc (fn [a new]
             (swap! a update-in [:transactions] (fn [txs] (dissoc txs (uuid new))))
             a)})


(defonce val-atom (atom {}))


(defn setup-replikativ []
  (go-try S
          (let [local-store (<? S (new-mem-store))
                local-peer (<? S (client-peer S local-store))
                stage (<? S (create-stage! user local-peer))
                stream (stream-into-identity! stage
                                              [user ormap-id]
                                              stream-eval-fns
                                              val-atom)]
            (<? S (s/create-ormap! stage
                                   :description "messages"
                                   :id ormap-id))
            (connect! stage uri)
            {:store local-store
             :stage stage
             :stream stream
             :peer local-peer})))


(declare client-state)


(defn add-transaction! [app-state tx]
  (s/assoc! (:stage client-state)
            [user ormap-id]
            (uuid tx)
            [['assoc tx]]))

                                        ; QUERIES

(defmulti read (fn [env key params] key))

(defmethod read :default
  [{:keys [state] :as env} key params]
  (let [st @state]
    (if-let [[_ value] (find st key)]
      {:value value}
      {:value :not-found})))

(defmethod read :transactions/list
  [{:keys [state]} key {:keys [sort-key compare-fn]}]
  (let [st @state]
    (if-let [[_ txs] (find st :transactions)]
      (let [process-tx (comp
                        (map (fn [[k v]] (assoc v :id k)))
                        (map (fn [tx] (update-in tx [:value]
                                                 #(/ % 100)))))]
        {:value (sort-by sort-key compare-fn (transduce process-tx conj [] txs))})
      {:value []})))

(def process-tx-value
  (comp (remove nil?)
        (map
         (fn [{:keys [value type]}]
           (if (= type :expense)
             (* -1 value)
             (* 1 value))))
        (map #(/ % 100))))

(defn mean-reducer [memo x]
  (-> memo
      (update-in [:sum] + x)
      (update-in [:count] inc)))

(defmethod read :account/balance
  [{:keys [state] :as env} key {:keys [start-date end-date]}]
  (let [sum-count (reduce (process-tx-value mean-reducer)
                          {:sum 0 :count 0}
                          (vals (:transactions @state)))]
    {:value (assoc sum-count :mean (/ (:sum sum-count) (:count sum-count)))}))

(defmulti mutate om/dispatch)

(defmethod mutate 'transactions/add
  [{:keys [state]} _ tx]
  {:action
   (fn []
     (s/assoc! (:stage client-state)
               [user ormap-id]
               (uuid tx)
               [['assoc tx]]))})

                                        ; VIEWS

(defn input-widget [component placeholder local-key type]
  [:input {:value (get (om/get-state component) local-key)
           :placeholder placeholder
           :type type
           :on-change (fn [e]
                        (om/update-state! component assoc local-key (target-val e)))}])


(defn transactions-widget [transactions]
  [:div
   [:h3 "Transactions"]
   [:table
    [:tbody
     [:tr
      [:th.created "Created"]
      [:th "Description"]
      [:th.value "Value"]]
     (mapv
      (fn [{:keys [id description created value type date] :as tx}]
        [:tr {:key id}
         [:td (-> created
                  js/Date.
                  .toLocaleDateString)]
         [:td.description description]
         [:td.value {:style {:color (if (= type :expense) "#F00" "#0F0")}} value]])
      transactions)]]])


(defn transaction-add-button [component]
  (let [app-state (om/props component)
        {:keys [input-description input-value input-date input-type-toggle]} (om/get-state component)]
      [:button
       {:className (if input-type-toggle "add-income-button" "add-expense-button")
        :on-click (fn [e]
                    (let [new-tx {:description input-description
                                  :created (.getTime (js/Date.))
                                        ;:date #_(.getTime input-date)
                                  :type (if input-type-toggle :expense :income)
                                  :value (-> input-value
                                             js/parseFloat
                                             (* 100)
                                             js/Math.round)}]
                      (do
                        (om/transact! component `[(transactions/add ~new-tx)])
                        (om/update-state! component assoc :input-value "")
                        (om/update-state! component assoc :input-type-toggle true)
                        (om/update-state! component assoc :input-description ""))))}
       (str "Add " (if input-type-toggle "Expense" "Income"))]))


(defn type-toggle-widget [component]
  [:label.switch
   [:input {:type "checkbox"
            :checked (:input-type-toggle (om/get-state component))
            :on-click (fn [_] (om/update-state! component update :input-type-toggle not))}]])


(defui App
  static om/IQueryParams
  (params [this]
    {:start-date (js/Date. 2016) :end-date (js/Date. 2017) :sort-key :created :compare-fn >})
  static om/IQuery
  (query [this]
    '[(:transactions/list {:sort-key ?sort-key :compare-fn ?compare-fn})
      (:account/balance {:start-date ?start-date :end-date ?end-date})])
  Object
  (componentWillMount [this]
    (om/set-state! this {:input-description ""
                         :input-type-toggle true
                         :input-date nil
                         :input-value ""}))
  (render [this]
    (let [{:keys [account/balance transactions/list]} (om/props this)]
      (html
       [:div
        [:div.base-widget
         [:div.widget.input-widget
          [:h3 "New Transaction"]
          [:div.input-container
           (input-widget this "Description" :input-description :text)
           (input-widget this "Value" :input-value :number)
           #_(input-widget this "Date" :input-date :time)
           (type-toggle-widget this)]
          (transaction-add-button this)]
         [:div.widget.balance-widget
          [:h3 "Overview"]
          [:table
           [:tbody
            [:tr [:td "Balance"] [:td.value (:sum balance)]]
            [:tr [:td "Transactions"] [:td.value (:count balance)]]
            [:tr [:td "Mean"] [:td.value (:mean balance)]]]]]]
        [:div.widget
         (transactions-widget list)]]))))


(defn main [& args]
  (go-try S
          (def client-state (<? S (setup-replikativ)))
          (.error js/console "INITIATED")))


(def reconciler
  (om/reconciler {:state val-atom
                  :parser (om/parser {:read read :mutate mutate})}))


(om/add-root! reconciler App (.getElementById js/document "app"))


(comment

  (vals (get-in @val-atom [:transactions]))

  )
