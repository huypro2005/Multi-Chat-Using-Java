# Frontend Log — Archive Mode (W8-D3)

> Consolidated for final release. Keep only high-signal milestones.

---

## [W8-D3] Project close wrap-up
- Added `/profile` page with tabs: info, password, account.
- Added `/settings` page with tabs: blocked users and notification stub.
- Added UX polish: `NotFoundPage`, `ErrorBoundary`, shared `Skeleton`, shared `EmptyState`.
- Wired router and conversation layout shortcuts to profile/settings.
- Updated user hooks/api for current-user, update-profile, and change-password.
- Verified frontend quality gates: `npm run build` and `npm run lint` pass.

## [W8-D2] Pin + block UI
- Added pin actions in message UI and pinned banner navigation.
- Added block/unblock controls and blocked users list integration.

## [W8-D1] Reactions
- Added reaction trigger UI + emoji picker integration.
- Added realtime reaction state update handling.

## [W7] Group UX and system message rendering
- Added group management dialogs and role-aware actions.
- Added system message renderer and subscription handlers.
- Switched avatar display to public URL-first rendering in key flows.

## [W6] File upload UX
- Added attachment upload, preview, progress/error handling, and attachment cards.

## [ARCHIVE] W1-W5 summary
- W1-W2: auth pages and token-state scaffolding.
- W3: conversation layout + protected route baseline.
- W4-W5: websocket-driven message lifecycle (send/edit/delete/typing/read).
