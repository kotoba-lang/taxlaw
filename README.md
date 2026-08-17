# kotoba-taxlaw

**What a tax record must carry, by jurisdiction** — a
[kotoba-lang](https://github.com/kotoba-lang) capability library that answers
one question: in this jurisdiction, does this document support the treatment
being claimed for it?

> **Not tax advice.** This is a mechanism plus a small, cited rule set. It is
> deliberately incomplete, and its most important behaviour is what it does
> about that.

Sibling of [`kotoba-lang/worklaw`](https://github.com/kotoba-lang/worklaw),
and the same shape on purpose.

## The invariant: absence is never sufficiency

```clojure
(require '[kotoba.taxlaw :as taxlaw])

(taxlaw/credit-support [:atlantis] {:registration-number "T1234567890123"})
;; => {:taxlaw/coverage :none :taxlaw/unchecked [[:atlantis]]}
;;    no :taxlaw/supported? key at all — nobody checked

(taxlaw/credit-support [:jp] {:registration-number nil})
;; => {:taxlaw/coverage :checked :taxlaw/supported? false
;;     :taxlaw/reason :missing-registration-number ...}

(taxlaw/supported? [:atlantis] {:registration-number "T1234567890123"})
;; => false
```

`requires-qualified-invoice?` returns **nil**, not `false`, for an
uncatalogued jurisdiction — so a caller cannot read *we have no rule* as
*there is no requirement*. `supported?` is deliberately not
`(:taxlaw/supported? …)`: a caller who reaches for the convenient boolean
gets the conservative answer rather than the flattering one.

That distinction is the whole library. A bookkeeping actor that treated
silence as sufficiency would let a receipt nobody has seen the law about
support an input-tax credit.

## Jurisdictions are paths, not codes

`[:jp]` today. A path leaves room for `[:jp :tokyo]` or `[:us :ca]` without
renaming anything. A bare keyword (`:jp`) is accepted too, because making
every actor convert at the call site is how a shared library stops being
used.

## Citations are checked against the corpus, not against HTTP

Every statute carries its **e-Gov law id**, and `tools/verify_citations.cljs`
resolves those ids against
[`kotoba-lang/jp.go.e-gov.elaws`](https://github.com/kotoba-lang/jp.go.e-gov.elaws)'s
`index/laws.edn` — 9,536 Japanese laws with status, title and content hashes.

This replaces an earlier check that fetched each URL and accepted HTTP 200.
Reachability is the weaker claim by some distance:

| | HTTP 200 | corpus index |
|---|---|---|
| the URL resolves | yes | — |
| the law exists under that id | inferred | stated |
| the title is what we said it is | no | **compared** |
| the law has been **repealed** | **invisible** | `:law.status/repealed` |

A repealed statute serves its page with a 200 like any other.

```bash
nbb tools/verify_citations.cljs ../jp.go.e-gov.elaws
# CORPUS  …/index/laws.edn   9536 laws
# SCANNED 8
#   ok  132AC0000000048  :law.status/in-force  商法
#   …
# 8 / 8 in force with matching titles
# 4 of those are :superseded-revision — the corpus snapshot predates a
#    later revision of a law still in force. Not a bad citation.
```

**`:law.status/superseded-revision` is not repeal.** It means the corpus
snapshot predates a later revision of a law that is still in force. Four of
the eight statutes are in that state; the checker separates them from
`:repealed` / `:lapsed` / `:expired`, which fail.

### Exit codes are three-valued

| | |
|---|---|
| `0` | every cited statute exists, is in force, and its title matches |
| `1` | a citation is bad — absent, no longer in force, or retitled |
| `2` | **the run could not answer** — corpus not on disk, or index parsed to nothing |

`2` exists because a checker whose corpus is missing has learned nothing
about the citations, and reporting "0 problems" would make that
indistinguishable from having checked. An empty scan prints `SCANNED 0` and
refuses.

Measured 2026-08-17, all four discriminated:

| injected | result |
|---|---|
| cite a repealed law (`113DF0000000036` 旧刑法) | `FAIL … no longer in force: :law.status/repealed` |
| cite a law id that does not exist | exit 1, `not in corpus` |
| change a title, leave the URL working | exit 1, `title differs` |
| point at a directory with no corpus | exit 2, refuses |

## 電子帳簿保存法 第七条 — read, then enforced

The catalog carried an electronic-transaction rule that nothing consumed and
that nobody had read: `:rule/review :reachable-not-read`. Before wiring it
into a governor, the article was retrieved and quoted.

```
第七条  所得税（源泉徴収に係る所得税を除く。）及び法人税に係る保存義務者は、
        電子取引を行った場合には、財務省令で定めるところにより、当該電子取引の
        取引情報に係る電磁的記録を保存しなければならない。
```

Retrieved 2026-08-17 via `GET /api/2/law_data/410AC0000000025`, so
`:rule/review` is `:read-from-source` and `:rule/quote` carries the text.

**The article's scope is recorded, not widened.** It binds 保存義務者 for
income tax (excluding withholding) and corporation tax — `:rule/applies-to
#{:income-tax :corporation-tax}`. This library does not decide whether a
given holder is one; a caller who does not know has not established that it
is exempt.

```clojure
(taxlaw/record-preservation [:jp] {:origin :electronic-transaction
                                   :preservation :paper})
;; => {:taxlaw/coverage :checked :taxlaw/preserved? false
;;     :taxlaw/reason :electronic-record-not-preserved
;;     :taxlaw/provision "電子帳簿保存法 第七条" ...}
```

Three-valued like `credit-support`, with one more state that matters:
`:not-declared` — the document does not say how the transaction happened, so
nothing was asserted and nothing was checked. Printing an electronic
transaction and keeping the paper is the case the article addresses: the
obligation is to preserve the 電磁的記録 itself.

## 所得税法 第百八十三条第一項 / 第百九十条 — read, then enforced

The withholding facet exists because a payroll actor needed it. Same standing
rule, so the articles were retrieved first, via
`GET /api/2/law_data/340AC0000000033` on 2026-08-17 (revision
`340AC0000000033_20260812_508AC0000000064`, newer than the pinned corpus
snapshot — recorded in `:retrieved-revision`).

```
第百八十三条  居住者に対し国内において第二十八条第一項（給与所得）に規定する
             給与等（以下この章において「給与等」という。）の支払をする者は、
             その支払の際、その給与等について所得税を徴収し、その徴収の日の
             属する月の翌月十日までに、これを国に納付しなければならない。
```

**The scope is recorded, not widened**, and here that does real work. The
article reaches a payer of 給与等 **居住者に対し** **国内において** — and
nothing else. So `withholding-obligation` has a fourth state:

```clojure
(taxlaw/withholding-obligation [:jp] {:payment-kind :employment-income
                                      :recipient-residency :resident
                                      :paid-in :domestic})
;; => {:taxlaw/coverage :checked :taxlaw/accounted-for? false
;;     :taxlaw/reason :withholding-not-recorded
;;     :taxlaw/provision "所得税法 第百八十三条第一項"
;;     :taxlaw/amount-checked? false ...}

(taxlaw/withholding-obligation [:jp] {:payment-kind :employment-income
                                      :recipient-residency :non-resident
                                      :income-tax-withheld 0})
;; => {:taxlaw/coverage :out-of-scope
;;     :taxlaw/read-provision "所得税法 第百八十三条第一項" ...}
;;    no :taxlaw/accounted-for? key — and this is NOT a finding that no
;;    withholding obligation exists. Other provisions govern payments to
;;    non-residents; none of them was read.
```

**Silence is not the article's exclusion.** A payment that declares
employment income but says nothing about residency or place stays `:checked`.
Only an explicit `:non-resident` / `:overseas` reaches `:out-of-scope`,
because absence of a declaration is the unchecked case and the unchecked case
never buys an exemption.

**Presence is checked; the amount never is.** The article says 徴収し —
collect *the* income tax on that 給与等. How much that is comes from
所得税法 別表第二 / 別表第五, which were **not** read, so every result carries
`:taxlaw/amount-checked? false`. A recorded amount must not read as a correct
one, and a recorded **zero** is accepted for the same reason: this library
did not read the tables that would say otherwise.

`year-end-adjustment` (第百九十条) has the same four states, and takes its
three exclusions verbatim off the text — declaration not filed, not the
year's final payment, annual 給与等 above **二千万円** (`:rule/income-ceiling-yen
20000000`, the number in the article). Its quote is the operative opening
sentence only; the 各号 set out how the year's tax is computed and are
omitted, which `catalog-verification` records as `:quote-is-partial?` with a
`:quote-omits` note.

### A quote assertion a paraphrase can satisfy is not evidence

Measured 2026-08-17. This test was green:

```clojure
(is (str/includes? (:rule/quote r) "その支払の際、その給与等について所得税を徴収し"))
```

…and it stayed green under a mutation that rewrote the duty as
「所得税を徴収**してもよく**」 — a *shall* turned into a *may* — because
徴収し is a prefix of 徴収してもよく. Both quote tests now pin the whole
operative clause through the 義務, and both mutations redden.

## What was verified, and what was not

`catalog-verification` records this as data, because the two are different
claims:

- **existence / status / title** — all 8 statutes, against the corpus.
- **content** — four claims. The qualified-invoice registration-number
  format, read off the 国税庁 publication site
  (「"T"を除く13桁の半角数字」); 電子帳簿保存法 第七条; and 所得税法
  第百八十三条第一項 / 第百九十条 — each retrieved from the e-Gov law API and
  quoted, the last one partially and saying so. Everything else cites the
  instrument **without** quoting article text and is marked
  `:rule/review :reachable-not-read`.

  The rule is: **a claim this library enforces must be read, not merely
  cited.** Every read claim backs a rule a governor acts on; the
  `:reachable-not-read` entries back nothing that holds anything.
  `every-enforced-rule-was-read-not-merely-cited` asserts the direction that
  matters — each enforced facet carries `:read-from-source`, a provision, a
  quote and a retrieval date — so the rule is a test rather than a paragraph.

Two candidates were **dropped rather than cited**, recorded in
`:catalog/rejected` with reasons: `asb.or.jp` (connection timed out) and
中小企業の会計に関する基本要領 (403 to a plain client; 200 only with a
spoofed browser User-Agent — a citation this repo's own gate could not
verify). An absent citation that leaves no trace looks identical to one
nobody thought of.

## Scope

Japan, and within Japan what a bookkeeping, invoicing or payroll actor
actually has to gate on: whether input-tax credit requires a qualified
invoice, how long records must be kept, whether an electronic transaction's
record must be preserved as such, and whether an employer paying employment
income must withhold income tax and settle the year's over/under at the final
payment.

**Not a tax engine.** It computes no tax, files nothing, and renders no
opinion — see `:taxlaw/amount-checked?`.

## Consumers

| repo | uses it for |
|---|---|
| [`cloud-itonami/cloud-itonami-isco-4311`](https://github.com/cloud-itonami/cloud-itonami-isco-4311) | the **receiving** side — a journal entry claiming 仕入税額控除 must cite a document carrying a valid registration number |
| [`cloud-itonami/tehai`](https://github.com/cloud-itonami/tehai) | the **issuing** side — an invoice drafted for a client in a qualified-invoice jurisdiction must carry the issuer's registration number, or its recipient cannot credit it |
| [`cloud-itonami/cloud-itonami-isco-4313`](https://github.com/cloud-itonami/cloud-itonami-isco-4313) | the **paying** side — a payroll run for an employer in a jurisdiction requiring withholding must account for the income tax withheld |

## Maturity

| | |
|---|---|
| Role | capability |
| Tests | 24 tests / 255 assertions, all green (`clojure -M:test`) |
| Dependencies | none |
| Citation check | `nbb tools/verify_citations.cljs`, three-valued, demonstrated in both directions |

Apache-2.0.
