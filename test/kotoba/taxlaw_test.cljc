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

(deftest the-verification-record-lists-every-read-claim
  (let [claims (:catalog/content-verified taxlaw/catalog-verification)]
    (is (= 7 (count claims)))
    (is (some #(= :qualified-invoice-tax-amount-calculation (:claim %)) claims))
    (is (some #(= :electronic-transaction-record-preservation (:claim %)) claims))
    (is (some #(= :book-search-function-for-preferential-treatment (:claim %)) claims))
    (is (some #(= :electronic-transaction-search-function (:claim %)) claims))
    (is (some #(= :employment-income-withholding-obligation (:claim %)) claims))
    (is (some #(= :year-end-adjustment (:claim %)) claims))
    (testing "read claims are still strictly fewer than sources"
      (is (< (count claims) (count taxlaw/sources))))
    (testing "a partial quote says it is partial and says what it omits"
      (doseq [c claims :when (:quote-is-partial? c)]
        (is (not (str/blank? (:quote-omits c)))
            "quoting part of an article without saying which part is how a
             quote stops being evidence")))))

(deftest every-enforced-rule-was-read-not-merely-cited
  ;; The library's standing rule, as an assertion rather than a paragraph:
  ;; each facet below backs a function callers gate on, so `:reachable-not-read`
  ;; is not good enough for any of them.
  (doseq [facet [:jurisdiction/electronic-transaction
                 :jurisdiction/wage-withholding
                 :jurisdiction/year-end-adjustment]]
    (testing (str facet)
      (let [r (get-in taxlaw/jurisdictions [[:jp] facet])]
        (is (= :read-from-source (:rule/review r)))
        (is (not (str/blank? (:rule/provision r))))
        (is (not (str/blank? (:rule/quote r))))
        (is (= "2026-08-17" (:rule/retrieved-at r)))))))

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

;; ---------------------------------------------------------------------------
;; 源泉徴収 — 所得税法 第百八十三条第一項
;; ---------------------------------------------------------------------------

(deftest the-withholding-rule-records-the-articles-own-scope
  (let [r (get-in taxlaw/jurisdictions [[:jp] :jurisdiction/wage-withholding])]
    (is (= "所得税法 第百八十三条第一項" (:rule/provision r)))
    (testing "the quote is the article's text, not a paraphrase of it"
      ;; Measured 2026-08-17: an earlier version of this test asserted the
      ;; fragment 「所得税を徴収し」, and a mutation that rewrote the duty as
      ;; 「所得税を徴収してもよく」 — turning a shall into a may — left the
      ;; suite GREEN, because 徴収し is a prefix of 徴収してもよく. A quote
      ;; assertion that a paraphrase can satisfy is not evidence of a quote,
      ;; so the whole operative clause is pinned, ending at the 義務.
      (is (str/includes?
           (:rule/quote r)
           (str "居住者に対し国内において第二十八条第一項（給与所得）に規定する"
                "給与等（以下この章において「給与等」という。）の支払をする者は、"
                "その支払の際、その給与等について所得税を徴収し、その徴収の日の"
                "属する月の翌月十日までに、これを国に納付しなければならない。"))))
    (testing "the scope in the text is recorded, not widened"
      (is (= {:recipient :resident :place :domestic
              :payment-kind :employment-income}
             (:rule/scope r)))
      (is (= #{:employment-income} (:rule/applies-to r))))
    (testing "what was NOT read is named"
      (is (not (str/blank? (:rule/amount-source-not-read r)))
          "the tax TABLES were not read, so no amount here is verified"))))

(deftest requires-wage-withholding?-is-nil-not-false-when-unknown
  (is (true? (taxlaw/requires-wage-withholding? [:jp])))
  (is (nil? (taxlaw/requires-wage-withholding? [:atlantis])))
  (is (nil? (taxlaw/requires-wage-withholding? nil))))

(def ^:private jp-wage
  {:payment-kind :employment-income
   :recipient-residency :resident
   :paid-in :domestic})

(deftest withholding-obligation-is-four-valued
  (testing "nobody catalogued this jurisdiction"
    (let [r (taxlaw/withholding-obligation [:atlantis] jp-wage)]
      (is (= :none (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/accounted-for?))
          "absent on purpose, exactly as credit-support omits :supported?")
      (is (= [[:atlantis]] (:taxlaw/unchecked r)))))

  (testing "the record does not say what kind of payment this is"
    (let [r (taxlaw/withholding-obligation [:jp] {:income-tax-withheld 1000})]
      (is (= :not-declared (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/accounted-for?))
          "nothing was asserted, so nothing was checked")))

  (testing "declared outside the one article that was read"
    (doseq [[label p] [[:non-resident (assoc jp-wage :recipient-residency :non-resident)]
                       [:overseas (assoc jp-wage :paid-in :overseas)]
                       [:not-wages (assoc jp-wage :payment-kind :contractor-fee)]]]
      (testing (str label)
        (let [r (taxlaw/withholding-obligation [:jp] (assoc p :income-tax-withheld nil))]
          (is (= :out-of-scope (:taxlaw/coverage r)))
          (is (not (contains? r :taxlaw/accounted-for?))
              "out-of-scope is NOT a finding that no obligation exists —
               other provisions govern these and none of them was read")
          (is (= "所得税法 第百八十三条第一項" (:taxlaw/read-provision r))
              "it names the article it DID read, so a caller can see the limit")
          (is (not (str/blank? (:taxlaw/why r))))))))

  (testing "in scope and not accounted for"
    (let [r (taxlaw/withholding-obligation [:jp] jp-wage)]
      (is (= :checked (:taxlaw/coverage r)))
      (is (false? (:taxlaw/accounted-for? r)))
      (is (= :withholding-not-recorded (:taxlaw/reason r)))
      (is (= "所得税法 第百八十三条第一項" (:taxlaw/provision r))
          "a refusal names the article it rests on")
      (is (= "徴収の日の属する月の翌月十日" (:taxlaw/remittance-deadline r)))))

  (testing "a withheld amount that is not an amount"
    (doseq [bad [-1 "1000" :none]]
      (let [r (taxlaw/withholding-obligation
               [:jp] (assoc jp-wage :income-tax-withheld bad))]
        (is (false? (:taxlaw/accounted-for? r)) (str "should refuse " (pr-str bad)))
        (is (= :malformed-withholding-amount (:taxlaw/reason r))))))

  (testing "in scope and accounted for"
    (let [r (taxlaw/withholding-obligation
             [:jp] (assoc jp-wage :income-tax-withheld 8420))]
      (is (true? (:taxlaw/accounted-for? r)))
      (is (nil? (:taxlaw/reason r)))
      (is (true? (:taxlaw/withholding-required? r)))))

  (testing "zero is an amount — the article says collect THE tax on that 給与等,
            and this library did not read the tables that say how much that is"
    (let [r (taxlaw/withholding-obligation
             [:jp] (assoc jp-wage :income-tax-withheld 0))]
      (is (true? (:taxlaw/accounted-for? r)))))

  (testing "presence was checked; the amount never was, on every result"
    (doseq [w [nil 0 8420 999999999]]
      (is (false? (:taxlaw/amount-checked?
                   (taxlaw/withholding-obligation
                    [:jp] (assoc jp-wage :income-tax-withheld w))))
          "`an amount is recorded` must not be readable as `the amount is right`"))))

(deftest silence-about-residency-is-not-the-articles-exclusion
  (testing "employment income with residency and place unstated stays IN scope"
    (let [r (taxlaw/withholding-obligation [:jp] {:payment-kind :employment-income})]
      (is (= :checked (:taxlaw/coverage r))
          "only an explicit :non-resident / :overseas takes a payment outside
           the article — absence of a declaration buys no exemption")
      (is (false? (:taxlaw/accounted-for? r))))))

(deftest accounts-for-withholding?-is-conservative
  (is (taxlaw/accounts-for-withholding? [:jp] (assoc jp-wage :income-tax-withheld 8420)))
  (is (not (taxlaw/accounts-for-withholding? [:jp] jp-wage)))
  (testing "no non-:checked coverage may come back true through the shortcut"
    (is (not (taxlaw/accounts-for-withholding?
              [:atlantis] (assoc jp-wage :income-tax-withheld 8420))))
    (is (not (taxlaw/accounts-for-withholding?
              [:jp] {:income-tax-withheld 8420})))
    (is (not (taxlaw/accounts-for-withholding?
              [:jp] (assoc jp-wage :recipient-residency :non-resident
                           :income-tax-withheld 8420)))
        "out-of-scope through the convenient boolean must not read as a pass")))

;; ---------------------------------------------------------------------------
;; 年末調整 — 所得税法 第百九十条
;; ---------------------------------------------------------------------------

(deftest the-year-end-rule-records-the-articles-own-conditions
  (let [r (get-in taxlaw/jurisdictions [[:jp] :jurisdiction/year-end-adjustment])]
    (is (= "所得税法 第百九十条" (:rule/provision r)))
    (is (str/includes? (:rule/quote r) "給与所得者の扶養控除等申告書を提出した居住者"))
    (is (str/includes? (:rule/quote r) "二千万円以下"))
    (is (str/includes? (:rule/quote r) "その年最後に給与等の支払をする場合"))
    (testing "the operative clause is pinned whole, so a paraphrase cannot
              satisfy it — see the note on 第百八十三条第一項 above"
      (is (str/includes?
           (:rule/quote r)
           (str "その超過額は、その年最後に給与等の支払をする際徴収すべき所得税に"
                "充当し、その不足額は、その年最後に給与等の支払をする際徴収して"
                "その徴収の日の属する月の翌月十日までに国に納付しなければならない。"))))
    (testing "the ceiling is the number in the text"
      (is (= 20000000 (:rule/income-ceiling-yen r))))
    (testing "the quote admits it is only part of the article"
      (is (true? (:rule/quote-is-partial? r))))))

(deftest requires-year-end-adjustment?-is-nil-not-false-when-unknown
  (is (true? (taxlaw/requires-year-end-adjustment? [:jp])))
  (is (nil? (taxlaw/requires-year-end-adjustment? [:atlantis])))
  (is (nil? (taxlaw/requires-year-end-adjustment? nil))))

(def ^:private jp-year-end
  {:final-payment-of-year? true
   :declaration-filed? true
   :annual-employment-income 4800000})

(deftest year-end-adjustment-is-four-valued
  (testing "nobody catalogued this jurisdiction"
    (let [r (taxlaw/year-end-adjustment [:atlantis] jp-year-end)]
      (is (= :none (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/adjusted?)))))

  (testing "the record does not say whether this is the year's final payment"
    (let [r (taxlaw/year-end-adjustment [:jp] (dissoc jp-year-end :final-payment-of-year?))]
      (is (= :not-declared (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/adjusted?))
          "the article's own trigger is unstated, so nothing was checked")))

  (testing "each exclusion the article states, and only those"
    (doseq [[label rec] [[:not-final (assoc jp-year-end :final-payment-of-year? false)]
                         [:no-declaration (assoc jp-year-end :declaration-filed? false)]
                         [:over-ceiling (assoc jp-year-end
                                               :annual-employment-income 20000001)]]]
      (testing (str label)
        (let [r (taxlaw/year-end-adjustment [:jp] rec)]
          (is (= :out-of-scope (:taxlaw/coverage r)))
          (is (not (contains? r :taxlaw/adjusted?)))
          (is (= "所得税法 第百九十条" (:taxlaw/read-provision r)))))))

  (testing "二千万円ちょうど is inside the article — 「二千万円以下」"
    (let [r (taxlaw/year-end-adjustment
             [:jp] (assoc jp-year-end :annual-employment-income 20000000
                          :year-end-adjustment-settled? true))]
      (is (= :checked (:taxlaw/coverage r)))
      (is (true? (:taxlaw/adjusted? r)))))

  (testing "in scope, and the adjustment was never recorded"
    (let [r (taxlaw/year-end-adjustment [:jp] jp-year-end)]
      (is (= :checked (:taxlaw/coverage r)))
      (is (false? (:taxlaw/adjusted? r)))
      (is (= :adjustment-not-recorded (:taxlaw/reason r)))
      (is (= "所得税法 第百九十条" (:taxlaw/provision r)))))

  (testing "in scope and explicitly not settled"
    (let [r (taxlaw/year-end-adjustment
             [:jp] (assoc jp-year-end :year-end-adjustment-settled? false))]
      (is (false? (:taxlaw/adjusted? r)))
      (is (= :year-end-adjustment-not-settled (:taxlaw/reason r)))))

  (testing "in scope and settled"
    (let [r (taxlaw/year-end-adjustment
             [:jp] (assoc jp-year-end :year-end-adjustment-settled? true))]
      (is (true? (:taxlaw/adjusted? r)))
      (is (nil? (:taxlaw/reason r)))))

  (testing "an unstated annual amount leaves the record in scope"
    (let [r (taxlaw/year-end-adjustment
             [:jp] (dissoc jp-year-end :annual-employment-income))]
      (is (= :checked (:taxlaw/coverage r))
          "silence about the amount is not the article's ceiling exclusion"))))

(deftest adjusted?-is-conservative
  (is (taxlaw/adjusted? [:jp] (assoc jp-year-end :year-end-adjustment-settled? true)))
  (is (not (taxlaw/adjusted? [:jp] jp-year-end)))
  (is (not (taxlaw/adjusted? [:atlantis]
                             (assoc jp-year-end :year-end-adjustment-settled? true))))
  (is (not (taxlaw/adjusted? [:jp] {:year-end-adjustment-settled? true}))))

;; ---------------------------------------------------------------------------
;; 法人税法施行規則 第五十九条 — retention, read rather than assumed
;; ---------------------------------------------------------------------------

(deftest the-retention-rule-is-now-read-not-merely-cited
  (let [r (get-in taxlaw/jurisdictions [[:jp] :jurisdiction/retention])]
    (is (= :read-from-source (:rule/review r))
        "it is consulted by `retention`, so citing it was not enough")
    (is (= "法人税法施行規則 第五十九条" (:rule/provision r)))
    (is (str/includes? (:rule/quote r) "起算日から七年間"))
    (is (str/includes? (:rule/basis-date-quote r) "二月を経過した日"))
    (is (str/includes? (:rule/extended-quote r) "十年間"))
    (testing "the article's own scope is recorded, not widened"
      (is (= :blue-return-corporation (:rule/binds r))))
    (testing "seven is not the only number in the text"
      (is (= 7 (:rule/years r)))
      (is (= 10 (:rule/years-with-loss-carryforward r))))))

(def ^:private mar-fye {:fiscal-year-end "2026-03-31" :blue-return? true})

(deftest retention-is-four-valued
  (testing "uncatalogued jurisdiction"
    (let [r (taxlaw/retention [:atlantis] mar-fye)]
      (is (= :none (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/retain-years)))))

  (testing "nothing asserted"
    (is (= :not-declared (:taxlaw/coverage (taxlaw/retention [:jp] {:blue-return? true}))))
    (is (= :not-declared (:taxlaw/coverage (taxlaw/retention [:jp] {:fiscal-year-end "2026-03-31"})))
        "silence about filing status is not a claim to be 青色申告"))

  (testing "out of scope is NOT a finding that no obligation exists"
    (let [r (taxlaw/retention [:jp] {:fiscal-year-end "2026-03-31" :blue-return? false})]
      (is (= :out-of-scope (:taxlaw/coverage r)))
      (is (not (contains? r :taxlaw/retain-years)))
      (is (= "法人税法施行規則 第五十九条" (:taxlaw/read-provision r))
          "it names the provision that was read, so a caller can see what
           was NOT read")))

  (testing "checked"
    (let [r (taxlaw/retention [:jp] mar-fye)]
      (is (= :checked (:taxlaw/coverage r)))
      (is (= 7 (:taxlaw/retain-years r))))))

(deftest the-clock-starts-at-起算日-not-the-transaction-date
  (testing "第五十九条第二項: the day after the fiscal year ends, plus two months"
    (is (= "2026-06-01" (:taxlaw/retain-from (taxlaw/retention [:jp] mar-fye)))
        "FY ends 2026-03-31 -> 04-01 -> +2 months")
    (is (= "2027-03-01" (:taxlaw/retain-from
                         (taxlaw/retention [:jp] {:fiscal-year-end "2026-12-31"
                                                  :blue-return? true})))))
  (testing "a filing extension moves it, per 第二項第一号"
    (is (= "2026-08-01" (:taxlaw/retain-from
                         (taxlaw/retention [:jp] (assoc mar-fye :filing-extension-months 2)))))))

(deftest carrying-a-loss-forward-makes-it-ten
  (let [r (taxlaw/retention [:jp] (assoc mar-fye :loss-carryforward? true))]
    (is (= 10 (:taxlaw/retain-years r)))
    (is (= "法人税法施行規則 第二十六条の三第一項" (:taxlaw/provision r)))))

(deftest a-clamped-date-says-so
  (testing "adding two months to 12-30 has no literal answer; the clamp is
            this library's convention and must be visible"
    (let [r (taxlaw/retention [:jp] {:fiscal-year-end "2026-12-30" :blue-return? true})]
      (is (= "2027-02-28" (:taxlaw/retain-from r)))
      (is (= :clamped-to-month-end (:taxlaw/date-convention r)))))
  (testing "and an ordinary date does not claim a convention was applied"
    (is (not (contains? (taxlaw/retention [:jp] mar-fye) :taxlaw/date-convention)))))

(deftest there-is-deliberately-no-retain-until
  (testing "七年間 from a date does not say whether the last day is inside
            the period; emitting one would make a guess look like a rule"
    (is (not (contains? (taxlaw/retention [:jp] mar-fye) :taxlaw/retain-until)))))

(deftest an-impossible-fiscal-year-end-is-refused-not-rolled-forward
  (doseq [bad ["2026-02-30" "2026-13-01" "26-03-31" "2026/03/31" "" nil]]
    (is (= :not-declared (:taxlaw/coverage (taxlaw/retention [:jp] {:fiscal-year-end bad
                                                                    :blue-return? true})))
        (str "should refuse " (pr-str bad)))))

(deftest a-leap-day-fiscal-year-end-works
  (is (= "2028-04-29" (:taxlaw/retain-from
                       (taxlaw/retention [:jp] {:fiscal-year-end "2028-02-28"
                                                :blue-return? true})))))

;; ---------------------------------------------------------------------------
;; 検索要件 — 規則第五条第五項第一号ハ (帳簿) and 規則第二条第六項第五号 (電子取引)
;;
;; Two regimes with different requirements and different exemptions. The
;; tests below are mostly about what these functions REFUSE to answer: both
;; requirements turn on facts about the holder that no library observes, and
;; the failure mode this catalog exists to prevent is a compliance answer
;; that reads as a pass because nobody could check it.
;; ---------------------------------------------------------------------------

(def ^:private full-search
  {:searchable-by #{:transaction-date :amount :counterparty}
   :range-search? true :combination-search? true})

(deftest the-book-search-requirement-is-not-a-duty-and-does-not-say-it-is
  (testing "規則第五条第五項第一号ハ attaches to 法第八条第四項 — ordinary
            electronic preservation under 法第四条第一項 requires no search"
    (is (= :claiming-preferential-treatment (taxlaw/requires-book-search? [:jp]))
        "a keyword, not true — whether it bites is the holder's decision")
    (is (nil? (taxlaw/requires-book-search? [:zz])))))

(deftest not-claiming-the-benefit-is-not-the-same-as-satisfying-the-rule
  (let [r (taxlaw/book-search [:jp] (assoc full-search
                                           :claiming-preferential-treatment? false))]
    (is (= :checked (:taxlaw/coverage r)))
    (is (false? (:taxlaw/search-required? r)))
    (is (nil? (:taxlaw/adequate? r))
        "nil, not true — `the rule does not reach you` is not `you satisfy it`")
    (is (not (taxlaw/book-search-adequate? [:jp]
                                           (assoc full-search
                                                  :claiming-preferential-treatment? false))))))

(deftest books-that-do-not-say-what-they-are-claiming-get-no-answer
  (let [r (taxlaw/book-search [:jp] full-search)]
    (is (= :not-declared (:taxlaw/coverage r)))
    (is (nil? (:taxlaw/adequate? r)))
    (is (not (taxlaw/book-search-adequate? [:jp] full-search))
        "a system that can search everything still has not established that
         the requirement was met, because nobody said it applied")))

(deftest the-three-record-items-are-the-ones-the-provision-names
  (let [claiming (assoc full-search :claiming-preferential-treatment? true)]
    (is (taxlaw/book-search-adequate? [:jp] claiming))
    (testing "each 記録項目 is load-bearing on its own"
      (doseq [item [:transaction-date :amount :counterparty]]
        (let [r (taxlaw/book-search
                 [:jp] (update claiming :searchable-by disj item))]
          (is (false? (:taxlaw/adequate? r)) (str "missing " item))
          (is (= #{:record-items} (:taxlaw/missing r))))))
    (testing "（２）range and （３）combination are separate failures"
      (is (= #{:range} (:taxlaw/missing (taxlaw/book-search
                                         [:jp] (assoc claiming :range-search? false)))))
      (is (= #{:combination} (:taxlaw/missing
                              (taxlaw/book-search
                               [:jp] (assoc claiming :combination-search? false))))))))

(deftest an-electronic-transaction-setup-that-hides-the-deciding-fact-gets-no-answer
  (testing "both exemptions in 規則第四条第一項 turn on whether the holder can
            respond to 電磁的記録の提示等の要求"
    (let [r (taxlaw/electronic-transaction-search [:jp] full-search)]
      (is (= :not-declared (:taxlaw/coverage r)))
      (is (not (taxlaw/electronic-transaction-search-adequate? [:jp] full-search))))))

(deftest producing-on-demand-drops-range-and-combination-but-not-the-items
  (let [base (assoc full-search :can-produce-on-demand? true)]
    (testing "ロ and ハ drop, so a system with neither still passes"
      (let [r (taxlaw/electronic-transaction-search
               [:jp] (assoc base :range-search? false :combination-search? false))]
        (is (= :checked (:taxlaw/coverage r)))
        (is (true? (:taxlaw/adequate? r)))
        (is (= :on-demand-production (:taxlaw/exemption r)))))
    (testing "イ does not drop"
      (let [r (taxlaw/electronic-transaction-search
               [:jp] (update base :searchable-by disj :counterparty))]
        (is (= #{:record-items} (:taxlaw/missing r)))))))

(deftest the-wider-exemption-needs-both-legs-and-says-so-when-one-is-unstated
  (let [cannot-search {:searchable-by #{} :range-search? false
                       :combination-search? false :can-produce-on-demand? true}]
    (testing "under the 五千万円 ceiling, the whole of 第五号 drops"
      (let [r (taxlaw/electronic-transaction-search
               [:jp] (assoc cannot-search :base-period-sales-yen 49999999))]
        (is (true? (:taxlaw/adequate? r)))
        (is (false? (:taxlaw/search-required? r)))
        (is (= :small-holder-or-organized-paper (:taxlaw/exemption r)))))
    (testing "organized paper output reaches the same exemption"
      (is (true? (:taxlaw/adequate?
                  (taxlaw/electronic-transaction-search
                   [:jp] (assoc cannot-search :paper-output-organized? true))))))
    (testing "over the ceiling and no paper, it is a real failure"
      (let [r (taxlaw/electronic-transaction-search
               [:jp] (assoc cannot-search :base-period-sales-yen 50000001
                            :paper-output-organized? false))]
        (is (= :checked (:taxlaw/coverage r)))
        (is (false? (:taxlaw/adequate? r)))
        (is (= #{:record-items} (:taxlaw/missing r)))))
    (testing "the ceiling is 以下, so exactly 五千万円 is inside it"
      (is (true? (:taxlaw/adequate?
                  (taxlaw/electronic-transaction-search
                   [:jp] (assoc cannot-search :base-period-sales-yen 50000000))))))
    (testing "but with the sales figure unstated it refuses rather than failing —
              `you fail` and `you did not state the deciding fact` are different"
      (let [r (taxlaw/electronic-transaction-search [:jp] cannot-search)]
        (is (= :not-declared (:taxlaw/coverage r)))
        (is (nil? (:taxlaw/adequate? r)))
        (is (= #{:record-items} (:taxlaw/missing r))
            "it still says what is missing — refusing to conclude is not
             refusing to inform")))))

(deftest a-passing-setup-never-needs-the-unstated-fact
  (testing "if イ is satisfied and the records can be produced on demand, the
            sales figure could only have helped, and nothing needed helping"
    (let [r (taxlaw/electronic-transaction-search
             [:jp] (assoc full-search :can-produce-on-demand? true))]
      (is (= :checked (:taxlaw/coverage r)))
      (is (true? (:taxlaw/adequate? r))))))

(deftest neither-search-rule-applies-to-an-uncatalogued-jurisdiction
  (doseq [f [taxlaw/book-search taxlaw/electronic-transaction-search]]
    (let [r (f [:zz] full-search)]
      (is (= :none (:taxlaw/coverage r)))
      (is (= [[:zz]] (:taxlaw/unchecked r))))))

(deftest the-two-regimes-are-catalogued-apart-and-quote-different-text
  (testing "帳簿 reads 「取引年月日、取引金額及び取引先」; 書類/電子取引 reads
            「取引年月日その他の日付、…」 — one phrase apart, and recorded as read"
    (let [b (get-in taxlaw/jurisdictions [[:jp] :jurisdiction/book-search])
          e (get-in taxlaw/jurisdictions [[:jp] :jurisdiction/electronic-transaction-search])]
      (is (not= (:rule/provision b) (:rule/provision e)))
      (is (str/includes? (:rule/quote b) "取引年月日、取引金額及び取引先"))
      (is (str/includes? (:rule/quote e) "取引年月日その他の日付"))
      (is (not (str/includes? (:rule/quote b) "その他の日付"))
          "the 帳簿 provision does not carry that phrase and the catalog must
           not lend it one")
      (is (= 50000000 (:rule/small-holder-sales-ceiling-yen e)))
      (is (nil? (:rule/small-holder-sales-ceiling-yen b))
          "the 帳簿 regime has no sales ceiling; inventing one would be the
           conflation this pair of rules exists to prevent")
      (doseq [r [b e]]
        (is (= :read-from-source (:rule/review r)))
        (is (= "2026-08-18" (:rule/retrieved-at r)))))))

(deftest a-holder-that-cannot-produce-on-demand-owes-all-three
  (testing "every earlier test here says :can-produce-on-demand? true or
            leaves it unstated. The tier where NO exemption applies is the
            base case of 規則第二条第六項第五号 and nothing had exercised it"
    (let [base (assoc full-search :can-produce-on-demand? false)]
      (is (true? (:taxlaw/adequate?
                  (taxlaw/electronic-transaction-search [:jp] base))))
      (doseq [[k missing] [[:range-search? :range]
                           [:combination-search? :combination]]]
        (let [r (taxlaw/electronic-transaction-search [:jp] (assoc base k false))]
          (is (= :checked (:taxlaw/coverage r))
              "no exemption is in play, so nothing is undecidable here")
          (is (false? (:taxlaw/adequate? r)))
          (is (= #{missing} (:taxlaw/missing r))))))))

(deftest the-wider-exemption-does-not-work-on-sales-alone
  (testing "規則第四条第一項 grants it to a holder that can respond to
            電磁的記録の提示等の要求 AND is under the ceiling — being small
            is not, by itself, an exemption from anything"
    (let [r (taxlaw/electronic-transaction-search
             [:jp] {:searchable-by #{} :range-search? false
                    :combination-search? false
                    :can-produce-on-demand? false
                    :base-period-sales-yen 1000000
                    :paper-output-organized? true})]
      (is (= :checked (:taxlaw/coverage r)) "both legs are stated, so it is decidable")
      (is (false? (:taxlaw/adequate? r)))
      (is (nil? (:taxlaw/exemption r)) "neither exemption is reached")
      (is (= #{:record-items :range :combination} (:taxlaw/missing r))
          "all three, because no exemption dropped any of them"))))

;; ---------------------------------------------------------------------------
;; 消費税額等 — 消費税法施行令 第七十条の十
;;
;; The article settles three things that a naive implementation gets wrong:
;; the multiplication is on the per-rate subtotal and not per line, the
;; rounding happens once on that figure, and the article does not say which
;; way to round. The tests below are mostly about the third — a library that
;; picks a direction is wrong by ¥1 per rate on every invoice, forever, and
;; nothing in its output says so.
;; ---------------------------------------------------------------------------

(def ^:private inv
  {:method :tax-exclusive :rounding :floor :subtotals {:standard 33333}})

(deftest neither-the-method-nor-the-rounding-is-defaulted
  (testing "「いずれかとする」 and 「端数を処理するものとする」 are both choices
            the article hands the issuer"
    (let [no-method (taxlaw/consumption-tax-amount [:jp] (dissoc inv :method))]
      (is (= :not-declared (:taxlaw/coverage no-method)))
      (is (= #{:tax-exclusive :tax-inclusive} (:taxlaw/choices no-method))))
    (let [no-round (taxlaw/consumption-tax-amount [:jp] (dissoc inv :rounding))]
      (is (= :not-declared (:taxlaw/coverage no-round)))
      (is (= #{:floor :ceil :round-half-up} (:taxlaw/choices no-round))))
    (testing "and nil comes back, not 0 — 0 is a real tax amount"
      (is (nil? (taxlaw/consumption-tax [:jp] (dissoc inv :rounding)))))))

(deftest a-method-the-article-does-not-offer-is-refused
  (let [r (taxlaw/consumption-tax-amount [:jp] (assoc inv :method :per-line))]
    (is (= :not-declared (:taxlaw/coverage r)))
    (is (str/includes? (:taxlaw/why r) ":per-line"))))

(deftest the-two-methods-are-the-two-the-article-names
  (testing "第一号 税抜 33,333 x 10/100 = 3,333.3"
    (is (= 3333 (taxlaw/consumption-tax [:jp] inv)))
    (is (= 3334 (taxlaw/consumption-tax [:jp] (assoc inv :rounding :ceil))))
    (is (= 3333 (taxlaw/consumption-tax [:jp] (assoc inv :rounding :round-half-up)))))
  (testing "第二号 税込 33,333 x 10/110 = 3,030.27…"
    (let [i (assoc inv :method :tax-inclusive)]
      (is (= 3030 (taxlaw/consumption-tax [:jp] i)))
      (is (= 3031 (taxlaw/consumption-tax [:jp] (assoc i :rounding :ceil))))))
  (testing "軽減税率 — 8/100 税抜 and 8/108 税込"
    (is (= 2666 (taxlaw/consumption-tax [:jp] (assoc inv :subtotals {:reduced 33333}))))
    (is (= 2469 (taxlaw/consumption-tax
                 [:jp] (assoc inv :method :tax-inclusive :subtotals {:reduced 33333}))))))

(deftest rounding-half-up-turns-on-the-half-and-not-on-anything-else
  (testing "税抜 x 10/100 — the remainder is the last digit, so 5 is the hinge"
    (is (= 100 (taxlaw/consumption-tax
                [:jp] {:method :tax-exclusive :rounding :round-half-up
                       :subtotals {:standard 1004}})))
    (is (= 101 (taxlaw/consumption-tax
                [:jp] {:method :tax-exclusive :rounding :round-half-up
                       :subtotals {:standard 1005}})))))

(deftest an-exact-figure-is-not-touched-by-any-policy
  (testing "「一円未満の端数が生じたときは」— when none arises, nothing is processed"
    (doseq [p [:floor :ceil :round-half-up]]
      (is (= 1000 (taxlaw/consumption-tax
                   [:jp] {:method :tax-exclusive :rounding p
                          :subtotals {:standard 10000}}))
          (str "under " p)))))

(deftest the-rounding-happens-once-per-rate-and-not-once-per-line
  (testing "the article multiplies 税率の異なるごとに区分して合計した金額 —
            three ¥333 lines are one ¥999 subtotal, not three roundings"
    (let [one-subtotal (taxlaw/consumption-tax
                        [:jp] {:method :tax-exclusive :rounding :floor
                               :subtotals {:standard 999}})
          per-line (* 3 (taxlaw/consumption-tax
                         [:jp] {:method :tax-exclusive :rounding :floor
                                :subtotals {:standard 333}}))]
      (is (= 99 one-subtotal))
      (is (= 99 per-line) "these happen to agree here")))
  (testing "and here they do not — which is why the shape of the input matters"
    (let [one-subtotal (taxlaw/consumption-tax
                        [:jp] {:method :tax-exclusive :rounding :ceil
                               :subtotals {:standard 999}})
          per-line (* 3 (taxlaw/consumption-tax
                         [:jp] {:method :tax-exclusive :rounding :ceil
                                :subtotals {:standard 333}}))]
      (is (= 100 one-subtotal))
      (is (= 102 per-line))
      (is (not= one-subtotal per-line)
          "a function taking lines could not have told these apart"))))

(deftest the-two-rates-are-summed-after-each-is-rounded-on-its-own
  (let [r (taxlaw/consumption-tax-amount
           [:jp] {:method :tax-exclusive :rounding :floor
                  :subtotals {:standard 1234 :reduced 5678}})]
    (is (= :checked (:taxlaw/coverage r)))
    (is (= {:standard 123 :reduced 454} (:taxlaw/tax-by-category r)))
    (is (= 577 (:taxlaw/tax r)))
    (is (= :tax-category-subtotal (:taxlaw/rounds-per r)))))

(deftest a-tax-category-the-article-does-not-name-is-refused-not-assumed
  (let [r (taxlaw/consumption-tax-amount
           [:jp] (assoc inv :subtotals {:standard 1000 :export 5000}))]
    (is (= :not-declared (:taxlaw/coverage r)))
    (is (= #{:export} (:taxlaw/unknown-categories r)))
    (is (nil? (taxlaw/consumption-tax
               [:jp] (assoc inv :subtotals {:standard 1000 :export 5000})))
        "and it does not quietly return the tax on the part it understood")))

(deftest a-negative-subtotal-is-refused-rather-than-rounded-backwards
  (testing "quot truncates toward zero, so :floor on a negative rounds UP and
            does it silently. 返還インボイス is governed elsewhere"
    (let [r (taxlaw/consumption-tax-amount [:jp] (assoc inv :subtotals {:standard -1000}))]
      (is (= :not-declared (:taxlaw/coverage r)))
      (is (= #{:standard} (:taxlaw/rejected r))))
    (testing "a non-integer subtotal too — no float ever holds a tax figure"
      (is (nil? (taxlaw/consumption-tax [:jp] (assoc inv :subtotals {:standard 1000.5})))))))

(deftest an-empty-subtotal-map-is-a-real-zero-and-says-so
  (let [r (taxlaw/consumption-tax-amount [:jp] (assoc inv :subtotals {}))]
    (is (= :checked (:taxlaw/coverage r)) "nothing was taxable; that is an answer")
    (is (= 0 (:taxlaw/tax r)))
    (is (= 0 (taxlaw/consumption-tax [:jp] (assoc inv :subtotals {})))
        "and 0 is distinguishable from the nil that means `could not answer`")))

(deftest the-article-is-recorded-as-read-with-its-omission-named
  (let [rule (get-in taxlaw/jurisdictions [[:jp] :jurisdiction/qualified-invoice-tax-amount])]
    (is (= :read-from-source (:rule/review rule)))
    (is (= "消費税法施行令 第七十条の十" (:rule/provision rule)))
    (is (true? (:rule/quote-is-partial? rule)))
    (is (not (str/blank? (:rule/quote-omits rule))))
    (is (true? (:rule/rounding-is-issuers-choice? rule)))
    (testing "the four rate pairs are the ones in the text"
      (is (= {:standard [10 100] :reduced [8 100]}
             (dissoc (get-in rule [:rule/methods :tax-exclusive]) :statute)))
      (is (= {:standard [10 110] :reduced [8 108]}
             (dissoc (get-in rule [:rule/methods :tax-inclusive]) :statute))))))

(deftest an-uncatalogued-jurisdiction-gets-no-tax-figure
  (is (= :none (:taxlaw/coverage (taxlaw/consumption-tax-amount [:zz] inv))))
  (is (nil? (taxlaw/consumption-tax [:zz] inv))))
