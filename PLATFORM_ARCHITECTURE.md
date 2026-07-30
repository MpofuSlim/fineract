# Multi-Core Banking Platform — Architecture Notes

**Status:** working design notes, not a decision record yet.
**Last updated:** 2026-07-30
**Purpose:** hand-off document. A fresh session should be able to read this file alone
and pick up the architecture work without re-deriving anything.

> This file lives in the Fineract fork because that is where the work started. It is a
> design document only. **The platform code itself should not live in this repo** — see
> [§7 Repo layout](#7-repo-layout).

---

## 1. Where things actually stand

| Thing | State |
| --- | --- |
| Consumer mobile app | One app, intended to serve multiple countries |
| Oradian middleware | **Already built and in hand** — this is the incumbent, working integration |
| Fineract | This repo (fork of upstream `develop`); being added as a second core |
| Veengu | Planned later, for another country |
| API access | Held for both Oradian and Veengu (docs/credentials, not just marketing pages) |

**The task for the next session:** consolidate the existing Oradian middleware and the
new Fineract integration into **one single middleware** that serves the mobile app,
rather than one middleware per core.

### Why this changes earlier advice

The initial recommendation was "don't build a provider abstraction until a second core
is contractually real." That condition is now met — and inverted in a useful way. There
is already **one working implementation** (Oradian) and a **second one arriving**
(Fineract). That is precisely the right moment to extract an abstraction, because it can
be derived from two real, understood implementations instead of guessed from vendor PDFs.

**But there is one dominant risk in this consolidation** — see [§6](#6-the-consolidation-plan).

---

## 2. Strategic questions that outrank every technical decision

These are unresolved and they change the architecture more than any code choice. Answer
them before building.

1. **Are you the licensed/regulated entity in each market, or is a partner FI?**
   This single fact determines who owns KYC/AML, who holds customer float, who the
   regulator talks to, and where the golden customer record must live. It reshapes the
   whole design. Highest-value unknown.

2. **Why add Fineract at all, when Oradian already works?**
   Oradian is live, SaaS, regulator-aligned (CBN Nigeria, BSP Philippines, OJK
   Indonesia), and already integrated. Adding a second core costs 2x integration,
   reconciliation, vendor management, on-call knowledge, and per-market feature drift —
   permanently. Valid reasons to accept that cost: a market Oradian cannot serve, a
   regulator or partner FI mandating a specific core, an acquisition, or a capability
   gap. "Different country" on its own is **not** a reason.
   *If the honest answer is licence cost: Fineract is free to licence and expensive to
   operate. Self-hosting a Java monolith + per-tenant databases + batch jobs + event
   pipeline + reconciliation + security patching in a regulated domain is a real
   subscription, paid in team time and risk.*

3. **Is Fineract the right core to front with a consumer app?**
   Upstream deleted the consumer self-service API as insecure and states in its own
   hardening guide that apps should not be built against it (see §3). Fineract is a
   back-office MFI core; the project does not want to serve consumer traffic. You *can*
   front it with a BFF, but you then own the entire consumer-facing security surface
   with a privileged service account. Of the three vendors it is the **hardest** to put
   a consumer app in front of.

4. **What are the payment rails per market?**
   Mobile money aggregator, card processor, interbank switch. This is the hardest
   integration in the system and it is *not* the core banking system. Likely to dwarf
   the Fineract adapter in effort. Needs to be a first-class workstream with its own
   adapter and reconciliation.

5. **Does an app-facing contract already exist in the wild?**
   If the Oradian middleware already serves a shipped app, its contract is already
   deployed on phones and constrains the canonical contract. Determines whether the
   canonical API is a reshape of v1 or a new v2 alongside it. Check before designing.

---

## 3. Verified facts — this Fineract repo

All verified by reading the tree at HEAD `6cb7eb14` (branch
`claude/multi-backend-architecture-0whe5a`, tracking fork of upstream `develop`,
2026-05-27, Spring Boot 3.5.14). File references included so these need not be
re-derived.

### 3.1 The consumer self-service API is gone

- Commit **`5364ddbe`** — *"FINERACT-2480: Remove insecure self-service feature"*
  (authored 2026-02-15, merged 2026-03-13) deleted **196 files / 11,344 lines**, the
  entire `org.apache.fineract.portfolio.self.*` package. No `/v1/self/*` endpoint exists
  at HEAD.
- DB migration dropping the supporting tables:
  `fineract-provider/src/main/resources/db/changelog/tenant/parts/0219_remove_self_service_feature.xml`
  (drops `m_selfservice_user_client_mapping`, `m_selfservice_beneficiaries_tpt`,
  `m_pocket`, `client_device_registration`, and column `m_appuser.is_self_service_user`).
- Project stance, `fineract-doc/src/docs/en/chapters/security/harden.adoc:51-55`:
  *"It is recommended that you leave the Self Service APIs disabled… Apps should not be
  developed to use those APIs."*
- Pre-removal code is still readable at git rev `5364ddbe^` if a capability reference is
  ever needed. Even then it was read-mostly: no savings deposit/withdrawal, no loan
  repayment, internal same-instance transfers only, one device per client, no 2FA for
  self-service users.

**Consequence:** the mobile app can never talk to Fineract directly. A middleware is
forced, not chosen.

### 3.2 API shape and quirks (all must be hidden behind the adapter)

| Aspect | Detail |
| --- | --- |
| Base path | `{context-path}/api/v1/…` — `server.servlet.context-path=/fineract-provider` (`application.properties:382`), `@ApplicationPath("/api")` in `JerseyConfig.java:37`, resources at `@Path("/v1/…")` |
| Date/number bodies | JSON bodies with dates **require** `dateFormat` + `locale` fields — `JsonParserHelper.java:226-270` |
| State transitions | `POST /resource/{id}?command=activate` style verbs |
| Error format | `ApiGlobalErrorResponse` — `developerMessage`, `httpStatusCode` (string), `defaultUserMessage`, `userMessageGlobalisationCode` (i18n code, worth mapping through rather than discarding), `errors[]` |
| Write pipeline | All writes go through `CommandWrapper` → `PortfolioCommandSourceWritePlatformServiceImpl.logCommandSource` → `SynchronousCommandProcessingService.executeCommand`. Responses are command-shaped, not resource-shaped. |
| Second command framework | Modules `fineract-command`, `-async`, `-jdbc`, `-disruptor` are a parallel framework mid-migration on `develop` (`fineract.command.jdbc.enabled=true` by default). Write-path behaviour can shift under you — another reason to pin a release. |

### 3.3 The maker-checker HTTP 200 trap — **highest-severity integration landmine**

When maker-checker is enabled for a permission, Fineract **returns HTTP 200 OK** with a
body carrying `rollbackTransaction: true` and a `commandId`. No resource was created;
the business effect was rolled back and the command is parked awaiting a checker.

- `RollbackTransactionNotApprovedExceptionMapper.java:50` returns `Response.ok()`
- `CommandSourceService.java:140-149` throws `RollbackTransactionNotApprovedException`
- Approve/reject via `MakercheckersApiResource` `@Path("/v1/makercheckers")`
- Toggled **per-permission, per-tenant at runtime** by the FI's ops team
  (`configurationDomainService.isMakerCheckerEnabledForTask`) — outside engineering control

**An adapter that treats 200 as success will tell a customer a transfer completed when it
has not.** The customer then retries, and it may later execute twice. This is the reason
the normalized result contract must be tri-state (§5).

### 3.4 Auth and multi-tenancy

- **HTTP Basic, on by default** — `fineract.security.basicauth.enabled` (`application.properties:24`).
  `POST /v1/authentication` returns `base64EncodedAuthenticationKey` (base64 of
  `username:password`) for reuse. A static credential.
- **OAuth2, off by default** — `fineract.security.oauth2.enabled` (`:25`).
  `AuthorizationServerConfig.java` embeds Spring Authorization Server (JWT resource
  server, `tenantId` auth detail). **Prefer this over Basic.** There is an `oauth2-tests`
  module at repo root.
- **2FA, off by default** — `fineract.security.2fa.enabled` (`:26`), SMS/email OTP,
  `TwoFactorApiResource` `@Path("/v1/twofactor")`. Targets staff users.
- **Tenancy** — header `Fineract-Platform-TenantId`
  (`TenantAwareBasicAuthenticationFilter.java:70`), query fallback `tenantIdentifier`
  (`:116`). Tenants are **separate databases**, connection details per tenant in
  `FineractPlatformTenantConnection`, routed via a routing datasource. Largely
  irrelevant complexity if you are a single tenant of your own instance.

### 3.5 Things Fineract already gives you — do not rebuild

- **Idempotency is first-class.** Header `Idempotency-Key`
  (`fineract.idempotency-key-header-name`, `application.properties:163`),
  `IdempotencyStoreFilter.java:72`. The key is persisted on `CommandSource` with the
  result and status code, and a retry with the same key **replays the stored response**.
  → Generate the key at the app/middleware boundary, persist it before calling, and pass
  the *same* key down. Do not build a parallel mechanism.
- **Batch API** — `POST /v1/batches` (`BatchApiResource.java:63`) with
  `?enclosingTransaction=true` runs all sub-requests in one DB transaction with full
  rollback. Use instead of hand-rolled sagas for multi-step writes.
- **Outbound events, two options, both off by default:**
  - *Hooks module* — `@Path("/v1/hooks")`, templates Web / Elastic Search / Message
    Gateway / SMS Bridge. Fires per command via `SynchronousCommandProcessingService.publishHookEvent`.
    Plain HTTP POST, **fire-and-forget with no retry**. Cheap; fine for v1.
  - *External events outbox* — transactional outbox in table `m_external_event`,
    published by `SendAsynchronousEventsTasklet` to **Kafka** or ActiveMQ/JMS. Avro
    payloads (`fineract-avro-schemas`, `MessageV1.avsc`). Config:
    `fineract.events.external.enabled=false` (`:124`),
    `…producer.kafka.enabled=false` (`:140`). Reliable, but costs a broker + Avro schema
    handling. Defer until volume or reliability demands it.
- **Client SDKs as references** — `fineract-client` (generated from the OpenAPI spec) and
  `fineract-client-feign` (`TenantIdRequestInterceptor` sets the tenant header).
- **Docker compose files** ship in the repo (`docker-compose-postgresql.yml`, `…-kafka.yml`,
  etc.) — use for adapter integration tests in CI.

### 3.6 Custom module mechanism (the one place your code may live in this repo)

`settings.gradle:88-95` auto-discovers modules matching
`custom/<company>/<category>/<module>`; `custom/acme/{loan,event,note}` is the worked
example, and `custom:docker` builds an image with them included.

Use **only** for things that must run in-process inside Fineract (a custom COB job, a
business-event listener, a custom loan processor). Every custom module is code you
maintain across upstream upgrades. Integration logic belongs in the middleware, where
upstream cannot break it.

### 3.7 Interoperation module (still present)

`org.apache.fineract.interoperation`, `@Path("/v1/interoperation")` — a Mojaloop-pattern
surface: parties / quotes / transfers with prepare-hold → commit → release semantics,
`InteropIdentifierType` = MSISDN, EMAIL, PERSONAL_ID, BUSINESS, DEVICE, ACCOUNT_ID, IBAN,
ALIAS, BBAN. **Operates on savings accounts only.** Doc samples reference the Mifos
Payment Hub channel API; global config `enable-payment-hub-integration`. Presumes a
Payment Hub deployment — cost that explicitly before relying on it for rails.

---

## 4. Vendor landscape

Clearly separating verified fact from vendor marketing. Note that both vendor sites
blocked automated fetching, so vendor-site claims below came via search extracts — **you
now have real API access, so verify all of it against the actual specs.**

### Oradian (product historically *Instafin*)

- **Verified:** Zagreb-based, founded 2012. Cloud-only SaaS on AWS; no on-prem option
  found. Multi-tenant, single codebase — *"all customers receive all updates at the same
  time"*, i.e. **no version pinning; the vendor can change under you on their schedule.**
  Full-service in-house implementation model. ~55 institutions across ~13 countries,
  anchored Nigeria + Philippines, expanding Kenya + Indonesia. SOC 2 / ISO 27001 per
  third-party profile. Regulator alignment cited: CBN, BSP, OJK.
- **Verified:** consumer apps on top are built by partners or in-house against its APIs
  (Agribank PH app by Geniusto; FutureBank/Global Kinetic front-end; Boost chat
  onboarding at RAFI). Oradian's own app — *Instafin Field Officer* — is **staff-facing**,
  not a customer app.
- **Marketing claims to verify against your specs:** "750+ native API endpoints", every
  module exposed over REST, push + pull APIs, "Oradian Notifier" webhook service, custom
  endpoints via custom JAR plugins, an MCP surface.
- **Architectural implication:** no version pinning means forced upgrades. Get
  advance-notice and sandbox-preview clauses in the contract.

### Veengu

- **Verified:** founded 2020, HQ Dubai, engineering Belgrade, **11–50 employees** —
  viability and key-man risk worth naming. Closed-source "core banking and payment
  orchestration" platform, configurable to four shapes: wallets, mobile money, neobanks,
  remittance. SaaS **or on-premise licence**. Sold by scope, not per-seat.
- **Verified, and strategically important:** Veengu states it **"can sit alongside an
  in-house core (loans and deposits engine like Mambu) to deliver the digital
  transactional layer."** It is a wallet/e-money/payments platform, **not a lending
  engine** — lending support is thin-to-absent. Its first-class entities are profiles,
  KYC tiers, wallets, merchant/nostro/vostro accounts, immutable double-entry postings,
  P2P/QR/agent cash-in-out, payment orchestration. Fineract's are clients, loan and
  savings products, interest accrual, and a full GL.
- **Verified:** ships **white-label mobile apps with frontend source code shared with
  clients** — `Veengu Wallet` (`com.veengu.wallet`) and `Veengu Mobile POS`
  (`com.veengu.mpos`) demo apps on Google Play, described as implementing *"default
  scenarios as an example of Veengu API usage."*
- **Verified:** 10+ deployments (Middle East, Africa, Caribbean), largest 5M+ accounts.
  Flagship case: Zimbabwe wallet-to-bank transformation, 1.9M profiles migrated in one
  overnight switchover, 3M+ end users. No client names published.
- **Two implications:**
  1. If a Veengu country needs **lending**, Veengu alone may not deliver it → that
     country could need **two** cores. Materially different architecture and cost. Decide
     the country's feature set against Veengu's real API before committing.
  2. Building your own adapter + app screens for a Veengu market may mean re-implementing
     what the vendor hands you for free. Make "our app via an adapter" vs "vendor
     white-label app" an explicit per-country decision, not an assumption.

---

## 5. The recommended design

### Terminology: "BFF", "middleware" and "platform" all mean the same thing here

**BFF = Backend For Frontend.** It is an ordinary backend web service — REST/JSON, a
database, deployed as a container — whose job is to be *the only thing your mobile app
talks to*. The name just means its API is designed around **your app's screens**, not
around any vendor's data model.

The difference in practice:

```
Without a BFF (not possible here anyway — self-service API deleted, §3.1):
  app ──► GET /fineract-provider/api/v1/clients/123/accounts
          Basic auth + Fineract-Platform-TenantId header
          vendor-shaped JSON, dateFormat/locale quirks, 200-means-maybe (§3.3)

With a BFF:
  app ──► GET /v1/home           (your contract, your bearer token)
          ◄── { customer, accounts[], recentTransactions[] }
                    │
          BFF makes however many core calls that needs, maps errors,
          checks entitlements, records the journal, and returns one clean payload
```

One screen should ideally cost the app **one call**. The BFF does the fan-out, stitching,
and normalizing server-side — which matters a lot on low-bandwidth mobile networks.

In this project the BFF also carries the responsibilities in §5.2, so it is far more than
response shaping: it is the security boundary, the system of record for app-specific data,
the idempotency and journal owner, and the host for the per-core adapters.

**A BFF is not an API gateway.** A gateway (Kong, nginx, AWS API Gateway) does routing,
TLS, rate limiting and token validation, and holds no business logic. You may put one in
front of the BFF; it does not replace it.

**You already have one.** The existing Oradian middleware *is* a BFF, or close to it.
The consolidation work (§6) is evolving it into a multi-core one — not starting over.

---

**One middleware. One app-facing contract. One core per deployment. Product logic written
once, core-agnostic.**

```
              Mobile app (one codebase)
                       │
                       │  ONE versioned app-facing contract
                       │  (designed from app screens — never from a vendor's API)
                       ▼
        ┌──────────────────────────────────────────┐
        │  Platform / BFF  (the single middleware) │
        │                                          │
        │  identity refs · entitlements · audit    │
        │  money-movement journal · idempotency    │
        │  limits · KYC orchestration · notif.     │
        │  reconciliation · normalized errors      │
        └──────────────────────────────────────────┘
                       │  provider port (extracted, not guessed)
        ┌──────────────┼───────────────┬───────────────┐
        ▼              ▼               ▼               ▼
   Oradian        Fineract        Veengu?        Payment rails
   adapter        adapter         adapter        adapter(s)
   (exists)       (to build)      (later)        (PSP / mobile money)
```

### 5.1 The one rule that carries all the value

**Product logic is written once and is core-agnostic; only the thin talk-to-vendor layer
varies.** Entitlements, journal, idempotency, limits, audit, KYC orchestration,
notifications — none of these should exist twice. Duplicated product logic per core is
the genuinely catastrophic outcome, and it is what "a middleware per core" always decays
into.

Corollary: **no vendor shape ever crosses into the app-facing contract.** Not
`dateFormat`/`locale`, not `ApiGlobalErrorResponse`, not `?command=` verbs, not the
tenant header, not Oradian's or Veengu's equivalents. Enforce with a DTO boundary plus
build-level dependency rules (Gradle module deps + ArchUnit on the JVM), so CI fails
rather than a reviewer having to notice.

### 5.2 Non-negotiables — build these carefully, they cannot be retrofitted

| Concern | Why it cannot wait |
| --- | --- |
| **Versioned app-facing contract** | App binaries live 2–4 years in low-connectivity markets (sideloaded APKs, delayed rollouts). |
| **Min-supported-version kill switch + server-driven feature flags, in app v1.0** | Cannot be added to binaries already installed on phones. Also how you gate features per market instead of forcing parity. |
| **Tri-state result: `SUCCEEDED` / `PENDING`(+reference) / `FAILED`(+retryability)** | Fineract's maker-checker returns 200 for a parked write (§3.3). Boolean success is a double-spend bug. |
| **End-to-end idempotency** | Persist the key *before* calling the core; deterministic key mapping middleware→core; **"unknown outcome" is a first-class state** that blocks user-visible retry until resolved by status query or reconciliation. |
| **Immutable money-movement journal** | intent → provider request → provider response → terminal state. The only safety net for ambiguous outcomes, and the basis of reconciliation. |
| **Deny-by-default entitlement middleware** | Post-FINERACT-2480 the middleware calls Fineract as a privileged service account for *all* customers. One missed ownership check is a cross-customer IDOR across the whole book. Fuzz-test it. |
| **Customer-attributed audit log** | The core's audit trail records your service account, not the human. Disputes, fraud investigation and regulator requests cannot be served from core data alone. |
| **Reconciliation as a subsystem** | Daily: journal vs core ledger, core ledger vs external rails. Regulators require demonstrable float/ledger reconciliation for e-money. Make *"fetch authoritative transaction feed for period"* a required operation in the provider port so every adapter must support it. |

### 5.3 Additional design rules

- **Buy, don't build:** managed IdP (own the identity *domain*, not the IdP), KYC/screening
  vendor, PSP. Build only the product.
- **The middleware is not the only writer.** Tellers, field-officer apps and batch
  interest/fee postings write directly to the core. Cache invalidation cannot be
  app-driven → read through to the core for balance-critical screens, cache history only.
- **AML/limits must see the full transaction stream**, which the middleware does not have.
  Feed monitoring from the **core's** event/transaction feed, and enforce or reconcile
  limits at the core, not only in the middleware.
- **Event ingestion:** at-least-once with dedupe keys, out-of-order tolerance, signature
  verification, DLQ, and a **polling/reconciliation fallback that detects gaps** — a silent
  webhook outage otherwise means customers see stale balances while making payment
  decisions. Start with Fineract's hooks module; defer Kafka.
- **Deployment:** one codebase, per-region deployments bound to one core by config. **No
  dynamic routing layer** until a single deployment must genuinely serve two cores at once.
- **Data residency:** classify every datastore (IdP, ID-mapping, notifications, logs,
  backups, analytics) by residency requirement. A single global IdP or global mapping
  table silently recreates the cross-border transfer that regional deployment was meant to
  avoid. Nigeria (NDPA/CBN), Indonesia (OJK/PP71), Zimbabwe all impose in-country
  requirements; support access from another country is itself a transfer. Also confirm each
  SaaS vendor's hosting region and regulator cloud-approval status.
- **Cards:** adopt a tokenize-at-the-edge rule *now* — PAN goes app SDK → PCI-certified
  tokenizer, middleware stores tokens only — and write it into the port so no adapter can
  ever require raw PAN. Veengu markets card orchestration, so this will come up.
- **SCA / payment authorization:** device binding at enrolment, step-up auth per risk tier
  and per country (CBN and BSP mandate multi-factor for electronic payments). Shapes the
  app contract, so design before the first money-movement endpoint.
- **Exit rights at signing:** full export of customers, KYC documents, complete ledger and
  balances in a documented format; source/data escrow for Veengu; advance-notice and
  sandbox-preview clauses for Oradian's forced upgrades. Keep enough canonical data in the
  middleware's own store to stand up a replacement core.
- **Pin Fineract to a tagged release.** This fork currently tracks `develop` and builds as
  `0.0.0-SNAPSHOT` (no tags in the clone; `build.gradle:145` + git-versioning plugin). This
  is the branch that deleted 11k lines of API surface mid-stream and has a second command
  framework mid-migration. Tracking `develop` in production is continuous-breakage exposure.

---

## 6. The consolidation plan

Turning "an Oradian middleware" into "the platform, which happens to speak Oradian and
Fineract."

### 6.1 The dominant risk

**Do not let the existing Oradian middleware's internal model silently become the
abstraction.** Most first integrations end up shaped like the vendor they integrate — if
the middleware's internal types *are* Oradian's types, then mechanically extracting an
interface from it produces an Oradian-shaped port, and the Fineract adapter will fight it
forever (and Veengu, a wallet platform, will fight it harder).

The canonical model must be derived from **the app's use cases**, then checked against
both cores' realities — not lifted from whichever core was integrated first.

### 6.2 Second risk: do not rewrite

The Oradian middleware is working code with tests and a deployment, possibly serving a
live market. **Evolve it in place** — rename and reshape modules inside it, strangler-style,
keeping it serving throughout. A big-bang rewrite of a working, regulated integration is
how this project dies. Reshaping modules inside one repo is dramatically cheaper than
migrating to a fresh one.

### 6.3 Sequence for the next session

1. **Inventory the existing Oradian middleware.** What is its app-facing API? Its internal
   domain model? Precisely where does Oradian leak into it? Which product logic already
   exists (auth, entitlements, journal, idempotency, limits, notifications) and which of
   that is genuinely core-agnostic versus Oradian-coupled? Produce a written map — this is
   the input to everything else.
2. **Establish whether the app-facing contract is already in the wild** (§2.5). Determines
   reshape-v1 versus introduce-v2-alongside.
3. **Build the use-case → endpoint matrix.** Top ~20 app use cases (login, balance,
   history, P2P transfer, bill pay, loan application, repayment, onboarding/KYC, …) mapped
   against the *real* Oradian and Fineract APIs you now have access to. Three outputs:
   - where both agree → the canonical contract core
   - where only one supports it → a per-market feature flag, **not** an abstraction leak
   - where a use case needs several vendor calls or a rail → orchestration owned by the
     middleware
4. **Define the canonical domain model and the app-facing contract v-next** from that
   matrix. Explicitly resolve the semantic mismatches: balance meanings (available /
   ledger / held), e-money float versus deposit, wallet versus savings account, pending
   states, limit and error taxonomies.
5. **Extract the provider port**, using the Fineract adapter as the forcing function.
   Required operations must include the authoritative transaction feed (§5.2). Expect the
   port to change while the Fineract adapter is built — **that is the point**, and it is
   cheap inside one repo.
6. **Refactor the Oradian code into an adapter behind that port**, incrementally, keeping
   it green.
7. **Then** build the Fineract adapter, with contract tests against a dockerized Fineract
   in CI (compose files ship in this repo) asserting the §3.2/§3.3 quirks explicitly:
   `?command=` transitions, `dateFormat`/`locale` handling, `Idempotency-Key` replay, and
   the maker-checker 200-with-`rollbackTransaction` case.

### 6.4 Per-vendor questions to answer from the real specs

You have API access — answer these from the actual documents, for each core. They shape
the port and cannot be inferred from marketing.

1. Auth model — OAuth2 vs API keys, token lifetimes, per-environment credentials, IP allowlisting
2. **Idempotency on money movement** — idempotency key or client-supplied reference on
   transfer/payment creation, plus a query-by-reference endpoint. If a vendor has neither,
   "unknown outcome after timeout" becomes yours to solve by status polling. Make
   *"supports idempotent create OR queryable client reference"* a written contractual
   requirement.
3. Async/pending semantics — can writes return in-progress, and how is completion learned
   (webhook or poll)? Confirms whether tri-state applies to them too.
4. Webhooks/events — delivery guarantees, retries, signing, replay, ordering. Assume a
   polling fallback is needed regardless of what is promised.
5. **Authoritative transaction feed** — can you pull the full ledger for an account or
   institution over a time window? This is the reconciliation primitive.
6. Balance semantics — available vs ledger vs held; e-money float vs deposit.
7. Machine-readable error codes, for mapping into the normalized contract.
8. Rate limits, pagination, sandbox-vs-production parity.
9. Data export / exit capability — proven, not promised.

---

## 7. Repo layout

**Recommendation: one repo for the platform** — evolved from the existing Oradian
middleware repo (§6.2), modularized internally:

```
/contract           app-facing DTOs + OpenAPI spec        ← the crown jewel
/core               product logic: auth, entitlements, journal,
                    idempotency, limits, orchestration, reconciliation
/port               provider interface + normalized domain types
/adapter-oradian    (evolved from existing code)
/adapter-fineract
/adapter-veengu     (later, if at all)
/deploy-*           thin deployables: core + one adapter + config
```

Enforce with build-level dependency rules: `core` may only see `/port`, never a concrete
adapter; adapters may never import each other. **This is stronger isolation than separate
repos** — a build rule fails CI, whereas separate repos can still leak vendor types to
each other through a shared library with nothing to catch it.

### Why not a repo per core

- There is no "Fineract middleware" and "Oradian middleware" — there is **one product
  backend** that talks to a core. Naming a repo `fineract-middleware` guarantees a
  Fineract-shaped thing that gets rebuilt for the next core, with product logic grown
  inside it. That duplication is the failure mode; repo count is a rounding error.
- Contract types would need a published, versioned shared library — every change becomes
  edit-lib → publish → bump in A → bump in B. The alternative, copy-paste, is where drift
  starts: country A's `Account` gains a field, country B's does not, and you can no longer
  ship one app build.
- **Independent deploys do not require separate repos.** Per-region deployments of one
  codebase already give independent release timing, separate databases and separate blast
  radius.
- Direction of travel: extracting a bounded module out of a monorepo later is easy and
  keeps history (`git subtree split` / `filter-repo`). Merging repos that drifted for a
  year is painful. Monorepo-first is the reversible choice.

**Separate repos genuinely earn their keep only for:** separate teams with separate
ownership and release cadence; a contractual/NDA access boundary (check your vendor API
terms); or a genuinely different tech stack. Not "different countries" and not "different
vendors."

### The other repos

- **`fineract` (this repo)** — stays separate. It tracks upstream and will be rebased
  forever. Pin a tagged release; keep patches at or near zero. **Do not put platform code
  here** — proprietary code in an Apache-licensed fork you must keep rebasing is a
  recurring merge tax plus a licensing conversation. Exception: genuine in-process
  extensions via `custom/<company>/…` (§3.6).
- **Mobile app** — either its own repo or inside the platform repo. For a small team the
  app-store-cadence argument for splitting is weak, and co-locating app + `/contract` means
  the generated client cannot drift.

---

## 8. Record of changed positions

Kept deliberately, so the next session does not re-litigate settled ground or inherit
stale advice.

| Earlier position | Current position | Why |
| --- | --- | --- |
| "Don't build a provider abstraction until a second core is real" | **Extract it now** | Condition met: Oradian exists and works, Fineract is arriving. Abstraction can be derived from two real implementations rather than guessed. |
| Presented a six-module layout with all three adapters up front | Modules appear as adapters become real; Veengu stays hypothetical | Was inconsistent with the advice given one turn earlier. |
| Treated "I have the vendor APIs" as significant de-risking | De-risks **designing**, not **building** | Docs are not a signed contract, test tenant, licence, or market. Should not pull adapter work forward. |
| Three repos asserted somewhat reflexively | One platform repo; mobile app either way | The repo boundary that matters is upstream-tracking (Fineract fork) vs product. |
| Multi-core treated as a given | Multi-core is a **cost you accept**, never a goal — and "why Fineract when Oradian works?" is still open (§2.2) | Softened this originally; it is the highest-leverage question in the project. |

Positions that have **not** changed: the security posture (the middleware is the bank's
authorization system in front of a privileged core account), end-to-end idempotency, the
maker-checker tri-state requirement, reconciliation as a first-class subsystem, and
pinning a tagged Fineract release.

---

## 9. First actions for the next session

1. Answer §2.1 (licensed entity?) and §2.2 (why Fineract when Oradian works?) — these can
   invalidate work downstream.
2. Inventory the existing Oradian middleware and write the leak map (§6.3.1).
3. Determine whether an app-facing contract is already deployed on phones (§2.5).
4. Build the use-case → endpoint matrix against the real Oradian and Fineract specs (§6.3.3).
5. Only then: canonical model → port extraction → Fineract adapter.
