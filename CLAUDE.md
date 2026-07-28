# Fineract fork — Claude memory

Project instructions for Claude when working in this repo (MpofuSlim/fineract,
a fork of Apache Fineract).

## Branching

> [!IMPORTANT]
> **All work goes on a `feature/<short-kebab-description>` branch, cut from `develop`.**
> NEVER commit or push work on the session's auto-assigned `claude/<random-words>`
> branch — that name is a harness default, not our convention, and must never
> appear on a PR. Create the `feature/*` branch first.

```sh
git checkout -b feature/<name> origin/develop
git push -u origin feature/<name>
```

The default branch on this fork is `develop` (tracking upstream Apache Fineract),
not `master`/`main`. One feature per branch; open a **draft** PR.

## Inherited Apache CI — two checks cannot pass on internal PRs

This fork inherits Apache Fineract's governance workflows, and two of them are
structurally unsatisfiable for our own internal PRs. **Do not try to game them.**

1. **`Validate Jira Ticket ID`** (`.github/workflows/pr-title-check.yml`) requires
   the PR title to match `^FINERACT-[0-9]+: `. That references the *Apache* Jira
   project, which we do not control. **Do not invent a FINERACT ticket number** to
   turn the check green — it fabricates a reference to a real external tracker.
2. **`Verify Commit Signatures`** (`.github/workflows/verify-commits.yml`) runs
   `scripts/verify-signed-commits.sh --strict`, requiring GPG/SSH-signed commits.
   Agent sessions have no signing key.

If these should not gate internal work, disable the two workflows on the fork (or
scope them with an `if:` guard to PRs targeting an upstream contribution branch).
Until then, expect them red on internal PRs and judge CI on the `build-*` jobs.

## Customization — never edit core

All customization goes in `custom/innbucks/<category>/<module>` — **exactly three
levels deep**, which is what `settings.gradle` auto-discovers. Editing
`fineract-provider` or `fineract-core` is what makes upstream merges painful and
is explicitly discouraged by the project's own docs.

Non-negotiables when adding a custom module:

- Package `com.innbucks.fineract.<category>.<module>`.
- One `starter` module per category with `@AutoConfiguration` listed in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- The auto-config **must** `@ComponentScan("com.innbucks.fineract...")` — core
  scans only `org.apache.fineract.**` and will never see your beans.
- Gate it with `@ConditionalOnProperty` so the module ships dormant.
- Liquibase migrations go in `src/main/resources/db/custom-changelog/` with
  **globally unique filenames** (`includeAll` flattens them).
- Every file needs the 18-line ASF licence header — unlike `custom/acme/**`, our
  prefix is fully Checkstyle- and SpotBugs-linted.
- Do **not** add the module to `fineract-provider`'s `dependencies.gradle` — it
  creates a circular dependency and fails the build.
- Build the image with `./gradlew :custom:docker:jibDockerBuild` → `fineract-custom:latest`.

## Integration strategy

The architecture decision record lives at
[`docs/innbucks-fineract-integration-strategy.md`](./docs/innbucks-fineract-integration-strategy.md).
Read it before starting integration work. Summary of the load-bearing constraints:

- **Fineract's self-service (customer-facing) feature has been removed** from the
  codebase (changelog `0219_remove_self_service_feature.xml`). Every remaining API
  is a staff API. Fineract must never be exposed to the internet or to a
  front-end, and never authenticates a customer.
- **No security/auth/user-administration bean carries `@ConditionalOnMissingBean`.**
  The auth beans are deliberately closed — customer authentication stays in the
  InnBucks `user-service`, and the middleware talks to Fineract as a single
  service-account `AppUser`.
- **Fineract's SMS/email machinery is not usable as a generic gateway client**: it
  speaks a proprietary Message-Gateway protocol, hard-codes `.scheme("http")`, and
  spans four call sites. Notification delivery stays on the existing InnBucks
  notify/WhatsApp gateway.
- **Do not reintroduce a message broker.** A custom `ExternalEventProducer`
  registered `@Primary` reaches the durable `m_external_event` outbox with zero
  core edits and gives at-least-once delivery without Kafka/ActiveMQ.
