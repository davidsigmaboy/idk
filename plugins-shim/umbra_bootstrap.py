# Umbra - Copyright (c) 2026 Anonymous Author. Grumpy Anonymous Author-First Source-Available Noncommercial License, see LICENSE.md and NOTICE.md.
"""Entry point used by the iOS app: `umbra_bootstrap.handle(json_str) -> json_str`."""
import os

import umbra_host

_host = umbra_host.Host(os.environ.get("UMBRA_PLUGIN_DATA", "."))


def handle(request):
    return _host.handle(request)
