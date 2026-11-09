#!/usr/bin/env python3
"""Local, memory-only delivery of opaque user proposals. No request logging."""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import hmac
import ipaddress
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import secrets
import ssl
import threading
import time
from typing import Callable
from urllib.parse import urlsplit
import uuid


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 48680
DEFAULT_PUBLIC_URL = "http://127.0.0.1:48680"
MAX_BODY_BYTES = 12 * 1024
MAX_LINK_LENGTH = 8192
ROOM_TTL_MILLIS = 15 * 60 * 1000
MAX_ROOMS = 32
MAX_MESSAGES = 20
_UUID = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
_ROOM_PATH = re.compile(rf"/v1/rooms/(?P<room>{_UUID})(?P<suffix>/messages(?:/(?P<message>{_UUID})(?P<ack>/ack)?)?)?")
_ERRORS = {400: "invalid_request", 401: "unauthorized", 409: "conflict", 410: "gone", 429: "rate_limited", 500: "internal_error"}
_PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
_WEB_ROOT = Path(__file__).with_name("dropoff_web")
_WEB_ASSETS = {
    "/dropoff/": ("index.html", "text/html; charset=utf-8"),
    "/dropoff/dropoff.css": ("dropoff.css", "text/css; charset=utf-8"),
    "/dropoff/dropoff.js": ("dropoff.js", "text/javascript; charset=utf-8"),
    "/dropoff/leaflet.css": ("leaflet.css", "text/css; charset=utf-8"),
    "/dropoff/leaflet.js": ("leaflet.js", "text/javascript; charset=utf-8"),
}
_WEB_CSP = (
    "default-src 'none'; script-src 'self'; style-src 'self'; "
    "img-src 'self' https://tile.openstreetmap.org data:; "
    "connect-src 'self' https://photon.komoot.io; "
    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
)


def _local_ipv4(value: str, *, listener: bool = False) -> ipaddress.IPv4Address:
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError:
        raise ValueError("use an exact local IPv4 address, without hostnames or network ranges") from None
    if address.is_loopback or any(address in network for network in _PRIVATE_NETWORKS):
        return address
    if listener and address.is_unspecified:
        return address
    raise ValueError("use a loopback or private IPv4 address")


class RelayError(Exception):
    def __init__(self, status: int):
        self.status = status
        super().__init__(_ERRORS[status])


def _milliseconds() -> int:
    return time.time_ns() // 1_000_000


def _link(value: object) -> str:
    if not isinstance(value, str) or not 1 <= len(value) <= MAX_LINK_LENGTH:
        raise RelayError(400)
    if not all(0x21 <= ord(char) <= 0x7E for char in value):
        raise RelayError(400)
    return value


def _message_id(value: object) -> str:
    if not isinstance(value, str) or re.fullmatch(_UUID, value) is None:
        raise RelayError(400)
    return str(uuid.UUID(value))


def _public_url(value: str) -> str:
    try:
        parsed = urlsplit(value)
        if not 1 <= len(value) <= 2048 or any(not 0x21 <= ord(char) <= 0x7E for char in value):
            raise ValueError
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError
        if parsed.username is not None or parsed.password is not None or "?" in value or "#" in value:
            raise ValueError
        if parsed.path not in ("", "/") or (parsed.port is not None and not 1 <= parsed.port <= 65535):
            raise ValueError
    except ValueError:
        raise ValueError("public URL must be an HTTP(S) base URL without credentials, query or fragment") from None
    return value.rstrip("/")


def _tls_context(cert_file: str | None, key_file: str | None) -> ssl.SSLContext | None:
    if cert_file is None and key_file is None:
        return None
    if not cert_file or not key_file:
        raise ValueError("TLS requires both --tls-cert and --tls-key")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.set_alpn_protocols(["http/1.1"])
    try:
        # An empty password prevents an encrypted key from opening an interactive prompt.
        context.load_cert_chain(cert_file, key_file, password="")
    except (OSError, ValueError):
        raise ValueError("could not load the TLS certificate chain and unencrypted private key") from None
    return context


@dataclass(repr=False)
class _Message:
    link: str
    status: str = "queued"


@dataclass(repr=False)
class _Room:
    receiver_token: str
    sender_token: str
    expires_at: int
    seed_link: str | None
    messages: dict[str, _Message] = field(default_factory=dict)


class RelayStore:
    """All lookup, expiry, authorization and mutations share one lock."""

    def __init__(self, public_base_url: str = DEFAULT_PUBLIC_URL, clock_ms: Callable[[], int] = _milliseconds):
        self.public_base_url = _public_url(public_base_url)
        self._clock_ms = clock_ms
        self._rooms: dict[str, _Room] = {}
        self._lock = threading.Lock()

    def _prune(self, now: int) -> None:
        expired = [room_id for room_id, room in self._rooms.items() if now >= room.expires_at]
        for room_id in expired:
            del self._rooms[room_id]

    def prune_expired(self) -> None:
        with self._lock:
            self._prune(self._clock_ms())

    def _authorized(self, room_id: str, token: str, receiver: bool, now: int) -> _Room:
        self._prune(now)
        room = self._rooms.get(room_id)
        if room is None:
            raise RelayError(410)
        expected = room.receiver_token if receiver else room.sender_token
        if not isinstance(token, str) or not token.isascii() or not hmac.compare_digest(token, expected):
            raise RelayError(401)
        return room

    def create_room(self, seed_link: str | None = None) -> dict:
        if seed_link is not None:
            _link(seed_link)
        with self._lock:
            now = self._clock_ms()
            self._prune(now)
            if len(self._rooms) >= MAX_ROOMS:
                raise RelayError(429)
            receiver_token = secrets.token_urlsafe(32)
            sender_token = secrets.token_urlsafe(32)
            while hmac.compare_digest(receiver_token, sender_token):
                sender_token = secrets.token_urlsafe(32)
            room_id = str(uuid.uuid4())
            expires_at = now + ROOM_TTL_MILLIS
            self._rooms[room_id] = _Room(receiver_token, sender_token, expires_at, seed_link)
            return {"roomId": room_id, "receiverToken": receiver_token, "senderToken": sender_token,
                    "expiresAtMillis": expires_at, "publicBaseUrl": self.public_base_url}

    def room_info(self, room_id: str, token: str) -> dict:
        with self._lock:
            room = self._authorized(room_id, token, False, self._clock_ms())
            return {"expiresAtMillis": room.expires_at, "seedLink": room.seed_link}

    def post_message(self, room_id: str, token: str, message_id: str, link: str) -> dict:
        message_id, link = _message_id(message_id), _link(link)
        with self._lock:
            room = self._authorized(room_id, token, False, self._clock_ms())
            previous = room.messages.get(message_id)
            if previous is not None:
                if previous.link != link:
                    raise RelayError(409)
                return {"status": previous.status}
            if len(room.messages) >= MAX_MESSAGES:
                raise RelayError(429)
            room.messages[message_id] = _Message(link)
            return {"status": "queued"}

    def poll_messages(self, room_id: str, token: str) -> dict:
        with self._lock:
            room = self._authorized(room_id, token, True, self._clock_ms())
            return {"messages": [{"messageId": message_id, "link": message.link}
                                 for message_id, message in room.messages.items() if message.status == "queued"]}

    def message_status(self, room_id: str, token: str, message_id: str) -> dict:
        with self._lock:
            room = self._authorized(room_id, token, False, self._clock_ms())
            message = room.messages.get(message_id)
            if message is None:
                raise RelayError(410)
            return {"status": message.status}

    def acknowledge(self, room_id: str, token: str, message_id: str, status: str) -> dict:
        if status not in ("stored", "rejected"):
            raise RelayError(400)
        with self._lock:
            room = self._authorized(room_id, token, True, self._clock_ms())
            message = room.messages.get(message_id)
            if message is None:
                raise RelayError(410)
            if message.status != "queued" and message.status != status:
                raise RelayError(409)
            message.status = status
            return {"status": status}

    def delete_room(self, room_id: str, token: str) -> None:
        with self._lock:
            self._authorized(room_id, token, True, self._clock_ms())
            del self._rooms[room_id]


def _json_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _invalid_constant(_: str) -> None:
    raise ValueError("invalid JSON constant")


class RelayServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], store: RelayStore,
                 allowed_clients: tuple[str, ...] | None = None, *, tls_context: ssl.SSLContext | None = None):
        if tls_context is not None and urlsplit(store.public_base_url).scheme != "https":
            raise ValueError("a TLS listener requires an HTTPS public URL")
        host = _local_ipv4("127.0.0.1" if address[0] == "localhost" else address[0], listener=True)
        if allowed_clients is None:
            if not host.is_loopback:
                raise ValueError("a non-loopback listener requires explicit --allow-client addresses")
            clients = frozenset((host,))
        else:
            clients = frozenset(_local_ipv4(value) for value in allowed_clients)
            if not clients:
                raise ValueError("at least one allowed client address is required")
        self.store = store
        self.allowed_clients = clients
        self.tls_context = tls_context
        super().__init__((str(host), address[1]), RelayHandler)

    def verify_request(self, request, client_address) -> bool:
        # Reject before a handler can parse headers, read a body or allocate a room.
        try:
            return ipaddress.IPv4Address(client_address[0]) in self.allowed_clients
        except ipaddress.AddressValueError:
            return False

    def handle_error(self, request, client_address) -> None:
        # The default implementation prints tracebacks and client addresses.
        pass

    def finish_request(self, request, client_address) -> None:
        if self.tls_context is None:
            return super().finish_request(request, client_address)
        # Client filtering precedes this worker; an unfinished handshake cannot block accept().
        with self.tls_context.wrap_socket(request, server_side=True, do_handshake_on_connect=False) as connection:
            connection.settimeout(5)
            super().finish_request(connection, client_address)

    def service_actions(self) -> None:
        self.store.prune_expired()


class RelayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    default_request_version = "HTTP/1.1"

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(5)

    def version_string(self) -> str:
        return "ProposalRelay"

    def log_message(self, format, *args) -> None:
        pass

    def send_error(self, code, message=None, explain=None) -> None:
        self._reply(400, {"error": "invalid_request"})

    def handle_expect_100(self) -> bool:
        self._reply(400, {"error": "invalid_request"})
        return False

    def parse_request(self) -> bool:
        valid = super().parse_request()
        self.close_connection = True
        if not valid:
            return False
        if len(self.requestline.split(" ")) != 3 or self.request_version != "HTTP/1.1":
            self._reply(400, {"error": "invalid_request"})
            return False
        return True

    def _reply_bytes(self, status: int, body: bytes, media_type: str, *, browser: bool = False) -> None:
        self.close_connection = True
        self.request_version = "HTTP/1.1"
        self.send_response(status)
        self.send_header("Content-Type", media_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        if browser:
            self.send_header("Content-Security-Policy", _WEB_CSP)
            self.send_header("Referrer-Policy", "strict-origin-when-cross-origin")
            self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _reply(self, status: int, payload: dict | None = None) -> None:
        body = b"" if payload is None else json.dumps(payload, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode("ascii")
        self._reply_bytes(status, body, "application/json; charset=utf-8")

    def _body_length(self) -> int:
        if self.headers.get_all("Transfer-Encoding") or self.headers.get_all("Expect") or self.headers.get_all("Upgrade"):
            raise RelayError(400)
        lengths = self.headers.get_all("Content-Length", [])
        if len(lengths) > 1 or (self.command == "POST" and len(lengths) != 1):
            raise RelayError(400)
        if not lengths:
            return 0
        raw = lengths[0]
        if re.fullmatch(r"0|[1-9][0-9]*", raw) is None:
            raise RelayError(400)
        if len(raw) > 5 or int(raw) > MAX_BODY_BYTES:
            raise RelayError(429)
        length = int(raw)
        if self.command != "POST" and length != 0:
            raise RelayError(400)
        return length

    def _body(self, length: int, allowed_keys: tuple[set[str], ...]) -> dict:
        content_types = self.headers.get_all("Content-Type", [])
        if len(content_types) > 1 or (content_types and content_types[0].split(";", 1)[0].strip().lower() != "application/json"):
            raise RelayError(400)
        body = self.rfile.read(length)
        if len(body) != length:
            raise RelayError(400)
        try:
            parsed = json.loads(body.decode("utf-8"), object_pairs_hook=_json_object, parse_constant=_invalid_constant)
        except (UnicodeDecodeError, ValueError, RecursionError):
            raise RelayError(400) from None
        if not isinstance(parsed, dict) or set(parsed) not in allowed_keys:
            raise RelayError(400)
        return parsed

    def _token(self) -> str:
        headers = self.headers.get_all("Authorization", [])
        if len(headers) != 1:
            return ""
        parts = headers[0].split(" ")
        if len(parts) != 2 or parts[0].lower() != "bearer":
            return ""
        return parts[1]

    def _dispatch(self) -> tuple[int, dict | None]:
        length = self._body_length()
        # BaseHTTPRequestHandler normalizes a leading double slash; inspect the original target.
        path = self.requestline.split(" ")[1]
        store = self.server.store
        if path == "/v1/rooms" and self.command == "POST":
            body = self._body(length, (set(), {"seedLink"}))
            seed = _link(body["seedLink"]) if "seedLink" in body else None
            return 201, store.create_room(seed)
        match = _ROOM_PATH.fullmatch(path)
        if match is None:
            raise RelayError(400)
        room_id = str(uuid.UUID(match["room"]))
        message_id = str(uuid.UUID(match["message"])) if match["message"] else None
        suffix, token = match["suffix"], self._token()
        if suffix is None:
            if self.command == "GET":
                return 200, store.room_info(room_id, token)
            if self.command == "DELETE":
                store.delete_room(room_id, token)
                return 204, None
        elif suffix == "/messages":
            if self.command == "GET":
                return 200, store.poll_messages(room_id, token)
            if self.command == "POST":
                body = self._body(length, ({"messageId", "link"},))
                return 202, store.post_message(room_id, token, body["messageId"], body["link"])
        elif match["ack"] is not None:
            if self.command == "POST":
                body = self._body(length, ({"status"},))
                return 200, store.acknowledge(room_id, token, message_id, body["status"])
        elif self.command == "GET":
            return 200, store.message_status(room_id, token, message_id)
        raise RelayError(400)

    def _handle(self) -> None:
        try:
            asset = _WEB_ASSETS.get(self.requestline.split(" ")[1])
            if asset is not None and self.command in ("GET", "HEAD"):
                self._body_length()
                filename, media_type = asset
                self._reply_bytes(200, (_WEB_ROOT / filename).read_bytes(), media_type, browser=True)
                return
            status, payload = self._dispatch()
        except RelayError as error:
            status, payload = error.status, {"error": _ERRORS[error.status]}
        except (OSError, ValueError, TypeError):
            status, payload = 400, {"error": "invalid_request"}
        except Exception:
            status, payload = 500, {"error": "internal_error"}
        try:
            self._reply(status, payload)
        except OSError:
            self.close_connection = True

    do_GET = _handle
    do_HEAD = _handle
    do_POST = _handle
    do_DELETE = _handle


def main() -> None:
    parser = argparse.ArgumentParser(description="Memory-only development proposal relay")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--public-url", default=DEFAULT_PUBLIC_URL)
    parser.add_argument("--require-https-url", action="store_true",
                        help="require an HTTPS public URL when TLS is terminated by a proxy")
    parser.add_argument("--allow-client", action="append", default=None,
                        help="exact loopback/private IPv4 client address; repeat for each test device")
    parser.add_argument("--tls-cert", help="PEM certificate chain for the HTTPS listener")
    parser.add_argument("--tls-key", help="unencrypted PEM private key, stored outside the repository")
    args = parser.parse_args()
    try:
        store = RelayStore(args.public_url)
    except ValueError:
        parser.error("public URL must be an HTTP(S) base URL without credentials, query or fragment")
    if args.require_https_url and urlsplit(store.public_base_url).scheme != "https":
        parser.error("HTTPS public URL is required for this configuration")
    try:
        tls_context = _tls_context(args.tls_cert, args.tls_key)
        listener = RelayServer((args.host, args.port), store,
                               None if args.allow_client is None else tuple(args.allow_client),
                               tls_context=tls_context)
    except ValueError as error:
        parser.error(str(error))
    except (OSError, OverflowError):
        parser.error("could not open the relay listener")
    with listener as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
