#!/usr/bin/env python3
import http.server, os
from pathlib import Path
ROOT=Path("/srv")
class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self,*a,**kw): super().__init__(*a,directory=str(ROOT),**kw)
    def do_GET(self):
        if self.path=="/health":
            self.send_response(200); self.send_header("Content-Type","text/plain"); self.end_headers(); self.wfile.write(b"ok"); return
        if self.path=="/":
            self.send_response(200); self.send_header("Content-Type","text/html; charset=utf-8"); self.end_headers()
            self.wfile.write(b'<html><meta charset="utf-8"><h2>Hamed Smart Delivery 0.4.3</h2><a href="/hamed-smart-delivery-0.4.3-debug.apk">Download debug APK</a><br><a href="/riyadh-build-report.json">Riyadh DB report</a><br><a href="/apk-build-report.json">APK verification report</a><br><a href="/apk-signature.txt">APK signing certificate</a></html>'); return
        return super().do_GET()
http.server.ThreadingHTTPServer(("0.0.0.0",int(os.environ.get("PORT","10000"))),Handler).serve_forever()
