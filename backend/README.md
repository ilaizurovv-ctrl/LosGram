# LosGram API

FastAPI backend starter for the LosGram Android messenger.

## Run locally

1. Install Python 3.12+ and Docker.
2. Copy `.env.example` to `.env`.
3. From this directory run `docker compose up --build`.
4. Open `http://localhost:8000/docs`; health check: `/health`.

The Compose stack starts PostgreSQL and the API. For a local non-Docker run, install requirements and set `DATABASE_URL=sqlite:///./losgram.db`, then run `uvicorn app.main:app --reload`.

## Telegram-based LosGram ID login

The app's intended login flow uses a Telegram bot instead of a phone number:

1. The user opens the official LosGram bot in a private Telegram chat and sends `/start`.
2. The bot privately sends a permanent virtual ID such as `LG12345678` and a random six-digit, one-time code.
3. The app submits both values to `POST /auth/telegram/verify` and receives a JWT.
4. For later sign-ins, the user sends `/login` to the same bot; the ID stays the same and a fresh code is issued. Previous unused codes are invalidated.

Configure `TELEGRAM_BOT_TOKEN` and a long random `TELEGRAM_WEBHOOK_SECRET` as server environment secrets (never commit real values). Set the Telegram webhook to `https://YOUR_API_HOST/telegram/webhook` and provide that secret as Telegram's `secret_token`. The bot token is created via Telegram's official BotFather. The webhook endpoint accepts only private-chat `/start` and `/login` commands and checks the Telegram webhook secret header.

The code is stored as an HMAC hash, expires after `LOGIN_CODE_TTL_MINUTES` (default 5), is single-use, and is invalidated when a new code is issued. Five incorrect attempts consume the current code. The Telegram account ID is obtained from Telegram's authenticated update; the bot does not need the user's phone number.

**Important:** this backend change does not yet connect the Android screens to the new endpoint. The current Android app remains a UI prototype. Also, `Base.metadata.create_all` does not migrate existing databases: before upgrading a database created by an earlier backend build, create and apply a proper migration for the new nullable `losgram_id` and `telegram_user_id` columns and the `telegram_login_codes` table. For a brand-new database, the tables are created automatically.

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
