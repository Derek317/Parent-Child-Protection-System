"""Tiny JSON-over-HTTP client for a local server (urllib only)."""
import json
import time
import urllib.error
import urllib.request


def request(port, path, payload=None, timeout=900, retries=3):
    url = f"http://127.0.0.1:{port}{path}"
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"} if data is not None else {}
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=data, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{path}: HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:500]}") from None
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == retries - 1:
                raise
            time.sleep(2)
