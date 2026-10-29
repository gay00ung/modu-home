import contextlib
from http.client import HTTPSConnection
import io
import json
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import uuid
import warnings

from relay import RelayServer, RelayStore, _tls_context, main


class RelayTLSTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        openssl = shutil.which("openssl")
        if openssl is None:
            raise RuntimeError("HTTPS tests require the openssl CLI")
        cls.files = tempfile.TemporaryDirectory(prefix="modu-home-relay-tls-")
        cls.addClassCleanup(cls.files.cleanup)
        root = Path(cls.files.name)
        cls.ca = root / "ca.pem"
        cls.ca_key = root / "ca-key.pem"
        cls.cert = root / "server.pem"
        cls.key = root / "server-key.pem"
        cls.encrypted_key = root / "encrypted-key.pem"
        request = root / "server.csr"
        extensions = root / "server.ext"
        extensions.write_text(
            "basicConstraints=critical,CA:FALSE\n"
            "keyUsage=critical,digitalSignature,keyEncipherment\n"
            "extendedKeyUsage=serverAuth\n"
            "subjectAltName=DNS:localhost,IP:10.0.2.2\n"
            "subjectKeyIdentifier=hash\n"
            "authorityKeyIdentifier=keyid,issuer\n"
        )

        def run(*arguments, input_data=None):
            subprocess.run(
                [openssl, *map(str, arguments)], input=input_data,
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                check=True, timeout=20,
            )

        run("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=ModuHome Relay Test Root",
            "-addext", "basicConstraints=critical,CA:TRUE",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign",
            "-keyout", cls.ca_key, "-out", cls.ca)
        run("req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
            "-keyout", cls.key, "-out", request)
        run("x509", "-req", "-in", request, "-CA", cls.ca, "-CAkey", cls.ca_key,
            "-CAcreateserial", "-days", "1", "-sha256", "-extfile", extensions,
            "-out", cls.cert)
        run("pkey", "-in", cls.key, "-aes-256-cbc", "-passout", "stdin",
            "-out", cls.encrypted_key, input_data=secrets.token_urlsafe(24).encode() + b"\n")
        for key in (cls.ca_key, cls.key, cls.encrypted_key):
            key.chmod(0o600)

    def setUp(self):
        self.context = _tls_context(str(self.cert), str(self.key))
        self.store = RelayStore("https://localhost")
        self.server = RelayServer(("127.0.0.1", 0), self.store, tls_context=self.context)
        self.store.public_base_url = f"https://localhost:{self.server.server_port}"
        self.thread = self.start(self.server)

    def tearDown(self):
        self.close(self.server, self.thread)

    @staticmethod
    def start(server):
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        thread.start()
        return thread

    @staticmethod
    def close(server, thread):
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
        if thread.is_alive():
            raise AssertionError("relay listener did not stop")

    def client_context(self, trusted=True):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.verify_flags |= ssl.VERIFY_X509_STRICT
        if trusted:
            context.load_verify_locations(cafile=str(self.ca))
        else:
            context.load_default_certs()
        return context

    def request(self, method, path, body=None, token=None, *, context=None, hostname="localhost"):
        headers = {}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            body = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        client = HTTPSConnection(hostname, self.server.server_port, timeout=2,
                                 context=context or self.client_context())
        try:
            client.request(method, path, body=body, headers=headers)
            response = client.getresponse()
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
        finally:
            client.close()

    def test_delivery_and_role_boundaries_over_https(self):
        status, room = self.request("POST", "/v1/rooms", {})
        self.assertEqual(201, status)
        self.assertEqual(self.store.public_base_url, room["publicBaseUrl"])
        base = "/v1/rooms/" + room["roomId"]
        messages = base + "/messages"
        message_id = str(uuid.uuid4())
        body = {"messageId": message_id, "link": "modu-home://proposal?v=1"}
        self.assertEqual(200, self.request("GET", base, token=room["senderToken"])[0])
        self.assertEqual(401, self.request("GET", base, token=room["receiverToken"])[0])
        self.assertEqual((202, {"status": "queued"}),
                         self.request("POST", messages, body, room["senderToken"]))
        pending = self.request("GET", messages, token=room["receiverToken"])[1]
        self.assertEqual([body], pending["messages"])
        message_path = messages + "/" + message_id
        self.assertEqual((200, {"status": "stored"}),
                         self.request("POST", message_path + "/ack", {"status": "stored"}, room["receiverToken"]))
        self.assertEqual((202, {"status": "stored"}),
                         self.request("POST", messages, body, room["senderToken"]))
        self.assertEqual((200, {"status": "stored"}),
                         self.request("GET", message_path, token=room["senderToken"]))
        self.assertEqual((204, None), self.request("DELETE", base, token=room["receiverToken"]))
        self.assertEqual(410, self.request("GET", base, token=room["senderToken"])[0])

    def test_default_trust_rejects_untrusted_issuer(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.request("POST", "/v1/rooms", {}, context=self.client_context(trusted=False))
        self.assertEqual(0, len(self.store._rooms))

    def test_trusted_ca_still_checks_hostname(self):
        with self.assertRaises(ssl.SSLCertVerificationError) as error:
            self.request("POST", "/v1/rooms", {}, hostname="127.0.0.1")
        self.assertIn("IP address mismatch", str(error.exception))
        self.assertEqual(0, len(self.store._rooms))

    def test_tls_1_2_and_http_1_1_negotiate(self):
        context = self.client_context()
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
        context.set_alpn_protocols(["h2", "http/1.1"])
        with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2) as raw:
            with context.wrap_socket(raw, server_hostname="localhost") as connection:
                self.assertEqual("TLSv1.2", connection.version())
                self.assertEqual("http/1.1", connection.selected_alpn_protocol())

    def test_tls_1_1_is_rejected_by_the_server(self):
        context = self.client_context()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_1
        context.set_ciphers("ALL:@SECLEVEL=0")
        with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2) as raw:
            with self.assertRaises(ssl.SSLError) as error:
                context.wrap_socket(raw, server_hostname="localhost")
        self.assertEqual("TLSV1_ALERT_PROTOCOL_VERSION", error.exception.reason)

    def test_plain_http_never_reaches_room_handling(self):
        with patch.object(self.store, "create_room", wraps=self.store.create_room) as create:
            with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2) as client:
                client.sendall(b"POST /v1/rooms HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}")
                try:
                    response = client.recv(4096)
                except ConnectionResetError:
                    response = b""
            self.assertFalse(response.startswith(b"HTTP/"))
            create.assert_not_called()

    def test_unfinished_handshake_does_not_block_other_clients(self):
        with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2):
            self.assertEqual(201, self.request("POST", "/v1/rooms", {})[0])

    def test_ip_rejection_precedes_tls(self):
        store = RelayStore("https://localhost")
        server = RelayServer(("127.0.0.1", 0), store, ("192.168.0.20",), tls_context=self.context)
        thread = self.start(server)
        try:
            with patch.object(self.context, "wrap_socket", wraps=self.context.wrap_socket) as wrap:
                with socket.create_connection(("127.0.0.1", server.server_port), timeout=2) as raw:
                    with self.assertRaises(OSError):
                        self.client_context().wrap_socket(raw, server_hostname="localhost")
                wrap.assert_not_called()
            self.assertEqual(0, len(store._rooms))
        finally:
            self.close(server, thread)

    def test_tls_requires_https_public_url_before_binding(self):
        with patch("relay.ThreadingHTTPServer.__init__") as bind:
            with self.assertRaises(ValueError):
                RelayServer(("127.0.0.1", 0), RelayStore(), tls_context=self.context)
            bind.assert_not_called()

    def test_invalid_identity_configuration_fails_before_binding(self):
        cases = [
            ["--tls-cert", str(self.cert)],
            ["--tls-key", str(self.key)],
            ["--tls-cert", str(self.cert), "--tls-key", str(Path(self.files.name) / "missing.pem")],
            ["--tls-cert", str(self.cert), "--tls-key", str(self.ca_key)],
        ]
        for arguments in cases:
            with self.subTest(arguments=arguments), patch("sys.argv", ["relay", "--public-url", "https://localhost", *arguments]):
                with patch("relay.ThreadingHTTPServer.__init__") as bind, contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as error:
                        main()
                    self.assertEqual(2, error.exception.code)
                    bind.assert_not_called()

    def test_encrypted_key_fails_without_an_interactive_prompt(self):
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("relay.py")), "--public-url", "https://localhost",
             "--tls-cert", str(self.cert), "--tls-key", str(self.encrypted_key)],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=3,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn(b"could not load the TLS certificate chain", result.stderr)
        self.assertNotIn(b"pass phrase", result.stderr.lower())
        self.assertNotIn(str(self.key).encode(), result.stderr)


if __name__ == "__main__":
    unittest.main()
