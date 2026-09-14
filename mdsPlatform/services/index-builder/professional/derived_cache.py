"""Immutable content-addressed derived shards. Only current references are copied forward."""
import hashlib
import gzip
import json
from collections import Counter
from pathlib import Path

from .contracts import json_safe, write_json


class DerivedCache:
    VERSION = "professional-derived-1.12"

    def __init__(self, current: Path, previous: Path | None):
        self.current, self.previous = current, previous
        self.stats = Counter()
        self.data, self.old = {}, {}

    def _key(self, kind, inputs):
        return hashlib.sha256(json.dumps([self.VERSION, kind, inputs], ensure_ascii=False, sort_keys=True,
                                         separators=(",", ":"), default=str).encode()).hexdigest()

    def get(self, kind, inputs):
        key = self._key(kind, inputs)
        if kind not in self.old:
            self.old[kind] = {}
            path = self.previous / f"{kind}.json.gz" if self.previous else None
            if path and path.is_file():
                try:
                    with gzip.open(path,"rt",encoding="utf-8") as stream: self.old[kind] = json.load(stream)
                except (OSError, ValueError): pass
        for data in (self.data.get(kind, {}), self.old[kind]):
            if key not in data: continue
            try:
                wrapper = data[key]
                encoded = json.dumps(wrapper["payload"], sort_keys=True, ensure_ascii=False, separators=(",", ":"))
                if hashlib.sha256(encoded.encode()).hexdigest() != wrapper["sha256"]: continue
                self.stats[f"{kind}Reused"] += 1
                self.data.setdefault(kind,{})[key] = wrapper
                return wrapper["payload"]
            except (OSError, ValueError, KeyError):
                continue
        return None

    def put(self, kind, inputs, payload):
        payload = json_safe(payload)
        encoded = json.dumps(payload, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        self.data.setdefault(kind,{})[self._key(kind,inputs)] = {"payload":payload,"sha256":hashlib.sha256(encoded.encode()).hexdigest()}
        self.stats[f"{kind}Built"] += 1
        return payload

    def memo(self, kind, inputs, factory):
        value = self.get(kind, inputs)
        return value if value is not None else self.put(kind, inputs, factory())

    def flush(self):
        self.current.mkdir(parents=True,exist_ok=True)
        for kind, records in self.data.items():
            with gzip.open(self.current / f"{kind}.json.gz","wt",encoding="utf-8",compresslevel=3) as stream:
                json.dump(records,stream,ensure_ascii=False,separators=(",",":"))
