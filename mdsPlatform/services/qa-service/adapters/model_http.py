"""Close the model connection when the QA deadline or live authority expires."""
from http.client import HTTPConnection
from io import BytesIO
import json
import socket
from threading import Event, Thread
from urllib.error import HTTPError
from urllib.parse import urlsplit

from core.request_budget import BudgetExceeded, current_budget
from core.worker_authority import AuthorityError, current_guard


def post_json(url, data, headers, timeout):
    endpoint = urlsplit(url)
    if (endpoint.scheme != 'http' or endpoint.hostname not in {'127.0.0.1', 'localhost', 'vllm'}
            or endpoint.username or endpoint.password or endpoint.query or endpoint.fragment):
        raise ValueError('Invalid private model endpoint')
    budget = current_budget()
    guard = current_guard()
    connection = HTTPConnection(endpoint.hostname, endpoint.port, timeout=budget.remaining(timeout))
    done = Event()
    failure = []
    watcher = None
    try:
        connection.connect()
        transport = connection.sock
        transport.settimeout(budget.remaining(timeout))

        def monitor():
            while not done.wait(0.4):
                try:
                    budget.remaining()
                    guard.revalidate()
                except (AuthorityError, BudgetExceeded) as error:
                    failure.append(error)
                    try:
                        transport.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    return

        watcher = Thread(target=monitor, name='qa-model-authorization', daemon=True)
        watcher.start()
        try:
            connection.request('POST', endpoint.path, body=data, headers=headers)
            response = connection.getresponse()
            raw = response.read(16 * 1024 * 1024 + 1)
        except Exception:
            if failure:
                raise failure[0]
            raise
        if failure:
            raise failure[0]
        budget.remaining()
        if response.status >= 400:
            raise HTTPError(url, response.status, response.reason, response.headers, BytesIO(raw))
        if response.status != 200 or len(raw) > 16 * 1024 * 1024:
            raise ValueError('Invalid model response status or size')
        return json.loads(raw.decode())
    finally:
        done.set()
        connection.close()
        if watcher is not None:
            watcher.join(timeout=2.2)
