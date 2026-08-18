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

  Japan, and within Japan what a bookkeeping, invoicing or payroll actor
  actually has to gate on: whether input-tax credit requires a qualified
  invoice, how long records must be kept, whether an electronic transaction's
  record must be preserved as such, and — since 2026-08-17 — whether an
  employer paying employment income must withhold income tax and settle the
  year's over/under at the final payment.

  Not a tax engine. **It computes no tax**, files nothing, and renders no
  opinion. The withholding facet checks that a payroll record *accounts for*
  withheld income tax; it does not check the amount, because 所得税法 別表第二
  / 別表第五 were not read. Every result says so in `:taxlaw/amount-checked?`."
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

   :jp/hojinzei-kisoku
   {:source/title "法人税法施行規則"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "340M50000040012"
    :source/url "https://laws.e-gov.go.jp/law/340M50000040012"}

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
                 "保存しなければならない。")}
    {:claim :employment-income-withholding-obligation
     :source :jp/shotokuzei-ho
     :provision "所得税法 第百八十三条第一項"
     :retrieved-via "e-Gov law API v2 GET /api/2/law_data/340AC0000000033"
     :retrieved-at "2026-08-17"
     ;; The API served revision 340AC0000000033_20260812_508AC0000000064, which
     ;; is NEWER than the pinned corpus snapshot (`:law.status/superseded-revision`).
     ;; Recorded so a later reader can tell which text was actually read.
     :retrieved-revision "340AC0000000033_20260812_508AC0000000064"
     :quote (str "居住者に対し国内において第二十八条第一項（給与所得）に規定する"
                 "給与等（以下この章において「給与等」という。）の支払をする者は、"
                 "その支払の際、その給与等について所得税を徴収し、その徴収の日の"
                 "属する月の翌月十日までに、これを国に納付しなければならない。")}
    {:claim :year-end-adjustment
     :source :jp/shotokuzei-ho
     :provision "所得税法 第百九十条"
     :retrieved-via "e-Gov law API v2 GET /api/2/law_data/340AC0000000033"
     :retrieved-at "2026-08-17"
     ;; The API served revision 340AC0000000033_20260812_508AC0000000064, which
     ;; is NEWER than the pinned corpus snapshot (`:law.status/superseded-revision`).
     ;; Recorded so a later reader can tell which text was actually read.
     :retrieved-revision "340AC0000000033_20260812_508AC0000000064"
     :quote-is-partial? true
     :quote-omits (str "各号（第一号・第二号とそのイ〜ヘ）は引用していない。"
                       "そこにあるのは年税額の計算方法であって、この library が"
                       "主張する適用条件ではない。全文は上記 API で取得できる。")
     :quote (str "給与所得者の扶養控除等申告書を提出した居住者で、第一号に規定する"
                 "その年中に支払うべきことが確定した給与等の金額が二千万円以下で"
                 "あるものに対し、その提出の際に経由した給与等の支払者がその年最後に"
                 "給与等の支払をする場合（その居住者がその後その年十二月三十一日"
                 "までの間に当該支払者以外の者に当該申告書を提出すると見込まれる"
                 "場合を除く。）において、同号に掲げる所得税の額の合計額がその年"
                 "最後に給与等の支払をする時の現況により計算した第二号に掲げる"
                 "税額に比し過不足があるときは、その超過額は、その年最後に給与等の"
                 "支払をする際徴収すべき所得税に充当し、その不足額は、その年最後に"
                 "給与等の支払をする際徴収してその徴収の日の属する月の翌月十日までに"
                 "国に納付しなければならない。")}]
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

    ;; Read, not cited. The flat `:rule/years 7` that stood here was wrong
    ;; in three ways, and each is in the text:
    ;;
    ;;   it is 7 years OR 10 (第二十六条の三, when 欠損金の繰越し is relied on)
    ;;   it binds 青色申告法人, not every company
    ;;   the clock starts at 起算日 — fiscal-year end + 2 months — not at
    ;;   the transaction date
    ;;
    ;; Retrieved 2026-08-18, `GET /api/2/law_data/340M50000040012`,
    ;; revision 340M50000040012_20260731_508M60000040051.
    :jurisdiction/retention
    {:rule/years 7
     :rule/years-with-loss-carryforward 10
     :rule/binds :blue-return-corporation
     :rule/review :read-from-source
     :rule/provision "法人税法施行規則 第五十九条"
     :rule/quote (str "青色申告法人は、次に掲げる帳簿書類を整理し、起算日から"
                      "七年間、これを納税地…に保存しなければならない。")
     :rule/basis-date-provision "法人税法施行規則 第五十九条第二項"
     :rule/basis-date-quote (str "前項に規定する起算日とは、帳簿については"
                                 "その閉鎖の日の属する事業年度終了の日の翌日から"
                                 "二月を経過した日をいい、書類についてはその作成"
                                 "又は受領の日の属する事業年度終了の日の翌日から"
                                 "二月を経過した日をいう。")
     :rule/extended-provision "法人税法施行規則 第二十六条の三第一項"
     :rule/extended-quote (str "…第五十九条第二項に規定する起算日から十年間、"
                               "これを納税地…に保存しなければならない。")
     :rule/basis-months 2
     :rule/retrieved-at "2026-08-18"
     :rule/sources [:jp/hojinzei-kisoku :jp/hojinzei-ho :jp/nta-5930]}

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
     :rule/sources [:jp/dencho-ho :jp/dencho-kisoku]}

    ;; 源泉徴収義務 — read, not merely cited. 所得税法 第百八十三条第一項,
    ;; retrieved 2026-08-17 from `GET /api/2/law_data/340AC0000000033`.
    ;;
    ;; The article's own scope, recorded and NOT widened. It reaches a payer
    ;; who pays 給与等 (employment income, 所得税法 第二十八条第一項)
    ;;   - 居住者に対し   → to a RESIDENT
    ;;   - 国内において   → DOMESTICALLY
    ;; and nothing else. Payments to non-residents, payments made outside
    ;; Japan, and payments that are not 給与等 are governed by provisions
    ;; this library has NOT read, so it says nothing about them — see
    ;; `withholding-obligation`'s `:out-of-scope`, which is deliberately not
    ;; a finding that no obligation exists.
    :jurisdiction/wage-withholding
    {:rule/must-withhold-income-tax? true
     :rule/review :read-from-source
     :rule/provision "所得税法 第百八十三条第一項"
     :rule/quote (str "居住者に対し国内において第二十八条第一項（給与所得）に規定する"
                      "給与等（以下この章において「給与等」という。）の支払をする者は、"
                      "その支払の際、その給与等について所得税を徴収し、その徴収の日の"
                      "属する月の翌月十日までに、これを国に納付しなければならない。")
     :rule/retrieved-at "2026-08-17"
     :rule/retrieved-via "e-Gov law API v2 GET /api/2/law_data/340AC0000000033"
     :rule/applies-to #{:employment-income}
     :rule/scope {:recipient :resident
                  :place :domestic
                  :payment-kind :employment-income}
     ;; 「その徴収の日の属する月の翌月十日までに」— recorded because the
     ;; article states it. This library does not compute or check dates.
     :rule/remittance-deadline "徴収の日の属する月の翌月十日"
     ;; The article says 徴収し — collect THE income tax on that 給与等. How
     ;; much that is comes from 別表第二 / 別表第五, which were NOT read, so
     ;; nothing here verifies an amount. See `:taxlaw/amount-checked?`.
     :rule/amount-source-not-read "所得税法 別表第二・別表第五（税額表）"
     :rule/sources [:jp/shotokuzei-ho]}

    ;; 年末調整 — read, not merely cited. 所得税法 第百九十条, same retrieval.
    ;; The quote is the article's operative opening sentence; its 各号 set out
    ;; how the year's tax is computed and are omitted, which
    ;; `catalog-verification` records as `:quote-is-partial?`.
    ;;
    ;; Three conditions are read off the text and nothing is added to them:
    ;;   - 給与所得者の扶養控除等申告書を提出した居住者
    ;;   - その年中に支払うべきことが確定した給与等の金額が二千万円以下
    ;;   - その提出の際に経由した給与等の支払者がその年最後に給与等の支払をする場合
    :jurisdiction/year-end-adjustment
    {:rule/must-adjust-at-year-end? true
     :rule/review :read-from-source
     :rule/provision "所得税法 第百九十条"
     :rule/quote (str "給与所得者の扶養控除等申告書を提出した居住者で、第一号に規定する"
                      "その年中に支払うべきことが確定した給与等の金額が二千万円以下で"
                      "あるものに対し、その提出の際に経由した給与等の支払者がその年最後に"
                      "給与等の支払をする場合（その居住者がその後その年十二月三十一日"
                      "までの間に当該支払者以外の者に当該申告書を提出すると見込まれる"
                      "場合を除く。）において、同号に掲げる所得税の額の合計額がその年"
                      "最後に給与等の支払をする時の現況により計算した第二号に掲げる"
                      "税額に比し過不足があるときは、その超過額は、その年最後に給与等の"
                      "支払をする際徴収すべき所得税に充当し、その不足額は、その年最後に"
                      "給与等の支払をする際徴収してその徴収の日の属する月の翌月十日までに"
                      "国に納付しなければならない。")
     :rule/quote-is-partial? true
     :rule/retrieved-at "2026-08-17"
     :rule/retrieved-via "e-Gov law API v2 GET /api/2/law_data/340AC0000000033"
     :rule/declaration "給与所得者の扶養控除等申告書"
     ;; 「二千万円以下」 — from the text, not from guidance.
     :rule/income-ceiling-yen 20000000
     :rule/sources [:jp/shotokuzei-ho]}}})

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

(defn- js-or-parse [x]
  #?(:clj (Long/parseLong x) :cljs (js/parseInt x 10)))

(defn- leap? [y] (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400)))))

(defn- days-in-month [y m]
  (case (long m) 1 31 2 (if (leap? y) 29 28) 3 31 4 30 5 31 6 30
        7 31 8 31 9 30 10 31 11 30 12 31 nil))

(defn- parse-date
  "\"YYYY-MM-DD\" -> `{:y :m :d}`, or nil. Rejects an impossible day rather
  than rolling it forward — 2026-02-30 is a data error, not February 30th."
  [s]
  (when (string? s)
    (when-let [[_ y m d] (re-matches #"(\d{4})-(\d{2})-(\d{2})" s)]
      (let [y (js-or-parse y) m (js-or-parse m) d (js-or-parse d)]
        (when (and (<= 1 m 12) (<= 1 d (days-in-month y m)))
          {:y y :m m :d d})))))

(defn- fmt [{:keys [y m d]}]
  (str y "-" (when (< m 10) "0") m "-" (when (< d 10) "0") d))

(defn- add-months
  "Calendar month addition, clamping an overflowing day to the month end.

  Returns `[date clamped?]`. The clamp is a CONVENTION THIS LIBRARY CHOSE,
  not something 第五十九条第二項 states: adding two months to 12-31 has no
  literal answer. The flag is returned rather than swallowed so a caller can
  see that a convention was applied to its particular date."
  [{:keys [y m d]} n]
  (let [t (+ (dec m) n)
        y' (+ y (quot t 12))
        m' (inc (mod t 12))
        dim (days-in-month y' m')
        clamped? (> d dim)]
    [{:y y' :m m' :d (min d dim)} clamped?]))

(defn- add-days-1 [{:keys [y m d]}]
  (let [dim (days-in-month y m)]
    (if (< d dim)
      {:y y :m m :d (inc d)}
      (if (= m 12) {:y (inc y) :m 1 :d 1} {:y y :m (inc m) :d 1}))))

(defn retention
  "How long must this fiscal year's books and documents be kept, and from when?

  Four-valued, like `withholding-obligation`, and for the same reason — the
  article's own scope is narrower than \"a company\":

    {:taxlaw/coverage :none}          nobody catalogued this jurisdiction
    {:taxlaw/coverage :not-declared}  no fiscal-year end, or filing status
                                      unstated — nothing was asserted
    {:taxlaw/coverage :out-of-scope}  not a 青色申告法人. 第五十九条 binds
                                      them and nobody else; 白色申告 is
                                      governed by provisions NOT READ here,
                                      so this is not a finding that no
                                      obligation exists
    {:taxlaw/coverage :checked ...}   with :retain-from and :retain-years

  `:retain-from` is 起算日 per 第五十九条第二項: the day after the fiscal
  year ends, plus two months. NOT the transaction date, which is what a
  flat \"keep receipts seven years\" reading gets wrong.

  `:retain-years` is 10 rather than 7 when `:loss-carryforward?` is true
  (第二十六条の三第一項).

  ## Two things it declines to resolve

  `:date-convention :clamped-to-month-end` appears when adding two months
  overflowed the day — 12-31 plus two months has no literal answer, and the
  clamp is this library's choice, not the article's.

  There is deliberately **no `:retain-until`**. 「七年間」 from a date does
  not say whether the final day is inside or outside the period, and that is
  a counting convention the text does not settle. Emitting a specific last
  day would make a guess look like a rule. `:retain-years` and
  `:retain-from` are what the article actually gives."
  [j {:keys [fiscal-year-end blue-return? loss-carryforward? filing-extension-months]}]
  (let [path (normalize j)
        facet (get-in jurisdictions [path :jurisdiction/retention])]
    (cond
      (not (covered? path))
      {:taxlaw/coverage :none :taxlaw/unchecked [path]}

      (or (nil? blue-return?) (nil? fiscal-year-end))
      {:taxlaw/coverage :not-declared
       :taxlaw/why (if (nil? fiscal-year-end)
                     "no fiscal-year end declared"
                     "filing status (青色申告) not declared")}

      (false? blue-return?)
      {:taxlaw/coverage :out-of-scope
       :taxlaw/read-provision (:rule/provision facet)
       :taxlaw/why (str "第五十九条 binds 青色申告法人; other filing statuses are "
                        "governed by provisions this catalog has not read")}

      :else
      (if-let [fye (parse-date fiscal-year-end)]
        (let [[from clamped?] (add-months (add-days-1 fye)
                                          (+ (:rule/basis-months facet)
                                             (or filing-extension-months 0)))]
          (cond-> {:taxlaw/coverage :checked
                   :taxlaw/jurisdiction path
                   :taxlaw/retain-from (fmt from)
                   :taxlaw/retain-years (if loss-carryforward?
                                          (:rule/years-with-loss-carryforward facet)
                                          (:rule/years facet))
                   :taxlaw/provision (if loss-carryforward?
                                       (:rule/extended-provision facet)
                                       (:rule/provision facet))
                   :taxlaw/basis-date-provision (:rule/basis-date-provision facet)}
            clamped? (assoc :taxlaw/date-convention :clamped-to-month-end)))
        {:taxlaw/coverage :not-declared
         :taxlaw/why (str "fiscal-year end is not a valid YYYY-MM-DD: "
                          (pr-str fiscal-year-end))}))))

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

;; ---------------------------------------------------------------------------
;; 源泉徴収 / 年末調整 — 所得税法 第百八十三条第一項 and 第百九十条
;; ---------------------------------------------------------------------------

(defn requires-wage-withholding?
  "Must an employer paying employment income withhold income tax here?

  **nil** for an uncatalogued jurisdiction — deliberately not false, for the
  same reason `requires-qualified-invoice?` is."
  [j]
  (get-in jurisdictions
          [(normalize j) :jurisdiction/wage-withholding
           :rule/must-withhold-income-tax?]))

(defn requires-year-end-adjustment?
  "Must an employer settle the year's over/under-withholding at the final
  payment? **nil** when unknown, for the same reason."
  [j]
  (get-in jurisdictions
          [(normalize j) :jurisdiction/year-end-adjustment
           :rule/must-adjust-at-year-end?]))

(defn withholding-obligation
  "Does this `payment` record account for the income tax its jurisdiction
  obliges the payer to withhold?

  Same shape as `credit-support` and `record-preservation`, with one more
  non-`:checked` state that the read article makes necessary:

    {:taxlaw/coverage :none}          nobody catalogued this jurisdiction
    {:taxlaw/coverage :not-declared}  the record does not say what kind of
                                      payment this is; nothing was asserted,
                                      so nothing was checked
    {:taxlaw/coverage :out-of-scope}  the record declares itself OUTSIDE the
                                      one article this library has read
    {:taxlaw/coverage :checked ...}   it is in scope, and here is the answer

  `:taxlaw/accounted-for?` is absent in all three non-`:checked` cases, so a
  careless caller gets nil — falsey, the conservative answer — and a careful
  one can tell `refused` from `not checked`.

  A `payment` declares:

    :payment-kind         :employment-income | anything else
    :recipient-residency  :resident | :non-resident | nil
    :paid-in              :domestic | :overseas | nil
    :income-tax-withheld  amount actually withheld, or nil

  ## What `:out-of-scope` is and is not

  所得税法 第百八十三条第一項 binds a payer of 給与等 **to a 居住者** **国内に
  おいて**. A payment that declares itself outside that — a non-resident
  recipient, a payment made abroad, a payment that is not 給与等 — is not
  reached by the article this library read. That is **not** a finding that no
  withholding obligation exists: other provisions govern those cases and this
  library has not read them. `:out-of-scope` says so in
  `:taxlaw/read-provision`, and a caller that treats it as a pass is making a
  claim this library did not make.

  ## Silence is not an exemption

  A payment that declares employment income but says nothing about residency
  or place is `:checked`, not `:out-of-scope`. Absence of a declaration is
  the unchecked case, and the unchecked case never buys the article's own
  exclusion — only an explicit `:non-resident` / `:overseas` does.

  ## What is checked, and what is not

  Presence, not amount. The article says 徴収し — collect the income tax on
  that 給与等; how much that is comes from 別表第二 / 別表第五, which were not
  read. `:taxlaw/amount-checked?` is `false` on every result, so a caller
  cannot mistake `an amount is recorded` for `the amount is right`."
  [j payment]
  (let [path (normalize j)
        {:keys [payment-kind recipient-residency paid-in income-tax-withheld]} payment
        read-provision (get-in jurisdictions
                               [path :jurisdiction/wage-withholding :rule/provision])]
    (cond
      (not (covered? path))
      {:taxlaw/coverage :none :taxlaw/unchecked [path]}

      (nil? payment-kind)
      {:taxlaw/coverage :not-declared
       :taxlaw/why "the record does not say what kind of payment this is"}

      (not= :employment-income payment-kind)
      {:taxlaw/coverage :out-of-scope
       :taxlaw/read-provision read-provision
       :taxlaw/payment-kind payment-kind
       :taxlaw/why (str "読んだのは給与等（所得税法 第二十八条第一項）の支払に係る"
                        "条文だけで、この支払はそれではないと宣言されている。"
                        "他の条文については何も述べていない（不適用の判断ではない）。")}

      (or (= :non-resident recipient-residency) (= :overseas paid-in))
      {:taxlaw/coverage :out-of-scope
       :taxlaw/read-provision read-provision
       :taxlaw/recipient-residency recipient-residency
       :taxlaw/paid-in paid-in
       :taxlaw/why (str "読んだ条文は「居住者に対し国内において」支払う者を縛る。"
                        "この支払はその外だと宣言されている。国外払・非居住者"
                        "への支払を規律する条文は読んでいない（不適用の判断ではない）。")}

      :else
      (let [required? (true? (requires-wage-withholding? path))
            ok? (or (not required?)
                    (and (number? income-tax-withheld)
                         (not (neg? income-tax-withheld))))]
        {:taxlaw/coverage :checked
         :taxlaw/jurisdiction path
         :taxlaw/accounted-for? ok?
         :taxlaw/withholding-required? required?
         :taxlaw/income-tax-withheld income-tax-withheld
         ;; presence, never amount — 別表第二 / 別表第五 were not read.
         :taxlaw/amount-checked? false
         :taxlaw/provision (when required? read-provision)
         :taxlaw/remittance-deadline
         (when required?
           (get-in jurisdictions
                   [path :jurisdiction/wage-withholding :rule/remittance-deadline]))
         :taxlaw/reason (cond ok? nil
                              (nil? income-tax-withheld) :withholding-not-recorded
                              :else :malformed-withholding-amount)}))))

(defn accounts-for-withholding?
  "Convenience boolean over `withholding-obligation`, conservative in the same
  way `supported?` and `preserved?` are: `:none`, `:not-declared` and
  `:out-of-scope` all come back false, because none of them established that
  the payment accounts for withheld income tax."
  [j payment]
  (true? (:taxlaw/accounted-for? (withholding-obligation j payment))))

(defn year-end-adjustment
  "Does this `record` settle the year's over/under-withholding where the read
  article requires it?

  Same four-state shape as `withholding-obligation`:

    {:taxlaw/coverage :none}          nobody catalogued this jurisdiction
    {:taxlaw/coverage :not-declared}  the record does not say whether this is
                                      the year's final payment — 第百九十条's
                                      own trigger — so nothing was checked
    {:taxlaw/coverage :out-of-scope}  the record declares a condition the
                                      article excludes
    {:taxlaw/coverage :checked ...}   `:taxlaw/adjusted?`

  A `record` declares:

    :final-payment-of-year?      the payer's last 給与等 payment of the year
    :declaration-filed?          給与所得者の扶養控除等申告書 was filed via
                                 this payer
    :annual-employment-income    その年中に支払うべきことが確定した給与等の金額
    :year-end-adjustment-settled? the over/under was applied at that payment

  The three exclusions are read off the text and nothing is added to them: a
  declaration explicitly NOT filed, an explicit annual amount above
  二千万円, and an explicit `not the final payment`. Each must be stated —
  an absent field leaves the question open and the record in scope, because
  silence is not an exemption here either."
  [j record]
  (let [path (normalize j)
        {:keys [final-payment-of-year? declaration-filed?
                annual-employment-income year-end-adjustment-settled?]} record
        rule (get-in jurisdictions [path :jurisdiction/year-end-adjustment])
        ceiling (:rule/income-ceiling-yen rule)]
    (cond
      (not (covered? path))
      {:taxlaw/coverage :none :taxlaw/unchecked [path]}

      (nil? final-payment-of-year?)
      {:taxlaw/coverage :not-declared
       :taxlaw/why (str "the record does not say whether this is the year's "
                        "final payment, which is the article's own trigger")}

      (false? final-payment-of-year?)
      {:taxlaw/coverage :out-of-scope
       :taxlaw/read-provision (:rule/provision rule)
       :taxlaw/why "その年最後の給与等の支払ではないと宣言されている"}

      (false? declaration-filed?)
      {:taxlaw/coverage :out-of-scope
       :taxlaw/read-provision (:rule/provision rule)
       :taxlaw/why (str "給与所得者の扶養控除等申告書を提出していないと宣言されている。"
                        "確定申告その他の経路については何も述べていない。")}

      (and (number? annual-employment-income)
           (number? ceiling)
           (> annual-employment-income ceiling))
      {:taxlaw/coverage :out-of-scope
       :taxlaw/read-provision (:rule/provision rule)
       :taxlaw/annual-employment-income annual-employment-income
       :taxlaw/income-ceiling-yen ceiling
       :taxlaw/why "その年中に支払うべきことが確定した給与等の金額が二千万円を超える"}

      :else
      (let [required? (true? (requires-year-end-adjustment? path))
            ok? (or (not required?) (true? year-end-adjustment-settled?))]
        {:taxlaw/coverage :checked
         :taxlaw/jurisdiction path
         :taxlaw/adjusted? ok?
         :taxlaw/year-end-adjustment-required? required?
         :taxlaw/amount-checked? false
         :taxlaw/provision (when required? (:rule/provision rule))
         :taxlaw/reason (cond ok? nil
                              (nil? year-end-adjustment-settled?) :adjustment-not-recorded
                              :else :year-end-adjustment-not-settled)}))))

(defn adjusted?
  "Convenience boolean over `year-end-adjustment`, conservative like the rest:
  every non-`:checked` coverage comes back false."
  [j record]
  (true? (:taxlaw/adjusted? (year-end-adjustment j record))))

(defn supported?
  "Convenience boolean over `credit-support`.

  Deliberately not `(:taxlaw/supported? ...)` alone: an uncatalogued
  jurisdiction has no `:taxlaw/supported?` key, so this returns false there
  too. A caller who reaches for the convenient boolean gets the
  conservative answer rather than the flattering one — worklaw's
  `compliant?` makes the same choice for the same reason."
  [j document]
  (true? (:taxlaw/supported? (credit-support j document))))
