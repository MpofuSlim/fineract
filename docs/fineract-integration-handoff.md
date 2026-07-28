# Fineract integration — session handoff

**Purpose.** A self-contained brief for starting a new Claude Code (or human) session on
this work with no prior context. Everything below was verified against the actual code on
2026-07-28; where something is inferred rather than proven, it says so.

Companion documents:

- [`docs/innbucks-fineract-integration-strategy.md`](./innbucks-fineract-integration-strategy.md) — the full decision document
- [`CLAUDE.md`](../CLAUDE.md) — repo conventions (branching, custom modules, CI)

---

## 1. TL;DR

Build an **InnBucks-owned middleware service** that owns every conversation with Fineract.
Keep Fineract private, back-office-only, never reachable from the internet, and never
authenticating a customer. The customer-facing mobile app talks to the existing
`api-gateway`, which routes to the middleware, which talks to Fineract.

```
Mobile app  →  api-gateway  →  fineract-middleware  →  Fineract (private, no ingress)
               (existing)      (new — to build)           ↓ events (custom module)
                               fineract-middleware  →  existing notify / WhatsApp gateway
```

Five rules:

1. **Never expose Fineract to the app or the internet.** Its customer-facing surface was
   deleted upstream; every remaining API is a staff API.
2. **Never edit Fineract core.** All Fineract-side code lives in `custom/innbucks/**`.
3. **Customer identity stays in InnBucks `user-service`.** Fineract never sees a customer
   credential; the middleware uses one service account.
4. **Notification delivery stays in InnBucks.** Fineract emits facts; the middleware decides
   who is told and how.
5. **The middleware owns customer isolation.** Fineract has no per-customer scoping — see
   §6, this is the highest-severity control in the design.

---

## 2. The two repositories

| | |
|---|---|
| `MpofuSlim/fineract` | Fork of Apache Fineract. Java 21, Spring Boot 3.5.14, PostgreSQL supported. Sits on upstream `develop` commit `6cb7eb14` (2026-05-27). Default branch is **`develop`**, not `master`. |
| `MpofuSlim/ticketing-system` | "InnBucks" — live Spring Boot fleet: `api-gateway`, `discovery-server` (Eureka), `user-service`, `booking-service`, `event-service`, `seat-service`, `payment-service`, loyalty. Single-node **k3s** on one EC2 box, namespace `ticketing`. Postgres + Redis. **No message broker** — Kafka was deliberately removed. |

**Capacity:** the fleet is 13 pods requesting 4.1 GB, capped at 6.1 GB, on a 16 GB / 4 vCPU
box. Fineract wants ~1 GB heap / ~2 GB limit, so co-location is comfortable. Capacity is
not a constraint on any decision here.

**Branching:** `feature/<short-kebab-description>`. Never commit on the session's
auto-assigned `claude/<random-words>` branch — that is a harness default, not the
convention. This applies to **both** repos.

---

## 3. The findings that drove the design

These took research to establish. A new session should not need to rediscover them.

### 3.1 Fineract's customer-facing surface has been REMOVED

Not disabled — deleted. This is the single most consequential fact.

`fineract-provider/src/main/resources/db/changelog/tenant/parts/0219_remove_self_service_feature.xml`
drops `m_selfservice_user_client_mapping`, `m_selfservice_beneficiaries_tpt`,
`m_pocket_accounts_mapping`, `m_pocket`, `client_device_registration`, and the column
`m_appuser.is_self_service_user`.

Corroborated: **zero** Java references to self-service anywhere in the tree; `AppUser` has
no `Client` association. Fineract's own `fineract-doc/.../security/harden.adoc` states the
self-service APIs should not be used and apps should not be developed against them.

**Consequence:** a fronting middleware is the only supported shape, not a stylistic choice.

### 3.2 Auth beans are deliberately closed

70 files across the codebase use `@ConditionalOnMissingBean`, and nearly every domain ships
a `*Configuration` starter with individually override-guarded `@Bean` methods — the override
seam is far wider than the official docs claim (they still say only `Note` services are
overridable, which is badly out of date).

**But there is not a single `@ConditionalOnMissingBean` in any security, auth or
user-administration package.** `TenantAwareJpaPlatformUserDetailsService` is a concrete
`@Service` injected by concrete type; `SpringSecurityPlatformSecurityContext` hard-casts the
principal to `AppUser`. Plugging in an external identity provider would require forking core.

Also: `FineractJwtAuthenticationTokenConverter` calls
`userDetailsService.loadUserByUsername(jwt.getSubject())`, so an external-IdP `sub` claim
must match an **existing** `m_appuser.username`. There is **no JIT provisioning**.

### 3.3 Fineract's SMS/email machinery is unusable as a generic gateway client

Rated 3/5 viability by adversarial verification. Four independent blockers:

- It speaks a **proprietary Message-Gateway protocol**, not generic HTTP: a JSON array of
  `{internalId, tenantId, createdOnDate, sourceAddress, mobileNumber, message, providerId}`,
  headers `Fineract-Platform-TenantId` + `Fineract-Tenant-App-Key`, a mandatory `202
  ACCEPTED`, a `POST /sms/report` delivery-report poll, and a `GET /smsbridges` provider
  list. The InnBucks notify API matches none of it.
- `SmsConfigUtils` **hard-codes `.scheme("http")`**, and `MessageGatewayConfigurationData.sslEnabled`
  is itself dead — hard-coded `false` with no config key. **It cannot reach HTTPS.**
- It is **four call sites**, not one. `SendMessageToSmsGatewayTasklet`,
  `GetDeliveryReportsFromSmsGatewayTasklet` and `SmsCampaignDropdownReadPlatformServiceImpl`
  each hold their own `new RestTemplate()` and bypass the service interface.
- Email has **no HTTP seam at all** and lives in two unrelated beans.

Trap: a campaign-less `SmsMessage` left `PENDING` **NPEs the nightly drain job**.

### 3.4 Stock webhooks are fine for a spike, not for production

The `Web` hook template genuinely POSTs JSON to a configured URL with zero code, and can
trigger on any non-read non-checker permission. But dispatch is OkHttp `.enqueue()` —
**fire-and-forget, no retry, no persistence, no dead-letter** — and it cannot carry a bearer
token obtained from a login call. Disqualifying for financial events.

### 3.5 The durable event outbox IS reachable with zero core edits

This was the key positive finding. The seam:

```java
public interface ExternalEventProducer {
    void sendEvents(Map<Long, List<byte[]>> partitions) throws AcknowledgementTimeoutException;
}
```

A custom HTTP producer inherits the transactional outbox `m_external_event`
(`idempotency_key`, `aggregate_root_id`, `status`), per-aggregate ordering, at-least-once
delivery, the retry loop, the purge job, and per-event-type toggles via
`PUT /v1/externalevents/configuration` — **with no broker**.

Three things that are easy to get wrong:

1. `SendAsynchronousEventsTasklet.isDownstreamChannelEnabled()` returns true only when JMS or
   Kafka is enabled, so a custom producer is otherwise **dead code**. The method is
   `protected` on a **non-final `@Component`**, and Fineract sets
   `spring.main.allow-bean-definition-overriding=true`, so a `@Primary` subclass from a
   custom module defeats it with **zero core edits**.
2. **`@Primary` is mandatory.** `NoopExternalEventProducer` has no `@ConditionalOnMissingBean`
   and its condition is exactly "neither JMS nor Kafka" — i.e. your configuration. Without
   `@Primary` you get `NoUniqueBeanDefinitionException` at startup.
3. **The receiver must be idempotent.** `sendEventsToProducer()` runs *before*
   `markEventsAsSent()`, and `execute()` swallows exceptions while still reporting success —
   so a partial failure redelivers the **entire batch** and **no alert fires**. Key on the
   Avro `MessageV1` idempotency key and add your own outbox-depth metric and alert.

Not yet empirically proven: the `@Primary` subclass approach is derived from Spring's
documented resolution order, not from a booted build. **Spike it first.**

### 3.6 InnBucks already has a core-banking SPI

`user-service/src/main/java/com/innbucks/userservice/corebanking/CoreBankingPort.java`:

```java
public interface CoreBankingPort {
    String provider();                                    // "ORADIAN", "VEENGU"
    CoreBankingCustomerResult createCustomer(CoreBankingCreateCustomerCommand c, String idempotencyKey);
    List<DepositAccount> listDeposits(String msisdn);
}
```

Selected at deploy time by `innbucks.core-banking.provider` (env `INNBUCKS_CORE_BANKING`),
one provider per country cell. Oradian (Instafin, via a separate `OradianMiddleware` repo
**not in this session**) is live; Veengu is planned. Fineract would be a third peer.

**`CoreBankingProviderConfig` has a hardcoded allowlist `Set.of("oradian")` that FAILS BOOT
on anything else.** Adding `"fineract"` there is mandatory whenever that path is taken.

---

## 4. Customization mechanism

`settings.gradle` auto-discovers `custom/<company>/<category>/<module>` — **exactly three
levels deep**. Nothing in core needs editing to add a module.

| Rule | Detail |
|---|---|
| Depth | Exactly `custom/innbucks/<category>/<module>`. Two or four levels are invisible. |
| Package | `com.innbucks.fineract.<category>.<module>` |
| Starter | One per category, with `@AutoConfiguration` listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Component scan | The auto-config **must** `@ComponentScan("com.innbucks.fineract...")` — core scans only `org.apache.fineract.**` and will silently never see your beans |
| Feature flag | Gate with `@ConditionalOnProperty` so the module ships dormant |
| Migrations | `src/main/resources/db/custom-changelog/NNNN_*.xml`, **globally unique filenames** (`includeAll` flattens them) |
| Licence header | 18-line ASF header on every file. Unlike `custom/acme/**`, the `innbucks` prefix is **fully Checkstyle- and SpotBugs-linted** |
| Never | Add the module to `fineract-provider`'s `dependencies.gradle` — circular dependency, build fails |
| Image | `./gradlew :custom:docker:jibDockerBuild` → `fineract-custom:latest` |

`custom/acme/event` is the working four-part template for a custom event: module +
auto-config starter + `ExternalEventSourceProvider` bean + Liquibase
`m_external_event_configuration` row. That last part is **mandatory** —
`ExternalEventConfigurationValidationService` fails startup if a discovered event type has
no config row.

---

## 5. The four decisions

**Customization.** Everything in `custom/innbucks/**`; never touch `fineract-provider` or
`fineract-core`. The fork is currently zero commits ahead of upstream — protect that and
`git pull` stays a fast-forward indefinitely.

**Customer auth.** Stays entirely in `user-service`. The mobile app keeps
`POST /auth/login` with a stable per-install `X-Device-Id`, a 15-minute access token, a
7-day refresh token, and a 401→refresh→retry interceptor. The middleware **verifies** that
JWT and issues nothing. It authenticates to Fineract as a single service-account `AppUser`
(`password_never_expires`, narrow role, `BYPASS_TWOFACTOR` if 2FA is on) over HTTP Basic
with the `Fineract-Platform-TenantId` header.

Rejected: pointing Fineract at an external IdP (no JIT provisioning, would mint a staff user
per customer); giving the app Fineract credentials directly (staff-level, no scoping);
re-implementing self-service inside Fineract (permanent upstream divergence).

Note: Fineract's SMS 2FA reads the mobile number from the **Staff** record, so it
structurally cannot deliver an OTP to a customer. The existing InnBucks OTP flow already
does this properly — keep it.

**Notifications.** A custom `ExternalEventProducer` (§3.5) POSTs to the middleware, which
fans out to the existing `EmailNotificationClient` / `WhatsAppNotificationClient` /
`UserNotificationDispatcher`. All templating, channel routing, GSM sanitisation and
WhatsApp fallback stay where they already work.

**The app.** Routes through `api-gateway` → middleware. Never touches Fineract.

---

## 6. The highest-severity control — do not build an IDOR

The middleware holds **one Fineract service account with broad permissions**, and Fineract's
only data scoping is an office-hierarchy string prefix
(`SpringSecurityPlatformSecurityContext.validateAccessRights`). There is no per-customer
row-level security, no `client_credentials` grant, no on-behalf-of delegation.

**Fineract will not stop customer A from reading customer B's accounts. That isolation lives
entirely in middleware code.**

Resolve the Fineract client id **server-side from the JWT's `userUuid` claim** on every
request. Never from a client-supplied path, query or body parameter.

```
GET /banking/accounts                        # correct — client id derived from the token
GET /banking/clients/{fineractClientId}/...  # an IDOR exposing every customer's balances
```

The wrong shape is the tempting one, because it mirrors Fineract's own API. The defect is
invisible in manual testing — the developer's own account always returns correct data — so
pin it with a test that authenticates as customer A and asserts a failure for customer B's
identifiers.

---

## 7. Phase plan

| Phase | Work | Effort |
|---|---|---|
| **0. Spike** | Run Fineract on PostgreSQL locally. Create the service account + role. Call `POST /v1/clients` and `GET /v1/clients/{id}/accounts` by hand. **Prove the data model fits before writing code.** Also spike the `@Primary` custom-module override (§3.5) since it is unproven. | 1–2 days |
| **1. Middleware skeleton** | New service, Eureka-registered, behind `api-gateway`. Verifies the existing InnBucks JWT. One read path end-to-end. WireMock contract test. Client-id scoping test (§6). | 3–5 days |
| **2. Onboarding write path** | Create a Fineract client from InnBucks KYC with a **stable** idempotency key derived from `User.id`. Persist the linkage locally. | 3–5 days |
| **3. Notifications** | `custom/innbucks/event/{producer,starter}` + `@Primary` HTTP producer → middleware `/internal/fineract-events` → existing notify clients. Idempotent receiver + outbox-depth alert. | ~1 week |
| **4. Consolidate** | If Fineract becomes a cell's core-banking provider: implement `FineractCoreBankingAdapter` and add `"fineract"` to the `CoreBankingProviderConfig` allowlist. | 3–5 days |

**Do phase 0 first.** The biggest unknown is not Spring wiring — it is whether Fineract's
client/savings model fits the product.

---

## 8. InnBucks conventions that apply

Enforced by CI and `ticketing-system/CLAUDE.md`. A Fineract integration gets no exemption.

- **WireMock contract test is mandatory** for every external HTTP client: pure JUnit, **no
  `@SpringBootTest`**, one case per observed response shape, a connect-refused case against
  a **separate** dead port, outbound body verified with `matchingJsonPath`, guard rails with
  `verify(0, ...)`. Canonical: `user-service/.../client/SmsNotificationClientContractTest.java`.
- **Internal endpoints need three files to agree**: (1) controller checks `X-Internal-Token`
  with a constant-time compare — use `InternalTokenAuthorizer`, which also writes an audit
  row and metric; (2) the service's `SecurityConfig` `.permitAll()`s the exact path, or
  Spring Security 401s before the controller runs; (3) `api-gateway` has a `*-internal-deny`
  route forwarding to `forward:/__edge_deny__` **before** the service catch-all. Assert
  `.isUnauthorized()` / `.isBadRequest()` — **never** `.is4xxClientError()`.
- **Gateway route required.** The gateway has no catch-all; unrouted paths 404. Use
  `Path=/banking/**` + `uri: lb://fineract-middleware`, the standard `RequestRateLimiter`,
  and `resilientRedisRateLimiter` on money-moving paths.
- **Idempotency-Key** minted when the user taps Send, not when the request starts.
- **Secrets** are env vars with `change-me` defaults, registered in `ProductionSecretsGuard`
  if boot-critical, documented in `.env.example`.
- **Timestamps**: `LocalDateTime.now(ZoneOffset.UTC)` — never bare `now()`. Wire format
  carries an explicit `Z`.
- **Swagger**: real `@ApiResponses` / `@ExampleObject` bodies in the `ApiResult` envelope.
- **Linkage migration**: mirror
  `user-service/src/main/resources/db/migration/V10__customer_profile_oradian_linkage.sql` —
  `fineract_client_id BIGINT` + `fineract_external_id VARCHAR(64)`, nullable, each with a
  **partial** unique index (`WHERE ... IS NOT NULL`).

---

## 9. Current state

- **Branch:** `feature/fineract-integration-strategy` on `MpofuSlim/fineract`.
- **PR:** [#12](https://github.com/MpofuSlim/fineract/pull/12) (draft) → `develop`.
  Documentation only; no code, build, schema or API changes.
- **PR #11 is closed** — it was opened from a mis-named `claude/*` branch and superseded.
- **CI:** `Validate Jira Ticket ID` and `Verify Commit Signatures` are **red and will stay
  red**. They are inherited Apache governance workflows: the first requires the PR title to
  match `^FINERACT-[0-9]+: ` (a ticket in the *Apache* Jira project this fork does not
  control — **do not invent a number** to go green), the second runs
  `scripts/verify-signed-commits.sh --strict` and needs GPG-signed commits. Judge CI on the
  `build-*` jobs. If these should not gate internal work, add an `if:` guard to
  `.github/workflows/pr-title-check.yml` and `.github/workflows/verify-commits.yml`.

**Nothing has been implemented yet.** All work to date is analysis and documentation.

---

## 10. Open questions

1. ~~What is the app?~~ **Answered: the customer-facing InnBucks mobile app.** A
   *third-party partner* app would be a different problem — Fineract has no
   `client_credentials` grant, no inbound API-key auth and no per-partner scoping.
2. **Is push a day-one requirement?** Neither side provides it: Fineract's GCM/FCM module is
   dead code (`registrationId` hard-coded `null`), and the InnBucks gateway does SMS,
   WhatsApp and email but not push. Changes scope, not design — once events reach the
   middleware the fan-out is channel-agnostic.
3. **Is Fineract replacing Oradian or running alongside it?** Replacing makes
   `FineractCoreBankingAdapter` the priority; alongside means the middleware is a standalone
   surface and phase 4 may never happen.
4. **Which Fineract products?** Loans, savings or both — determines whether `CoreBankingPort`
   needs widening beyond `createCustomer` + `listDeposits`.
5. **Idempotency on client creation.** `externalId` is the usual stable-key workaround;
   confirm Fineract's duplicate-create behaviour in phase 0 before relying on it.
6. **Tenancy.** Single tenant (`default`) or one per country cell? Affects the service-account
   model and `Fineract-Platform-TenantId` handling.

---

## 11. Suggested opening prompt for the new session

> Read `docs/fineract-integration-handoff.md` and
> `docs/innbucks-fineract-integration-strategy.md` in the fineract repo, then start
> **phase 0**: stand up Fineract locally on PostgreSQL, create a narrowly-scoped service
> account, and verify by hand that `POST /v1/clients` and `GET /v1/clients/{id}/accounts`
> can back the `CoreBankingPort` SPI. Report the field-by-field mapping gaps and whether
> `externalId` gives us idempotent client creation.

Both repos must be attached to the session (`MpofuSlim/fineract` and
`MpofuSlim/ticketing-system`). Note that `OradianMiddleware` is a **separate repo not
currently attached** — add it if the Oradian contract needs inspecting.
