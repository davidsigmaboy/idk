# Umbra - Copyright (c) 2026. Non-Commercial Source-Available License (NCSAL) v1.0, see LICENSE.md and NOTICE.md.
"""Entry point used by the iOS app: `umbra_bootstrap.handle(json_str) -> json_str`."""
import os

import umbra_host

_host = umbra_host.Host(os.environ.get("UMBRA_PLUGIN_DATA", "."))


def handle(request):
    return _host.handle(request)
