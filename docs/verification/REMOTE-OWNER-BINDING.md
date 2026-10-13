# Remote session owner binding

Related: #217, #219, #220 and #187.

## Reproduced problem and protocol

Changing the token registry's owner for an existing token previously let an
already authenticated Java client create a project under the new owner. A
Quarkus integration test reproduced the write instead of the expected rejection.
Comparing the owner's state after rejection is essential: rejecting only the
response would leave that foreign write committed.

Authenticated API responses now identify their owner through
`X-Timekeeper-Owner`, using canonical unpadded base64url of UTF-8. The Java client
binds to the first returned owner, including empty pages and 204 responses, and
sends that value on subsequent requests. The server compares it to the current
authenticated principal before reading resources or performing mutations.
Mismatch returns 401 `OWNER_CHANGED`; malformed or duplicate headers return 400.
The binding only restricts access and never grants another owner's permissions.
Stateless consumers may omit the header. Issued tokens should not be reassigned;
the binding defends existing sessions if registry configuration changes anyway.

The client retains its binding after failure. Restoring the original owner can
recover that session. An intentional account switch starts a new client session.
Profile/resource owner IDs also validate response consistency. Metadata checks
both profile owner and version. Long polling rechecks the principal while it
waits; the disposable reply gate forwards the binding in both directions.

## Evidence, 2026-10-13

- The server reactor passed **61 tests**: three domain, six service, sixteen HTTP
  client and thirty-six server tests, with no failures/errors/skips. New actual
  Quarkus tests reject owner reassignment before a foreign write and compare both
  owners' states. They verify explicit new sessions, recovery of the original
  session, Unicode owners, binding from a first empty page, malformed/duplicate
  headers and unchanged read-only restrictions.
- The OpenAPI contract and existing boundaries passed all **11 checks** using
  the isolated contract environment.
- Packaged acceptance passed all **12 HTTP cases**, durable/forced restart,
  exact command replay, token revocation and long polling on the owner-binding
  server candidate. The current candidate is clean committed source `5cbbdc0`,
  ZIP SHA-256 `9addfac8e944f4484e4c61b050fb67e3bf554806a3da353eb8df403873641e4d`.
  Its server code matches the earlier accepted candidate; the new provenance
  includes the corrected parent container recovery test and CSV test boundary.
- Installed acceptance passed **113 main tests plus seven restart/account/switch
  tests**, with no failures/errors/skips. All nine installed phases and the new
  `phase.owner.binding` marker passed. The retained account-B runtime reassigns its
  selected token to owner C while an actual task editor has a draft. It must
  become unavailable through polling, preserve its B identity/cache and closed
  local storage, reject writes/export without changing either owner's state,
  and retain the enabled draft. An explicit fresh client may use C. Restoring B
  recovered the existing session and saved that same draft once. The final
  installed run used the current candidate/checksum recorded above.

Shared module counts across reactors overlap and are not summed. The first
installed attempt hit an undeclared Gson return type in a test method reference;
the test now uses the existing CSV boundary instead of adding that dependency.
CI must independently verify the final commit before clearing this acceptance
gate. These functional tests do not certify a clean Error Log.

## Remaining release work

Native label/report/CSV preference flows, remaining inline/other editor failures
and uncertain operations remain in #220. Web #221 and web synchronization #219,
Mylyn Error Log #243, accepted tagged artifacts and public GitHub p2 installation
remain required before 2.0.
