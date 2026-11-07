"""exteraGram `ui.settings` elements. They only describe the form; Umbra renders it natively."""


class _Item:
    kind = "item"
    _fields = ()

    def __init__(self, *args, **kwargs):
        defaults = self._defaults()
        names = list(defaults)
        if len(args) > len(names):
            raise TypeError("too many positional arguments")
        values = dict(defaults)
        values.update(zip(names, args))
        for k, v in kwargs.items():
            if k not in defaults:
                raise TypeError(f"unexpected argument {k!r}")
            values[k] = v
        for k, v in values.items():
            setattr(self, k, v)

    @classmethod
    def _defaults(cls):
        return {}

    def to_dict(self):
        d = {"type": self.kind}
        action = False
        for k in self._defaults():
            v = getattr(self, k)
            if callable(v):
                action = True
            else:
                d[k] = v
        d["has_action"] = action
        return d


class Header(_Item):
    kind = "header"
    @classmethod
    def _defaults(cls): return {"text": ""}

class Divider(_Item):
    kind = "divider"
    @classmethod
    def _defaults(cls): return {"text": ""}

class Text(_Item):
    kind = "text"
    @classmethod
    def _defaults(cls): return {"text": "", "icon": None, "accent": False, "red": False, "on_click": None}

class Switch(_Item):
    kind = "switch"
    @classmethod
    def _defaults(cls): return {"key": "", "text": "", "default": False, "subtext": None, "icon": None,
                                "on_change": None, "on_long_click": None}

class Input(_Item):
    kind = "input"
    @classmethod
    def _defaults(cls): return {"key": "", "text": "", "default": "", "subtext": None, "icon": None, "on_change": None}

class Selector(_Item):
    kind = "selector"
    @classmethod
    def _defaults(cls): return {"key": "", "text": "", "default": 0, "items": None, "icon": None, "on_change": None}

class EditText(_Item):
    kind = "edit_text"
    @classmethod
    def _defaults(cls): return {"key": "", "hint": "", "default": "", "multiline": False, "max_length": None,
                                "mask": None, "on_change": None}
