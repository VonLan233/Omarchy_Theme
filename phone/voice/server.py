#!/usr/bin/env python3
"""Private loopback bridge for Codex's subscription-authenticated WebRTC route."""
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent
ORIGIN = "http://127.0.0.1:8771"
STATE = Path.home() / ".local/share/feiyu-voice"
PROMPT = ("你是飞鱼的语音助手，默认用简洁自然的中文回答。"
          "只进行语音交流，不使用工具，不访问文件，不执行命令或操作手机。"
          "如果用户要求这些操作，说明当前版本只支持语音问答。")


class VoiceCall:
    def __init__(self, ident, workspace):
        self.ident, self.last_seen = ident, time.monotonic()
        self.condition = threading.Condition()
        self.responses, self.seq, self.sdp = {}, 0, None
        self.error, self.closed = None, False
        self.process = subprocess.Popen(
            ["codex", "app-server", "--listen", "stdio://"], cwd=workspace,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, bufsize=1)
        threading.Thread(target=self.read, daemon=True).start()

    def write(self, message):
        self.process.stdin.write(json.dumps(message, ensure_ascii=False) + "\n")
        self.process.stdin.flush()

    def read(self):
        try:
            for line in self.process.stdout:
                message = json.loads(line)
                with self.condition:
                    if "id" in message and "method" in message:
                        # Voice UI has no approval flow; never grant backend tool requests.
                        self.write({"id": message["id"], "error": {
                            "code": -32000, "message": "Tools are disabled in this voice UI"}})
                    elif "id" in message:
                        self.responses[message["id"]] = message
                    elif message.get("method") == "thread/realtime/sdp":
                        self.sdp = message["params"]["sdp"]
                    elif message.get("method") == "thread/realtime/error":
                        self.error = "语音服务连接失败，请稍后重试"
                    self.condition.notify_all()
        except (ValueError, OSError):
            pass
        finally:
            with self.condition:
                self.error = self.error or "语音连接已结束"
                self.condition.notify_all()

    def rpc(self, method, params, timeout=45):
        with self.condition:
            if self.closed:
                raise RuntimeError("会话已停止")
            self.seq += 1
            ident = self.seq
            self.write({"id": ident, "method": method, "params": params})
            ready = self.condition.wait_for(
                lambda: ident in self.responses or self.error or self.closed, timeout)
            if not ready or ident not in self.responses:
                raise RuntimeError(self.error or "语音服务连接超时")
            message = self.responses.pop(ident)
            if "error" in message:
                raise RuntimeError("Codex 未能建立语音会话，请检查订阅登录")
            return message["result"]

    def start(self, offer, workspace):
        self.rpc("initialize", {"clientInfo": {"name": "feiyu_voice", "version": "0.1"},
                                "capabilities": {"experimentalApi": True}})
        with self.condition:
            self.write({"method": "initialized"})
        thread = self.rpc("thread/start", {"cwd": str(workspace), "ephemeral": True,
            "sandbox": "read-only", "approvalPolicy": "on-request", "baseInstructions": PROMPT})
        self.thread_id = thread["thread"]["id"]
        self.rpc("thread/realtime/start", {"threadId": self.thread_id,
            "outputModality": "audio", "version": "v3", "voice": "juniper", "includeStartupContext": False,
            "transport": {"type": "webrtc", "sdp": offer}, "prompt": PROMPT})
        with self.condition:
            self.condition.wait_for(lambda: self.sdp or self.error or self.closed, 45)
            if self.closed or self.error or not self.sdp:
                raise RuntimeError(self.error or "语音连接未完成")
            return self.sdp

    def stop(self):
        with self.condition:
            if self.closed:
                return
            self.closed = True
            try:
                if hasattr(self, "thread_id"):
                    self.write({"id": 99999, "method": "thread/realtime/stop",
                                "params": {"threadId": self.thread_id}})
                self.process.stdin.close()
            except OSError:
                pass
            self.condition.notify_all()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()


class VoiceServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, token, workspace, call_factory=VoiceCall):
        super().__init__(address, Handler)
        self.token, self.workspace, self.call_factory = token, workspace, call_factory
        self.call, self.lock = None, threading.Lock()

    def stop_call(self, ident=None):
        with self.lock:
            call = self.call
            if not call or (ident is not None and call.ident != ident):
                return
            self.call = None
        call.stop()

    def watchdog(self):
        while True:
            time.sleep(10)
            with self.lock:
                call = self.call
                expired = call and time.monotonic() - call.last_seen > 90
            if expired:
                self.stop_call(call.ident)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Never persist auth, SDP or conversation content.

    def reply(self, status, data, mime="application/json"):
        if isinstance(data, dict):
            data = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; "
                         "style-src 'self'; font-src 'self'; connect-src 'self'; media-src blob:; "
                         "frame-ancestors 'none'; base-uri 'none'")
        self.send_header("Permissions-Policy", "microphone=(self), camera=()")
        if status == 200 and self.path == "/":
            self.send_header("Set-Cookie", "voice=" + self.server.token + "; HttpOnly; SameSite=Strict; Path=/")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def authorized(self, post=False):
        if self.headers.get("Host") != ORIGIN.removeprefix("http://"):
            self.reply(403, {"error": "Invalid host"})
            return False
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get("Cookie", ""))
        except Exception:
            pass
        header = self.headers.get("Authorization", "")
        token = header[7:] if header.startswith("Bearer ") else (
            cookie["voice"].value if "voice" in cookie else "")
        if not hmac.compare_digest(token.encode(), self.server.token.encode()):
            self.reply(401, {"error": "请从像素语音入口打开"})
            return False
        if post and self.headers.get("Origin") != ORIGIN:
            self.reply(403, {"error": "Invalid origin"})
            return False
        return True

    def do_GET(self):
        if not self.authorized():
            return
        if self.path == "/health":
            self.reply(200, {"ok": True})
            return
        assets = {"/": (ROOT / "index.html", "text/html; charset=utf-8"),
                  "/voice.js": (ROOT / "voice.js", "text/javascript; charset=utf-8"),
                  "/voice.css": (ROOT / "voice.css", "text/css; charset=utf-8"),
                  "/font.ttf": (Path.home() / ".termux/font.ttf", "font/ttf")}
        if self.path not in assets:
            self.reply(404, {"error": "Not found"})
            return
        path, mime = assets[self.path]
        try:
            self.reply(200, path.read_bytes(), mime)
        except OSError:
            self.reply(404, {"error": "资源未安装"})

    def do_POST(self):
        self.connection.settimeout(10)
        if not self.authorized(post=True):
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 32000 or self.headers.get("Transfer-Encoding"):
                self.reply(413, {"error": "Invalid size"})
                return
            if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                raise ValueError()
            body = json.loads(self.rfile.read(length))
            ident = body.get("id", "")
            if not isinstance(ident, str) or len(ident) != 32 or any(c not in "0123456789abcdef" for c in ident):
                raise ValueError()
        except (ValueError, AttributeError, OSError):
            self.reply(400, {"error": "Invalid request"})
            return
        if self.path == "/stop":
            self.server.stop_call(ident)
            self.reply(200, {"ok": True})
        elif self.path == "/ping":
            with self.server.lock:
                call = self.server.call
                if call and call.ident == ident:
                    call.last_seen = time.monotonic()
                    ok = not call.closed and not call.error
                else:
                    ok = False
            self.reply(200, {"ok": ok})
        elif self.path == "/start":
            sdp = body.get("sdp", "")
            if not isinstance(sdp, str) or not sdp.startswith("v=0") or len(sdp) > 24000:
                self.reply(400, {"error": "Invalid SDP"})
                return
            with self.server.lock:
                if self.server.call:
                    self.reply(409, {"error": "已有语音会话，请先停止"})
                    return
                try:
                    call = self.server.call_factory(ident, self.server.workspace)
                except OSError:
                    self.reply(503, {"error": "无法启动 Codex，请在 Termux 检查安装"})
                    return
                self.server.call = call
            try:
                answer = call.start(sdp, self.server.workspace)
                self.reply(200, {"sdp": answer})
            except (RuntimeError, OSError):
                self.server.stop_call(ident)
                self.reply(503, {"error": "语音连接失败，请确认 Termux 中 Codex 已登录后重试"})
        else:
            self.reply(404, {"error": "Not found"})


if __name__ == "__main__":
    os.umask(0o077)
    STATE.mkdir(parents=True, exist_ok=True)
    workspace = STATE / "workspace"
    workspace.mkdir(exist_ok=True)
    token_file = STATE / "token"
    if not token_file.exists():
        token_file.write_text(secrets.token_hex(32))
    token_file.chmod(0o600)
    server = VoiceServer(("127.0.0.1", 8771), token_file.read_text().strip(), workspace)
    threading.Thread(target=server.watchdog, daemon=True).start()
    print("像素语音服务已启动；请保持 Termux 运行。", flush=True)
    try:
        server.serve_forever()
    finally:
        server.stop_call()
        server.server_close()
