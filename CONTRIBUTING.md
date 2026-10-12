# Contributing

Use Java 21+ and Docker. Run `./gradlew check bootJar` before submitting a change. Include a test for security, quota, accounting or provider behavior changes. Preserve project isolation and never return or log secrets.

CLI changes also require `python -m unittest discover -s scripts -p test_setup.py` and `python scripts/smoke_setup.py` (Python 3.10+). The smoke test uses its own Compose project and removes only its test volumes. Use `sh ./orbit init --rebuild` (Windows: `.\orbit.ps1 init --rebuild`) to run a source build locally; plain `init` uses the released image. CI publishes images only after all checks pass. Release instructions are in the README.

Provider adapters belong under `provider` and implement `StorageProvider`. Add the same contract tests for each adapter. Database changes require a new Flyway migration; never rewrite applied migrations.

Keep changes focused. Explain the resulting API behavior, operational implications and validation in your pull request. Do not commit `.env`, test credentials for real services, build outputs or object data.
