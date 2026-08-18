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

   :jp/shohizei-rei
   {:source/title "消費税法施行令"
    :source/authority "日本国 / e-Gov 法令検索"
    :source/kind :statute
    :law/id "363CO0000000360"
    :source/url "https://laws.e-gov.go.jp/law/363CO0000000360"}

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
    {:claim :qualified-invoice-tax-amount-calculation
     :source :jp/shohizei-rei
     :provision "消費税法施行令 第七十条の十"
     :retrieved-via "e-Gov law API v2 GET /api/2/law_data/363CO0000000360"
     :retrieved-at "2026-08-18"
     :quote (str "法第五十七条の四第一項第五号に規定する政令で定める方法は、"
                 "次の各号に掲げる方法のいずれかとする。この場合において、"
                 "当該各号に掲げる方法により算出した金額に一円未満の端数が"
                 "生じたときは、当該端数を処理するものとする。一法第五十七条の四"
                 "第一項第四号に規定する課税資産の譲渡等に係る税抜価額を税率の"
                 "異なるごとに区分して合計した金額に百分の十（当該合計した金額が"
                 "軽減対象課税資産の譲渡等に係るものである場合には、百分の八）を"
                 "乗じて算出する方法二法第五十七条の四第一項第四号に規定する"
                 "課税資産の譲渡等に係る税込価額を税率の異なるごとに区分して"
                 "合計した金額に百十分の十（当該合計した金額が軽減対象課税資産の"
                 "譲渡等に係るものである場合には、百八分の八）を乗じて算出する方法")}
   {:claim :book-search-function-for-preferential-treatment
     :source :jp/dencho-kisoku
     :provision "電子帳簿保存法施行規則 第五条第五項第一号ハ"
     :retrieved-via "e-Gov law API v2 GET /api/2/law_data/410M50000040043"
     :retrieved-at "2026-08-18"
     :retrieved-revision "410M50000040043_20250401_507M60000040028"
     :quote (str "ハ当該国税関係帳簿に係る電磁的記録の記録事項の検索をすることが"
                 "できる機能（次に掲げる要件を満たすものに限る。）を確保しておく"
                 "こと。（１）取引年月日、取引金額及び取引先（（２）及び（３）に"
                 "おいて「記録項目」という。）を検索の条件として設定することが"
                 "できること。（２）日付又は金額に係る記録項目については、その"
                 "範囲を指定して条件を設定することができること。（３）二以上の"
                 "任意の記録項目を組み合わせて条件を設定することができること。")}
    {:claim :electronic-transaction-search-function
     :source :jp/dencho-kisoku
     :provision "電子帳簿保存法施行規則 第二条第六項第五号（第四条第一項が準用）"
     :retrieved-via "e-Gov law API v2 GET /api/2/law_data/410M50000040043"
     :retrieved-at "2026-08-18"
     :retrieved-revision "410M50000040043_20250401_507M60000040028"
     :quote (str "五当該国税関係書類に係る電磁的記録の記録事項の検索をすることが"
                 "できる機能（次に掲げる要件を満たすものに限る。）を確保しておく"
                 "こと。イ取引年月日その他の日付、取引金額及び取引先（ロ及びハに"
                 "おいて「記録項目」という。）を検索の条件として設定することが"
                 "できること。ロ日付又は金額に係る記録項目については、その範囲を"
                 "指定して条件を設定することができること。ハ二以上の任意の記録"
                 "項目を組み合わせて条件を設定することができること。")}
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

    ;; 適格請求書に記載すべき消費税額等の計算 — read, not merely cited.
    ;; 消費税法施行令 第七十条の十, retrieved 2026-08-18 from
    ;; `GET /api/2/law_data/363CO0000000360`.
    ;;
    ;; ## Three things the text settles that a naive implementation gets wrong
    ;;
    ;; 1. **The multiplication is on the per-rate SUBTOTAL, not per line.**
    ;;    「税率の異なるごとに区分して合計した金額に…を乗じて」— sum first,
    ;;    then multiply. Taxing each line and adding the results is a third
    ;;    method, and the article offers exactly two.
    ;; 2. **The rounding happens once, on that one figure.** 「当該各号に
    ;;    掲げる方法により算出した金額に一円未満の端数が生じたときは、当該
    ;;    端数を処理するものとする」.
    ;; 3. **The article does not say which way to round.** 処理する, not
    ;;    切り捨てる. The issuer picks, so this library refuses to.
    ;;
    ;; 消費税額等 is defined upstream (施行令 第四十五条) as 消費税額 plus the
    ;; 地方消費税額 computed on it, so the 十 in 百分の十 is the combined
    ;; figure and no separate local-tax step belongs here.
    :jurisdiction/qualified-invoice-tax-amount
    {:rule/review :read-from-source
     :rule/provision "消費税法施行令 第七十条の十"
     :rule/quote (str "法第五十七条の四第一項第五号に規定する政令で定める方法は、"
                      "次の各号に掲げる方法のいずれかとする。この場合において、"
                      "当該各号に掲げる方法により算出した金額に一円未満の端数が"
                      "生じたときは、当該端数を処理するものとする。")
     :rule/quote-is-partial? true
     :rule/quote-omits "第一号・第二号（二つの算出方法の本文）— :rule/methods に転記"
     :rule/retrieved-at "2026-08-18"
     :rule/retrieved-via "e-Gov law API v2 GET /api/2/law_data/363CO0000000360"
     ;; The two methods, as numerator/denominator pairs read off the text.
     ;; Exact integers, so nothing here goes through a float.
     :rule/methods
     {:tax-exclusive {:statute "第一号（税抜価額）"
                      :standard [10 100] :reduced [8 100]}
      :tax-inclusive {:statute "第二号（税込価額）"
                      :standard [10 110] :reduced [8 108]}}
     :rule/tax-categories #{:standard :reduced}
     ;; 「端数を処理する」— the article names no direction, so neither does
     ;; this catalog. These are the choices a caller may state.
     :rule/rounding-policies #{:floor :ceil :round-half-up}
     :rule/rounding-is-issuers-choice? true
     ;; Once per rate, on the subtotal — NOT per line.
     :rule/rounds-per :tax-category-subtotal
     :rule/sources [:jp/shohizei-ho :jp/shohizei-rei :jp/nta-invoice]}

    ;; 検索要件 — read, not merely cited. 電子帳簿保存法施行規則, retrieved
    ;; 2026-08-18 from `GET /api/2/law_data/410M50000040043` (revision
    ;; 410M50000040043_20250401_507M60000040028).
    ;;
    ;; ## Two regimes, not one
    ;;
    ;; A 帳簿 and an electronic transaction record are governed by DIFFERENT
    ;; provisions with DIFFERENT search requirements, and conflating them is
    ;; the easy mistake here. Both are catalogued separately below.
    ;;
    ;;   帳簿      規則第五条第五項第一号ハ  — required only to claim the
    ;;                                         過少申告加算税 reduction in
    ;;                                         法第八条第四項 (優良帳簿).
    ;;                                         Ordinary electronic book
    ;;                                         preservation under 法第四条
    ;;                                         第一項 does not require search
    ;;                                         at all.
    ;;   電子取引  規則第四条第一項 → 第二条  — required, with two exemptions
    ;;             第六項第五号                that turn on facts about the
    ;;                                         holder.
    ;;
    ;; The 記録項目 also differ by one phrase: 帳簿 reads 「取引年月日、
    ;; 取引金額及び取引先」 and 書類/電子取引 reads 「取引年月日その他の日付、
    ;; 取引金額及び取引先」. Recorded as read.
    :jurisdiction/book-search
    {:rule/required-only-when :claiming-preferential-treatment
     :rule/review :read-from-source
     :rule/provision "電子帳簿保存法施行規則 第五条第五項第一号ハ"
     :rule/quote (str "ハ当該国税関係帳簿に係る電磁的記録の記録事項の検索を"
                      "することができる機能（次に掲げる要件を満たすものに限る。）"
                      "を確保しておくこと。（１）取引年月日、取引金額及び取引先"
                      "（（２）及び（３）において「記録項目」という。）を検索の"
                      "条件として設定することができること。（２）日付又は金額に"
                      "係る記録項目については、その範囲を指定して条件を設定する"
                      "ことができること。（３）二以上の任意の記録項目を組み合わせて"
                      "条件を設定することができること。")
     :rule/retrieved-at "2026-08-18"
     :rule/retrieved-via "e-Gov law API v2 GET /api/2/law_data/410M50000040043"
     :rule/retrieved-revision "410M50000040043_20250401_507M60000040028"
     ;; The three 記録項目, as named by （１）.
     :rule/record-items #{:transaction-date :amount :counterparty}
     ;; （２）range, （３）combination of two or more.
     :rule/range-items #{:transaction-date :amount}
     :rule/combination-minimum 2
     ;; What the requirement buys. Named so nobody reads it as a duty.
     :rule/benefit "法第八条第四項（過少申告加算税の軽減）"
     :rule/sources [:jp/dencho-ho :jp/dencho-kisoku]}

    ;; 電子取引の検索要件. 規則第四条第一項 imports 第二条第六項第五号 and then
    ;; carves two exemptions out of it in the same sentence. Both are read off
    ;; the text and neither is widened:
    ;;
    ;;   (a) 電磁的記録の提示等の要求に応じることができるようにしている
    ;;         → ロ (range) and ハ (combination) drop; イ still stands
    ;;   (b) (a) AND (基準期間における売上高が五千万円以下
    ;;              OR 整然とした形式・明瞭な状態で出力され取引年月日その他の
    ;;                 日付及び取引先ごとに整理された書面の提示等に応じられる)
    ;;         → the whole 第五号 drops
    ;;
    ;; Both turn on facts about the holder that no library can observe, which
    ;; is why `electronic-transaction-search` refuses to answer rather than
    ;; defaulting either way.
    :jurisdiction/electronic-transaction-search
    {:rule/must-provide-search? true
     :rule/review :read-from-source
     :rule/provision "電子帳簿保存法施行規則 第二条第六項第五号（第四条第一項が準用）"
     :rule/quote (str "五当該国税関係書類に係る電磁的記録の記録事項の検索を"
                      "することができる機能（次に掲げる要件を満たすものに限る。）"
                      "を確保しておくこと。イ取引年月日その他の日付、取引金額及び"
                      "取引先（ロ及びハにおいて「記録項目」という。）を検索の条件"
                      "として設定することができること。ロ日付又は金額に係る記録"
                      "項目については、その範囲を指定して条件を設定することが"
                      "できること。ハ二以上の任意の記録項目を組み合わせて条件を"
                      "設定することができること。")
     :rule/retrieved-at "2026-08-18"
     :rule/retrieved-via "e-Gov law API v2 GET /api/2/law_data/410M50000040043"
     :rule/retrieved-revision "410M50000040043_20250401_507M60000040028"
     :rule/record-items #{:transaction-date :amount :counterparty}
     :rule/range-items #{:transaction-date :amount}
     :rule/combination-minimum 2
     ;; 「五千万円以下」 — from 規則第四条第一項, not from guidance.
     :rule/small-holder-sales-ceiling-yen 50000000
     :rule/exemptions
     {:on-demand-production "ロ・ハが外れる（イは残る）"
      :small-holder-or-organized-paper "第五号全体が外れる（提示等に応じられる場合に限る）"}
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
;; 消費税額等 — 消費税法施行令 第七十条の十
;; ---------------------------------------------------------------------------

(defn- round-exact
  "`num`/`den` as an integer under `policy`. Exact integer arithmetic — no
  float ever holds a tax figure here.

  Refuses a negative numerator by returning nil rather than rounding it:
  `quot` truncates toward zero, so `:floor` on a negative would round UP and
  do it silently. A 返還インボイス is a real thing and this article is not
  the one that governs it."
  [num den policy]
  (when (and (integer? num) (>= num 0) (pos? den))
    (let [q (quot num den) r (rem num den)]
      (cond (zero? r) q
            (= :floor policy) q
            (= :ceil policy) (inc q)
            (= :round-half-up policy) (if (>= (* 2 r) den) (inc q) q)))))

(defn consumption-tax-amount
  "The 消費税額等 to write on a 適格請求書, per 消費税法施行令 第七十条の十.

  `invoice` states the three things the article leaves to the issuer:

      {:method :tax-exclusive | :tax-inclusive     第一号 / 第二号
       :rounding :floor | :ceil | :round-half-up   「端数を処理する」
       :subtotals {:standard n :reduced n}}        税率の異なるごとに
                                                   区分して合計した金額

  ## The shape of `:subtotals` is the point

  The article multiplies **the per-rate subtotal**, once, and rounds **that
  one figure**, once. Taxing each line and summing the results is a third
  method and the article offers two. This function therefore takes subtotals
  and cannot be handed lines — a caller holding lines must group them, which
  is where the grouping decision belongs and where it can be seen.

  ## What it refuses

    {:taxlaw/coverage :none}          uncatalogued jurisdiction
    {:taxlaw/coverage :not-declared}  the invoice does not state the method,
                                      or does not state the rounding, or
                                      names a tax category the article does
                                      not. **Not a pass, and not a zero.**
    {:taxlaw/coverage :checked ...}   `:taxlaw/tax-by-category` and
                                      `:taxlaw/tax` — the sum of the
                                      per-category figures, each already
                                      rounded once

  Neither the method nor the rounding is defaulted. 「いずれかとする」 and
  「端数を処理するものとする」 are both choices the article hands the issuer,
  and a library that picks one has answered a question it was not asked —
  by ¥1 per rate, on every invoice, forever."
  [j invoice]
  (let [path (normalize j)
        rule (get-in jurisdictions [path :jurisdiction/qualified-invoice-tax-amount])
        {:keys [method rounding subtotals]} invoice
        methods (:rule/methods rule)
        known (:rule/tax-categories rule)]
    (cond
      (or (not (covered? path)) (nil? rule))
      {:taxlaw/coverage :none :taxlaw/unchecked [path]}

      (nil? (get methods method))
      {:taxlaw/coverage :not-declared
       :taxlaw/why (str "the invoice does not state which of the two methods in "
                        (:rule/provision rule) " it uses"
                        (when (some? method) (str "; got " (pr-str method))))
       :taxlaw/choices (set (keys methods))
       :taxlaw/provision (:rule/provision rule)}

      (not (contains? (:rule/rounding-policies rule) rounding))
      {:taxlaw/coverage :not-declared
       :taxlaw/why (str "the article says 端数を処理する and does not say which "
                        "way; the issuer must state it"
                        (when (some? rounding) (str "; got " (pr-str rounding))))
       :taxlaw/choices (:rule/rounding-policies rule)
       :taxlaw/provision (:rule/provision rule)}

      (not (map? subtotals))
      {:taxlaw/coverage :not-declared
       :taxlaw/why "no per-rate subtotals were given"
       :taxlaw/provision (:rule/provision rule)}

      (seq (remove known (keys subtotals)))
      {:taxlaw/coverage :not-declared
       :taxlaw/why (str "the article names 標準 and 軽減 and this catalog has "
                        "read no other; refusing to treat an unknown category "
                        "as either")
       :taxlaw/unknown-categories (set (remove known (keys subtotals)))
       :taxlaw/provision (:rule/provision rule)}

      :else
      (let [by-cat (reduce (fn [acc [cat amount]]
                             (let [[n d] (get-in methods [method cat])]
                               (assoc acc cat (round-exact (* amount n) d rounding))))
                           {} subtotals)]
        (if (some nil? (vals by-cat))
          {:taxlaw/coverage :not-declared
           :taxlaw/why (str "a subtotal is not a non-negative integer; this "
                            "article governs 適格請求書 and not 返還インボイス, "
                            "and rounding a negative would silently go the "
                            "other way")
           :taxlaw/rejected (set (keep (fn [[c v]] (when (nil? v) c)) by-cat))
           :taxlaw/provision (:rule/provision rule)}
          {:taxlaw/coverage :checked
           :taxlaw/jurisdiction path
           :taxlaw/method method
           :taxlaw/method-statute (get-in methods [method :statute])
           :taxlaw/rounding rounding
           :taxlaw/tax-by-category by-cat
           :taxlaw/tax (reduce + 0 (vals by-cat))
           :taxlaw/rounds-per (:rule/rounds-per rule)
           :taxlaw/provision (:rule/provision rule)})))))

(defn consumption-tax
  "The single figure from `consumption-tax-amount`, or **nil** when it
  refused. Conservative like `preserved?` — but nil rather than false,
  because 0 is a real tax amount and must not be what `could not answer`
  looks like."
  [j invoice]
  (let [r (consumption-tax-amount j invoice)]
    (when (= :checked (:taxlaw/coverage r)) (:taxlaw/tax r))))

;; ---------------------------------------------------------------------------
;; 検索要件 — 規則第五条第五項第一号ハ (帳簿) and 規則第二条第六項第五号 (電子取引)
;; ---------------------------------------------------------------------------

(defn- search-shortfall
  "Which of the three sub-requirements a system does not meet, given the
  rule and what the system says it can do. A set, so an empty one means
  `nothing was found missing` — and the caller still has to have established
  that anything was looked at."
  [rule {:keys [searchable-by range-search? combination-search?]} required]
  (let [items (set (or searchable-by #{}))]
    (cond-> #{}
      (and (contains? required :items)
           (not (every? items (:rule/record-items rule))))
      (conj :record-items)

      (and (contains? required :range) (not (true? range-search?)))
      (conj :range)

      (and (contains? required :combination) (not (true? combination-search?)))
      (conj :combination))))

(defn requires-book-search?
  "Must a 国税関係帳簿 be searchable here?

  **This is deliberately not a plain yes.** 規則第五条第五項第一号ハ attaches
  to 法第八条第四項 — the 過少申告加算税 reduction — and ordinary electronic
  book preservation under 法第四条第一項 does not require search at all. So
  the honest answer is a keyword, not a boolean:

    nil                              uncatalogued jurisdiction
    :claiming-preferential-treatment required IF the holder is claiming it

  A caller that wanted `true` and got a keyword has learned something real:
  whether the requirement bites depends on a decision the holder makes and
  the software does not observe."
  [j]
  (get-in jurisdictions
          [(normalize j) :jurisdiction/book-search :rule/required-only-when]))

(defn book-search
  "Does this bookkeeping system meet 規則第五条第五項第一号ハ?

  `books` states what the system can do and what the holder is claiming:

      {:claiming-preferential-treatment? true|false   法第八条第四項
       :searchable-by #{:transaction-date :amount :counterparty}
       :range-search? true|false
       :combination-search? true|false}

  Four-valued, like `record-preservation` and for the same reason:

    {:taxlaw/coverage :none}          nobody catalogued this jurisdiction
    {:taxlaw/coverage :not-declared}  the books do not say whether the
                                      holder is claiming 法第八条第四項.
                                      **Not a pass.** The requirement may
                                      or may not bite and nothing here can
                                      tell which.
    {:taxlaw/coverage :checked
     :taxlaw/search-required? false}  explicitly not claiming it — the
                                      requirement genuinely does not apply
    {:taxlaw/coverage :checked
     :taxlaw/search-required? true
     :taxlaw/adequate? bool
     :taxlaw/missing #{...}}          it does apply, and here is the answer

  `:taxlaw/adequate?` is **nil** when the requirement does not apply, not
  true. `the rule does not reach you` and `you satisfy the rule` are
  different facts and a caller that needs the second must not be handed the
  first wearing its clothes."
  [j books]
  (let [path (normalize j)
        rule (get-in jurisdictions [path :jurisdiction/book-search])]
    (cond
      (or (not (covered? path)) (nil? rule))
      {:taxlaw/coverage :none :taxlaw/unchecked [path]}

      (nil? (:claiming-preferential-treatment? books))
      {:taxlaw/coverage :not-declared
       :taxlaw/why (str "the books do not say whether the holder is claiming "
                        (:rule/benefit rule)
                        "; the search requirement attaches to that claim and "
                        "to nothing else")
       :taxlaw/provision (:rule/provision rule)}

      (not (true? (:claiming-preferential-treatment? books)))
      {:taxlaw/coverage :checked
       :taxlaw/jurisdiction path
       :taxlaw/search-required? false
       :taxlaw/adequate? nil
       :taxlaw/why (str "ordinary electronic preservation under 法第四条第一項 "
                        "carries no search requirement")}

      :else
      (let [missing (search-shortfall rule books #{:items :range :combination})]
        {:taxlaw/coverage :checked
         :taxlaw/jurisdiction path
         :taxlaw/search-required? true
         :taxlaw/adequate? (empty? missing)
         :taxlaw/missing missing
         :taxlaw/record-items (:rule/record-items rule)
         :taxlaw/provision (:rule/provision rule)}))))

(defn book-search-adequate?
  "Convenience boolean over `book-search`, conservative like `preserved?`.

  False for `:none`, for `:not-declared`, **and for a holder that is not
  claiming 法第八条第四項** — in that last case nothing is wrong, but
  nothing was satisfied either, and this function answers the second
  question."
  [j books]
  (true? (:taxlaw/adequate? (book-search j books))))

(defn electronic-transaction-search
  "Does this setup meet the search requirement 規則第四条第一項 imports for
  electronic transaction records?

  `setup` states what the system can do and the two facts the exemptions
  turn on:

      {:searchable-by #{...} :range-search? bool :combination-search? bool
       :can-produce-on-demand? bool     電磁的記録の提示等の要求に応じられる
       :base-period-sales-yen n         基準期間における売上高
       :paper-output-organized? bool    取引年月日その他の日付及び取引先ごとに
                                        整理された書面の提示等に応じられる}

  ## What the exemptions do, read off 規則第四条第一項

  Producing the records on demand drops ロ (range) and ハ (combination) and
  leaves イ. Doing that **and** either being under the 五千万円 sales ceiling
  or being able to produce organized paper drops the whole of 第五号.

  ## Why this can answer `:not-declared` even when it knows a lot

  If the holder has not said whether it can produce on demand, the tier is
  unknown and the answer is `:not-declared`. If it can produce on demand and
  イ is satisfied, the answer is settled and the sales figure never matters —
  the wider exemption could only help, and nothing needed helping. But if
  イ is **not** satisfied, the wider exemption is the only thing left, and
  it turns on a sales figure or a paper capability that may not be stated:
  then this returns `:not-declared` rather than a refusal, because
  `you fail` and `you did not tell me the fact that decides it` are not the
  same finding."
  [j setup]
  (let [path (normalize j)
        rule (get-in jurisdictions [path :jurisdiction/electronic-transaction-search])]
    (cond
      (or (not (covered? path)) (nil? rule))
      {:taxlaw/coverage :none :taxlaw/unchecked [path]}

      (nil? (:can-produce-on-demand? setup))
      {:taxlaw/coverage :not-declared
       :taxlaw/why (str "the setup does not say whether it can respond to "
                        "電磁的記録の提示等の要求; both exemptions in "
                        "規則第四条第一項 depend on it")
       :taxlaw/provision (:rule/provision rule)}

      :else
      (let [on-demand? (true? (:can-produce-on-demand? setup))
            sales (:base-period-sales-yen setup)
            ceiling (:rule/small-holder-sales-ceiling-yen rule)
            small (cond (number? sales) (<= sales ceiling) :else nil)
            paper (:paper-output-organized? setup)
            ;; The wider exemption needs on-demand AND (small OR paper).
            wider (cond (not on-demand?) false
                        (or (true? small) (true? paper)) true
                        ;; both legs known and neither holds
                        (and (false? small) (false? paper)) false
                        :else nil)
            required (cond wider #{}
                           on-demand? #{:items}
                           :else #{:items :range :combination})
            missing (search-shortfall rule setup required)]
        (if (and (seq missing) (nil? wider))
          {:taxlaw/coverage :not-declared
           :taxlaw/why (str "the setup does not meet " (pr-str missing)
                            " and does not state the 基準期間の売上高 or the "
                            "organized-paper capability that the wider "
                            "exemption in 規則第四条第一項 turns on")
           :taxlaw/missing missing
           :taxlaw/provision (:rule/provision rule)}
          {:taxlaw/coverage :checked
           :taxlaw/jurisdiction path
           :taxlaw/search-required? (boolean (seq required))
           :taxlaw/exemption (cond wider :small-holder-or-organized-paper
                                   on-demand? :on-demand-production
                                   :else nil)
           :taxlaw/adequate? (empty? missing)
           :taxlaw/missing missing
           :taxlaw/record-items (:rule/record-items rule)
           :taxlaw/provision (:rule/provision rule)})))))

(defn electronic-transaction-search-adequate?
  "Convenience boolean over `electronic-transaction-search`, conservative in
  the same way — `:none` and `:not-declared` are both false."
  [j setup]
  (true? (:taxlaw/adequate? (electronic-transaction-search j setup))))

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
