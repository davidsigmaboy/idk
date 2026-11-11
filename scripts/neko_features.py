#!/usr/bin/env python3
"""Umbra features for the Nekogram-based Android client. Usage: neko_features.py <nekogram-dir>
Every edit asserts that it found its target."""
import pathlib, sys

root = pathlib.Path(sys.argv[1])


def edit(path, old, new):
    p = root / path
    s = p.read_text()
    assert s.count(old) == 1, f"{path}: expected exactly one match for {old[:70]!r}, got {s.count(old)}"
    p.write_text(s.replace(old, new))


java = root / "TMessagesProj/src/main/java/app/umbra"
java.mkdir(parents=True, exist_ok=True)

# ---- Keep chats you were removed from (kicked / banned) in the chat list with their saved messages ----
(java / "KeepKicked.java").write_text("""package app.umbra;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

/**
 * Telegram normally deletes a group from the chat list and wipes its saved messages when you are kicked.
 * Umbra keeps it. Only what is already stored on the phone can be read: the server sends nothing more
 * once you are out, and leaving a chat yourself still removes it as usual.
 */
public final class KeepKicked {
    private KeepKicked() {
    }

    public static boolean enabled() {
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("umbra", Context.MODE_PRIVATE);
        return p.getBoolean("keep_kicked", true);
    }

    /** True when the chat must stay in the list although the account is no longer a member. */
    public static boolean keep(TLRPC.Chat chat) {
        if (chat == null || !enabled()) {
            return false;
        }
        if (chat.left) {
            return false;
        }
        return chat.kicked || chat instanceof TLRPC.TL_channelForbidden || chat instanceof TLRPC.TL_chatForbidden;
    }
}
""")
mc = "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
edit(mc, "} else if (ChatObject.isNotInChat(chat) && dialog != null && (promoDialog == null || promoDialog.id != dialog.id)) {\n                                deleteDialog(dialog.id, 0);",
         "} else if (ChatObject.isNotInChat(chat) && !app.umbra.KeepKicked.keep(chat) && dialog != null && (promoDialog == null || promoDialog.id != dialog.id)) {\n                                deleteDialog(dialog.id, 0);")
edit(mc, "                            TLRPC.Dialog dialog = dialogs_dict.get(-chat.id);\n                            if (dialog != null) {\n                                deleteDialog(dialog.id, 0);\n                            }",
         "                            TLRPC.Dialog dialog = dialogs_dict.get(-chat.id);\n                            if (dialog != null && !app.umbra.KeepKicked.keep(chat)) {\n                                deleteDialog(dialog.id, 0);\n                            }")
print("features applied")
