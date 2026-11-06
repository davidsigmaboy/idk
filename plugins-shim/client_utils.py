"""exteraGram `client_utils`: only what has an equivalent in Umbra."""
import threading
from typing import Any, Callable

_host = None  # set by umbra_host


def run_on_queue(fn: Callable[[], Any], queue: Any = None, delay: float = 0) -> None:
    t = threading.Timer(delay / 1000.0 if delay else 0, fn)
    t.daemon = True
    t.start()


def get_account_instance(account: int = 0):
    return _host.client(account) if _host else None


def send_message(params: dict) -> None:
    """exteraGram: get_send_messages_helper().sendMessage(...). Umbra: queue a message for the host."""
    if _host:
        _host.queue_action({"op": "send_message", **params})


def get_send_messages_helper(account: int = 0):
    class _Helper:
        def sendMessage(self, params):
            send_message(params if isinstance(params, dict) else vars(params))
    return _Helper()
