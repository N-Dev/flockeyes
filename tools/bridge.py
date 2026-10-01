#!/usr/bin/env python3
"""Runs ONNX models for the Kotlin tests on machines without ONNX Runtime for Java.

  python3 tools/bridge.py [port]        then run the tests with -Dflockeyes.bridge=http://127.0.0.1:PORT

The tests post a model once (/load) and then inputs (/run); answers come back as raw float32. On GitHub the
tests use ONNX Runtime for Java directly, as the app does, and this isn't needed.
"""
import hashlib
import json
import struct
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import numpy as np
import onnxruntime as ort

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8790
SESSIONS = {}
DT = {"float32": np.float32, "uint8": np.uint8}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/octet-stream"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        u = urlparse(self.path)
        data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            if u.path == "/load":
                sid = hashlib.sha1(data).hexdigest()[:12]
                if sid not in SESSIONS:
                    so = ort.SessionOptions()
                    so.intra_op_num_threads = 2
                    SESSIONS[sid] = ort.InferenceSession(data, so, providers=["CPUExecutionProvider"])
                s = SESSIONS[sid]
                out = {"sid": sid, "inputNames": [i.name for i in s.get_inputs()], "outputNames": [o.name for o in s.get_outputs()]}
                return self._send(200, json.dumps(out).encode(), "application/json")
            if u.path == "/run":
                s = SESSIONS[parse_qs(u.query)["sid"][0]]
                hl = struct.unpack("<I", data[:4])[0]
                header = json.loads(data[4:4 + hl])
                o = 4 + hl
                feeds = {}
                for f in header["feeds"]:
                    feeds[f["name"]] = np.frombuffer(data[o:o + f["byteLength"]], dtype=DT[f["type"]]).reshape(f["dims"])
                    o += f["byteLength"]
                names = [x.name for x in s.get_outputs()]
                outs = s.run(names, feeds)
                meta, bufs = [], []
                for name, arr in zip(names, outs):
                    b = np.ascontiguousarray(arr.astype(np.float32)).tobytes()
                    meta.append({"name": name, "dims": list(arr.shape), "byteLength": len(b)})
                    bufs.append(b)
                hb = json.dumps({"outputs": meta}).encode()
                return self._send(200, struct.pack("<I", len(hb)) + hb + b"".join(bufs))
        except Exception as e:  # noqa: BLE001
            return self._send(500, repr(e).encode(), "text/plain")
        return self._send(404, b"not found", "text/plain")


if __name__ == "__main__":
    print(f"model bridge on {PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
