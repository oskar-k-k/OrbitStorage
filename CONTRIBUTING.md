# Contributing

Use Java 21+ and Docker. Run `./gradlew check bootJar` before submitting a change. Include a test for security, quota, accounting or provider behavior changes. Preserve project isolation and never return or log secrets.

Provider adapters belong under `provider` and implement `StorageProvider`. Add the same contract tests for each adapter. Database changes require a new Flyway migration; never rewrite applied migrations.

Keep changes focused. Explain the resulting API behavior, operational implications and validation in your pull request. Do not commit `.env`, test credentials for real services, build outputs or object data.
