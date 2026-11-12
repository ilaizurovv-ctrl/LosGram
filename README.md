# LosGram

Android messenger starter built with Kotlin and Jetpack Compose.

## Current status

This repository contains the first UI prototype only. It includes a dark messenger-style interface, LosGram branding, Contacts/Chats/Calls tabs, search over demo chats, and sample conversations.

## Android project

Open this repository folder in Android Studio, allow Gradle sync, then run the `app` configuration on an Android device or emulator.

- Package: `com.losgram.app`
- Version: `0.1.0`
- Min Android: API 24
- Compile SDK: 35

## Not implemented yet

Real accounts, backend API, persistent messages, file uploads, push notifications, voice/video calls, and end-to-end encryption. Do not use this prototype for real private conversations.

## Planned architecture

- Android: Kotlin + Jetpack Compose
- Backend: Python + FastAPI
- Database: PostgreSQL
- Realtime: WebSocket
- Push: Firebase Cloud Messaging
- Media: S3-compatible object storage

Development will proceed in small MVP stages, starting with authentication and one-to-one messaging.

## Backend (new)

A FastAPI + PostgreSQL backend foundation is in `backend/`. It includes phone-code authentication scaffolding, JWT sessions, user profiles/search, direct conversations, message history, and WebSocket message events.

See [backend/README.md](backend/README.md) for setup and API details. The development OTP shortcut is not real SMS; production login is deliberately disabled until an SMS provider is configured. This backend is an early MVP foundation and has not yet been deployed or security-reviewed.
