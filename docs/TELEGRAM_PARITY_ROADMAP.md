# LosGram — Telegram feature parity plan

Goal: deliver a native Android messenger branded LosGram, backed by the LosGram API, with behavior and interaction patterns aligned as closely as legally and technically possible with the current Telegram clients. Do not claim parity until each item is implemented and verified.

## Product principles
- Keep LosGram identity, backend, package identity, and signing continuity.
- Reproduce user-visible workflows and interaction details; do not copy Telegram trademarks, proprietary assets, or closed-source code.
- Prefer server-authoritative state so chats, folders, pins, read state, and profile changes stay consistent across devices.
- Preserve offline drafts and pending sends; make network state and failures visible.
- Every feature marked complete must have backend coverage, Android UI, error/loading/empty states, and automated verification.

## P0 — messaging foundation
- [ ] Authentication, session refresh/revocation, account recovery and multi-device session management.
- [ ] Direct chats: stable conversation identity, message history pagination, send/edit/delete, replies, forwards, pins, read receipts, typing and presence.
- [ ] Reliable delivery: idempotency keys, retry queue, offline outbox, attachment upload lifecycle.
- [ ] Real-time event transport (WebSocket or equivalent) with reconnect, resume cursor and polling fallback.
- [ ] Message types: text, links/previews, photos, video, files, voice messages, video messages, locations, contacts, stickers and animated media.
- [ ] Search across messages and users; server-side pagination and filters.

## P1 — Telegram-style organization and social features
- [ ] Chat folders with include/exclude rules, unread counters and reorder.
- [ ] Archive, mute, pin limits, drafts, Saved Messages and per-chat notification settings.
- [ ] Groups: roles, permissions, invite links, topics, slow mode, moderation and member search.
- [ ] Channels: public/private usernames, subscriber management, posts, comments/discussion groups and statistics.
- [ ] Reactions, custom emoji/stickers, polls, quizzes, scheduled messages and message effects.
- [ ] Stories with privacy audience, reactions, replies, expiry and viewer list.

## P2 — calls, bots and platform capabilities
- [ ] One-to-one and group voice/video calls, call signaling, permissions and reconnect.
- [ ] Bot identity distinct from owner identity; Bot API authentication, update delivery, webhooks, commands and deep links.
- [ ] BotFather as an ordinary bot conversation with command-driven flows and inline keyboards; no fake command list.
- [ ] Mini-app webview bridge with scoped permissions and payment safety.
- [ ] Login QR/deep links, contacts sync, device/session list and privacy controls.

## P3 — finish and release quality
- [ ] Accessibility, localization, dynamic type, dark/light themes and tablet layouts.
- [ ] Performance profiling, startup/memory/network budgets and large-chat stress tests.
- [ ] Security review: authorization on every object, rate limits, abuse controls, encrypted transport, secure token storage and backup policy.
- [ ] Automated API, database migration, Compose UI and end-to-end tests.
- [ ] Release signing key continuity, reproducible release workflow, crash reporting and staged rollout.

## Current known gaps from repository audit
- Android currently polls messages periodically rather than using a resumable real-time event stream.
- BotFather is currently a management panel embedded in a chat-shaped screen; listed slash commands are not implemented as a conversational command state machine.
- Existing bot API surface is partial and does not yet provide full bot identity/update routing semantics.
- Several settings rows are placeholders rather than complete settings workflows.
- A successful debug APK workflow verifies compilation only; it does not prove feature parity or release readiness.

## Definition of done
A feature is complete only when the Android workflow is implemented, API authorization and persistence are in place, empty/loading/error/offline states are handled, automated tests pass, and a built APK contains the change. Never mark a feature complete based only on a successful Gradle build.