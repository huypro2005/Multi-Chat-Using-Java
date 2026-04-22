# Backend Log — Archive Mode (W8-D3)

> Consolidated for project close. Historical deep details moved into docs + tests + knowledge patterns.
> Newest entries kept at top; W1-W6 summarized.

---

## [W8-D3] Project close wrap-up
- Added backend profile endpoints: `GET /api/users/me`, `PATCH /api/users/me` (avatarUrl tristate).
- Added auth endpoint: `POST /api/auth/change-password`.
- Kept user avatar model as `avatarUrl` (public file URL) per finalized model.
- Validation: fullName range, avatar ownership/public checks, password validation + same-as-current guard.
- Fix applied: `Message.systemMetadata` mapping switched to converter-only column mapping to avoid H2 deserialize errors in full test runs.
- Verification: `mvn clean test` passed with 355 tests.

## [W8-D2] Pin Message + Bilateral Block
- Added V14/V15 migrations and services for pin + block flow.
- Extended DTOs and broadcast integration.
- Outcome: 355 tests passing at end of phase.

## [W8-D1] Reactions
- Added reaction aggregate model, toggle flow, STOMP handler, and broadcast support.
- Updated message DTO pipeline for reaction data.

## [W7] Group management + system messages + read receipts
- Completed role-based member actions, owner transfer, system event messages.
- Added read receipt flow and unread count alignment.

## [W6] File module hardening
- Added MIME validation, thumbnail service, cleanup jobs, and hybrid visibility model.
- Added default avatar records and safety checks.

## [ARCHIVE] W1-W5 summary
- W1-W2: auth, JWT, OAuth, refresh + redis rate-limit patterns.
- W3: conversation core and member model.
- W4-W5: websocket message lifecycle, typing/edit/delete consistency.
