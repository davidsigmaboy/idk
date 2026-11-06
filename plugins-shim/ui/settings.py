"""exteraGram `ui.settings` elements. They only describe the form; Umbra renders it natively."""
from dataclasses import dataclass, field
from typing import Any, Callable, List, Optional


class _Item:
    kind = "item"

    def to_dict(self):
        d = {"type": self.kind}
        for k, v in self.__dict__.items():
            if callable(v) or k.startswith("_"):
                continue
            d[k] = v
        d["has_action"] = any(callable(v) for v in self.__dict__.values())
        return d


@dataclass
class Header(_Item):
    text: str = ""
    kind = "header"

@dataclass
class Divider(_Item):
    text: str = ""
    kind = "divider"

@dataclass
class Text(_Item):
    text: str = ""
    icon: Optional[str] = None
    accent: bool = False
    red: bool = False
    on_click: Optional[Callable[..., Any]] = None
    kind = "text"

@dataclass
class Switch(_Item):
    key: str = ""
    text: str = ""
    default: bool = False
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[bool], Any]] = None
    on_long_click: Optional[Callable[..., Any]] = None
    kind = "switch"

@dataclass
class Input(_Item):
    key: str = ""
    text: str = ""
    default: str = ""
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[str], Any]] = None
    kind = "input"

@dataclass
class Selector(_Item):
    key: str = ""
    text: str = ""
    default: int = 0
    items: List[str] = field(default_factory=list)
    icon: Optional[str] = None
    on_change: Optional[Callable[[int], Any]] = None
    kind = "selector"

@dataclass
class EditText(_Item):
    key: str = ""
    hint: str = ""
    default: str = ""
    multiline: bool = False
    max_length: Optional[int] = None
    mask: Optional[str] = None
    on_change: Optional[Callable[[str], Any]] = None
    kind = "edit_text"
