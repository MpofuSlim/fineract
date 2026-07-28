# Fineract customization & integration strategy (InnBucks)

Decision document. Written against this fork at upstream `develop` commit `6cb7eb14`
(2026-05-27), which is **zero commits ahead of upstream** — a clean slate.

---

## 1. Recommendation in short

Build a **Fineract middleware service** that owns every conversation with Fineract, and
keep Fineract itself a private, back-office-only engine that is never reachable from the
internet and never authenticates a customer.

```
  Customer app / FE
        |  HTTPS, existing InnBucks JWT
        v
  api-gateway            (existing public edge: CORS, rate limiting, routing)
        |  lb://fineract-middleware
        v
  fineract-middleware    (NEW — the service you described)
        |  HTTP Basic service account + Fineract-Platform-TenantId
        v
  Fineract               (private; no ingress, no public route)
        |  custom-module event producer (HTTP)
        v
  fineract-middleware  -->  existing InnBucks notify / WhatsApp gateway
```

Three rules hold the whole design together:

1. **Never expose Fineract to the FE or the internet.** Its customer-facing surface no
   longer exists (see §4) and every remaining API is a staff API.
2. **Never edit core.** All Fineract-side code lives in `custom/innbucks/**`, so pulling
   upstream `develop` stays a fast-forward for years.
3. **Notification delivery stays in InnBucks, not in Fineract.** Fineract emits facts;
   the middleware decides who gets told and how.

Your instinct — your own middleware, consumed by your FE — is the right one. The single
refinement: put it **behind the existing `api-gateway`** rather than standing it up as a
second public edge, so you inherit CORS, the Redis rate limiter, TLS termination and the
existing JWT instead of reimplementing four security controls.

---

## 2. Why a middleware is not optional

Fineract **deleted its self-service (customer-facing) feature**. This is not a disabled
flag — the code and schema are gone:

- `fineract-provider/src/main/resources/db/changelog/tenant/parts/0219_remove_self_service_feature.xml`
  drops `m_selfservice_user_client_mapping`, `m_selfservice_beneficiaries_tpt`,
  `m_pocket_accounts_mapping`, `m_pocket`, `client_device_registration`, and the column
  `m_appuser.is_self_service_user`.
- There are **zero** Java references to self-service anywhere in the tree.
- `AppUser` has no `Client` association — the user↔customer link is gone.
- Fineract's own `fineract-doc/.../security/harden.adoc` says the self-service APIs should
  not be used and that apps should not be developed against them.

So there is no customer surface to configure and no endpoint list to hand your FE. A BFF
in front is not a stylistic preference; it is the only supported shape.

---

## 3. How to customize Fineract at all

`settings.gradle` auto-discovers `custom/<company>/<category>/<module>` — **exactly three
levels deep**. Nothing needs editing to add a module.

```groovy
// settings.gradle
file("${rootDir}/custom").eachDir { companyDir ->
    ... include ":custom:${companyDir.name}:${categoryDir.name}:${moduleDir.name}"
}
```

Conventions that are mandatory, not stylistic:

| Rule | Detail |
|---|---|
| Depth | Exactly `custom/innbucks/<category>/<module>`. Two or four levels are invisible. |
| Package | `com.innbucks.fineract.<category>.<module>` |
| Starter | One `starter` module per category, with `@AutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Component scan | The auto-config **must** `@ComponentScan("com.innbucks.fineract...")` — core scans only `org.apache.fineract.**` and will never see your beans |
| Migrations | `src/main/resources/db/custom-changelog/NNNN_*.xml`; filenames must be globally unique (they are flattened by `includeAll`) |
| Licence header | Every file needs the 18-line ASF header — unlike `custom/acme/**`, your prefix is fully Checkstyle- and SpotBugs-linted |
| Image | `./gradlew :custom:docker:jibDockerBuild` → `fineract-custom:latest` |
| Feature flag | Gate the auto-config with `@ConditionalOnProperty` so the module ships dormant |

**Do not add your module to `fineract-provider`'s `dependencies.gradle`** — that creates a
circular dependency and fails the build.

### The override seam is wider than the docs claim

The docs say only `NoteReadPlatformService` / `NoteWritePlatformService` are overridable.
That is badly out of date: 70 files use `@ConditionalOnMissingBean`, and nearly every
domain ships a `*Configuration` starter with individually override-guarded `@Bean` methods.

But note the deliberate exception: **there is not a single `@ConditionalOnMissingBean` in
any security, auth or user-administration package.** The auth beans are closed on purpose.
Plan around them rather than trying to force them open.

---

## 4. Customer authentication

**Decision: customer identity stays 100% in InnBucks. Fineract never sees a customer credential.**

Fineract has no customer identity model left. A customer is a `m_client` row — a *record*,
not a *principal*. The only principals are staff `AppUser`s.

- **Customers** authenticate against the existing `user-service` (`/auth/login`,
  `/auth/otp/*`, MFA) and carry the existing InnBucks JWT (issuer `innbucks-ticketing`,
  audience `innbucks-app`, 15-min access / 7-day refresh, Redis denylist + token-version
  epoch). The middleware verifies that JWT with a copy of the existing `JwtFilter`.
- **The middleware** authenticates to Fineract as a single **service account**: an
  `AppUser` with `password_never_expires`, a narrow role, and `BYPASS_TWOFACTOR` if 2FA is
  on. Credentials are env vars, never committed.
- **The mapping** from InnBucks customer → Fineract client is a column on the InnBucks
  side, exactly as `customer_profiles.core_banking_provider` /
  `oradian_client_id` already work for Oradian.

Rejected alternatives, and why:

- *Point Fineract at an external IdP so customers log into Fineract.*
  `FineractJwtAuthenticationTokenConverter` calls
  `userDetailsService.loadUserByUsername(jwt.getSubject())` — the `sub` claim must match an
  **existing** `m_appuser.username`, and there is no JIT provisioning. You would be minting
  a staff user per customer. No.
- *Give the FE Fineract credentials directly.* Fineract has no per-call scoping beyond
  office hierarchy, no `client_credentials` grant, and no inbound API-key auth. Any
  credential the FE holds is a staff credential. Absolutely not.
- *Re-implement self-service inside Fineract.* Large, permanently divergent from upstream,
  and duplicates auth you already own and have hardened.

### 2FA note

Fineract's SMS 2FA reads the mobile number from the **Staff** record
(`TwoFactorServiceImpl`), so it structurally cannot deliver an OTP to a customer. Your
existing OTP flow (HMAC-hashed, 5-min TTL, SMS→WhatsApp fallback by country) already does
this properly. Keep it.

---

## 5. Notifications

**Decision: Fineract emits events over HTTP to the middleware; the middleware calls the
existing InnBucks notify / WhatsApp gateway. Fineract's own SMS/email machinery is not used.**

### Why not Fineract's SMS campaigns (verified, 3/5 viability)

- It speaks a **proprietary Message-Gateway protocol**, not generic HTTP: a JSON array of
  `{internalId, tenantId, createdOnDate, sourceAddress, mobileNumber, message, providerId}`,
  headers `Fineract-Platform-TenantId` + `Fineract-Tenant-App-Key`, a mandatory `202
  ACCEPTED`, a `POST /sms/report` delivery-report poll, and a `GET /smsbridges` provider
  list. The InnBucks notify API matches none of this.
- `SmsConfigUtils` **hard-codes `.scheme("http")`**, and `MessageGatewayConfigurationData.sslEnabled`
  is hard-coded `false` with no config key. It cannot reach an HTTPS gateway — an A02
  violation under the ticketing-system rules.
- It is **four call sites**, not one: `SendMessageToSmsGatewayTasklet`,
  `GetDeliveryReportsFromSmsGatewayTasklet` and `SmsCampaignDropdownReadPlatformServiceImpl`
  each hold their own `new RestTemplate()` and bypass the service interface.
- Email has **no HTTP seam at all** and lives in two unrelated beans.
- A campaign-less `SmsMessage` left `PENDING` **NPEs the nightly drain job**.

### Why not stock webhooks

The `Web` hook template does POST JSON to a URL with no code — genuinely attractive for a
spike. But dispatch is OkHttp `.enqueue()`: **fire-and-forget, no retry, no persistence, no
dead-letter**, and it cannot carry a bearer token obtained from a login call. For financial
events that is disqualifying. Fine for a day-one smoke test; not for production.

### The chosen seam: a custom `ExternalEventProducer`

```java
public interface ExternalEventProducer {
    void sendEvents(Map<Long, List<byte[]>> partitions) throws AcknowledgementTimeoutException;
}
```

You inherit the transactional outbox `m_external_event` (with `idempotency_key`,
`aggregate_root_id`, `status`), ordering per aggregate, at-least-once delivery, the retry
loop (unsent rows stay `TO_BE_SENT`), the purge job, and per-event-type enable/disable via
`PUT /v1/externalevents/configuration`. No broker — which matters, because Kafka was
deliberately decommissioned from this stack and should not return.

Three things to get right, all verified:

1. **Defeat the downstream gate without editing core.**
   `SendAsynchronousEventsTasklet.isDownstreamChannelEnabled()` returns true only if JMS or
   Kafka is enabled, so a custom producer is otherwise dead code. The method is `protected`
   on a non-final `@Component`, and Fineract sets
   `spring.main.allow-bean-definition-overriding=true`, so a `@Primary` subclass from your
   custom module wins with **zero core edits**.
2. **`@Primary` is required, not optional.** `NoopExternalEventProducer` has no
   `@ConditionalOnMissingBean`; its condition is exactly "neither JMS nor Kafka", i.e. your
   configuration. Without `@Primary` you get a `NoUniqueBeanDefinitionException` at startup
   (fail-fast, at least — not a silent black hole).
3. **The receiving endpoint must be idempotent.** `sendEventsToProducer()` runs *before*
   `markEventsAsSent()`, and `execute()` swallows exceptions and still reports success — so
   a partial failure redelivers the **entire batch** and **no alert fires**. Key on the
   Avro `MessageV1` idempotency key, and add your own metric/alert on outbox depth.

Also required: an `ExternalEventSourceProvider` bean and a Liquibase
`m_external_event_configuration` row per custom event —
`ExternalEventConfigurationValidationService` **fails startup** if a discovered event type
has no config row. `custom/acme/event` is the working template for all four parts.

---

## 6. Integrating the app

The app talks to the middleware. Nothing else changes.

- **Route it through the existing `api-gateway`.** The gateway has no catch-all, so a new
  `fineract-middleware-route` with `Path=/banking/**` and `uri: lb://fineract-middleware` is
  required or every call 404s. Apply the standard `RequestRateLimiter`, and
  `resilientRedisRateLimiter` on any money-moving path.
- **Generate the Fineract client, don't hand-write it.** Fineract serves OpenAPI at
  `/api-docs` (Swagger UI reads `/fineract.json`) and ships a generated Java SDK
  (`fineract-client`). Pin the spec in-tree the way `docs/api/veengu-*.json` is pinned.
- **Model the middleware on `OradianMiddleware`.** You already run exactly this pattern: an
  in-house shim fronting a core-banking system, exposing clean `/internal/*` endpoints with
  `X-Internal-Token`, `Idempotency-Key`, `X-Owner-Msisdn`, ProblemDetail errors, and
  Resilience4j retry + circuit breaker. Copy that shape rather than inventing one.
- **Later, fold it into the existing SPI.** `CoreBankingPort` already abstracts the
  core-banking provider (`createCustomer`, `listDeposits`) with `OradianCoreBankingAdapter`
  live and Veengu planned. A `FineractCoreBankingAdapter` is the natural third peer —
  remember `CoreBankingProviderConfig` has a hardcoded allowlist `Set.of("oradian")` that
  **fails boot** on anything else, so `"fineract"` must be added there.

---

## 7. Phase plan

Each phase is independently shippable and reversible.

| Phase | Work | Rough effort |
|---|---|---|
| **0. Spike** | Run Fineract on PostgreSQL locally; create the service account + role; call `POST /v1/clients` and `GET /v1/clients/{id}/accounts` by hand. Prove the data model fits before writing code. | 1–2 days |
| **1. Middleware skeleton** | New service, Eureka-registered, behind `api-gateway`. Verifies the existing InnBucks JWT. One read path end-to-end (client lookup). WireMock contract test. | 3–5 days |
| **2. Onboarding write path** | Create a Fineract client from InnBucks KYC, with a **stable** idempotency key derived from `User.id`. Persist the Fineract client id locally. | 3–5 days |
| **3. Notifications** | `custom/innbucks/event/{producer,starter}` module + `@Primary` HTTP producer → middleware `/internal/fineract-events` → existing notify/WhatsApp clients. Idempotent receiver + outbox-depth alert. | 1 week |
| **4. Consolidate** | If Fineract becomes a cell's core-banking provider, implement `FineractCoreBankingAdapter` and add `"fineract"` to the allowlist. | 3–5 days |

Do phase 0 before committing to anything else. The biggest unknown is not Spring wiring —
it is whether Fineract's client/savings model fits your product.

---

## 8. What we are deliberately not doing

- **Not editing `fineract-provider` or `fineract-core`.** Ever. It is the whole reason the
  custom-module mechanism exists.
- **Not using Fineract's SMS/email campaigns.** Wrong protocol, plaintext-only, four call
  sites, no email seam.
- **Not using stock webhooks in production.** No retry, no persistence, no bearer auth.
- **Not reintroducing a message broker.** The outbox gives at-least-once without one, and
  Kafka was removed from this stack on purpose.
- **Not putting customer credentials in Fineract.** No JIT provisioning, no per-customer
  scoping, staff-only principals.
- **Not exposing Fineract through the public edge.** No gateway route to it at all; a
  `fineract-*-deny` route if anything could ever reach it.

---

## 9. InnBucks conventions that apply

These are enforced by CI and by CLAUDE.md — a Fineract integration does not get an exemption.

- **WireMock contract test is mandatory** for every external client: pure JUnit, no
  `@SpringBootTest`, one case per observed response shape, a connect-refused case against a
  separate dead port, outbound body verified with `matchingJsonPath`, guard rails with
  `verify(0, ...)`. Canonical: `SmsNotificationClientContractTest`.
- **Internal endpoints need three files to agree**: controller checks `X-Internal-Token`
  with a constant-time compare (use `InternalTokenAuthorizer`), the service `SecurityConfig`
  `.permitAll()`s the exact path, and `api-gateway` has a `*-internal-deny` route forwarding
  to `forward:/__edge_deny__` *before* the service catch-all. Assert `.isUnauthorized()` —
  never `.is4xxClientError()`.
- **Gateway route required** for anything the FE calls, with the standard rate limiter.
- **Secrets are env vars** with `change-me` defaults, registered in `ProductionSecretsGuard`
  if boot-critical, documented in `.env.example`.
- **Timestamps**: `LocalDateTime.now(ZoneOffset.UTC)`, never bare `now()`.
- **Swagger**: real `@ApiResponses` / `@ExampleObject` bodies in the `ApiResult` envelope.

---

## 10. Open questions

1. **What is "the app"?** A customer-facing mobile app changes nothing above. An
   internal/agent tool might justify a thinner middleware. A third-party partner app would
   need its own credential model, which none of the above provides.
2. **Is Fineract replacing Oradian, or running alongside it?** Replacing it makes
   `FineractCoreBankingAdapter` the priority. Alongside means the middleware is a
   standalone product surface and phase 4 may never happen.
3. **Which Fineract products?** Loans, savings, or both — this determines whether
   `CoreBankingPort` needs widening beyond `createCustomer` + `listDeposits`.
4. **Idempotency on client creation.** Fineract's `externalId` is the usual stable-key
   workaround, but confirm the duplicate-create behaviour in phase 0 before relying on it.
5. **Tenancy.** Single tenant (`default`) or one per country cell? This affects the
   service-account model and the `Fineract-Platform-TenantId` handling.
