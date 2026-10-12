# Security

Report suspected vulnerabilities privately to the repository owner's private security reporting channel when this project is published. Do not include live API keys, provider credentials or file contents in reports.

Before public deployment: terminate HTTPS at a reverse proxy, preserve the localhost application binding unless isolated by a private network, generate distinct random admin and monitoring secrets, restrict provider bucket credentials, protect backups and the encryption key, and apply proxy request/connection limits. Public admin access requires a dedicated key and should additionally be network restricted.

This first release has automated security behavior tests but has not undergone an independent security audit. Content-type policy validates declarations, not payload content. Billing/versioning and objects created outside this service require provider-side monitoring.
