# LosGram API

FastAPI backend starter for the LosGram Android messenger.

## Run locally

1. Install Python 3.12+ and Docker.
2. Copy `.env.example` to `.env`.
3. From this directory run `docker compose up --build`.
4. Open `http://localhost:8000/docs`; health check: `/health`.

The Compose stack starts PostgreSQL and the API. For a local non-Docker run, install requirements and set `DATABASE_URL=sqlite:///./losgram.db`, then run `uvicorn app.main:app --reload`.

## Development phone login

In `APP_ENV=development`, `POST /auth/request-code` returns the configured `DEV_OTP_CODE` (default 123456) to simplify local integration. This is strictly a development shortcut, not real SMS delivery.

**Before any public deployment:** set `APP_ENV=production`, use a long random `JWT_SECRET`, configure an actual SMS provider and implement its delivery adapter. Production intentionally refuses to issue login codes until that provider is integrated. Add rate limiting, monitoring, database migrations, backups, and security review before handling real users.

## API overview

- `POST /auth/request-code` — request a phone verification code.
- `POST /auth/verify` — verify code; creates account on first login and returns JWT.
- `GET /me`, `PATCH /me` — read/update profile.
- `GET /users/search?q=...` — find users.
- `POST /conversations/direct` — create or reuse a direct chat.
- `GET /conversations` — list chats.
- `GET /conversations/{id}/messages` — message history.
- `POST /conversations/{id}/messages` — send a message.
- `WS /ws?token=JWT` — live message events for connected users.

## Current limitations

This is an MVP foundation, not production-ready infrastructure. WebSocket fan-out is in-process and requires a single API instance. OTP delivery is not connected to an SMS provider. There are no media uploads, groups/channels, end-to-end encryption, read receipts, push notifications, or database migrations yet. Do not use it for sensitive conversations.
