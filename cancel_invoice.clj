; 1) move to seubarriga/tasks/
; 2) export DATOMIC_URI as prod
; 3) lein with-profile prod,runner run cancel-invoice 1413 201810

(ns seubarriga.tasks.cancel-invoice
  (:require [datomic.api :as d]
            [seubarriga.app-core :refer [*conn* in? long->year-month]]
            [seubarriga.adapters.entry :as adapters.entry]
            [seubarriga.adapters.invoice :as adapters.invoice]
            [seubarriga.adapters.contract :as adapters.contract]
            [seubarriga.domain.contract :as contract]
            [seubarriga.domain.invoice.core :as invoice]))

(defn cancel-invoice!
  [invoice moment]
  (doseq [fine-and-interest-entry (invoice/fine-and-interest-entries invoice)]
    (prn (adapters.entry/retract! fine-and-interest-entry)))

  (doseq [postponed-entry (invoice/postponed-entries invoice)]
    (prn (adapters.entry/retract! postponed-entry)))

  (doseq [entry (->> (:invoice/entries invoice)
                     (filter #(in? #{:entry.producer/monthly-routine
                                     :entry.producer/monthly-routine-delayed
                                     :entry.producer/onboarding-routine-delayed} (:entry/producer %))))]

    (prn (adapters.entry/retract! entry)))

  (prn (invoice/cancel! invoice moment))

  (when-let [boleto (-> invoice :invoice/payment :boleto/_payment first)]
    (prn @(d/transact *conn* [[:db/retractEntity (:db/id boleto)]]))))

(defn run
  [contract-id year-month]
  (let [contract (adapters.contract/find-by-external-id (Long. contract-id))
        tenant   (contract/tenant-id contract)
        landlord (contract/landlord-id contract)

        accrual-year-month (-> year-month Long. long->year-month)]

    (doseq [account [tenant landlord]]
      (let [invoice (adapters.invoice/for-account account
                                                  accrual-year-month)]

        (prn contract-id (:contract/external-id contract)
             accrual-year-month (:invoice/status invoice) invoice)

        (when invoice
          (cancel-invoice! invoice
                           (java.util.Date.)))))))
