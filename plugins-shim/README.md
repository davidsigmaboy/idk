# Umbra plugin layer for exteraGram plugins

Re-implements the public exteraGram plugin API in plain Python, so that `.plugin` files written
for exteraGram can run in Umbra **without Android**.

| exteraGram API | Status |
|---|---|
| Metadata (`__id__`, `__name__`, `__version__`, ...) read with the AST | works |
| `BasePlugin`, `HookResult`, `HookStrategy`, `AppEvent` | works |
| `on_plugin_load / on_plugin_unload`, `add_on_send_message_hook`, `add_hook(..)` | works |
| `on_send_message_hook` (modify / cancel), `on_update_hook` (cancel / modify), priorities | works |
| `create_settings` with `Header, Divider, Text, Switch, Input, Selector, EditText`, `get/set_setting` (saved to JSON) | works (form is described as JSON; Umbra draws it) |
| `client_utils.run_on_queue`, `android_utils.run_on_ui_thread/log`, `BulletinHelper` | works (simple equivalents) |
| `pre_request_hook` / `post_request_hook` (raw MTProto requests) | **not available**: Umbra has no raw request layer |
| `java.*`, `jclass`, Xposed hooking, `android.*`, Android views | **not possible** on iOS |
| plugins that need pip packages (`__requirements__`) | only pure-Python ones |

So plugins that edit/cancel outgoing messages, react to incoming ones and have a settings page
(most "text tools", filters, auto-replies) can work; plugins that patch Android internals cannot.

`umbra_host.py` is the single entry point, JSON in / JSON out: `Host(data_dir).handle('{"op":"load","path":"x.plugin"}')`.
Ops: `load, unload, list, settings, set_setting, send, update, actions`.

Tests: `python3 -m unittest discover -s tests` (6 tests, use plugins written from the exteraGram docs).

## Not done yet (needs a Mac build)

The iOS app has no Python interpreter yet. Still to do: embed CPython (e.g. Python-Apple-support) in the
Bazel build, call `Host.handle` from the outgoing pipeline (`GGOutgoingPipeline`) and the incoming-message
hook, and a settings screen that draws the JSON form.

## How it is embedded in the iOS app

- `Ghostgram/Features/Messages/Sources/Plugins/GGPluginHost.swift` (in `patches/umbra-plugins.patch`) opens
  `Python.framework` with `dlopen` and calls `umbra_bootstrap.handle(json)`. No Python in the app → nothing happens.
- The CI step "Add Python and the plugin layer to the IPA" adds `Python.framework` (BeeWare Python-Apple-support, 3.13),
  the standard library, this folder (`python/umbra`) and turns on the Files-app access to the app's Documents.
- Switch: **Settings → Umbra → Sending → Plugins**. Put `.plugin` files into *Files → Umbra → Plugins*.
- Today plugins run on **outgoing text messages** (modify). Cancel and incoming-message hooks are not wired to the app yet.
