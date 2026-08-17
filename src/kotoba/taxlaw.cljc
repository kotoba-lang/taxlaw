(ns kotoba.taxlaw
  "**What a tax record must carry, by jurisdiction** — a
  [kotoba-lang](https://github.com/kotoba-lang) capability library that
  answers questions of the form: in this jurisdiction, does this document
  support the treatment being claimed for it?

  > **Not tax advice.** This is a mechanism plus a small, cited rule set. It
  > is deliberately incomplete, and its most important behaviour is what it
  > does about that.

  Sibling of `kotoba-lang/worklaw`, and the same shape on purpose: a
  jurisdiction is a path, an unchecked jurisdiction is never a pass, and the
  convenient boolean gives the conservative answer.

  ## The invariant: absence is never sufficiency

  ```clojure
  (taxlaw/covered? [:atlantis])                      ;; => false
  (taxlaw/requires-qualified-invoice? [:atlantis])   ;; => nil, NOT false
  (taxlaw/registration-number-valid? [:atlantis] \"T1234567890123\")
  ;; => false — an uncatalogued jurisdiction cannot validate anything
  ```

  `requires-qualified-invoice?` returns **nil** rather than false for an
  uncatalogued jurisdiction, so a caller cannot read `we have no rule` as
  `there is no requirement`. That distinction is the whole library: a
  bookkeeping actor that treated silence as sufficiency would let a receipt
  nobody has seen the law about support a credit.

  ## Jurisdictions are paths, not codes

  `[:jp]` today. A path leaves room for `[:jp :tokyo]` or `[:us :ca]`
  without renaming anything, exactly as worklaw does.

  ## Citations are checked against the corpus, not against HTTP

  Every statute here carries its **e-Gov law id**, and
  `tools/verify_citations.cljs` resolves those ids against
  `kotoba-lang/jp.go.e-gov.elaws`'s `index/laws.edn` — 9,536 Japanese laws
  with `:law/status`, `:law/title` and content hashes.

  This replaces an earlier check that fetched each URL and accepted HTTP
  200. Reachability is the weaker claim by some distance:

  | | HTTP 200 | corpus index |
  |---|---|---|
  | the URL resolves | yes | — |
  | the law exists | inferred | stated |
  | the title is what we said it is | no | **compared** |
  | the law has been **repealed** | **invisible** | `:law.status/repealed` |

  `:law.status/superseded-revision` is NOT repeal — it means the corpus
  snapshot predates a later revision of a law that is still in force. Four
  of the eight statutes below are in that state as of the pinned corpus, and
  that is fine; the checker distinguishes them and says so.

  ## Scope

  Japan, and within Japan the two things a bookkeeping or invoicing actor
  actually has to gate on: whether input-tax credit requires a qualified
  invoice, and how long records must be kept. Not a tax engine. It computes
  no tax, files nothing, and renders no opinion."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; sources
;; ---------------------------------------------------------------------------

(def sources
  "Primary sources, keyed by id.

  `:law/id` is the e-Gov law id, present only for instruments that live in
  the e-Gov corpus — it is what makes a citation checkable rather than
  merely reachable. Administrative guidance (国税庁 pages) has no law id and
  carries `:source/kind :guidance`; those are verifiable by fetch only, and
  the checker reports them as a separate class rather than counting them as
  corpus-verified."
  {:jp/dencho-ho
   {:source/title "電子計算機を使用して作成する国税関係帳簿書類の保存方法等の特例に関する法律"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "410AC0000000025"
    :source/url "https://laws.e-gov.go.jp/law/410AC0000000025"}

   :jp/dencho-kisoku
   {:source/title "電子計算機を使用して作成する国税関係帳簿書類の保存方法等の特例に関する法律施行規則"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "410M50000040043"
    :source/url "https://laws.e-gov.go.jp/law/410M50000040043"}

   :jp/shohizei-ho
   {:source/title "消費税法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "363AC0000000108"
    :source/url "https://laws.e-gov.go.jp/law/363AC0000000108"}

   :jp/hojinzei-ho
   {:source/title "法人税法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "340AC0000000034"
    :source/url "https://laws.e-gov.go.jp/law/340AC0000000034"}

   :jp/shotokuzei-ho
   {:source/title "所得税法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "340AC0000000033"
    :source/url "https://laws.e-gov.go.jp/law/340AC0000000033"}

   :jp/kaisha-ho
   {:source/title "会社法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "417AC0000000086"
    :source/url "https://laws.e-gov.go.jp/law/417AC0000000086"}

   :jp/kaisha-keisan-kisoku
   {:source/title "会社計算規則"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "418M60000010013"
    :source/url "https://laws.e-gov.go.jp/law/418M60000010013"}

   :jp/shoho
   {:source/title "商法"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "132AC0000000048"
    :source/url "https://laws.e-gov.go.jp/law/132AC0000000048"}

   :jp/nta-invoice
   {:source/title "インボイス制度（適格請求書等保存方式）"
    :source/authority "国税庁"
    :source/kind :guidance
    :source/url "https://www.nta.go.jp/taxes/shiraberu/zeimokubetsu/shohi/keigenzeiritsu/invoice.htm"}

   :jp/nta-invoice-kohyo
   {:source/title "適格請求書発行事業者公表サイト"
    :source/authority "国税庁"
    :source/kind :guidance
    :source/url "https://www.invoice-kohyo.nta.go.jp/"}

   :jp/nta-6496
   {:source/title "タックスアンサー No.6496 仕入税額控除"
    :source/authority "国税庁"
    :source/kind :guidance
    :source/url "https://www.nta.go.jp/taxes/shiraberu/taxanswer/shohi/6496.htm"}

   :jp/nta-5930
   {:source/title "タックスアンサー No.5930 帳簿書類等の保存期間"
    :source/authority "国税庁"
    :source/kind :guidance
    :source/url "https://www.nta.go.jp/taxes/shiraberu/taxanswer/hojin/5930.htm"}

   :jp/nta-jirei
   {:source/title "法令解釈通達・質疑応答事例"
    :source/authority "国税庁"
    :source/kind :guidance
    :source/url "https://www.nta.go.jp/law/joho-zeikaishaku/sonota/jirei/index.htm"}

   :jp/e-tax
   {:source/title "e-Tax 国税電子申告・納税システム"
    :source/authority "国税庁"
    :source/kind :guidance
    :source/url "https://www.e-tax.nta.go.jp/"}

   :jp/jicpa
   {:source/title "日本公認会計士協会"
    :source/authority "日本公認会計士協会"
    :source/kind :guidance
    :source/url "https://jicpa.or.jp/"}})

(def catalog-verification
  "What was verified, and what was not. These are different claims and
  conflating them is how a citation list becomes decoration.

  `:catalog/rejected` records candidates that were considered and NOT
  cited, with the reason — an absent citation that leaves no trace looks
  identical to one nobody thought of."
  {:catalog/corpus "kotoba-lang/jp.go.e-gov.elaws"
   :catalog/corpus-checked-at "2026-08-17"
   :catalog/statute-count (count (filter #(= :statute (:source/kind %)) (vals sources)))
   :catalog/guidance-count (count (filter #(= :guidance (:source/kind %)) (vals sources)))
   :catalog/content-verified
   [{:claim :qualified-invoice-registration-format
     :source :jp/nta-invoice-kohyo
     :quote "「T」を除く13桁の半角数字"}
    {:claim :electronic-transaction-record-preservation
     :source :jp/dencho-ho
     :provision "第七条"
     :retrieved-via "e-Gov law API v2 GET /api/2/law_data/410AC0000000025"
     :retrieved-at "2026-08-17"
     :quote (str "所得税（源泉徴収に係る所得税を除く。）及び法人税に係る"
                 "保存義務者は、電子取引を行った場合には、財務省令で定める"
                 "ところにより、当該電子取引の取引情報に係る電磁的記録を"
                 "保存しなければならない。")}]
   :catalog/not-verified
   (str "article-level text of the statutes was not read. They are cited as "
        "instruments, and checked for existence / title / non-repeal against "
        "the corpus index — not for saying what a rule below claims.")
   :catalog/rejected
   [{:url "https://www.asb.or.jp/"
     :why "connection timed out on two attempts (25s, 40s) — an unfetchable citation is not a citation"}
    {:url "https://www.chusho.meti.go.jp/zaimu/youryou/"
     :why (str "403 to a plain client; 200 only with a browser User-Agent. "
               "Citing it would mean citing something this repo's own gate "
               "cannot verify, so 中小企業の会計に関する基本要領 is recorded as "
               "uncited rather than cited on a spoofed header.")}]})

;; ---------------------------------------------------------------------------
;; jurisdictions
;; ---------------------------------------------------------------------------

(def ^:private jp-registration-number
  ;; 国税庁 publication site: 登録番号 = "T" + 13 digits. `re-matches` anchors
  ;; the whole string on both JVM and JS, so no \A / \z (JS has neither).
  #"T\d{13}")

(def jurisdictions
  "Keyed by jurisdiction PATH, as in worklaw. `[:jp]` today."
  {[:jp]
   {:jurisdiction/path [:jp]
    :jurisdiction/label "日本"

    :jurisdiction/input-tax-credit
    {:rule/requires-qualified-invoice? true
     :rule/registration-number-pattern jp-registration-number
     :rule/registration-number-example "T1234567890123"
     :rule/review :reachable-not-read
     :rule/format-review :read-from-source
     :rule/sources [:jp/shohizei-ho :jp/nta-invoice :jp/nta-6496 :jp/nta-invoice-kohyo]}

    :jurisdiction/retention
    {:rule/years 7
     :rule/review :reachable-not-read
     :rule/sources [:jp/hojinzei-ho :jp/nta-5930]}

    ;; The one rule here whose STATUTORY TEXT was read, not just cited.
    ;; 電子帳簿保存法 第七条, retrieved 2026-08-17 from the e-Gov law API
    ;; (`GET /api/2/law_data/410AC0000000025`) and quoted below verbatim.
    ;;
    ;; Note the scope in the text: it binds 保存義務者 for 所得税 (excluding
    ;; withholding) and 法人税. It is not a universal rule about documents,
    ;; and this catalog does not widen it into one.
    :jurisdiction/electronic-transaction
    {:rule/must-preserve-electronic-record? true
     :rule/review :read-from-source
     :rule/provision "電子帳簿保存法 第七条"
     :rule/quote (str "所得税（源泉徴収に係る所得税を除く。）及び法人税に係る"
                      "保存義務者は、電子取引を行った場合には、財務省令で定める"
                      "ところにより、当該電子取引の取引情報に係る電磁的記録を"
                      "保存しなければならない。")
     :rule/retrieved-at "2026-08-17"
     :rule/applies-to #{:income-tax :corporation-tax}
     :rule/sources [:jp/dencho-ho :jp/dencho-kisoku]}}})

;; ---------------------------------------------------------------------------
;; the API
;; ---------------------------------------------------------------------------

(defn- normalize
  "Accept `[:jp]` or `:jp`. Actors store a jurisdiction however their own
  schema does; making them convert at every call site is how a shared
  library stops being used."
  [jurisdiction]
  (cond (vector? jurisdiction) jurisdiction
        (nil? jurisdiction) nil
        :else [jurisdiction]))

(defn covered?
  "Is this jurisdiction in the catalog? `nil` is NOT covered — an undeclared
  jurisdiction is the unchecked case, not a default one."
  [jurisdiction]
  (contains? jurisdictions (normalize jurisdiction)))

(defn jurisdiction [j] (get jurisdictions (normalize j)))

(defn source [source-id] (get sources source-id))

(defn source-urls []
  (vec (sort (map :source/url (vals sources)))))

(defn law-ids
  "e-Gov law ids for the statutes here. Guidance pages have none."
  []
  (vec (sort (keep :law/id (vals sources)))))

(defn requires-qualified-invoice?
  "Does this jurisdiction condition input-tax credit on a qualified invoice?

  **nil** for an uncatalogued jurisdiction — deliberately not false, so a
  caller cannot read `unknown` as `no requirement`."
  [j]
  (get-in jurisdictions
          [(normalize j) :jurisdiction/input-tax-credit
           :rule/requires-qualified-invoice?]))

(defn registration-number-valid?
  "Does `n` satisfy this jurisdiction's qualified-invoice registration
  format? False for an uncatalogued jurisdiction and for `nil` — this
  function never answers yes on absence of information."
  [j n]
  (boolean
   (when-let [pat (get-in jurisdictions
                          [(normalize j) :jurisdiction/input-tax-credit
                           :rule/registration-number-pattern])]
     (and (string? n)
          (not (str/blank? n))
          (some? (re-matches pat n))))))

(defn retention-years
  "How many years records must be kept. **nil** when unknown — a caller must
  not read that as zero."
  [j]
  (get-in jurisdictions [(normalize j) :jurisdiction/retention :rule/years]))

(defn credit-support
  "Does `document` support an input-tax credit claim in `jurisdiction`?

  Returns a map, never a bare boolean, because there are three answers and
  only two of them are `no`:

    {:taxlaw/coverage :none}          nobody has catalogued this jurisdiction
    {:taxlaw/supported? false ...}    catalogued, and this document does not
    {:taxlaw/supported? true ...}     catalogued, and it does

  `supported?` is absent in the `:none` case on purpose. A caller reaching
  for it gets nil, which is falsey — the conservative answer — and a caller
  that wants to tell `refused` from `not checked` can."
  [j document]
  (let [path (normalize j)]
    (if-not (covered? path)
      {:taxlaw/coverage :none
       :taxlaw/unchecked [path]}
      (let [needs? (requires-qualified-invoice? path)
            n (:registration-number document)
            ok? (or (not needs?) (registration-number-valid? path n))]
        {:taxlaw/coverage :checked
         :taxlaw/jurisdiction path
         :taxlaw/supported? ok?
         :taxlaw/requires-qualified-invoice? (boolean needs?)
         :taxlaw/registration-number n
         :taxlaw/reason (cond ok? nil
                              (str/blank? (str n)) :missing-registration-number
                              :else :malformed-registration-number)}))))

(defn requires-electronic-record?
  "Where a transaction was conducted electronically, must its electromagnetic
  record be preserved as such?

  **nil** for an uncatalogued jurisdiction — deliberately not false, for the
  same reason `requires-qualified-invoice?` is."
  [j]
  (get-in jurisdictions
          [(normalize j) :jurisdiction/electronic-transaction
           :rule/must-preserve-electronic-record?]))

(defn record-preservation
  "Is `document`'s preservation adequate for how the transaction happened?

  Three-valued, like `credit-support`, and for the same reason — `:none` is
  neither a pass nor a refusal:

    {:taxlaw/coverage :none}            nobody catalogued this jurisdiction
    {:taxlaw/coverage :not-declared}    the document does not say how the
                                        transaction happened; nothing was
                                        asserted, so nothing was checked
    {:taxlaw/coverage :checked ...}     it does say, and here is the answer

  A document declares `:origin :electronic-transaction` and
  `:preservation :electronic | :paper`. A paper substitute for an electronic
  transaction is the case 電子帳簿保存法 第七条 addresses: the obligation is
  to preserve the 電磁的記録 itself, so printing it and keeping the paper is
  not preservation of the thing the article names.

  This library does NOT decide whether the holder is a 保存義務者 for
  income or corporation tax — the article's own scope. A caller that knows
  it is not may ignore the result; a caller that does not know has not
  established that it is exempt."
  [j document]
  (let [path (normalize j)]
    (cond
      (not (covered? path))
      {:taxlaw/coverage :none :taxlaw/unchecked [path]}

      (nil? (:origin document))
      {:taxlaw/coverage :not-declared
       :taxlaw/why "the document does not say how the transaction happened"}

      :else
      (let [electronic? (= :electronic-transaction (:origin document))
            required? (and electronic? (true? (requires-electronic-record? path)))
            ok? (or (not required?) (= :electronic (:preservation document)))]
        {:taxlaw/coverage :checked
         :taxlaw/jurisdiction path
         :taxlaw/preserved? ok?
         :taxlaw/electronic-record-required? (boolean required?)
         :taxlaw/preservation (:preservation document)
         :taxlaw/provision (when required?
                             (get-in jurisdictions
                                     [path :jurisdiction/electronic-transaction
                                      :rule/provision]))
         :taxlaw/reason (cond ok? nil
                              (nil? (:preservation document)) :preservation-not-recorded
                              :else :electronic-record-not-preserved)}))))

(defn preserved?
  "Convenience boolean over `record-preservation`, conservative in the same
  way `supported?` is: `:none` and `:not-declared` both come back false,
  because neither established that the record is preserved."
  [j document]
  (true? (:taxlaw/preserved? (record-preservation j document))))

(defn supported?
  "Convenience boolean over `credit-support`.

  Deliberately not `(:taxlaw/supported? ...)` alone: an uncatalogued
  jurisdiction has no `:taxlaw/supported?` key, so this returns false there
  too. A caller who reaches for the convenient boolean gets the
  conservative answer rather than the flattering one — worklaw's
  `compliant?` makes the same choice for the same reason."
  [j document]
  (true? (:taxlaw/supported? (credit-support j document))))
