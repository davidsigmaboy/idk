"""exteraGram `base_plugin` API, re-implemented for Umbra (subset used by most plugins)."""
import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional


class HookStrategy(Enum):
    DEFAULT = "DEFAULT"
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    MODIFY_FINAL = "MODIFY_FINAL"


class AppEvent(Enum):
    START = "START"
    STOP = "STOP"
    PAUSE = "PAUSE"
    RESUME = "RESUME"


class HookResult:
    def __init__(self, strategy: HookStrategy = HookStrategy.DEFAULT, request: Any = None, response: Any = None,
                 update: Any = None, updates: Any = None, params: Any = None):
        self.strategy = strategy
        self.request = request
        self.response = response
        self.update = update
        self.updates = updates
        self.params = params


@dataclass
class MenuItemData:
    menu_type: Any = None
    text: str = ""
    on_click: Optional[Callable[..., Any]] = None
    item_id: Optional[str] = None
    icon: Optional[str] = None
    condition: Optional[str] = None
    priority: int = 0


class BasePlugin:
    """Hooks are dispatched by `umbra_host`. Metadata (`id`, `name`, ...) is filled in by the loader."""

    id: str = ""
    name: str = ""
    version: str = "1.0"

    def __init__(self):
        self._host = None
        self._hooks: List[Dict[str, Any]] = []
        self._menu_items: Dict[str, MenuItemData] = {}
        self.logger = logging.getLogger("plugin.unknown")

    # lifecycle -------------------------------------------------------
    def on_plugin_load(self): pass
    def on_plugin_unload(self): pass
    def on_app_event(self, event_type: AppEvent): pass

    # settings --------------------------------------------------------
    def create_settings(self) -> List[Any]:
        return []

    def get_setting(self, key: str, default: Any = None) -> Any:
        return self._host.get_setting(self.id, key, default) if self._host else default

    def set_setting(self, key: str, value: Any, reload_settings: bool = False):
        if self._host:
            self._host.set_setting(self.id, key, value)

    def export_settings(self) -> Dict[str, Any]:
        return self._host.export_settings(self.id) if self._host else {}

    def import_settings(self, settings: Dict[str, Any], reload_settings: bool = True):
        if self._host:
            for k, v in settings.items():
                self._host.set_setting(self.id, k, v)

    # hooks -----------------------------------------------------------
    def add_hook(self, name: str, match_substring: bool = False, priority: int = 0):
        self._hooks.append({"name": name, "match_substring": match_substring, "priority": priority})

    def remove_hook(self, name: str):
        self._hooks = [h for h in self._hooks if h["name"] != name]

    def add_on_send_message_hook(self, priority: int = 0):
        self._hooks.append({"name": "__send_message__", "match_substring": False, "priority": priority})

    def add_menu_item(self, item: MenuItemData) -> str:
        item_id = item.item_id or f"{self.id}:{len(self._menu_items)}"
        self._menu_items[item_id] = item
        return item_id

    def remove_menu_item(self, item_id: str):
        self._menu_items.pop(item_id, None)

    # default hook bodies (plugins override what they need) -----------
    def pre_request_hook(self, request_name: str, account: int, request: Any) -> HookResult: return HookResult()
    def post_request_hook(self, request_name: str, account: int, response: Any, error: Any) -> HookResult: return HookResult()
    def on_update_hook(self, update_name: str, account: int, update: Any) -> HookResult: return HookResult()
    def on_updates_hook(self, container_name: str, account: int, updates: Any) -> HookResult: return HookResult()
    def on_send_message_hook(self, account: int, params: Any) -> HookResult: return HookResult()

    def log(self, message: str):
        self.logger.info(message)

    def client(self, account: int = 0):
        return self._host.client(account) if self._host else None
