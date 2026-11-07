"""Umbra plugin host: loads exteraGram `.plugin` files and dispatches hooks to them.

The app talks to it with JSON only:  handle(json_str) -> json_str  (see `Host.handle`).
Ops: load, unload, list, settings (form description), set_setting, send, update, actions.
"""
import ast
import json
import logging
import os
import sys
import types
from typing import Any, Dict, List, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import base_plugin as bp  # noqa: E402
import client_utils  # noqa: E402
from base_plugin import BasePlugin, HookResult, HookStrategy  # noqa: E402

log = logging.getLogger("umbra.host")
SEND_HOOK = "__send_message__"


class TLObject(types.SimpleNamespace):
    """Stand-in for Telegram's TL objects: plain attributes, dict round-trip."""

    def to_dict(self) -> Dict[str, Any]:
        return {k: (v.to_dict() if isinstance(v, TLObject) else v) for k, v in vars(self).items()}

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "TLObject":
        return cls(**{k: (cls.from_dict(v) if isinstance(v, dict) else v) for k, v in d.items()})


def read_metadata(source: str) -> Dict[str, Any]:
    """exteraGram reads `__id__ = ...` etc. with the AST, so only literals are valid."""
    meta: Dict[str, Any] = {}
    for node in ast.parse(source).body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            name = node.targets[0].id
            if name.startswith("__") and name.endswith("__"):
                try:
                    meta[name.strip("_")] = ast.literal_eval(node.value)
                except ValueError:
                    pass
    return meta


class Host:
    SUPPORTED_SDK = "umbra-1"

    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        os.makedirs(data_dir, exist_ok=True)
        self.plugins: Dict[str, BasePlugin] = {}
        self.meta: Dict[str, Dict[str, Any]] = {}
        self._actions: List[Dict[str, Any]] = []
        self._settings: Dict[str, Dict[str, Any]] = {}
        self._files: Dict[str, Any] = {}      # path -> (mtime, plugin id or None)
        self.errors: Dict[str, str] = {}      # file name -> last load error
        client_utils._host = self

    # --- services used by the shim ------------------------------------
    def client(self, account: int = 0):
        return types.SimpleNamespace(account=account)

    def queue_action(self, action: Dict[str, Any]):
        self._actions.append(action)

    def _settings_path(self, pid: str) -> str:
        return os.path.join(self.data_dir, f"{pid}.json")

    def _load_settings(self, pid: str) -> Dict[str, Any]:
        if pid not in self._settings:
            try:
                with open(self._settings_path(pid), encoding="utf-8") as f:
                    self._settings[pid] = json.load(f)
            except (OSError, ValueError):
                self._settings[pid] = {}
        return self._settings[pid]

    def get_setting(self, pid: str, key: str, default: Any = None) -> Any:
        return self._load_settings(pid).get(key, default)

    def set_setting(self, pid: str, key: str, value: Any):
        s = self._load_settings(pid)
        s[key] = value
        with open(self._settings_path(pid), "w", encoding="utf-8") as f:
            json.dump(s, f, ensure_ascii=False)

    def export_settings(self, pid: str) -> Dict[str, Any]:
        return dict(self._load_settings(pid))

    # --- loading --------------------------------------------------------
    def load(self, path: str) -> str:
        with open(path, encoding="utf-8") as f:
            source = f.read()
        meta = read_metadata(source)
        pid = meta.get("id")
        if not isinstance(pid, str) or not (2 <= len(pid) <= 32) or not pid[0].isalpha() \
                or not all(c.isalnum() or c in "_-" for c in pid):
            raise ValueError("plugin has no valid __id__")
        if not meta.get("name"):
            raise ValueError("plugin has no __name__")
        if pid in self.plugins:
            self.unload(pid)

        module = types.ModuleType(f"umbra_plugin_{pid}")
        module.__file__ = path
        exec(compile(source, path, "exec"), module.__dict__)
        cls = next((v for v in vars(module).values()
                    if isinstance(v, type) and issubclass(v, BasePlugin) and v is not BasePlugin), None)
        if cls is None:
            raise ValueError("no BasePlugin subclass in the file")
        plugin = cls()
        plugin.id, plugin.name, plugin.version = pid, meta["name"], str(meta.get("version", "1.0"))
        plugin._host = self
        plugin.logger = logging.getLogger(f"plugin.{pid}")
        plugin.on_plugin_load()
        self.plugins[pid] = plugin
        self.meta[pid] = meta
        return pid

    def unload(self, pid: str):
        plugin = self.plugins.pop(pid, None)
        self.meta.pop(pid, None)
        if plugin:
            plugin.on_plugin_unload()

    def sync(self, directory: str) -> None:
        """Load new / changed `.plugin` files of a folder, unload removed ones. Cheap enough to call per message."""
        os.makedirs(directory, exist_ok=True)
        seen = {}
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".plugin"):
                continue
            path = os.path.join(directory, name)
            try:
                stamp = os.stat(path).st_mtime_ns
            except OSError:
                continue
            seen[path] = stamp
            old = self._files.get(path)
            if old and old[0] == stamp:
                continue
            if old and old[1]:
                self.unload(old[1])
            try:
                self._files[path] = (stamp, self.load(path))
                self.errors.pop(name, None)
            except Exception as e:  # a broken plugin must not stop the others
                self._files[path] = (stamp, None)
                self.errors[name] = f"{type(e).__name__}: {e}"
                log.warning("plugin %s: %s", name, e)
        for path in [p for p in self._files if p not in seen]:
            _, pid = self._files.pop(path)
            if pid:
                self.unload(pid)

    # --- dispatch ------------------------------------------------------
    def _ordered(self, hook_name: str, exact: str):
        found = []
        for plugin in self.plugins.values():
            for h in plugin._hooks:
                hit = (h["name"] in exact) if h["match_substring"] else (h["name"] == exact)
                if hit and h["name"] == hook_name or (hook_name == "*" and hit):
                    found.append((h["priority"], plugin))
        found.sort(key=lambda t: -t[0])
        return [p for _, p in found]

    def send(self, account: int, params: Dict[str, Any]) -> Dict[str, Any]:
        """Outgoing message. `params` is a dict (message, peer, ...). Returns {cancel, params}."""
        obj = TLObject.from_dict(params)
        cancel = False
        for plugin in self._ordered(SEND_HOOK, SEND_HOOK):
            try:
                result = plugin.on_send_message_hook(account, obj)
            except Exception:
                log.exception("plugin %s: on_send_message_hook failed", plugin.id)
                continue
            if not isinstance(result, HookResult) or result.strategy == HookStrategy.DEFAULT:
                continue
            if result.strategy == HookStrategy.CANCEL:
                cancel = True
                break
            if result.params is not None:
                obj = result.params
            if result.strategy == HookStrategy.MODIFY_FINAL:
                break
        return {"cancel": cancel, "params": obj.to_dict() if isinstance(obj, TLObject) else vars(obj)}

    def update(self, account: int, name: str, update: Dict[str, Any]) -> Dict[str, Any]:
        """Incoming update, e.g. name='TL_updateNewMessage'. Returns {cancel, update}."""
        obj = TLObject.from_dict(update)
        cancel = False
        for plugin in self._ordered("*", name):
            if any(h["name"] == SEND_HOOK for h in plugin._hooks) and not any(h["name"] != SEND_HOOK for h in plugin._hooks):
                continue
            try:
                result = plugin.on_update_hook(name, account, obj)
            except Exception:
                log.exception("plugin %s: on_update_hook failed", plugin.id)
                continue
            if not isinstance(result, HookResult) or result.strategy == HookStrategy.DEFAULT:
                continue
            if result.strategy == HookStrategy.CANCEL:
                cancel = True
                break
            if result.update is not None:
                obj = result.update
            if result.strategy == HookStrategy.MODIFY_FINAL:
                break
        return {"cancel": cancel, "update": obj.to_dict() if isinstance(obj, TLObject) else vars(obj)}

    def settings_form(self, pid: str) -> List[Dict[str, Any]]:
        plugin = self.plugins[pid]
        form = []
        for item in plugin.create_settings() or []:
            d = item.to_dict() if hasattr(item, "to_dict") else {"type": "unknown"}
            if "key" in d:
                d["value"] = self.get_setting(pid, d["key"], d.get("default"))
            form.append(d)
        return form

    # --- JSON entry point for the app ------------------------------------
    def handle(self, request: str) -> str:
        try:
            r = json.loads(request)
            op = r["op"]
            if op == "load":
                out: Any = {"id": self.load(r["path"])}
            elif op == "sync":
                self.sync(r["dir"]); out = {"errors": self.errors, "count": len(self.plugins)}
            elif op == "unload":
                self.unload(r["id"]); out = {}
            elif op == "list":
                out = [{"id": p, "name": pl.name, "version": pl.version, **{k: v for k, v in self.meta[p].items()
                        if k in ("description", "author", "icon")}} for p, pl in self.plugins.items()]
            elif op == "settings":
                out = self.settings_form(r["id"])
            elif op == "set_setting":
                self.set_setting(r["id"], r["key"], r["value"]); out = {}
            elif op == "send":
                out = self.send(int(r.get("account", 0)), r["params"])
            elif op == "update":
                out = self.update(int(r.get("account", 0)), r["name"], r["update"])
            elif op == "actions":
                out, self._actions = self._actions, []
            else:
                raise ValueError(f"unknown op {op}")
            return json.dumps({"ok": True, "result": out}, ensure_ascii=False)
        except Exception as e:  # never let a plugin kill the app
            return json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}"}, ensure_ascii=False)
