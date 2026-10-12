import http.client
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest

spec = importlib.util.spec_from_file_location("voice", Path(__file__).parents[1] / "phone/voice/server.py")
voice = importlib.util.module_from_spec(spec)
spec.loader.exec_module(voice)


class FakeCall:
    def __init__(self, ident, workspace):
        self.ident, self.last_seen, self.closed, self.error = ident, 0, False, None
    def start(self, sdp, workspace):
        return "v=0\r\nanswer"
    def stop(self):
        self.closed = True


class VoiceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = voice.VoiceServer(("127.0.0.1", 0), "test-secret", self.temp.name, FakeCall)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.stop_call()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, path="/health", body=None, raw=False, **overrides):
        headers = {"Host":"127.0.0.1:8771", "Authorization":"Bearer test-secret",
                   "Origin":voice.ORIGIN, "Content-Type":"application/json"}
        headers.update(overrides)
        conn = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        conn.request("POST" if body is not None else "GET", path,
                     json.dumps(body) if body is not None else None, headers)
        response = conn.getresponse()
        data = response.read()
        result = response.status, (data, dict(response.getheaders())) if raw else json.loads(data)
        conn.close()
        return result

    def test_auth_host_and_csrf_boundaries(self):
        self.assertEqual(self.request()[0], 200)
        self.assertEqual(self.request(Authorization="")[0], 401)
        self.assertEqual(self.request(Authorization="Bearer wrong")[0], 401)
        self.assertEqual(self.request(Host="attacker.example:8771")[0], 403)
        self.assertEqual(self.request("/stop", {"id":"a"*32}, Origin="https://attacker.example")[0], 403)
        self.assertEqual(self.request("/stop", {"id":"a"*32}, Origin="")[0], 403)
        self.assertEqual(self.request(Authorization="", Cookie="voice=test-secret")[0], 200)

    def test_page_assets_authenticated_and_retired_wake_paths_unavailable(self):
        for path, mime in (("/", "text/html"), ("/voice.js", "text/javascript")):
            code, (data, headers) = self.request(path, raw=True)
            self.assertEqual(code, 200)
            self.assertTrue(data)
            self.assertTrue(headers["Content-Type"].startswith(mime))
            self.assertNotIn("unsafe-eval", headers["Content-Security-Policy"])
            self.assertEqual(self.request(path, Authorization="")[0], 401)
        for path in ("/wake.js", "/vendor/gugugaga.onnx", "/vendor/ort.wasm.min.js",
                     "/vendor/../server.py", "/kws/encoder.int8.onnx", "/voice.js?x=1"):
            self.assertEqual(self.request(path)[0], 404)

    def test_subscription_voice_is_explicit_juniper(self):
        call = voice.VoiceCall.__new__(voice.VoiceCall)
        call.condition, call.sdp, call.error, call.closed = threading.Condition(), "v=0 answer", None, False
        messages = []
        call.write = lambda message: None
        def rpc(method, params):
            messages.append((method, params))
            return {"thread": {"id": "test"}}
        call.rpc = rpc
        self.assertEqual(call.start("v=0", self.temp.name), "v=0 answer")
        realtime = next(params for method, params in messages if method == "thread/realtime/start")
        self.assertEqual(realtime["voice"], "juniper")
        self.assertEqual(realtime["version"], "v3")

    def test_validation_and_call_ownership(self):
        self.assertEqual(self.request("/start", {"id":"a"*32,"sdp":"bad"})[0], 400)
        self.assertEqual(self.request("/start", {"id":"a"*32,"sdp":"v=0"+"x"*33000})[0], 413)
        self.assertEqual(self.request("/start", ["not an object"])[0], 400)
        self.assertEqual(self.request("/start", {"id":"a"*32,"sdp":"v=0"})[0], 200)
        call = self.server.call
        self.assertEqual(self.request("/start", {"id":"b"*32,"sdp":"v=0"})[0], 409)
        self.request("/stop", {"id":"b"*32})
        self.assertFalse(call.closed)
        self.assertTrue(self.request("/ping", {"id":"a"*32})[1]["ok"])
        self.request("/stop", {"id":"a"*32})
        self.assertTrue(call.closed)
        self.assertIsNone(self.server.call)


if __name__ == "__main__":
    unittest.main()
