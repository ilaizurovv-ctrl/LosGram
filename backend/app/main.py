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
from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Integer, String, Text, create_engine, select, or_
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


class AdminGrantRequest(BaseModel):
    user_id: int
    stars_delta: int = Field(default=0, ge=-1000000, le=1000000)
    premium_days: int = Field(default=0, ge=-36500, le=36500)


class DirectConversationRequest(BaseModel):
    user_id: int


class MessageRequest(BaseModel):
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
    db.add(TelegramLoginCode(
        telegram_user_id=telegram_user_id,
        code_hash=telegram_code_hash(telegram_user_id, code),
        expires_at=datetime.now(timezone.utc) + timedelta(minutes=LOGIN_CODE_TTL_MINUTES)))
    db.commit()
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
                                User.display_name.ilike(f"%{term}%"),
                                User.virtual_number.ilike(f"%{term}%")))
    rows = db.scalars(query.limit(50)).all()
    now = datetime.now(timezone.utc)
    return {"items": [{
        "id": x.id, "display_name": x.display_name, "username": x.username,
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
    return {"user_id": target.id, "stars_balance": target.stars_balance,
            "premium_until": target.premium_until.isoformat() if target.premium_until else None}


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
