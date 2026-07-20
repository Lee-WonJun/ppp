# PPP-027 Coolify Judge Deployment

Date: 2026-07-20
Status: deployment, persistent OAuth, and public real-provider canary passed

## Deployment contract

- Public origin: `https://ppp.openai.slopbook.org`
- Target: Coolify `localhost`, one Linux amd64 application replica
- Source: public `Lee-WonJun/ppp` `master`
- Deployed commit: `4020cae406cd18729b22a0dcf806ee5174dfb439`
- Container port: `8787`
- Limits: 0.5 CPU, 1 GiB soft memory, 2 GiB maximum memory
- Persistence: separate generated `ppp-data` and `codex-home` volumes
- Access: shared password in Coolify runtime-only secret storage
- Provider budget: rolling 100 starts per configured hour window

The application, volumes, and custom domain are separate from Slopbook. The
existing `app.slopbook.org` DNS record and Slopbook worker were not modified.

## Deployment observation

The original remote-worker attempt was cancelled after Coolify could not reach
that worker over its deployment SSH path. The approved recovery moved the
application to Coolify `localhost`. The PPP deployment job was removed from
the congested shared queue by its exact encrypted job identity and executed
directly; no unrelated queued job or global worker was restarted or deleted.

Coolify then:

1. imported the recorded public commit;
2. built both optimized browser bundles and the JVM uberjar;
3. created the two isolated persistent volumes;
4. started the bounded application container; and
5. completed a rolling update.

The deployment finished without compiler warnings. The host reported that
memory swappiness is unsupported and discarded that optional setting; the CPU
and hard/soft memory limits remain configured.

## Public access canary

- Browser TLS navigation: passed with a Let's Encrypt certificate for the
  exact hostname.
- `/healthz`: HTTP 200 with `{"status":"ok"}`.
- `/readyz`: correctly returned HTTP 503 with the bounded
  `provider/oauth-not-ready` reason before device authorization.
- Root: HTTP 200 and the shared-password sign-in surface rendered.
- URL fragment access was stripped and did not authenticate.
- Invalid password produced the visible non-secret rejection outcome.
- The real shared password opened the common Projects workspace in two fresh
  browser contexts.
- A fresh project opened the blank sandbox product canvas.
- The access cookie was Secure, HttpOnly, and SameSite Strict.
- `https://app.slopbook.org` remained HTTPS 200 after the DNS and deployment
  change.

The valid-password checks were run through Playwright with the password read
from the ignored local environment; the value was not printed or written to
this record.

## Persistent OAuth and real-provider canary

- Device authorization completed inside the running application container.
- `codex login status` reported ChatGPT login before and after a Coolify rolling
  restart.
- `/healthz` and `/readyz` both returned HTTP 200 after authorization and again
  after restart. Readiness identified the provider as Codex with ChatGPT OAuth.
- The pre-restart project remained present after restart; the filesystem session
  count and in-memory runtime registry remained equal.
- A deliberately disconnected verification browser produced the expected
  `runtime/requester-not-connected` rejection and did not activate its staged
  change.
- A fresh connected browser then completed one real Codex change, advanced from
  generation to the applied outcome, rendered the requested visible heading and
  body, and created checkpoint version 1.
- A second fresh browser reopened that project and independently verified the
  generated screen and checkpoint metadata after the requesting browser closed.
- The post-login canary reported zero page errors, zero console errors, and zero
  failed same-origin application requests.
- After the final canary, readiness remained HTTP 200 with no WebSocket
  connections or pending stages.

The English submission cut was also re-rendered from the already verified real
Codex recording. It is 168.74 seconds at 1440x900 with H.264 video, AAC English
narration, synchronized English subtitles, and an explicit explanation of how
Codex and GPT-5.6 are used. Publication remains separately owner-controlled.

The final local `bb verify` gate passed 186 JVM tests with 1,336 assertions,
29 ClojureScript tests with 132 assertions, 25 normal Chromium paths plus one
intentional skip, both production restart phases, the Linux amd64 Docker smoke,
formatting, lint, and a 205-file secret scan.

No password, device code, cookie, OAuth material, session identifier, prompt, generated
source, provider JSONL, host key, raw deployment log, or private application
data is retained in this evidence.
