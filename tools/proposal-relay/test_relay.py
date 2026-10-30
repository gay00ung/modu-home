import contextlib
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
import io
import json
import socket
import threading
import time
import unittest
from unittest.mock import patch
import uuid

import relay
from relay import (DEFAULT_PUBLIC_URL, MAX_BODY_BYTES, MAX_LINK_LENGTH, MAX_MESSAGES,
                   MAX_ROOMS, ROOM_TTL_MILLIS, RelayError, RelayServer, RelayStore)


class Clock:
    def __init__(self):
        self.value = 1_800_000_000_000

    def __call__(self):
        return self.value


class RelayProxyConfigurationTest(unittest.TestCase):
    def test_proxy_https_policy_rejects_http_before_opening_listener(self):
        output = io.StringIO()
        with patch('sys.argv', ['relay.py', '--require-https-url', '--public-url', 'http://relay.example']), \
                patch('relay.RelayServer') as bind, contextlib.redirect_stderr(output):
            with self.assertRaises(SystemExit) as result:
                relay.main()
        self.assertEqual(2, result.exception.code)
        self.assertIn('HTTPS public URL is required', output.getvalue())
        self.assertNotIn('http://relay.example', output.getvalue())
        bind.assert_not_called()


class RelayClientAccessTest(unittest.TestCase):
    def test_network_listener_requires_explicit_clients_before_socket_creation(self):
        with patch('relay.ThreadingHTTPServer.__init__') as bind:
            for host in ('0.0.0.0', '192.168.0.10'):
                with self.subTest(host=host), self.assertRaises(ValueError):
                    RelayServer((host, 0), RelayStore())
            bind.assert_not_called()

    def test_wildcards_network_ranges_hostnames_and_public_clients_are_rejected(self):
        with patch('relay.ThreadingHTTPServer.__init__') as bind:
            for clients in ((), ('0.0.0.0',), ('192.168.0.0/24',), ('localhost',), ('8.8.8.8',), ('::1',)):
                with self.subTest(clients=clients), self.assertRaises(ValueError):
                    RelayServer(('127.0.0.1', 0), RelayStore(), clients)
            bind.assert_not_called()

    def test_only_declared_source_addresses_pass_the_policy(self):
        with RelayServer(('127.0.0.1', 0), RelayStore(), ('127.0.0.1', '192.168.0.20')) as server:
            self.assertTrue(server.verify_request(None, ('127.0.0.1', 1234)))
            self.assertTrue(server.verify_request(None, ('192.168.0.20', 1234)))
            for source in ('192.168.0.21', '10.0.0.20', '127.0.0.2', '0.0.0.0', 'invalid'):
                self.assertFalse(server.verify_request(None, (source, 1234)))

    def test_rejected_socket_cannot_read_headers_or_allocate_a_room(self):
        store = RelayStore()
        server = RelayServer(('127.0.0.1', 0), store, ('192.168.0.20',))
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.01}, daemon=True)
        thread.start()
        try:
            with patch.object(store, 'create_room', wraps=store.create_room) as create:
                with socket.create_connection(('127.0.0.1', server.server_port), timeout=2) as client:
                    client.sendall(b'POST /v1/rooms HTTP/1.1\r\nHost: localhost\r\nX-Forwarded-For: 192.168.0.20\r\nContent-Length: 2\r\n\r\n{}')
                    try:
                        received = client.recv(4096)
                    except ConnectionResetError:
                        received = b''
                self.assertEqual(b'', received)
                create.assert_not_called()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


class RelayHTTPTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.store = RelayStore(clock_ms=self.clock)
        self.server = RelayServer(("127.0.0.1", 0), self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, body=None, token=None, headers=None):
        request_headers = dict(headers or {})
        if token is not None:
            request_headers["Authorization"] = "Bearer " + token
        if isinstance(body, dict):
            body = json.dumps(body).encode("utf-8")
            request_headers.setdefault("Content-Type", "application/json")
        client = HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            client.request(method, path, body=body, headers=request_headers)
            response = client.getresponse()
            raw = response.read()
            payload = json.loads(raw) if raw else None
            return response.status, payload, dict(response.getheaders())
        finally:
            client.close()

    def raw(self, packet):
        data = b""
        with socket.create_connection(("127.0.0.1", self.server.server_port), timeout=2) as client:
            client.sendall(packet)
            client.shutdown(socket.SHUT_WR)
            while True:
                try:
                    chunk = client.recv(65536)
                except ConnectionResetError:
                    break
                if not chunk:
                    break
                data += chunk
        return data

    def raw_status(self, packet):
        response = self.raw(packet)
        self.assertTrue(response.startswith(b"HTTP/1.1 "))
        return int(response.split(b" ", 2)[1])

    def create(self, seed=None):
        status, room, _ = self.request("POST", "/v1/rooms", {} if seed is None else {"seedLink": seed})
        self.assertEqual(201, status)
        return room

    def paths(self, room):
        base = "/v1/rooms/" + room["roomId"]
        return base, base + "/messages"

    def post(self, room, message_id=None, link="modu-home://proposal?v=1"):
        _, messages = self.paths(room)
        message_id = message_id or str(uuid.uuid4())
        result = self.request("POST", messages, {"messageId": message_id, "link": link}, room["senderToken"])
        return message_id, result

    def test_creation_has_exact_fields_and_never_trusts_the_host_header(self):
        status, room, headers = self.request("POST", "/v1/rooms", {}, headers={"Host": "untrusted.invalid"})
        self.assertEqual(201, status)
        self.assertEqual({"roomId", "receiverToken", "senderToken", "expiresAtMillis", "publicBaseUrl"}, set(room))
        self.assertTrue(str(uuid.UUID(room["roomId"])) == room["roomId"])
        self.assertTrue(len(room["receiverToken"]) == 43 and len(room["senderToken"]) == 43)
        self.assertTrue(room["receiverToken"] != room["senderToken"])
        self.assertTrue(room["publicBaseUrl"] == DEFAULT_PUBLIC_URL)
        self.assertEqual(self.clock.value + ROOM_TTL_MILLIS, room["expiresAtMillis"])
        self.assertEqual("close", headers["Connection"])
        self.assertEqual("no-store", headers["Cache-Control"])

    def test_sender_room_info_has_null_seed_when_none_was_shared(self):
        room = self.create()
        base, _ = self.paths(room)
        status, info, _ = self.request("GET", base, token=room["senderToken"])
        self.assertEqual(200, status)
        self.assertEqual({"expiresAtMillis", "seedLink"}, set(info))
        self.assertIsNone(info["seedLink"])

    def test_seed_is_returned_only_to_the_sender_and_never_in_creation_or_errors(self):
        seed = "modu-home://proposal?v=1&seed=selected-errand"
        room = self.create(seed)
        self.assertNotIn("seedLink", room)
        base, _ = self.paths(room)
        status, info, _ = self.request("GET", base, token=room["senderToken"])
        self.assertEqual(200, status)
        self.assertTrue(info["seedLink"] == seed)
        for token in (None, room["receiverToken"], "wrong-token"):
            status, payload, _ = self.request("GET", base, token=token)
            self.assertEqual(401, status)
            self.assertEqual({"error": "unauthorized"}, payload)

    def test_invalid_seed_payloads_do_not_allocate_rooms(self):
        for value in (None, "", 1, [], "a" * (MAX_LINK_LENGTH + 1), "has space", "한글", "line\nbreak"):
            self.assertEqual(400, self.request("POST", "/v1/rooms", {"seedLink": value})[0])
        self.assertEqual(400, self.request("POST", "/v1/rooms", {"seedLink": "valid", "extra": True})[0])
        for _ in range(MAX_ROOMS):
            self.create()

    def test_queue_storage_status_and_same_message_retry_are_idempotent(self):
        room = self.create()
        _, messages = self.paths(room)
        message_id, (status, response, _) = self.post(room)
        self.assertEqual((202, {"status": "queued"}), (status, response))
        message_path = messages + "/" + message_id
        self.assertEqual({"status": "queued"}, self.request("GET", message_path, token=room["senderToken"])[1])
        for _ in range(2):
            poll = self.request("GET", messages, token=room["receiverToken"])[1]
            self.assertEqual(1, len(poll["messages"]))
            self.assertTrue(poll["messages"][0]["messageId"] == message_id)
        self.assertEqual((200, {"status": "stored"}), self.request("POST", message_path + "/ack", {"status": "stored"}, room["receiverToken"])[:2])
        self.assertEqual({"messages": []}, self.request("GET", messages, token=room["receiverToken"])[1])
        self.assertEqual({"status": "stored"}, self.request("GET", message_path, token=room["senderToken"])[1])
        self.assertEqual((202, {"status": "stored"}), self.post(room, message_id)[1][:2])
        self.assertEqual(200, self.request("POST", message_path + "/ack", {"status": "stored"}, room["receiverToken"])[0])
        self.assertEqual(409, self.request("POST", message_path + "/ack", {"status": "rejected"}, room["receiverToken"])[0])

    def test_rejection_is_terminal_and_cannot_be_changed_to_storage(self):
        room = self.create()
        _, messages = self.paths(room)
        message_id, _ = self.post(room)
        target = messages + "/" + message_id
        for _ in range(2):
            self.assertEqual((200, {"status": "rejected"}), self.request("POST", target + "/ack", {"status": "rejected"}, room["receiverToken"])[:2])
        self.assertEqual(409, self.request("POST", target + "/ack", {"status": "stored"}, room["receiverToken"])[0])
        self.assertEqual((202, {"status": "rejected"}), self.post(room, message_id)[1][:2])
        self.assertEqual({"messages": []}, self.request("GET", messages, token=room["receiverToken"])[1])

    def test_same_id_with_different_content_conflicts_without_overwriting_the_queue(self):
        room = self.create()
        _, messages = self.paths(room)
        message_id, _ = self.post(room, link="first")
        self.assertEqual(409, self.post(room, message_id, "different")[1][0])
        result = self.request("GET", messages, token=room["receiverToken"])[1]
        self.assertTrue(result["messages"][0]["link"] == "first")

    def test_sender_and_receiver_tokens_cannot_be_used_for_the_opposite_role(self):
        room = self.create()
        base, messages = self.paths(room)
        message_id, _ = self.post(room)
        target = messages + "/" + message_id
        attempts = [
            ("GET", base, None, room["receiverToken"]),
            ("POST", messages, {"messageId": str(uuid.uuid4()), "link": "valid"}, room["receiverToken"]),
            ("GET", target, None, room["receiverToken"]),
            ("GET", messages, None, room["senderToken"]),
            ("POST", target + "/ack", {"status": "stored"}, room["senderToken"]),
            ("DELETE", base, None, room["senderToken"]),
        ]
        for method, path, body, token in attempts:
            self.assertEqual(401, self.request(method, path, body, token)[0])
            self.assertEqual(401, self.request(method, path, body)[0])
        self.assertEqual(401, self.request("GET", base, token="é")[0])

    def test_room_expiry_has_an_exact_boundary_and_releases_capacity(self):
        rooms = [self.create() for _ in range(MAX_ROOMS)]
        self.assertEqual(429, self.request("POST", "/v1/rooms", {})[0])
        base, _ = self.paths(rooms[0])
        self.clock.value += ROOM_TTL_MILLIS - 1
        self.assertEqual(200, self.request("GET", base, token=rooms[0]["senderToken"])[0])
        self.clock.value += 1
        self.assertEqual(410, self.request("GET", base, token=rooms[0]["senderToken"])[0])
        self.create()

    def test_idle_server_removes_expired_room_memory_without_a_followup_request(self):
        room = self.create("modu-home://proposal?v=1")
        self.clock.value = room["expiresAtMillis"]
        deadline = time.monotonic() + 1
        while True:
            with self.store._lock:
                retained = room["roomId"] in self.store._rooms
            if not retained or time.monotonic() >= deadline:
                break
            threading.Event().wait(0.01)
        self.assertFalse(retained)

    def test_tokens_from_another_room_cannot_read_its_seed_or_messages(self):
        first = self.create()
        second = self.create("modu-home://proposal?v=1&seed=private")
        base, messages = self.paths(second)
        for path, token in ((base, first["senderToken"]), (messages, first["receiverToken"])):
            status, payload, _ = self.request("GET", path, token=token)
            self.assertEqual(401, status)
            self.assertEqual({"error": "unauthorized"}, payload)

    def test_missing_rooms_or_messages_return_gone_and_receiver_deletion_revokes_both_roles(self):
        room = self.create()
        base, messages = self.paths(room)
        missing = messages + "/" + str(uuid.uuid4())
        self.assertEqual(410, self.request("GET", missing, token=room["senderToken"])[0])
        self.assertEqual(410, self.request("POST", missing + "/ack", {"status": "stored"}, room["receiverToken"])[0])
        status, body, _ = self.request("DELETE", base, token=room["receiverToken"])
        self.assertEqual(204, status)
        self.assertIsNone(body)
        self.assertEqual(410, self.request("GET", base, token=room["senderToken"])[0])
        self.assertEqual(410, self.request("GET", messages, token=room["receiverToken"])[0])
        self.assertEqual(410, self.post(room)[1][0])
        self.assertEqual(410, self.request("DELETE", base, token=room["receiverToken"])[0])

    def test_message_limit_retains_final_results_and_allows_retries_at_capacity(self):
        room = self.create()
        _, messages = self.paths(room)
        ids = []
        for _ in range(MAX_MESSAGES):
            message_id, result = self.post(room)
            self.assertEqual(202, result[0])
            ids.append(message_id)
        self.assertEqual(429, self.post(room)[1][0])
        self.assertEqual(202, self.post(room, ids[0])[1][0])
        self.assertEqual(409, self.post(room, ids[0], "different")[1][0])
        self.assertEqual(200, self.request("POST", messages + "/" + ids[0] + "/ack", {"status": "stored"}, room["receiverToken"])[0])
        self.assertEqual(429, self.post(room)[1][0])

    def test_link_length_and_ascii_bounds_match_the_transfer_format(self):
        room = self.create()
        self.assertEqual(202, self.post(room, link="x" * MAX_LINK_LENGTH)[1][0])
        for value in ("", "x" * (MAX_LINK_LENGTH + 1), "has space", "한글", "line\nbreak", 1, None, []):
            self.assertEqual(400, self.post(room, link=value)[1][0])

    def test_body_limit_is_in_bytes_and_accepts_the_exact_boundary(self):
        exact = b"{}" + b" " * (MAX_BODY_BYTES - 2)
        self.assertEqual(201, self.request("POST", "/v1/rooms", exact)[0])
        packet = b"POST /v1/rooms HTTP/1.1\r\nHost: local\r\nContent-Length: " + str(MAX_BODY_BYTES + 1).encode() + b"\r\n\r\n"
        self.assertEqual(429, self.raw_status(packet))

    def test_json_rejects_duplicate_keys_invalid_utf8_non_objects_and_extra_fields(self):
        bodies = [b'{"seedLink":"a","seedLink":"b"}', b'{"seedLink":NaN}', b'{"seedLink":"\xff"}',
                  b'[]', b'null', b'false', b'1', b'{} garbage', b'{"extra":{ "x":1,"x":2}}', b'']
        for body in bodies:
            self.assertEqual(400, self.request("POST", "/v1/rooms", body)[0])
        room = self.create()
        _, messages = self.paths(room)
        for body in ({"messageId": "invalid", "link": "a"}, {"messageId": str(uuid.uuid4())},
                     {"messageId": str(uuid.uuid4()), "link": "a", "extra": 1}):
            self.assertEqual(400, self.request("POST", messages, body, room["senderToken"])[0])
        message_id, _ = self.post(room)
        for body in ({"status": "queued"}, {"status": "stored", "extra": 1}, {"status": []}):
            self.assertEqual(400, self.request("POST", messages + "/" + message_id + "/ack", body, room["receiverToken"])[0])

    def test_ambiguous_http_framing_and_expect_are_rejected_without_reading_another_request(self):
        headers = [b"", b"Content-Length: 2\r\nContent-Length: 2\r\n", b"Content-Length: +2\r\n",
                   b"Content-Length: -1\r\n", b"Content-Length: 02\r\n", b"Content-Length: 2,2\r\n",
                   b"Content-Length: x\r\n", b"Content-Length: 2\r\nTransfer-Encoding: chunked\r\n",
                   b"Content-Length: 2\r\nExpect: 100-continue\r\n"]
        for framing in headers:
            packet = b"POST /v1/rooms HTTP/1.1\r\nHost: local\r\n" + framing + b"\r\n{}"
            self.assertEqual(400, self.raw_status(packet))

    def test_paths_methods_and_bodyless_requests_are_strict(self):
        for path in ("/v1/rooms/", "/v1/rooms?extra=1", "//v1/rooms", "/v1/%72ooms", "/v2/rooms", "http://local/v1/rooms"):
            self.assertEqual(400, self.request("POST", path, {})[0])
        room = self.create()
        base, messages = self.paths(room)
        for method, path in (("GET", "/v1/rooms"), ("POST", base), ("DELETE", messages), ("PATCH", base), ("OPTIONS", base)):
            self.assertEqual(400, self.request(method, path, {} if method == "POST" else None, room["receiverToken"])[0])
        self.assertEqual(400, self.request("GET", base, b"{}", room["senderToken"])[0])
        self.assertEqual(400, self.raw_status(b"GET /v1/rooms\r\n\r\n"))

    def test_pipelined_requests_are_not_reused_after_the_first_response(self):
        request = b"POST /v1/rooms HTTP/1.1\r\nHost: local\r\nConnection: keep-alive\r\nContent-Length: 2\r\n\r\n{}"
        response = self.raw(request + request)
        self.assertEqual(1, response.count(b"HTTP/1.1 "))
        self.assertIn(b"Connection: close\r\n", response)

    def test_request_and_error_paths_produce_no_output_or_access_logs(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            room = self.create("modu-home://proposal?v=1")
            base, _ = self.paths(room)
            self.request("GET", base, token="invalid")
            self.request("PATCH", base)
            self.request("POST", "/v1/rooms", b"invalid")
            self.raw(b"BAD REQUEST\r\n\r\n")
        self.assertTrue(output.getvalue() == "" and errors.getvalue() == "")


class RelayConcurrencyTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.store = RelayStore(clock_ms=self.clock)
        self.room = self.store.create_room()

    def test_concurrent_duplicate_posts_create_only_one_queue_entry(self):
        message_id = str(uuid.uuid4())
        barrier = threading.Barrier(16)

        def post(_):
            barrier.wait(timeout=2)
            return self.store.post_message(self.room["roomId"], self.room["senderToken"], message_id, "same")["status"]

        with ThreadPoolExecutor(max_workers=16) as executor:
            statuses = list(executor.map(post, range(16)))
        self.assertEqual(["queued"] * 16, statuses)
        self.assertEqual(1, len(self.store.poll_messages(self.room["roomId"], self.room["receiverToken"])["messages"]))

    def test_concurrent_conflicting_posts_cannot_overwrite_each_other(self):
        message_id = str(uuid.uuid4())
        barrier = threading.Barrier(2)

        def post(link):
            barrier.wait(timeout=2)
            try:
                self.store.post_message(self.room["roomId"], self.room["senderToken"], message_id, link)
                return 202
            except RelayError as error:
                return error.status

        with ThreadPoolExecutor(max_workers=2) as executor:
            statuses = list(executor.map(post, ("first", "second")))
        self.assertEqual([202, 409], sorted(statuses))
        self.assertEqual(1, len(self.store.poll_messages(self.room["roomId"], self.room["receiverToken"])["messages"]))

    def test_concurrent_room_creation_never_exceeds_the_room_limit(self):
        def create(_):
            try:
                self.store.create_room()
                return 201
            except RelayError as error:
                return error.status

        with ThreadPoolExecutor(max_workers=16) as executor:
            statuses = list(executor.map(create, range(40)))
        self.assertEqual(MAX_ROOMS - 1, statuses.count(201))
        self.assertEqual(40 - (MAX_ROOMS - 1), statuses.count(429))

    def test_explicit_public_base_url_is_constant_and_credential_urls_are_rejected(self):
        store = RelayStore("https://relay.example.test/", self.clock)
        self.assertTrue(store.create_room()["publicBaseUrl"] == "https://relay.example.test")
        for value in ("ftp://invalid", "https://user:secret@invalid", "https://invalid?token=secret", "https://invalid/#fragment",
                      "https://invalid/base", "https://invalid:0", "https://invalid:65536", "https://invalid?", "https://invalid#",
                      "https://" + "x" * 2041, "https://invalid\x7f"):
            with self.assertRaises(ValueError):
                RelayStore(value, self.clock)


if __name__ == "__main__":
    unittest.main()
