# Umbra - Copyright (c) 2026. Non-Commercial Source-Available License (NCSAL) v1.0, see LICENSE.md and NOTICE.md.
"""exteraGram `android_utils`: UI-thread helpers become plain calls; logging goes to the host log."""
import logging

_log = logging.getLogger("plugin.android_utils")


def run_on_ui_thread(fn, delay: float = 0):
    fn()


def log(message):
    _log.info(str(message))


def copy_to_clipboard(text: str):
    from client_utils import _host
    if _host:
        _host.queue_action({"op": "copy", "text": text})
