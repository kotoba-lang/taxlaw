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
    (is (= 4 (count claims)))
    (is (some #(= :electronic-transaction-record-preservation (:claim %)) claims))
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
