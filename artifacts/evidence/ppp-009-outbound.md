# PPP-009 Restricted Outbound and Connector Evidence

Date: 2026-07-16

## Executable boundary

- Generated actions receive only the catalogued `public-http!` and `connector-http!` capabilities; socket, DNS, Java interop, shell, filesystem, and raw credentials remain kernel-owned.
- Public requests accept HTTPS only, validate every DNS answer as public unicast, pin the approved address while preserving TLS hostname verification, and repeat the complete policy after every redirect.
- Request methods, headers, timeouts, redirects, ports, and decompressed response sizes are bounded. Authorization, cookie, forwarding, proxy, host, and hop-by-hop headers fail closed.
- Named connectors validate alias, method, normalized path, query names, body contract, target, and DNS before reading a developer-owned secret. Model context contains neither origins nor secret values nor environment-variable names.
- Runtime SQLite actions can execute only SQL string templates statically present in generated source; action values remain separate parameters.

## Verification gate

```text
clojure -M:fmt check src test
All source files formatted correctly

clojure -M:lint --lint src test
errors: 0, warnings: 0

clojure -M:test --seed 90061 \
  --focus ppp.outbound.policy-test \
  --focus ppp.outbound.client-test \
  --focus ppp.outbound.service-test \
  --focus ppp.integration.outbound-https-test \
  --focus ppp.property.http-policy-test \
  --focus ppp.provider.codex-test \
  --focus ppp.runtime.server-test \
  --focus ppp.runtime.sqlite-test
43 tests, 287 assertions, 0 failures.
```

PBT-06 runs four fixed-seed properties with 1,000 generated cases each:

- seed `90061`: IPv4 public/reserved classification;
- seed `90062`: IPv6 public/reserved classification;
- seed `90063`: mixed DNS answers fail closed;
- seed `90064`: redirect targets repeat URL and DNS policy.

## Controlled transport and redaction evidence

- `ppp.integration.outbound-https-test` creates a local certificate and controlled HTTPS server, then exercises real TLS hostname verification, safe redirects, rejected private redirects, timeout, decompression limit, and hostname mismatch.
- `ppp.outbound.service-test` captures resolution, credential lookup, and transport ordering. Invalid path, method, query, body, target, and DNS cases prove transport and credential lookup do not occur early.
- Connector catalog assertions prove the serialized model context excludes the base origin, secret value, and environment-variable name.
- Response normalization removes `Set-Cookie`; exception data and generated action errors contain no injected secret.
