from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from jose import JWTError, jwt
from pydantic import BaseModel, Field
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select, or_
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker

APP_ENV = os.getenv("APP_ENV", "development")
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./losgram.db")
JWT_SECRET = os.getenv("JWT_SECRET", "local-development-secret-change-me")
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_MINUTES = int(os.getenv("JWT_EXPIRE_MINUTES", "10080"))
OTP_TTL_MINUTES = int(os.getenv("OTP_TTL_MINUTES", "5"))
DEV_OTP_CODE = os.getenv("DEV_OTP_CODE", "123456")
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_WEBHOOK_SECRET = os.getenv("TELEGRAM_WEBHOOK_SECRET", "")
LOGIN_CODE_TTL_MINUTES = int(os.getenv("LOGIN_CODE_TTL_MINUTES", "5"))

connect_args = {"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {}
engine = create_engine(DATABASE_URL, connect_args=connect_args, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    phone: Mapped[str | None] = mapped_column(String(20), unique=True, index=True, nullable=True)
    losgram_id: Mapped[str | None] = mapped_column(String(10), unique=True, index=True, nullable=True)
    telegram_user_id: Mapped[str | None] = mapped_column(String(32), unique=True, index=True, nullable=True)
    display_name: Mapped[str] = mapped_column(String(80), default="Пользователь")
    username: Mapped[str | None] = mapped_column(String(32), unique=True, index=True, nullable=True)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class OtpCode(Base):
    __tablename__ = "otp_codes"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    phone: Mapped[str] = mapped_column(String(20), index=True)
    code_hash: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    consumed: Mapped[bool] = mapped_column(Boolean, default=False)


class TelegramLoginCode(Base):
    __tablename__ = "telegram_login_codes"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    telegram_user_id: Mapped[str] = mapped_column(String(32), index=True)
    code_hash: Mapped[str] = mapped_column(String(64))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    consumed: Mapped[bool] = mapped_column(Boolean, default=False)
    failed_attempts: Mapped[int] = mapped_column(Integer, default=0)


class Conversation(Base):
    __tablename__ = "conversations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    kind: Mapped[str] = mapped_column(String(16), default="direct")
    title: Mapped[str | None] = mapped_column(String(120), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class Participant(Base):
    __tablename__ = "participants"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)


class Message(Base):
    __tablename__ = "messages"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    conversation_id: Mapped[int] = mapped_column(ForeignKey("conversations.id"), index=True)
    sender_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    body: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


Base.metadata.create_all(bind=engine)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def hash_code(phone: str, code: str) -> str:
    return hashlib.sha256(f"{phone}:{code}".encode()).hexdigest()


def make_token(user: User) -> str:
    now = datetime.now(timezone.utc)
    payload = {"sub": str(user.id), "iat": now, "exp": now + timedelta(minutes=JWT_EXPIRE_MINUTES)}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def user_from_token(token: str, db: Session) -> User:
    try:
        payload = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        user_id = int(payload["sub"])
    except (JWTError, KeyError, ValueError):
        raise HTTPException(status_code=401, detail="Недействительный токен")
    user = db.get(User, user_id)
    if not user:
        raise HTTPException(status_code=401, detail="Пользователь не найден")
    return user


def current_user(authorization: str | None = Header(default=None), db: Session = Depends(get_db)) -> User:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Требуется Bearer-токен")
    return user_from_token(authorization[7:], db)


class PhoneRequest(BaseModel):
    phone: str = Field(min_length=8, max_length=20, pattern=r"^\+[1-9][0-9]{7,14}$")


class VerifyRequest(PhoneRequest):
    code: str = Field(min_length=4, max_length=8)


class TelegramVerifyRequest(BaseModel):
    losgram_id: str = Field(min_length=10, max_length=10, pattern=r"^LG[0-9]{8}$")
    code: str = Field(min_length=6, max_length=6, pattern=r"^[0-9]{6}$")


class ProfileUpdate(BaseModel):
    display_name: str = Field(min_length=1, max_length=80)
    username: str | None = Field(default=None, min_length=3, max_length=32, pattern=r"^[a-zA-Z0-9_]+$")


class DirectConversationRequest(BaseModel):
    user_id: int


class MessageRequest(BaseModel):
    body: str = Field(min_length=1, max_length=10000)


app = FastAPI(title="LosGram API", version="0.1.0", description="Backend API for the LosGram messenger")
origins = [x.strip() for x in os.getenv("CORS_ORIGINS", "*").split(",") if x.strip()]
app.add_middleware(CORSMiddleware, allow_origins=origins, allow_credentials=True, allow_methods=["*"], allow_headers=["*"])


def participant(db: Session, conversation_id: int, user_id: int) -> bool:
    return db.scalar(select(Participant.id).where(
        Participant.conversation_id == conversation_id, Participant.user_id == user_id
    )) is not None


class ConnectionHub:
    def __init__(self):
        self.connections: dict[int, set[WebSocket]] = {}

    async def connect(self, user_id: int, ws: WebSocket):
        await ws.accept()
        self.connections.setdefault(user_id, set()).add(ws)

    def disconnect(self, user_id: int, ws: WebSocket):
        self.connections.get(user_id, set()).discard(ws)

    async def send_to_user(self, user_id: int, payload: dict[str, Any]):
        for ws in list(self.connections.get(user_id, set())):
            await ws.send_json(payload)


hub = ConnectionHub()



def telegram_code_hash(telegram_user_id: str, code: str) -> str:
    return hmac.new(JWT_SECRET.encode(), f"telegram:{telegram_user_id}:{code}".encode(),
                    hashlib.sha256).hexdigest()


def telegram_api(method: str, payload: dict[str, Any]) -> dict[str, Any]:
    if not TELEGRAM_BOT_TOKEN:
        raise HTTPException(status_code=503, detail="Telegram-бот ещё не настроен")
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/{method}",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.loads(response.read().decode())
        if not result.get("ok"):
            raise RuntimeError("Telegram API rejected request")
        return result
    except (urllib.error.URLError, TimeoutError, ValueError, RuntimeError):
        raise HTTPException(status_code=502, detail="Не удалось отправить сообщение через Telegram")


def issue_telegram_code(db: Session, telegram_user_id: str) -> tuple[User, str]:
    user = db.scalar(select(User).where(User.telegram_user_id == telegram_user_id))
    if not user:
        user = User(telegram_user_id=telegram_user_id, display_name="Пользователь")
        for _ in range(10):
            candidate = f"LG{secrets.randbelow(100_000_000):08d}"
            if not db.scalar(select(User.id).where(User.losgram_id == candidate)):
                user.losgram_id = candidate
                break
        if not user.losgram_id:
            raise HTTPException(status_code=503, detail="Не удалось выдать LosGram ID")
        db.add(user)
        db.flush()
    db.query(TelegramLoginCode).filter(
        TelegramLoginCode.telegram_user_id == telegram_user_id,
        TelegramLoginCode.consumed.is_(False)).update(
            {"consumed": True}, synchronize_session=False)
    code = f"{secrets.randbelow(1_000_000):06d}"
    db.add(TelegramLoginCode(
        telegram_user_id=telegram_user_id,
        code_hash=telegram_code_hash(telegram_user_id, code),
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=LOGIN_CODE_TTL_MINUTES)))
    db.commit()
    return user, code


def send_telegram_code(chat_id: int, user: User, code: str) -> None:
    telegram_api("sendMessage", {"chat_id": chat_id, "text":
        f"LosGram — данные для входа\\n\\nВаш виртуальный номер: {user.losgram_id}\\n"
        f"Одноразовый код: {code}\\n\\nКод действует {LOGIN_CODE_TTL_MINUTES} минут. Никому его не пересылайте."})

@app.get("/health")
def health():
    return {"status": "ok", "service": "losgram-api", "version": "0.1.0"}



@app.post("/telegram/webhook")
async def telegram_webhook(update: dict[str, Any],
    secret: str | None = Header(default=None, alias="X-Telegram-Bot-Api-Secret-Token"),
    db: Session = Depends(get_db)):
    if not TELEGRAM_WEBHOOK_SECRET or not hmac.compare_digest(secret or "", TELEGRAM_WEBHOOK_SECRET):
        raise HTTPException(status_code=401, detail="Недействительный webhook secret")
    message = update.get("message") or {}
    chat = message.get("chat") or {}
    sender = message.get("from") or {}
    command_text = (message.get("text") or "").strip()
    if chat.get("type") != "private" or not sender.get("id") or not command_text.startswith("/"):
        return {"ok": True}
    command = command_text.split()[0].split("@")[0].lower()
    if command not in ("/start", "/login"):
        telegram_api("sendMessage", {"chat_id": chat["id"], "text": "Отправьте /start или /login для получения данных входа."})
        return {"ok": True}
    user, code = issue_telegram_code(db, str(sender["id"]))
    send_telegram_code(int(chat["id"]), user, code)
    return {"ok": True}


@app.post("/auth/telegram/verify")
def verify_telegram_code(payload: TelegramVerifyRequest, db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.losgram_id == payload.losgram_id))
    if not user or not user.telegram_user_id:
        raise HTTPException(status_code=400, detail="Неверный LosGram ID или код")
    record = db.scalar(select(TelegramLoginCode).where(
        TelegramLoginCode.telegram_user_id == user.telegram_user_id,
        TelegramLoginCode.consumed.is_(False)).order_by(TelegramLoginCode.id.desc()))
    if not record:
        raise HTTPException(status_code=400, detail="Код не найден. Запросите новый в Telegram-боте.")
    if record.failed_attempts >= 5:
        record.consumed = True
        db.commit()
        raise HTTPException(status_code=429, detail="Слишком много попыток. Запросите новый код.")
    expected = telegram_code_hash(user.telegram_user_id, payload.code)
    if not hmac.compare_digest(record.code_hash, expected):
        record.failed_attempts += 1
        db.commit()
        raise HTTPException(status_code=400, detail="Неверный LosGram ID или код")
    expires = record.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if expires < datetime.now(timezone.utc):
        record.consumed = True
        db.commit()
        raise HTTPException(status_code=400, detail="Код истёк. Запросите новый в боте.")
    record.consumed = True
    db.commit()
    return {"access_token": make_token(user), "token_type": "bearer",
            "user": {"id": user.id, "losgram_id": user.losgram_id,
                     "display_name": user.display_name, "username": user.username}}


@app.post("/auth/request-code")
def request_code(payload: PhoneRequest, db: Session = Depends(get_db)):
    # Development mode exposes a fixed code for testing only. Production must connect an SMS provider.
    if APP_ENV != "development":
        raise HTTPException(status_code=503, detail="SMS-провайдер ещё не настроен")
    code = DEV_OTP_CODE
    db.add(OtpCode(phone=payload.phone, code_hash=hash_code(payload.phone, code),
                   expires_at=datetime.now(timezone.utc) + timedelta(minutes=OTP_TTL_MINUTES)))
    db.commit()
    return {"status": "code_sent", "expires_in_seconds": OTP_TTL_MINUTES * 60, "dev_code": code}


@app.post("/auth/verify")
def verify_code(payload: VerifyRequest, db: Session = Depends(get_db)):
    now = datetime.now(timezone.utc)
    record = db.scalar(select(OtpCode).where(
        OtpCode.phone == payload.phone, OtpCode.consumed.is_(False)
    ).order_by(OtpCode.id.desc()))
    if not record or record.code_hash != hash_code(payload.phone, payload.code):
        raise HTTPException(status_code=400, detail="Неверный код")
    expires = record.expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if expires < now:
        raise HTTPException(status_code=400, detail="Срок действия кода истёк")
    record.consumed = True
    user = db.scalar(select(User).where(User.phone == payload.phone))
    if not user:
        user = User(phone=payload.phone)
        db.add(user)
    db.commit()
    db.refresh(user)
    return {"access_token": make_token(user), "token_type": "bearer",
            "user": {"id": user.id, "phone": user.phone, "display_name": user.display_name,
                     "username": user.username}}


@app.get("/me")
def me(user: User = Depends(current_user)):
    return {"id": user.id, "losgram_id": user.losgram_id, "phone": user.phone, "display_name": user.display_name,
            "username": user.username, "avatar_url": user.avatar_url}


@app.patch("/me")
def update_me(payload: ProfileUpdate, db: Session = Depends(get_db), user: User = Depends(current_user)):
    user.display_name = payload.display_name
    user.username = payload.username
    try:
        db.commit()
    except Exception:
        db.rollback()
        raise HTTPException(status_code=409, detail="Это имя пользователя уже занято")
    return {"id": user.id, "display_name": user.display_name, "username": user.username}


@app.get("/users/search")
def search_users(q: str, db: Session = Depends(get_db), user: User = Depends(current_user)):
    term = q.strip()
    if len(term) < 2:
        return {"items": []}
    rows = db.scalars(select(User).where(
        User.id != user.id,
        or_(User.username.ilike(f"%{term}%"), User.display_name.ilike(f"%{term}%"))
    ).limit(20)).all()
    return {"items": [{"id": x.id, "display_name": x.display_name, "username": x.username,
                       "avatar_url": x.avatar_url} for x in rows]}


@app.post("/conversations/direct")
def create_direct(payload: DirectConversationRequest, db: Session = Depends(get_db),
                  user: User = Depends(current_user)):
    if payload.user_id == user.id or not db.get(User, payload.user_id):
        raise HTTPException(status_code=404, detail="Пользователь не найден")
    mine = select(Participant.conversation_id).where(Participant.user_id == user.id)
    theirs = select(Participant.conversation_id).where(Participant.user_id == payload.user_id)
    existing = db.scalar(select(Conversation.id).where(
        Conversation.id.in_(mine), Conversation.id.in_(theirs), Conversation.kind == "direct"
    ))
    if existing:
        return {"id": existing, "kind": "direct"}
    conversation = Conversation(kind="direct")
    db.add(conversation)
    db.flush()
    db.add_all([Participant(conversation_id=conversation.id, user_id=user.id),
                Participant(conversation_id=conversation.id, user_id=payload.user_id)])
    db.commit()
    return {"id": conversation.id, "kind": "direct"}


@app.get("/conversations")
def list_conversations(db: Session = Depends(get_db), user: User = Depends(current_user)):
    memberships = db.scalars(select(Participant).where(Participant.user_id == user.id)).all()
    result = []
    for membership in memberships:
        conv = db.get(Conversation, membership.conversation_id)
        members = db.scalars(select(Participant).where(Participant.conversation_id == conv.id)).all()
        others = [db.get(User, m.user_id) for m in members if m.user_id != user.id]
        last = db.scalar(select(Message).where(Message.conversation_id == conv.id)
                         .order_by(Message.id.desc()).limit(1))
        result.append({"id": conv.id, "kind": conv.kind, "title": conv.title,
                       "members": [{"id": x.id, "display_name": x.display_name, "username": x.username} for x in others],
                       "last_message": ({"id": last.id, "sender_id": last.sender_id, "body": last.body,
                                         "created_at": last.created_at.isoformat()} if last else None)})
    return {"items": result}


@app.get("/conversations/{conversation_id}/messages")
def list_messages(conversation_id: int, before_id: int | None = None, limit: int = 50,
                  db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not participant(db, conversation_id, user.id):
        raise HTTPException(status_code=403, detail="Нет доступа к чату")
    query = select(Message).where(Message.conversation_id == conversation_id)
    if before_id:
        query = query.where(Message.id < before_id)
    rows = db.scalars(query.order_by(Message.id.desc()).limit(min(max(limit, 1), 100))).all()
    rows.reverse()
    return {"items": [{"id": x.id, "conversation_id": x.conversation_id, "sender_id": x.sender_id,
                       "body": x.body, "created_at": x.created_at.isoformat()} for x in rows]}


@app.post("/conversations/{conversation_id}/messages")
async def send_message(conversation_id: int, payload: MessageRequest,
                       db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not participant(db, conversation_id, user.id):
        raise HTTPException(status_code=403, detail="Нет доступа к чату")
    message = Message(conversation_id=conversation_id, sender_id=user.id, body=payload.body)
    db.add(message)
    db.commit()
    db.refresh(message)
    data = {"type": "message", "id": message.id, "conversation_id": conversation_id,
            "sender_id": user.id, "body": message.body, "created_at": message.created_at.isoformat()}
    members = db.scalars(select(Participant.user_id).where(Participant.conversation_id == conversation_id)).all()
    for member_id in members:
        await hub.send_to_user(member_id, data)
    return data


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket, token: str):
    db = SessionLocal()
    try:
        try:
            user = user_from_token(token, db)
        except HTTPException:
            await ws.close(code=1008)
            return
        await hub.connect(user.id, ws)
        try:
            while True:
                await ws.receive_text()
        except WebSocketDisconnect:
            hub.disconnect(user.id, ws)
    finally:
        db.close()
