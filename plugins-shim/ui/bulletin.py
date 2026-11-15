# Umbra - PolyForm Noncommercial License 1.0.0, see LICENSE.md and NOTICE.md.
class BulletinHelper:
    """Toasts: forwarded to the host (shown natively in Umbra)."""
    _sink = staticmethod(lambda kind, text: None)

    @classmethod
    def show_info(cls, message, fragment=None): cls._sink("info", message)
    @classmethod
    def show_error(cls, message, fragment=None): cls._sink("error", message)
    @classmethod
    def show_success(cls, message, fragment=None): cls._sink("success", message)
