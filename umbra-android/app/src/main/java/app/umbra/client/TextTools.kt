package app.umbra.client

/** Outgoing text transforms. Links, @mentions, /commands and #tags are never touched. */
object TextTools {
    private val protectedRe = Regex(
        "(?i)\\b(?:https?://|tg://|www\\.)\\S+|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+|(?<![\\p{L}\\p{N}_@])@\\w{3,32}|(?<![\\p{L}\\p{N}_/])/\\w{1,64}|(?<![\\p{L}\\p{N}_#])#[\\p{L}\\p{N}_]+"
    )

    private fun mapOutside(text: String, f: (String) -> String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in protectedRe.findAll(text)) {
            sb.append(f(text.substring(last, m.range.first)))
            sb.append(m.value)
            last = m.range.last + 1
        }
        sb.append(f(text.substring(last)))
        return sb.toString()
    }

    fun tidySpaces(text: String): String =
        mapOutside(text) { part ->
            part.replace(Regex("[ \\t]+(?=\\R)"), "")
                .replace(Regex("(?<=\\S)[ ]{2,}"), " ")
                .replace(Regex("(\\R){3,}"), "\n\n")
        }.trimEnd()

    fun smartPunctuation(text: String): String =
        mapOutside(text) { part ->
            part.replace(Regex("\\.{3,}"), "…")
                .replace(Regex("(?<= )-{1,2}(?= )"), "—")
        }

    fun antiCaps(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return text
        val upper = letters.count { it.isUpperCase() }
        val lower = letters.count { it.isLowerCase() }
        if (lower != 0 && upper.toDouble() / letters.length < 0.8) return text
        return mapOutside(text) { part ->
            val sb = StringBuilder()
            var start = true
            for (c in part) {
                when {
                    c.isLetter() -> { sb.append(if (start) c.uppercaseChar() else c.lowercaseChar()); start = false }
                    c == '.' || c == '!' || c == '?' || c == '…' || c == '\n' -> { sb.append(c); start = true }
                    else -> { sb.append(c); if (c.isDigit()) start = false }
                }
            }
            sb.toString()
        }
    }

    fun apply(text: String, p: Prefs): String {
        if (text.startsWith("/")) return text
        var t = text
        if (p.antiCaps) t = antiCaps(t)
        if (p.tidySpaces) t = tidySpaces(t)
        if (p.smartPunctuation) t = smartPunctuation(t)
        return t
    }
}
