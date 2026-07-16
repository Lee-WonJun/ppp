# Self-Host Deployment

Status: deployment contract; external deployment requires owner approval
Supported target: Linux amd64
Last updated: 2026-07-15

## 1. Deployment shape

PPP uses one public application container and two persistent volumes.

```text
internet
   |
TLS reverse proxy
   |
PPP JVM container :8787
   ├─ ppp-data    /var/lib/ppp
   └─ codex-home  /var/lib/codex
```

SQLite is embedded per session. No separate database service is required for the hackathon release.

## 2. Host requirements

- Linux amd64 host.
- Docker Engine with Compose v2.
- At least 2 CPU cores and 4 GiB memory recommended for JVM plus one Codex process.
- Persistent storage with sufficient space for the 2 GiB instance quota and backups.
- TLS termination for any non-local use.
- Outbound HTTPS for Codex and explicitly used public/connector APIs.

ARM64, Windows containers, native installers, Kubernetes, and multi-node HA are unsupported.

## 3. Required configuration

Create deployment secrets outside the repository:

```text
PPP_ENV=production
PPP_PORT=8787
PPP_DATA_DIR=/var/lib/ppp
PPP_ACCESS_CODE=<high-entropy judge code>
PPP_COOKIE_SECRET=<at least 32 random characters>
PPP_AI_PROVIDER=codex
PPP_CODEX_MODEL=gpt-5.6-terra
PPP_CODEX_REASONING=medium
PPP_REQUIRE_CLIENT_ACK=true
PPP_PUBLIC_BASE_URL=https://your-host.example
CODEX_HOME=/var/lib/codex
```

Do not bake these into the image or commit them. Prefer the deployment platform's secret store or a root-readable env file outside the project.

## 4. Local Docker workflow

Build and verify first:

```bash
bb verify
docker compose build
```

Authenticate Codex into the persistent volume:

```bash
docker compose run --rm app codex login --device-auth
docker compose run --rm app codex login status
```

Start:

```bash
docker compose up
```

Check:

```bash
curl --fail http://localhost:8787/healthz
curl --fail http://localhost:8787/readyz
```

Open the access fragment URL. The browser removes the fragment after exchange.

## 5. Container hardening contract

- Runtime user is non-root.
- Root filesystem can be read-only.
- `/tmp` is tmpfs.
- `/tmp` may remain `noexec`; SQLite JDBC extracts its native library into
  `/var/lib/ppp/.native` through `org.sqlite.tmpdir`.
- Only `/var/lib/ppp` and `/var/lib/codex` are writable persistent mounts.
- `auth.json` inside Codex volume is mode 0600.
- No source repository, Docker socket, host home, or SSH directory is mounted.
- Image contains no data, prompts, access code, connector secrets, or OAuth state.
- Application handles SIGTERM and stops accepting new jobs before process exit.

Inspect package evidence:

```bash
docker image inspect programmable-programming-page:local
docker run --rm --entrypoint id programmable-programming-page:local
```

The packaged smoke task performs the complete inspection and health flow:

```bash
bb docker-smoke
```

It also verifies that the Compose contract declares only `ppp-data` and
`codex-home`, that a mode-0600 file survives across disposable containers using the
Codex home volume, and that a quiesced data-volume archive restores into a fresh
volume with the same session runtime and checkpoints. It never reads or copies the
owner's OAuth file.

## 6. Volumes

### `ppp-data`

Contains source, prompts, history, checkpoints, journals, and per-session SQLite databases. Treat as private product data.

### `codex-home`

Contains Codex configuration and OAuth credentials. Treat as a password store. Do not include it in normal session exports or share it with judges.

Keep the volumes separate so session backup/restore does not copy OAuth material.

## 7. Backup

Before image change or deployment:

1. Stop new AI jobs.
2. Wait for current commit section to finish.
3. Confirm `/readyz` reports no recovery journal pending.
4. Create a host-level snapshot or archive of `ppp-data` only.
5. Record image digest and session format version.
6. Verify the backup can list session manifests and decompress a sampled checkpoint.

Do not copy a live SQLite file independently from its WAL. Session checkpoints already use consistent live snapshots; deployment backup should be volume-consistent or taken while quiesced.

OAuth backup is optional and must use a separate encrypted credential procedure.

### Local backup and rollback rehearsal

Run:

```bash
bb docker-smoke
```

The rehearsal creates isolated random named volumes, commits a deterministic fake
provider change, stops the application, archives the complete data volume, restores
the archive into a new volume, and boots the packaged image against that restored
volume. The gate fails unless the original session id, runtime version, generated
style, and checkpoint list are readable. All rehearsal containers and volumes are
removed afterward.

## 8. Image upgrade and rollback

Upgrade:

1. Run `bb verify` and packaged smoke locally.
2. Back up `ppp-data`.
3. Pull/build the new pinned image digest.
4. Start one instance.
5. Check health and readiness.
6. Open a real session, use an action, switch sessions, and restore a checkpoint.
7. Keep the prior digest until observation completes.

Rollback:

1. Stop the failing image.
2. If it never changed session format, start the previous image against the existing data volume.
3. If it changed session format, restore the pre-deploy `ppp-data` backup before starting the old image.
4. Verify readiness and a representative session.

Never assume an image rollback can downgrade persisted data.

## 9. Coolify preparation

Coolify uses the same Dockerfile and persistent mounts.

Configuration checklist:

- Deploy from the approved repository/commit or pinned GHCR digest.
- Architecture `linux/amd64`.
- Expose application port 8787 behind HTTPS.
- Mount persistent data and Codex home paths separately.
- Store environment values as secrets.
- Configure `/healthz` liveness and `/readyz` readiness.
- Set root filesystem read-only if supported and `/tmp` as ephemeral writable storage.
- Set a single replica.
- Allow sufficient startup time for recovery and provider preflight.
- Back up the session volume before each deployment.

Do not initiate the Coolify deployment from this repository workflow. The owner must explicitly approve the target, DNS, access delivery, backup, and rollback window.

## 10. GHCR and CI

CI runs with the fake provider and no owner credentials. It may:

- lint and test;
- build browser release;
- build Linux amd64 image;
- run packaged smoke;
- scan repository and image;
- publish a GHCR image only in an explicitly configured release job.

CI must not:

- use ChatGPT OAuth;
- seed `auth.json`;
- expose an API key to repository-controlled build steps;
- deploy automatically from a pull request.

## 11. Operational checks

### `/healthz`

Proves the JVM request loop is alive. It does not touch Codex or SQLite.

### `/readyz`

Proves:

- data root is writable;
- startup journal recovery completed;
- current manifests are readable;
- selected provider preflight succeeds.

Provider readiness checks login/configuration, not a billed generation.

### Logs

Collect JSON metadata fields only. Do not collect prompt, source, SQL, cookies, connector values, or provider reasoning. Retention and access should match private product data sensitivity.

## 12. Recovery playbook

| Symptom | Action |
|---|---|
| `/healthz` fails | Restart same image; inspect process metadata logs. |
| `/readyz` reports OAuth | Re-run device auth in `codex-home`; do not recreate session volume. |
| `/readyz` reports journal recovery | Keep traffic closed; inspect stable recovery error code and restore backup if automatic recovery cannot verify hashes. |
| Generated sidebar unusable | Use immutable handle or `Ctrl+Alt+Shift+P`; restore a checkpoint. |
| Quota reached | Stop new AI changes, back up, increase owner-approved storage; do not delete history automatically. |
| New image fails | Follow image rollback procedure and session-format check. |
| Access code leaked | Rotate secret, restart app, invalidate signed access cookies. |

## 13. Pre-deployment approval record

Leave blank until the owner authorizes an external deployment.

```text
Target:
Domain:
Image digest:
Backup verified at:
Rollback digest:
Access delivery method:
Owner approval:
Deployment operator:
```
