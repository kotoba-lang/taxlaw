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

## What was verified, and what was not

`catalog-verification` records this as data, because the two are different
claims:

- **existence / status / title** — all 8 statutes, against the corpus.
- **content** — exactly one claim: the qualified-invoice registration-number
  format, read off the 国税庁 publication site, which states
  「"T"を除く13桁の半角数字」. Everything else cites the instrument **without**
  quoting article text and is marked `:rule/review :reachable-not-read`.

Two candidates were **dropped rather than cited**, recorded in
`:catalog/rejected` with reasons: `asb.or.jp` (connection timed out) and
中小企業の会計に関する基本要領 (403 to a plain client; 200 only with a
spoofed browser User-Agent — a citation this repo's own gate could not
verify). An absent citation that leaves no trace looks identical to one
nobody thought of.

## Scope

Japan, and within Japan the two things a bookkeeping or invoicing actor
actually has to gate on: whether input-tax credit requires a qualified
invoice, and how long records must be kept.

**Not a tax engine.** It computes no tax, files nothing, and renders no
opinion.

## Consumers

| repo | uses it for |
|---|---|
| [`cloud-itonami/cloud-itonami-isco-4311`](https://github.com/cloud-itonami/cloud-itonami-isco-4311) | the **receiving** side — a journal entry claiming 仕入税額控除 must cite a document carrying a valid registration number |
| [`cloud-itonami/tehai`](https://github.com/cloud-itonami/tehai) | the **issuing** side — an invoice drafted for a client in a qualified-invoice jurisdiction must carry the issuer's registration number, or its recipient cannot credit it |

## Maturity

| | |
|---|---|
| Role | capability |
| Tests | 9 tests / 122 assertions, all green (`clojure -M:test`) |
| Dependencies | none |
| Citation check | `nbb tools/verify_citations.cljs`, three-valued, demonstrated in both directions |

Apache-2.0.
