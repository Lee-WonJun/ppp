# Codex Deployment Handoff

Repository contributor rules live in `AGENTS.md`. Product and deployment
contracts live in `docs/` and take precedence over this operational summary.

## Deploy Configuration (configured by /setup-deploy)

- Platform: Coolify v4 manual Dockerfile application
- Production URL: https://ppp.openai.slopbook.org
- Deploy workflow: owner-approved manual deploy from public `master`
- Deploy status command: Coolify deployment status plus HTTPS health checks
- Merge method: direct verified commits on `master`
- Project type: JVM web application with compiled browser shell
- Post-deploy health check: https://ppp.openai.slopbook.org/healthz

### Custom deploy hooks

- Pre-merge: `bb verify`
- Deploy trigger: manual Coolify deployment from the recorded Git commit
- Deploy status: Coolify application status and deployment log metadata
- Health check: `/healthz`, then `/readyz` after Codex device authentication

The target is one PPP replica on `worker1`. Existing Slopbook remains on
`worker3`. Persistent paths `/var/lib/ppp` and `/var/lib/codex` must be mounted
separately. Secrets are entered only through Coolify and are never copied into
this file.
