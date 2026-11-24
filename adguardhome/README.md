# AdGuard Home DNS Manager (Unofficial) #

This package is not supported nor endorsed by AdGuard!

This package will allow you to manage the global services of an AdGuard Home DNS server and it's configured clients.
A main application allows creation of multiple servers within Hubitat, and the clients are created as child devices of each server.


## Application ##

- [AdGuardHome_App.groovy](https://github.com/JoKneeMo/hubitat/blob/main/adguardhome/app/AdGuardHome_App.groovy) - Not required but makes managing multiple servers easier

## Main Driver ##

- [adGuardHome_Server.groovy](https://github.com/JoKneeMo/hubitat/blob/main/adguardhome/drivers/AdGuardHome_Server.groovy) - Required, this is the server device and handles the authentication and communication for each server.

## Client Drivers ##

- [adGuardHome_Client.groovy](https://github.com/JoKneeMo/hubitat/blob/main/adguardhome/drivers/AdGuardHome_Client.groovy) - Not required, this is the client device and is only needed if you want to manage individual client settings.

## AdGuard Home API compatibility (v1.2.0) ##

AdGuard Home's Control API (`/control/...`) changed shape several times. The
1.2.0 drivers do **not** hard-code a version cutoff; instead every write is a
read-modify-write of the document the server actually returned, and both the
current and the legacy response shapes are accepted on reads. The matrix below
describes what is exercised:

| AdGuard Home version | Support | Notes |
| --- | --- | --- |
| v0.107.x and later (incl. v1.x) | Full | Current shapes: `GET /control/status` `protected` field, `{"data": {...}}` wrapper on `/control/clients/find`, `PUT /control/safesearch/settings`, full-schema `POST /control/dhcp/set_config` |
| ~v0.103.x – v0.106.x | Functional via fallbacks | Plain-map `/control/clients/find` responses, legacy `POST /control/safesearch/enable` / `/disable`, protection read from `GET /control/dns_info` (`protection_enabled`) |
| older than ~v0.103 | Not tested | Endpoints predate the API this integration was written against |

Key endpoint behaviour:

| Feature | Read | Write strategy |
| --- | --- | --- |
| Protection | `GET /control/status` (`protected`, `protection_enabled`) with `GET /control/dns_info` fallback | read `/control/dns_info`, re-post the **whole** document via `POST /control/dns_config` so upstreams and every unrelated DNS setting survive |
| Filtering | `GET /control/filtering/status` | `POST /control/filtering/refresh` keeps the configured interval; enable/disable via `/filtering/enable|disable` |
| DHCP | `GET /control/dhcp/status` | `POST /control/dhcp/set_config` with the **complete** v4/v6 document (the API rejects partial payloads) |
| Safe Browsing / Parental | `GET /control/safebrowsing/status`, `/parental/status` | enable/disable endpoints |
| Safe Search | `GET /control/safesearch/status` | `PUT /control/safesearch/settings` preserving nested per-engine options, falling back to legacy enable/disable |
| Blocked services | `GET /control/blocked_services/list` before **every** change | `POST /control/blocked_services/toggle`; the schedule endpoint is never touched |
| Clients | `GET /control/clients`, `GET /control/clients/find?ip0=...` (both shapes), `GET /control/dhcp/clients` | `POST /control/clients/update` with the full authoritative client document |
| Auth | HTTP Basic on every request | AdGuard deprecated the session-cookie login; Basic auth remains supported through current releases |

Sources for the shape changes and endpoint contracts (check these first when a
new AdGuard Home release appears):

- AdGuard Home changelog: <https://github.com/AdguardTeam/AdGuardHome/blob/master/CHANGELOG.md>
- OpenAPI spec for the Control API: <https://github.com/AdguardTeam/AdGuardHome/blob/master/openapi/openapi.yaml>
- HTTP config API wiki: <https://github.com/AdguardTeam/AdGuardHome/wiki/HTTP-Config-API>

### Known limitations ###

- **TLS certificate verification is disabled** (`ignoreSSLIssues` on every
  HTTP call). This is pre-existing behaviour from 1.1.x, kept so that
  self-signed certificates keep working; it means a man-in-the-middle on an
  `https://` connection is not detected. If you use `https://`, prefer a
  certificate your Hubitat trusts.
- Authentication is HTTP Basic only (username/password of an AdGuard Home
  admin account). OAuth and the removed session-cookie login are not supported.

### Unsupported features (intentionally) ###

- Blocked-service **schedules** (`/control/blocked_services/schedule`) are
  preserved but never read or written.
- Filter rule / rewrite-rule editing, DNS server reconfiguration, upstream
  changes, query-log access, statistics, DHCP lease or static-lease management.
- Hubitat Remote Monitor capability integration beyond the preserved attribute
  names (all attributes remain strings for RM compatibility).

## Testing ##

### Automated mock tests (implemented and passing) ###

A Groovy mock suite lives in [`tests/`](tests/) and needs **no network and no
Hubitat**: it loads the real driver/app sources with a mock Hubitat runtime
(`tests/support/HubitatHarness.groovy`) and canned AdGuard Home JSON fixtures
(`tests/fixtures/`).

Requirements: any JVM (8+) with Groovy on the `PATH` (tested with Groovy
2.4.21 / JVM 21). Run from the repository root:

```bash
bash adguardhome/tests/run-tests.sh
```

All 9 test files (≈148 assertions) pass, covering: full refresh and version/
protection fallbacks, per-endpoint refresh failure isolation, protection
read-modify-write, DHCP/filtering full-schema writes, safe search settings +
legacy fallback, blocked-services authoritative reads, child-device lifecycle
across both `clients/find` shapes, and client refresh/mutation preservation
(including log sanitization — credentials and sensitive payloads are asserted
never to be logged).

### Live Hubitat testing (NOT yet performed) ###

Everything above is automated mock verification only. Before publishing, an
human should verify against a real Hubitat hub and a real AdGuard Home
instance:

1. Install/update the app and both drivers (Package Manager, or paste over the
   existing driver code in Hubitat and **Save**). Existing devices stay paired
   — namespaces, driver names, device network IDs, preferences, commands and
   attribute names are unchanged.
2. Set the server device preferences (IP/hostname, port, username, password,
   TLS) and press **Configure** (or let the scheduled refresh run).
3. Check the server device attributes after a refresh: `version`, `protection`,
   `filtering`, `dhcp`, `safeBrowsing`, `safeSearch`, `parental`,
   `blockedServices`.
4. Toggle each command once and confirm the change in the AdGuard Home admin
   UI **and that neighbouring settings survive**:
   - DHCP: note the interface, IP pool and lease durations, toggle DHCP off
     then on, confirm they are unchanged in the UI and that no other config
     was wiped.
   - Filtering: change the filter refresh interval, toggle filtering, confirm
     the interval is preserved.
   - Safe Search: enable per-engine options, toggle Safe Search, confirm the
     per-engine options are preserved.
   - Blocked services: toggle a service, confirm the blocked-services
     schedule is unchanged.
5. Create child devices (app **Create Client Devices**, or the driver command),
   toggle a client's blocked state, and confirm in the UI that the client's
   name, IDs, tags and per-client flags are untouched.
6. Failure paths: enter a wrong password and refresh — the device should log
   sanitized errors (no plaintext or base64 credentials), keep its last-known
   attribute values, and every mutation should abort with an "Aborting ..."
   error instead of writing defaults.
7. Repeat on both a recent AdGuard Home release (v0.107.x / v1.x) and, if
   available, an older pre-0.107 release to exercise the legacy fallbacks.

### Rollback (in place) ###

No Hubitat re-pairing is needed to roll back — identities are unchanged:

1. In Hubitat, open each driver (Server, Client) and paste the previous 1.1.1
   source over the current one, then **Save**. (Grab the 1.1.1 source from the
   `main` branch of this repository, or `git show <pre-1.2.0-tag>:adguardhome/drivers/AdGuardHome_Server.groovy`.)
2. The app did not change in 1.2.0; roll it back only if you also edited it.
3. Devices keep their preferences, attributes and children. Note that any
   state written by 1.2.0 is plain AdGuard Home configuration — rolling back
   does not undo configuration changes already applied to the server.
4. Package Manager users can alternatively install the previous release from
   the package repository if it is still published.
