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
from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, create_engine, select, or_, func
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker

APP_ENV = os.getenv("APP_ENV", "development")
DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./losgram.db")
if DATABASE_URL.startswith("postgres://"):
    DATABASE_URL = "postgresql+psycopg://" + DATABASE_URL[len("postgres://"):]
elif DATABASE_URL.startswith("postgresql://"):
    DATABASE_URL = "postgresql+psycopg://" + DATABASE_URL[len("postgresql://"):]
JWT_SECRET = os.getenv("JWT_SECRET", "local-development-secret-change-me")
JWT_ALGORITHM = "HS256"
JWT_EXPIRE_MINUTES = int(os.getenv("JWT_EXPIRE_MINUTES", "10080"))
OTP_TTL_MINUTES = int(os.getenv("OTP_TTL_MINUTES", "5"))
DEV_OTP_CODE = os.getenv("DEV_OTP_CODE", "123456")
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_WEBHOOK_SECRET = os.getenv("TELEGRAM_WEBHOOK_SECRET", "")
PUBLIC_BASE_URL = os.getenv("PUBLIC_BASE_URL", "").rstrip("/")
ADMIN_TELEGRAM_USER_ID = os.getenv("ADMIN_TELEGRAM_USER_ID", "")
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
    virtual_number: Mapped[str | None] = mapped_column(String(16), unique=True, index=True, nullable=True)
    telegram_user_id: Mapped[str | None] = mapped_column(String(32), unique=True, index=True, nullable=True)
    display_name: Mapped[str] = mapped_column(String(80), default="Пользователь")
    username: Mapped[str | None] = mapped_column(String(32), unique=True, index=True, nullable=True)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    bio: Mapped[str | None] = mapped_column(String(160), nullable=True)
    settings_json: Mapped[str] = mapped_column(Text, default="{}")
    business_name: Mapped[str | None] = mapped_column(String(120), nullable=True)
    business_description: Mapped[str | None] = mapped_column(String(500), nullable=True)
    premium_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    stars_balance: Mapped[int] = mapped_column(Integer, default=0)
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
    reply_to_id: Mapped[int | None] = mapped_column(ForeignKey("messages.id"), nullable=True)
    edited_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))



class MessageReaction(Base):
    __tablename__ = "message_reactions"
    __table_args__ = (UniqueConstraint("message_id", "user_id", name="uq_message_reaction_user"),)
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    message_id: Mapped[int] = mapped_column(ForeignKey("messages.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    emoji: Mapped[str] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class CollectibleGift(Base):
    __tablename__ = "collectible_gifts"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(80))
    model: Mapped[str] = mapped_column(String(80))
    background: Mapped[str] = mapped_column(String(80))
    pattern: Mapped[str] = mapped_column(String(80))
    number: Mapped[int] = mapped_column(Integer, unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class NFTListing(Base):
    __tablename__ = "nft_listings"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    gift_id: Mapped[int] = mapped_column(ForeignKey("collectible_gifts.id"), unique=True, index=True)
    seller_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    buyer_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), nullable=True, index=True)
    price: Mapped[int] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(16), default="active", index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    sold_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Bot(Base):
    __tablename__ = "bots"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(64))
    username: Mapped[str] = mapped_column(String(32), unique=True, index=True)
    description: Mapped[str] = mapped_column(String(512), default="")
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    token_prefix: Mapped[str] = mapped_column(String(24))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    webhook_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


class BotUpdate(Base):
    __tablename__ = "bot_updates"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    bot_id: Mapped[int] = mapped_column(ForeignKey("bots.id"), index=True)
    update_json: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))


Base.metadata.create_all(bind=engine)
from sqlalchemy import inspect
# Upgrade legacy users tables in-place; create_all only creates missing tables.
_user_columns = {column["name"] for column in inspect(engine).get_columns("users")}
_user_column_types = {
    "phone": "VARCHAR(20)",
    "losgram_id": "VARCHAR(10)",
    "telegram_user_id": "VARCHAR(32)",
    "display_name": "VARCHAR(80) DEFAULT 'Пользователь'",
    "username": "VARCHAR(32)",
    "avatar_url": "VARCHAR(500)",
    "bio": "VARCHAR(160)",
    "settings_json": "TEXT DEFAULT '{}'",
    "business_name": "VARCHAR(120)",
    "business_description": "VARCHAR(500)",
    "premium_until": "TIMESTAMP WITH TIME ZONE",
    "stars_balance": "INTEGER DEFAULT 0",
    "created_at": "TIMESTAMP WITH TIME ZONE",
    "virtual_number": "VARCHAR(16)",
}
with engine.begin() as connection:
    for _column, _sql_type in _user_column_types.items():
        if _column not in _user_columns:
            connection.exec_driver_sql(f"ALTER TABLE users ADD COLUMN {_column} {_sql_type}")
    # Legacy schema stored users.created_at as BIGINT (Unix timestamp).
    # Convert it to timestamptz so SQLAlchemy can insert new Telegram users.
    if engine.dialect.name == "postgresql":
        _created_at_info = next((c for c in inspect(engine).get_columns("users") if c["name"] == "created_at"), None)
        if _created_at_info and isinstance(_created_at_info["type"], BigInteger):
            connection.exec_driver_sql("""
                ALTER TABLE users
                ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE
                USING CASE
                    WHEN created_at IS NULL THEN NOW()
                    WHEN ABS(created_at) >= 100000000000
                        THEN to_timestamp(created_at / 1000.0)
                    ELSE to_timestamp(created_at)
                END
            """)
    # Legacy schema may have username NOT NULL, but Telegram users can have no username.
    if engine.dialect.name == "postgresql":
        connection.exec_driver_sql("ALTER TABLE users ALTER COLUMN username DROP NOT NULL")
        # Telegram-created accounts do not use the legacy password login field.
        connection.exec_driver_sql("ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL")
    for _column in ("losgram_id", "telegram_user_id", "username", "virtual_number"):
        connection.exec_driver_sql(
            f"CREATE UNIQUE INDEX IF NOT EXISTS ix_users_{_column} ON users ({_column})"
        )
    # Upgrade legacy messages tables created before conversations were introduced.
    _message_columns = {column["name"] for column in inspect(engine).get_columns("messages")}
    for _column, _sql_type in {"reply_to_id": "INTEGER", "edited_at": "TIMESTAMP WITH TIME ZONE", "deleted_at": "TIMESTAMP WITH TIME ZONE"}.items():
        if _column not in _message_columns:
            connection.exec_driver_sql(f"ALTER TABLE messages ADD COLUMN {_column} {_sql_type}")
    # Legacy messages.created_at was stored as BIGINT; the ORM now uses timestamptz.
    _message_created_at = next((c for c in inspect(engine).get_columns("messages") if c["name"] == "created_at"), None)
    if _message_created_at and isinstance(_message_created_at["type"], BigInteger):
        connection.exec_driver_sql("""
            ALTER TABLE messages
            ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE
            USING CASE
                WHEN created_at IS NULL THEN NOW()
                WHEN ABS(created_at) >= 100000000000
                    THEN to_timestamp(created_at / 1000.0)
                ELSE to_timestamp(created_at)
            END
        """)
    if "conversation_id" not in _message_columns:
        _global_id = connection.exec_driver_sql(
            "SELECT id FROM conversations WHERE kind = 'global' ORDER BY id LIMIT 1"
        ).scalar()
        if _global_id is None:
            _global_id = connection.exec_driver_sql(
                "INSERT INTO conversations (kind, title, created_at) VALUES ('global', 'LosGram', NOW()) RETURNING id"
            ).scalar()
        connection.exec_driver_sql("ALTER TABLE messages ADD COLUMN conversation_id INTEGER")
        connection.exec_driver_sql(
            "UPDATE messages SET conversation_id = %s WHERE conversation_id IS NULL" % int(_global_id)
        )
        connection.exec_driver_sql(
            "ALTER TABLE messages ALTER COLUMN conversation_id SET NOT NULL"
        )
    connection.exec_driver_sql(
        "CREATE INDEX IF NOT EXISTS ix_messages_conversation_id ON messages (conversation_id)"
    )
    # Legacy direct-message schema required recipient_id; conversations now identify recipients.
    if engine.dialect.name == "postgresql" and "recipient_id" in _message_columns:
        connection.exec_driver_sql("ALTER TABLE messages ALTER COLUMN recipient_id DROP NOT NULL")


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
    virtual_number: str = Field(min_length=13, max_length=13, pattern=r"^\+447[0-9]{9}$")
    code: str = Field(min_length=6, max_length=6, pattern=r"^[0-9]{6}$")


class ProfileUpdate(BaseModel):
    display_name: str | None = Field(default=None, min_length=1, max_length=80)
    username: str | None = Field(default=None, min_length=3, max_length=32, pattern=r"^[a-zA-Z0-9_]+$")
    bio: str | None = Field(default=None, max_length=160)
    avatar_url: str | None = Field(default=None, max_length=500)


class SettingsUpdate(BaseModel):
    notifications_enabled: bool | None = None
    show_phone: bool | None = None
    show_last_seen: bool | None = None
    theme: str | None = Field(default=None, pattern=r"^(dark|light|system)$")


class BusinessProfileUpdate(BaseModel):
    business_name: str = Field(min_length=2, max_length=120)
    business_description: str | None = Field(default=None, max_length=500)


class AdminGiftGrantRequest(BaseModel):
    user_id: int
    name: str = Field(min_length=1, max_length=80)
    model: str = Field(min_length=1, max_length=80)
    background: str = Field(min_length=1, max_length=80)
    pattern: str = Field(min_length=1, max_length=80)


class NFTListingCreateRequest(BaseModel):
    price: int = Field(ge=1, le=10000000)


class AdminGrantRequest(BaseModel):
    user_id: int
    stars_delta: int = Field(default=0, ge=-1000000, le=1000000)
    premium_days: int = Field(default=0, ge=-36500, le=36500)



class BotCreateRequest(BaseModel):
    name: str = Field(min_length=2, max_length=64)
    username: str = Field(min_length=5, max_length=32, pattern=r"^[a-zA-Z][a-zA-Z0-9_]{3,30}bot$")
    description: str = Field(default="", max_length=512)


class BotWebhookRequest(BaseModel):
    url: str | None = Field(default=None, max_length=500)


class BotSendMessageRequest(BaseModel):
    conversation_id: int
    text: str = Field(min_length=1, max_length=10000)


class DirectConversationRequest(BaseModel):
    user_id: int


class MessageRequest(BaseModel):
    body: str = Field(min_length=1, max_length=10000)
    reply_to_id: int | None = None


class MessageEditRequest(BaseModel):
    body: str = Field(min_length=1, max_length=10000)


app = FastAPI(title="LosGram API", version="0.1.0", description="Backend API for the LosGram messenger")


@app.on_event("startup")
def register_telegram_webhook():
    if TELEGRAM_BOT_TOKEN and TELEGRAM_WEBHOOK_SECRET and PUBLIC_BASE_URL:
        telegram_api("setWebhook", {
            "url": f"{PUBLIC_BASE_URL}/telegram/webhook",
            "secret_token": TELEGRAM_WEBHOOK_SECRET,
            "allowed_updates": ["message"],
        })


origins = [x.strip() for x in os.getenv("CORS_ORIGINS", "*").split(",") if x.strip()]
app.add_middleware(CORSMiddleware, allow_origins=origins, allow_credentials=True, allow_methods=["*"], allow_headers=["*"])


def participant(db: Session, conversation_id: int, user_id: int) -> bool:
    return db.scalar(select(Participant.id).where(
        Participant.conversation_id == conversation_id, Participant.user_id == user_id
    )) is not None


def ensure_saved_chat(db: Session, user: User) -> Conversation:
    conversation = db.scalar(select(Conversation).where(
        Conversation.kind == "saved",
        Conversation.title == f"saved:{user.id}"
    ).order_by(Conversation.id))
    if not conversation:
        conversation = Conversation(kind="saved", title=f"saved:{user.id}")
        db.add(conversation)
        db.flush()
    if not participant(db, conversation.id, user.id):
        db.add(Participant(conversation_id=conversation.id, user_id=user.id))
        db.commit()
    return conversation


def ensure_global_chat(db: Session, user: User) -> Conversation:
    conversation = db.scalar(select(Conversation).where(Conversation.kind == "global").order_by(Conversation.id))
    if not conversation:
        conversation = Conversation(kind="global", title="LosGram")
        db.add(conversation)
        db.flush()
    if not participant(db, conversation.id, user.id):
        db.add(Participant(conversation_id=conversation.id, user_id=user.id))
        db.commit()
    return conversation

def add_saved_system_message(db: Session, user: User, body: str) -> None:
    """Append an account activity notice to this user's private Saved Messages chat."""
    conversation = ensure_saved_chat(db, user)
    db.add(Message(conversation_id=conversation.id, sender_id=user.id, body=body))
    db.commit()


def account_event_time() -> str:
    return datetime.now(timezone.utc).strftime("%d.%m.%Y в %H:%M UTC")


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
        db.add(user)
        db.flush()
    if not user.virtual_number:
        for _ in range(30):
            candidate = f"+447{secrets.randbelow(1_000_000_000):09d}"
            if not db.scalar(select(User.id).where(User.virtual_number == candidate)):
                user.virtual_number = candidate
                break
        if not user.virtual_number:
            raise HTTPException(status_code=503, detail="Не удалось выдать виртуальный номер")
    db.query(TelegramLoginCode).filter(
        TelegramLoginCode.telegram_user_id == telegram_user_id,
        TelegramLoginCode.consumed.is_(False)).update(
            {"consumed": True}, synchronize_session=False)
    code = f"{secrets.randbelow(1_000_000):06d}"
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=LOGIN_CODE_TTL_MINUTES)
    db.add(TelegramLoginCode(
        telegram_user_id=telegram_user_id,
        code_hash=telegram_code_hash(telegram_user_id, code),
        expires_at=expires_at))
    db.commit()
    add_saved_system_message(
        db, user,
        "🔐 Вход в аккаунт LosGram\\n\\n"
        f"Код подтверждения: {code}\\n"
        f"Время запроса: {account_event_time()}\\n"
        f"Код действует до {expires_at.strftime('%H:%M UTC')}.\\n\\n"
        "Если это были не вы — никому не сообщайте код и смените доступ к аккаунту."
    )
    return user, code


def send_telegram_code(chat_id: int, user: User, code: str) -> None:
    telegram_api("sendMessage", {"chat_id": chat_id, "text":
        f"LosGram — данные для входа\\n\\nВаш номер профиля: {user.virtual_number}\\n"
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
    user = db.scalar(select(User).where(User.virtual_number == payload.virtual_number))
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
            "user": {"id": user.id, "virtual_number": user.virtual_number,
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
    premium_active = bool(user.premium_until and (user.premium_until.replace(tzinfo=timezone.utc) if user.premium_until.tzinfo is None else user.premium_until) > datetime.now(timezone.utc))
    return {"id": user.id, "losgram_id": user.losgram_id, "virtual_number": user.virtual_number, "phone": user.phone, "display_name": user.display_name,
            "username": user.username, "avatar_url": user.avatar_url, "bio": user.bio,
            "business_name": user.business_name, "business_description": user.business_description,
            "premium_active": premium_active, "premium_until": user.premium_until.isoformat() if user.premium_until else None,
            "stars_balance": user.stars_balance}


@app.get("/me/settings")
def get_settings(user: User = Depends(current_user)):
    try:
        settings = json.loads(user.settings_json or "{}")
    except (TypeError, ValueError):
        settings = {}
    defaults = {"notifications_enabled": True, "show_phone": False, "show_last_seen": True, "theme": "dark"}
    return {**defaults, **settings}


@app.patch("/me/settings")
def patch_settings(payload: SettingsUpdate, db: Session = Depends(get_db), user: User = Depends(current_user)):
    try:
        settings = json.loads(user.settings_json or "{}")
    except (TypeError, ValueError):
        settings = {}
    settings.update(payload.model_dump(exclude_none=True))
    user.settings_json = json.dumps(settings, ensure_ascii=False)
    db.commit()
    return settings


@app.put("/me/business")
def update_business(payload: BusinessProfileUpdate, db: Session = Depends(get_db), user: User = Depends(current_user)):
    user.business_name = payload.business_name
    user.business_description = payload.business_description
    db.commit()
    return {"business_name": user.business_name, "business_description": user.business_description}


@app.patch("/me")
def update_me(payload: ProfileUpdate, db: Session = Depends(get_db), user: User = Depends(current_user)):
    if payload.display_name is not None:
        user.display_name = payload.display_name
    if payload.username is not None:
        user.username = payload.username
    if "bio" in payload.model_fields_set:
        user.bio = payload.bio
    if "avatar_url" in payload.model_fields_set:
        user.avatar_url = payload.avatar_url
    try:
        db.commit()
    except Exception:
        db.rollback()
        raise HTTPException(status_code=409, detail="Это имя пользователя уже занято")
    return {"id": user.id, "display_name": user.display_name, "username": user.username, "bio": user.bio, "avatar_url": user.avatar_url}


def require_admin(user: User = Depends(current_user)) -> User:
    if not ADMIN_TELEGRAM_USER_ID or not user.telegram_user_id or not hmac.compare_digest(
        user.telegram_user_id, ADMIN_TELEGRAM_USER_ID
    ):
        raise HTTPException(status_code=403, detail="Доступ только владельцу LosGram")
    return user


@app.get("/admin/users")
def admin_users(q: str = "", db: Session = Depends(get_db), admin: User = Depends(require_admin)):
    term = q.strip()
    query = select(User).order_by(User.id.desc())
    if term:
        query = query.where(or_(User.username.ilike(f"%{term}%"),
                                User.losgram_id.ilike(f"%{term}%"),
                                User.display_name.ilike(f"%{term}%"),
                                User.virtual_number.ilike(f"%{term}%")))
    rows = db.scalars(query.limit(50)).all()
    now = datetime.now(timezone.utc)
    return {"items": [{
        "id": x.id, "losgram_id": x.losgram_id, "display_name": x.display_name, "username": x.username,
        "virtual_number": x.virtual_number, "stars_balance": x.stars_balance,
        "premium_until": x.premium_until.isoformat() if x.premium_until else None,
        "premium_active": bool(x.premium_until and (x.premium_until.replace(tzinfo=timezone.utc)
            if x.premium_until.tzinfo is None else x.premium_until) > now)
    } for x in rows]}


@app.post("/admin/grant")
def admin_grant(payload: AdminGrantRequest, db: Session = Depends(get_db),
                admin: User = Depends(require_admin)):
    target = db.get(User, payload.user_id)
    if not target:
        raise HTTPException(status_code=404, detail="Пользователь не найден")
    if target.stars_balance + payload.stars_delta < 0:
        raise HTTPException(status_code=400, detail="Недостаточно звёзд для списания")
    target.stars_balance += payload.stars_delta
    if payload.premium_days:
        current = target.premium_until
        if current and current.tzinfo is None:
            current = current.replace(tzinfo=timezone.utc)
        base = max(current, datetime.now(timezone.utc)) if current else datetime.now(timezone.utc)
        target.premium_until = base + timedelta(days=payload.premium_days)
    db.commit()
    event_lines = ["⭐ Операция со звёздами LosGram"]
    if payload.stars_delta > 0:
        event_lines.append(f"Зачислено: +{payload.stars_delta} ⭐")
    elif payload.stars_delta < 0:
        event_lines.append(f"Списано: {abs(payload.stars_delta)} ⭐")
    event_lines.append(f"Новый баланс: {target.stars_balance} ⭐")
    if payload.premium_days > 0:
        event_lines.extend([
            "",
            f"💎 LosGram Premium активирован на {payload.premium_days} дн.",
            f"Действует до: {target.premium_until.strftime('%d.%m.%Y %H:%M UTC')}",
        ])
    elif payload.premium_days < 0:
        event_lines.extend([
            "",
            f"Изменение Premium: {payload.premium_days} дн.",
            f"Новая дата окончания: {target.premium_until.strftime('%d.%m.%Y %H:%M UTC') if target.premium_until else 'не активен'}",
        ])
    event_lines.append(f"Время операции: {account_event_time()}")
    if payload.stars_delta > 0:
        topup_event = {
            "type": "stars_topup",
            "amount": payload.stars_delta,
            "balance": target.stars_balance,
            "time": account_event_time(),
        }
        add_saved_system_message(db, target, "LG_EVENT:" + json.dumps(topup_event, ensure_ascii=False))
    elif payload.stars_delta < 0 or payload.premium_days:
        add_saved_system_message(db, target, "\\n".join(event_lines))
    return {"user_id": target.id, "stars_balance": target.stars_balance,
            "premium_until": target.premium_until.isoformat() if target.premium_until else None}




@app.post("/admin/gifts/grant")
def admin_grant_gift(payload: AdminGiftGrantRequest, db: Session = Depends(get_db),
                     admin: User = Depends(require_admin)):
    target = db.get(User, payload.user_id)
    if not target:
        raise HTTPException(status_code=404, detail="Пользователь не найден")
    last_number = db.scalar(select(func.max(CollectibleGift.number))) or 0
    gift = CollectibleGift(user_id=target.id, name=payload.name.strip(),
        model=payload.model.strip(), background=payload.background.strip(),
        pattern=payload.pattern.strip(), number=last_number + 1)
    db.add(gift)
    db.commit()
    db.refresh(gift)
    add_saved_system_message(db, target,
        f"🎁 Вам выдан коллекционный NFT-подарок «{gift.name}» №{gift.number}. "
        f"Модель: {gift.model}; фон: {gift.background}; узор: {gift.pattern}.")
    return {"id": gift.id, "number": gift.number, "name": gift.name,
            "model": gift.model, "background": gift.background, "pattern": gift.pattern}


@app.post("/admin/diagnostics/gifts")
def diagnose_collectible_gifts(db: Session = Depends(get_db), admin: User = Depends(require_admin)):
    """Perform a real, temporary PostgreSQL write/read/delete round-trip as the owner."""
    from sqlalchemy import inspect as sqlalchemy_inspect

    required_columns = {"id", "user_id", "name", "model", "background", "pattern", "number", "created_at"}
    try:
        if not sqlalchemy_inspect(engine).has_table("collectible_gifts"):
            raise HTTPException(status_code=503, detail="Таблица collectible_gifts отсутствует")
        actual_columns = {column["name"] for column in sqlalchemy_inspect(engine).get_columns("collectible_gifts")}
        missing_columns = sorted(required_columns - actual_columns)
        if missing_columns:
            raise HTTPException(status_code=503, detail=f"В таблице NFT отсутствуют поля: {', '.join(missing_columns)}")

        last_number = db.scalar(select(func.max(CollectibleGift.number))) or 0
        marker = secrets.token_hex(6)
        gift = CollectibleGift(
            user_id=admin.id,
            name=f"LosGram DB check {marker}",
            model="Diagnostic",
            background="Diagnostic",
            pattern="Temporary",
            number=last_number + 1,
        )
        db.add(gift)
        db.commit()
        db.refresh(gift)
        gift_id = gift.id
        gift_number = gift.number

        persisted = db.scalar(select(CollectibleGift).where(
            CollectibleGift.id == gift_id,
            CollectibleGift.user_id == admin.id,
            CollectibleGift.name == f"LosGram DB check {marker}",
        ))
        if persisted is None:
            raise RuntimeError("Committed NFT row could not be read back")

        # Exercise the same query shape as /me/gifts, then remove only this diagnostic row.
        visible = db.scalars(select(CollectibleGift).where(
            CollectibleGift.user_id == admin.id,
            CollectibleGift.id == gift_id,
        )).all()
        if len(visible) != 1 or visible[0].number != gift_number:
            raise RuntimeError("NFT collection query did not return the persisted row")

        db.delete(persisted)
        db.commit()
        return {
            "ok": True,
            "table": "collectible_gifts",
            "schema_ok": True,
            "write_ok": True,
            "read_back_ok": True,
            "collection_query_ok": True,
            "cleanup_ok": True,
            "test_record_id": gift_id,
            "test_number": gift_number,
            "message": "Тестовая NFT-запись сохранена, прочитана и удалена. Реальные подарки не затронуты.",
        }
    except HTTPException:
        db.rollback()
        raise
    except Exception as exc:
        db.rollback()
        raise HTTPException(status_code=500, detail=f"Проверка NFT не пройдена: {type(exc).__name__}") from exc


@app.post("/me/gifts/{gift_id}/listing")
def create_nft_listing(gift_id: int, payload: NFTListingCreateRequest,
                       db: Session = Depends(get_db), user: User = Depends(current_user)):
    gift = db.scalar(select(CollectibleGift).where(
        CollectibleGift.id == gift_id, CollectibleGift.user_id == user.id
    ).with_for_update())
    if not gift:
        raise HTTPException(status_code=404, detail="NFT не найден в вашей коллекции")
    listing = db.scalar(select(NFTListing).where(NFTListing.gift_id == gift.id).with_for_update())
    if listing and listing.status == "active":
        raise HTTPException(status_code=409, detail="Этот NFT уже выставлен на продажу")
    if listing:
        listing.seller_id = user.id
        listing.buyer_id = None
        listing.price = payload.price
        listing.status = "active"
        listing.created_at = datetime.now(timezone.utc)
        listing.sold_at = None
    else:
        listing = NFTListing(gift_id=gift.id, seller_id=user.id, price=payload.price, status="active")
        db.add(listing)
    db.commit()
    db.refresh(listing)
    return {"id": listing.id, "gift_id": gift.id, "price": listing.price, "status": listing.status}


@app.delete("/me/gifts/{gift_id}/listing")
def cancel_nft_listing(gift_id: int, db: Session = Depends(get_db),
                       user: User = Depends(current_user)):
    listing = db.scalar(select(NFTListing).where(
        NFTListing.gift_id == gift_id, NFTListing.seller_id == user.id,
        NFTListing.status == "active"
    ).with_for_update())
    if not listing:
        raise HTTPException(status_code=404, detail="Активное объявление не найдено")
    listing.status = "cancelled"
    db.commit()
    return {"ok": True, "status": "cancelled"}


@app.get("/market/nfts")
def nft_market(db: Session = Depends(get_db), user: User = Depends(current_user)):
    rows = db.execute(
        select(NFTListing, CollectibleGift, User)
        .join(CollectibleGift, CollectibleGift.id == NFTListing.gift_id)
        .join(User, User.id == NFTListing.seller_id)
        .where(NFTListing.status == "active")
        .order_by(NFTListing.created_at.desc())
        .limit(100)
    ).all()
    return {"items": [{
        "listing_id": listing.id, "gift_id": gift.id, "name": gift.name,
        "model": gift.model, "background": gift.background, "pattern": gift.pattern,
        "number": gift.number, "price": listing.price, "seller_id": seller.id,
        "seller_name": seller.display_name, "seller_username": seller.username,
        "created_at": listing.created_at.isoformat(),
        "is_mine": listing.seller_id == user.id,
    } for listing, gift, seller in rows]}


@app.post("/market/nfts/{listing_id}/buy")
def buy_nft_listing(listing_id: int, db: Session = Depends(get_db),
                    buyer: User = Depends(current_user)):
    # Lock the listing, NFT and both wallets before changing ownership or balances.
    listing = db.scalar(select(NFTListing).where(
        NFTListing.id == listing_id, NFTListing.status == "active"
    ).with_for_update())
    if not listing:
        raise HTTPException(status_code=404, detail="Объявление уже продано или снято")
    if listing.seller_id == buyer.id:
        raise HTTPException(status_code=400, detail="Нельзя купить собственный NFT")
    gift = db.scalar(select(CollectibleGift).where(
        CollectibleGift.id == listing.gift_id,
        CollectibleGift.user_id == listing.seller_id
    ).with_for_update())
    if not gift:
        listing.status = "cancelled"
        db.commit()
        raise HTTPException(status_code=409, detail="Владелец NFT изменился. Объявление снято")
    locked_users = db.scalars(
        select(User).where(User.id.in_([listing.seller_id, buyer.id]))
        .order_by(User.id).with_for_update().execution_options(populate_existing=True)
    ).all()
    users_by_id = {account.id: account for account in locked_users}
    seller = users_by_id.get(listing.seller_id)
    locked_buyer = users_by_id.get(buyer.id)
    if not seller or not locked_buyer:
        raise HTTPException(status_code=404, detail="Покупатель или продавец не найден")
    price = listing.price
    if locked_buyer.stars_balance < price:
        raise HTTPException(status_code=400, detail=f"Недостаточно звёзд. Нужно: {price} ⭐")

    locked_buyer.stars_balance -= price
    seller.stars_balance += price
    gift.user_id = locked_buyer.id
    listing.status = "sold"
    listing.buyer_id = locked_buyer.id
    listing.sold_at = datetime.now(timezone.utc)
    db.commit()

    # Account activity messages are added after the atomic trade has committed.
    try:
        add_saved_system_message(locked_buyer,
            f"💎 Вы купили NFT «{gift.name}» №{gift.number} за {price} ⭐. Он добавлен в вашу коллекцию.")
        add_saved_system_message(seller,
            f"⭐ Ваш NFT «{gift.name}» №{gift.number} продан за {price} ⭐. Звёзды зачислены на баланс.")
    except Exception:
        db.rollback()
    return {"ok": True, "listing_id": listing.id, "gift_id": gift.id,
            "name": gift.name, "number": gift.number, "price": price,
            "buyer_balance": locked_buyer.stars_balance,
            "seller_balance": seller.stars_balance, "status": "sold"}


@app.get("/me/gifts")
def my_collectible_gifts(db: Session = Depends(get_db), user: User = Depends(current_user)):
    rows = db.execute(select(CollectibleGift, NFTListing).outerjoin(
        NFTListing, NFTListing.gift_id == CollectibleGift.id
    ).where(CollectibleGift.user_id == user.id).order_by(CollectibleGift.number.desc())).all()
    return {"items": [{
        "id": gift.id, "name": gift.name, "model": gift.model,
        "background": gift.background, "pattern": gift.pattern, "number": gift.number,
        "created_at": gift.created_at.isoformat(),
        "listing_id": listing.id if listing and listing.status == "active" else None,
        "listing_price": listing.price if listing and listing.status == "active" else None,
    } for gift, listing in rows]}


def bot_token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def owned_bot(db: Session, bot_id: int, owner: User) -> Bot:
    bot = db.get(Bot, bot_id)
    if not bot or bot.owner_id != owner.id:
        raise HTTPException(status_code=404, detail="Бот не найден")
    return bot


def bot_from_api_token(token: str, db: Session) -> Bot:
    if len(token) < 40 or len(token) > 160:
        raise HTTPException(status_code=401, detail="Недействительный токен бота")
    bot = db.scalar(select(Bot).where(Bot.token_hash == bot_token_hash(token)))
    if not bot or not bot.is_active:
        raise HTTPException(status_code=401, detail="Токен недействителен или бот отключён")
    return bot


@app.post("/bots")
def create_bot(payload: BotCreateRequest, db: Session = Depends(get_db), user: User = Depends(current_user)):
    username = payload.username.lower()
    if db.scalar(select(Bot.id).where(Bot.username == username)):
        raise HTTPException(status_code=409, detail="Этот username уже занят")
    token = f"lg_{secrets.token_hex(4)}_{secrets.token_urlsafe(32)}"
    bot = Bot(owner_id=user.id, name=payload.name.strip(), username=username,
              description=payload.description.strip(), token_hash=bot_token_hash(token), token_prefix=token[:12])
    db.add(bot)
    db.commit()
    db.refresh(bot)
    return {"id": bot.id, "name": bot.name, "username": bot.username, "description": bot.description,
            "token": token, "token_notice": "Сохраните токен сейчас. Повторно показать его нельзя.",
            "is_active": bot.is_active, "created_at": bot.created_at.isoformat()}


@app.get("/bots")
def list_my_bots(db: Session = Depends(get_db), user: User = Depends(current_user)):
    bots = db.scalars(select(Bot).where(Bot.owner_id == user.id).order_by(Bot.id.desc())).all()
    return {"items": [{"id": b.id, "name": b.name, "username": b.username, "description": b.description,
        "token_prefix": b.token_prefix, "is_active": b.is_active, "webhook_url": b.webhook_url,
        "created_at": b.created_at.isoformat()} for b in bots]}


@app.patch("/bots/{bot_id}/webhook")
def set_bot_webhook(bot_id: int, payload: BotWebhookRequest, db: Session = Depends(get_db), user: User = Depends(current_user)):
    bot = owned_bot(db, bot_id, user)
    if payload.url:
        from urllib.parse import urlparse
        parsed = urlparse(payload.url)
        if parsed.scheme != "https" or not parsed.netloc:
            raise HTTPException(status_code=400, detail="Webhook должен быть HTTPS URL")
    bot.webhook_url = payload.url
    db.commit()
    return {"ok": True, "webhook_url": bot.webhook_url}


@app.post("/bots/{bot_id}/revoke")
def revoke_bot_token(bot_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    bot = owned_bot(db, bot_id, user)
    token = f"lg_{secrets.token_hex(4)}_{secrets.token_urlsafe(32)}"
    bot.token_hash, bot.token_prefix = bot_token_hash(token), token[:12]
    db.commit()
    return {"ok": True, "token": token, "token_notice": "Старый токен отозван. Сохраните новый токен сейчас."}


@app.post("/bots/{bot_id}/disable")
def disable_bot(bot_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    bot = owned_bot(db, bot_id, user)
    bot.is_active = False
    db.commit()
    return {"ok": True, "is_active": False}


@app.post("/bots/{bot_id}/enable")
def enable_bot(bot_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    bot = owned_bot(db, bot_id, user)
    bot.is_active = True
    db.commit()
    return {"ok": True, "is_active": True}


@app.post("/botapi/{token}/sendMessage")
def bot_api_send_message(token: str, payload: BotSendMessageRequest, db: Session = Depends(get_db)):
    bot = bot_from_api_token(token, db)
    if not participant(db, payload.conversation_id, bot.owner_id):
        raise HTTPException(status_code=403, detail="Бот может писать только в чаты владельца")
    message = Message(conversation_id=payload.conversation_id, sender_id=bot.owner_id, body=f"🤖 {bot.name}: {payload.text}")
    db.add(message)
    db.flush()
    result = {"message_id": message.id, "conversation_id": message.conversation_id,
        "from": {"id": bot.id, "is_bot": True, "username": bot.username},
        "text": payload.text, "date": message.created_at.isoformat() if message.created_at else datetime.now(timezone.utc).isoformat()}
    db.add(BotUpdate(bot_id=bot.id, update_json=json.dumps({"update_id": message.id, "message": result}, ensure_ascii=False)))
    db.commit()
    return {"ok": True, "result": result}


@app.get("/botapi/{token}/getUpdates")
def bot_api_get_updates(token: str, offset: int | None = None, limit: int = 100, db: Session = Depends(get_db)):
    bot = bot_from_api_token(token, db)
    query = select(BotUpdate).where(BotUpdate.bot_id == bot.id)
    if offset is not None:
        query = query.where(BotUpdate.id >= offset)
    rows = db.scalars(query.order_by(BotUpdate.id).limit(min(max(limit, 1), 100))).all()
    return {"ok": True, "result": [json.loads(row.update_json) for row in rows]}


@app.get("/botapi/{token}/getMe")
def bot_api_get_me(token: str, db: Session = Depends(get_db)):
    bot = bot_from_api_token(token, db)
    return {"ok": True, "result": {"id": bot.id, "is_bot": True, "first_name": bot.name,
        "username": bot.username, "description": bot.description}}


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
    ensure_global_chat(db, user)
    ensure_saved_chat(db, user)
    memberships = db.scalars(select(Participant).where(Participant.user_id == user.id)).all()
    result = []
    for membership in memberships:
        conv = db.get(Conversation, membership.conversation_id)
        members = db.scalars(select(Participant).where(Participant.conversation_id == conv.id)).all()
        others = [db.get(User, m.user_id) for m in members if m.user_id != user.id]
        last = db.scalar(select(Message).where(Message.conversation_id == conv.id)
                         .order_by(Message.id.desc()).limit(1))
        result.append({"id": conv.id, "kind": conv.kind, "title": ("Избранное" if conv.kind == "saved" else conv.title),
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
    items = []
    for x in rows:
        reaction_rows = db.execute(
            select(MessageReaction.emoji, func.count(MessageReaction.id))
            .where(MessageReaction.message_id == x.id)
            .group_by(MessageReaction.emoji)
        ).all()
        items.append({"id": x.id, "conversation_id": x.conversation_id, "sender_id": x.sender_id,
                      "body": ("Сообщение удалено" if x.deleted_at else x.body),
                      "created_at": x.created_at.isoformat(), "reply_to_id": x.reply_to_id,
                      "edited": x.edited_at is not None, "deleted": x.deleted_at is not None,
                      "reactions": [{"emoji": emoji, "count": count,
                                     "mine": db.scalar(select(MessageReaction.id).where(
                                         MessageReaction.message_id == x.id,
                                         MessageReaction.user_id == user.id,
                                         MessageReaction.emoji == emoji
                                     ).limit(1)) is not None}
                                    for emoji, count in reaction_rows]})
    return {"items": items}


@app.post("/conversations/{conversation_id}/messages")
async def send_message(conversation_id: int, payload: MessageRequest,
                       db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not participant(db, conversation_id, user.id):
        raise HTTPException(status_code=403, detail="Нет доступа к чату")
    if payload.reply_to_id is not None:
        parent = db.get(Message, payload.reply_to_id)
        if not parent or parent.conversation_id != conversation_id or parent.deleted_at:
            raise HTTPException(status_code=400, detail="Нельзя ответить на это сообщение")
    message = Message(conversation_id=conversation_id, sender_id=user.id, body=payload.body,
                      reply_to_id=payload.reply_to_id)
    db.add(message)
    db.commit()
    db.refresh(message)
    data = {"type": "message", "id": message.id, "conversation_id": conversation_id,
            "sender_id": user.id, "body": message.body, "created_at": message.created_at.isoformat()}
    members = db.scalars(select(Participant.user_id).where(Participant.conversation_id == conversation_id)).all()
    for member_id in members:
        await hub.send_to_user(member_id, data)
    return data


@app.post("/conversations/{conversation_id}/messages/{message_id}/reactions")
def toggle_message_reaction(conversation_id: int, message_id: int, payload: dict,
                            db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not participant(db, conversation_id, user.id):
        raise HTTPException(status_code=403, detail="Нет доступа к чату")
    message = db.get(Message, message_id)
    if not message or message.conversation_id != conversation_id or message.deleted_at:
        raise HTTPException(status_code=404, detail="Сообщение не найдено")
    emoji = str(payload.get("emoji", ""))
    allowed = {"👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🎉"}
    if emoji not in allowed:
        raise HTTPException(status_code=400, detail="Недопустимая реакция")
    current = db.scalar(select(MessageReaction).where(
        MessageReaction.message_id == message_id, MessageReaction.user_id == user.id
    ))
    if current and current.emoji == emoji:
        db.delete(current)
        active = False
    else:
        if current:
            current.emoji = emoji
        else:
            db.add(MessageReaction(message_id=message_id, user_id=user.id, emoji=emoji))
        active = True
    db.commit()
    return {"ok": True, "active": active, "emoji": emoji}


@app.post("/conversations/{conversation_id}/messages/{message_id}/forward-to-saved")
def forward_message_to_saved(conversation_id: int, message_id: int,
                             db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not participant(db, conversation_id, user.id):
        raise HTTPException(status_code=403, detail="Нет доступа к чату")
    source = db.get(Message, message_id)
    if not source or source.conversation_id != conversation_id or source.deleted_at:
        raise HTTPException(status_code=404, detail="Сообщение не найдено")
    saved = ensure_saved_chat(db, user)
    forwarded = Message(conversation_id=saved.id, sender_id=user.id,
                        body=f"Переслано: {source.body}")
    db.add(forwarded)
    db.commit()
    db.refresh(forwarded)
    return {"ok": True, "conversation_id": saved.id, "message_id": forwarded.id}


@app.patch("/conversations/{conversation_id}/messages/{message_id}")
def edit_message(conversation_id: int, message_id: int, payload: MessageEditRequest, db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not participant(db, conversation_id, user.id):
        raise HTTPException(status_code=403, detail="Нет доступа к чату")
    message = db.get(Message, message_id)
    if not message or message.conversation_id != conversation_id:
        raise HTTPException(status_code=404, detail="Сообщение не найдено")
    if message.sender_id != user.id:
        raise HTTPException(status_code=403, detail="Можно изменять только свои сообщения")
    if message.deleted_at:
        raise HTTPException(status_code=400, detail="Сообщение уже удалено")
    message.body = payload.body
    message.edited_at = datetime.now(timezone.utc)
    db.commit()
    return {"id": message.id, "body": message.body, "edited": True}


@app.delete("/conversations/{conversation_id}/messages/{message_id}")
def delete_message(conversation_id: int, message_id: int, db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not participant(db, conversation_id, user.id):
        raise HTTPException(status_code=403, detail="Нет доступа к чату")
    message = db.get(Message, message_id)
    if not message or message.conversation_id != conversation_id:
        raise HTTPException(status_code=404, detail="Сообщение не найдено")
    if message.sender_id != user.id:
        raise HTTPException(status_code=403, detail="Можно удалять только свои сообщения")
    if not message.deleted_at:
        message.deleted_at = datetime.now(timezone.utc)
        message.body = ""
        db.commit()
    return {"id": message.id, "deleted": True}


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
