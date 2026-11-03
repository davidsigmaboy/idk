package app.umbra.client

import android.content.Context
import android.content.SharedPreferences

/** Umbra feature switches, stored locally. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("umbra", Context.MODE_PRIVATE)

    /** Do not tell the sender that messages were read; chat history is opened silently. */
    var ghostMode: Boolean
        get() = sp.getBoolean("ghostMode", false)
        set(v) = sp.edit().putBoolean("ghostMode", v).apply()

    /** Keep messages that the sender (or an admin) deleted. They are shown with a mark. */
    var keepDeleted: Boolean
        get() = sp.getBoolean("keepDeleted", true)
        set(v) = sp.edit().putBoolean("keepDeleted", v).apply()

    /** Collapse double spaces / extra blank lines before sending. */
    var tidySpaces: Boolean
        get() = sp.getBoolean("tidySpaces", false)
        set(v) = sp.edit().putBoolean("tidySpaces", v).apply()

    /** "..." -> "…", " - " -> " — " before sending. */
    var smartPunctuation: Boolean
        get() = sp.getBoolean("smartPunctuation", false)
        set(v) = sp.edit().putBoolean("smartPunctuation", v).apply()

    /** ALL CAPS -> Sentence case before sending. */
    var antiCaps: Boolean
        get() = sp.getBoolean("antiCaps", false)
        set(v) = sp.edit().putBoolean("antiCaps", v).apply()

    /** Seconds to wait before a message really goes out; can be cancelled meanwhile (0 = off). */
    var sendDelaySeconds: Int
        get() = sp.getInt("sendDelay", 0)
        set(v) = sp.edit().putInt("sendDelay", v).apply()
}
