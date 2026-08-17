(ns kotoba.taxlaw-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [kotoba.taxlaw :as taxlaw]))

;; ---------------------------------------------------------------------------
;; The invariant this library exists for
;; ---------------------------------------------------------------------------

(deftest an-uncatalogued-jurisdiction-is-never-sufficiency
  (testing "coverage"
    (is (taxlaw/covered? [:jp]))
    (is (taxlaw/covered? :jp) "a bare keyword is accepted too")
    (is (not (taxlaw/covered? [:atlantis])))
    (is (not (taxlaw/covered? nil)) "undeclared is unchecked, not default"))

  (testing "requires-qualified-invoice? is nil, not false, when unknown"
    (is (true? (taxlaw/requires-qualified-invoice? [:jp])))
    (is (nil? (taxlaw/requires-qualified-invoice? [:atlantis]))
        "false would read as `there is no requirement`")
    (is (nil? (taxlaw/requires-qualified-invoice? nil))))

  (testing "retention-years is nil, not zero, when unknown"
    (is (= 7 (taxlaw/retention-years [:jp])))
    (is (nil? (taxlaw/retention-years [:atlantis])))
    (is (nil? (taxlaw/retention-years nil))))

  (testing "an uncatalogued jurisdiction cannot validate anything"
    (is (not (taxlaw/registration-number-valid? [:atlantis] "T1234567890123")))
    (is (not (taxlaw/registration-number-valid? nil "T1234567890123")))))

(deftest credit-support-separates-refused-from-not-checked
  (testing "not checked"
    (let [r (taxlaw/credit-support [:atlantis] {:registration-number "T1234567890123"})]
      (is (= :none (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/supported?))
          "absent on purpose: nil is falsey, so the careless caller is safe,
           and the careful one can tell this from a refusal")
      (is (= [[:atlantis]] (:taxlaw/unchecked r)))))

  (testing "checked and refused"
    (let [r (taxlaw/credit-support [:jp] {:registration-number nil})]
      (is (= :checked (:taxlaw/coverage r)))
      (is (false? (:taxlaw/supported? r)))
      (is (= :missing-registration-number (:taxlaw/reason r))))
    (let [r (taxlaw/credit-support [:jp] {:registration-number "1234567890123"})]
      (is (false? (:taxlaw/supported? r)))
      (is (= :malformed-registration-number (:taxlaw/reason r)))))

  (testing "checked and supported"
    (let [r (taxlaw/credit-support [:jp] {:registration-number "T1234567890123"})]
      (is (true? (:taxlaw/supported? r)))
      (is (nil? (:taxlaw/reason r)))))

  (testing "the convenient boolean gives the conservative answer"
    (is (taxlaw/supported? [:jp] {:registration-number "T1234567890123"}))
    (is (not (taxlaw/supported? [:jp] {:registration-number nil})))
    (is (not (taxlaw/supported? [:atlantis] {:registration-number "T1234567890123"}))
        "an unchecked jurisdiction must not come back true through the shortcut")))

;; ---------------------------------------------------------------------------
;; The registration-number format
;; ---------------------------------------------------------------------------

(deftest registration-number-format
  (testing "国税庁 publication site: T followed by 13 digits"
    (is (taxlaw/registration-number-valid? [:jp] "T1234567890123")))
  (testing "absence, wrong length, wrong prefix and stray whitespace all fail"
    (doseq [n [nil "" "   " "T123456789012" "T12345678901234" "1234567890123"
               "T1234567890123 " " T1234567890123" "t1234567890123"
               "TX234567890123"]]
      (is (not (taxlaw/registration-number-valid? [:jp] n))
          (str "should reject " (pr-str n))))))

;; ---------------------------------------------------------------------------
;; The catalog
;; ---------------------------------------------------------------------------

(deftest every-source-is-well-formed
  (doseq [[id s] taxlaw/sources]
    (testing (str id)
      (is (not (str/blank? (:source/title s))))
      (is (not (str/blank? (:source/authority s))))
      (is (contains? #{:statute :guidance} (:source/kind s)))
      (is (str/starts-with? (:source/url s) "https://")))))

(deftest statutes-carry-a-law-id-and-guidance-does-not
  (doseq [[id s] taxlaw/sources]
    (testing (str id)
      (if (= :statute (:source/kind s))
        (is (some? (:law/id s))
            "a statute without a law id cannot be checked against the corpus")
        (is (nil? (:law/id s))
            "guidance pages are not in the law corpus; claiming an id would
             make the checker look for something that is not there")))))

(deftest law-ids-are-distinct
  (let [ids (taxlaw/law-ids)]
    (is (= (count ids) (count (distinct ids))))
    (is (seq ids) "a catalog with no checkable citation is not checkable")))

(deftest source-urls-are-distinct
  (let [urls (taxlaw/source-urls)]
    (is (= (count urls) (count (distinct urls))))))

(deftest verification-record-separates-its-claims
  (let [v taxlaw/catalog-verification]
    (is (pos? (:catalog/statute-count v)))
    (is (pos? (:catalog/guidance-count v)))
    (is (= (count taxlaw/sources)
           (+ (:catalog/statute-count v) (:catalog/guidance-count v)))
        "every source is one kind or the other")
    (testing "content was verified for strictly fewer claims than there are sources"
      (is (< (count (:catalog/content-verified v)) (count taxlaw/sources))))
    (testing "candidates that were considered and NOT cited leave a trace"
      (is (seq (:catalog/rejected v)))
      (doseq [r (:catalog/rejected v)]
        (is (not (str/blank? (:why r))))))))

(deftest jurisdiction-paths-not-codes
  (testing "keys are paths, so [:jp :tokyo] needs no rename later"
    (doseq [k (keys taxlaw/jurisdictions)]
      (is (vector? k)))))

;; ---------------------------------------------------------------------------
;; 電子帳簿保存法 第七条 — the one rule whose statutory text was read
;; ---------------------------------------------------------------------------

(deftest electronic-record-rule-is-marked-as-read-not-merely-cited
  (let [r (get-in taxlaw/jurisdictions [[:jp] :jurisdiction/electronic-transaction])]
    (is (= :read-from-source (:rule/review r))
        "this rule enforces something, so citing it was not enough")
    (is (= "電子帳簿保存法 第七条" (:rule/provision r)))
    (is (str/includes? (:rule/quote r) "電磁的記録"))
    (is (= "2026-08-17" (:rule/retrieved-at r)))
    (testing "the article's own scope is recorded, not widened"
      (is (= #{:income-tax :corporation-tax} (:rule/applies-to r))))))

(deftest the-verification-record-lists-this-second-read-claim
  (let [claims (:catalog/content-verified taxlaw/catalog-verification)]
    (is (= 2 (count claims)))
    (is (some #(= :electronic-transaction-record-preservation (:claim %)) claims))
    (testing "read claims are still strictly fewer than sources"
      (is (< (count claims) (count taxlaw/sources))))))

(deftest requires-electronic-record-is-nil-not-false-when-unknown
  (is (true? (taxlaw/requires-electronic-record? [:jp])))
  (is (nil? (taxlaw/requires-electronic-record? [:atlantis])))
  (is (nil? (taxlaw/requires-electronic-record? nil))))

(deftest record-preservation-is-three-valued
  (testing "nobody catalogued this jurisdiction"
    (let [r (taxlaw/record-preservation [:atlantis]
                                        {:origin :electronic-transaction
                                         :preservation :paper})]
      (is (= :none (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/preserved?)))))

  (testing "the document does not say how the transaction happened"
    (let [r (taxlaw/record-preservation [:jp] {:preservation :paper})]
      (is (= :not-declared (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/preserved?))
          "nothing was asserted, so nothing was checked — and it must be
           possible to tell that from a refusal")))

  (testing "an electronic transaction kept only on paper"
    (let [r (taxlaw/record-preservation [:jp] {:origin :electronic-transaction
                                               :preservation :paper})]
      (is (= :checked (:taxlaw/coverage r)))
      (is (false? (:taxlaw/preserved? r)))
      (is (= :electronic-record-not-preserved (:taxlaw/reason r)))
      (is (= "電子帳簿保存法 第七条" (:taxlaw/provision r))
          "a refusal names the article it rests on")))

  (testing "an electronic transaction whose preservation was never recorded"
    (let [r (taxlaw/record-preservation [:jp] {:origin :electronic-transaction})]
      (is (false? (:taxlaw/preserved? r)))
      (is (= :preservation-not-recorded (:taxlaw/reason r))
          "silence about preservation is not preservation")))

  (testing "an electronic transaction preserved electronically"
    (let [r (taxlaw/record-preservation [:jp] {:origin :electronic-transaction
                                               :preservation :electronic})]
      (is (true? (:taxlaw/preserved? r)))
      (is (nil? (:taxlaw/reason r)))))

  (testing "a paper transaction on paper raises no article 7 question"
    (let [r (taxlaw/record-preservation [:jp] {:origin :paper :preservation :paper})]
      (is (true? (:taxlaw/preserved? r)))
      (is (false? (:taxlaw/electronic-record-required? r))))))

(deftest preserved?-is-conservative-like-supported?
  (is (taxlaw/preserved? [:jp] {:origin :electronic-transaction
                                :preservation :electronic}))
  (is (not (taxlaw/preserved? [:jp] {:origin :electronic-transaction
                                     :preservation :paper})))
  (testing "neither :none nor :not-declared established preservation"
    (is (not (taxlaw/preserved? [:atlantis] {:origin :electronic-transaction
                                             :preservation :electronic})))
    (is (not (taxlaw/preserved? [:jp] {:preservation :electronic})))))
