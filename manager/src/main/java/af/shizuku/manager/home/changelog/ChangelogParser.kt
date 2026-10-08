package af.shizuku.manager.home.changelog

/**
 * Splits a GitHub release body (markdown headings + bullet lists) into categorised sections.
 * Anything before the first heading lands in an "Other" section; the "Recent Releases" digest
 * appended to old release bodies is dropped.
 */
object ChangelogParser {
    private val HEADING = Regex("""^#{2,4}\s+(.+?)\s*$""")
    private val BULLET = Regex("""^\s*[-*]\s+(.+)$""")
    private val COMMIT_HASH = Regex("""\s+\([0-9a-f]{7,8}\)\s*$""")
    private val CC_PREFIX =
        Regex(
            """^(fix|feat|chore|refactor|perf|test|docs|build|ci|style|revert)(\([^)]+\))?!?:\s*""",
            RegexOption.IGNORE_CASE,
        )
    private val LEADING_DECOR = Regex("""^[^\p{L}\p{N}]+""")

    fun parse(body: String): List<ChangeSection> {
        val cleaned = body.substringBefore("## 📦 Recent Releases").lines()
        val sections = mutableListOf<ChangeSection>()
        var title = ""
        var items = mutableListOf<String>()
        var pending: StringBuilder? = null

        fun flushPending() {
            pending?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { items.add(clean(it)) }
            pending = null
        }

        fun flushSection() {
            flushPending()
            if (items.isNotEmpty()) sections.add(ChangeSection(kindOf(title, items), displayTitle(title), items))
            items = mutableListOf()
        }

        for (line in cleaned) {
            val h = HEADING.matchEntire(line)
            if (h != null) {
                flushSection()
                title = h.groupValues[1]
                continue
            }
            if (line.startsWith("# ")) continue
            val b = BULLET.matchEntire(line)
            if (b != null) {
                flushPending()
                pending = StringBuilder(b.groupValues[1])
            } else if (pending != null && line.isNotBlank() && line.startsWith("  ")) {
                pending!!.append(' ').append(line.trim())
            } else if (line.isBlank()) {
                flushPending()
            }
        }
        flushSection()
        return mergeSameKind(sections)
    }

    private fun mergeSameKind(sections: List<ChangeSection>): List<ChangeSection> {
        val order = ChangeKind.values()
        return sections
            .groupBy { it.kind }
            .map { (kind, list) ->
                ChangeSection(kind, list.first().title.takeIf { list.size == 1 } ?: "", list.flatMap { it.items })
            }.sortedBy { order.indexOf(it.kind) }
    }

    private fun clean(text: String): String {
        val noHash = COMMIT_HASH.replace(text.trim(), "")
        val stripped = CC_PREFIX.replaceFirst(noHash, "")
        return stripped.replaceFirstChar { it.uppercase() }
    }

    private fun displayTitle(heading: String): String = heading.replace(LEADING_DECOR, "").trim()

    private fun kindOf(
        heading: String,
        items: List<String>,
    ): ChangeKind {
        val h = heading.lowercase()
        return when {
            "fix" in h || "bug" in h || "crash" in h -> ChangeKind.FIX
            "feat" in h || "added" in h || "new" in h || "✨" in h -> ChangeKind.FEATURE
            "ui" in h || "visual" in h || "design" in h || "polish" in h || "🎨" in h -> ChangeKind.UI
            "doc" in h || "📖" in h || "📚" in h -> ChangeKind.DOCS
            heading.isBlank() || h == "other" || h == "misc" -> inferFromItems(items)
            else -> ChangeKind.OTHER
        }
    }

    // Untitled/"Other" lists: infer from the dominant conventional-commit-ish verb.
    private fun inferFromItems(items: List<String>): ChangeKind {
        val first = items.firstOrNull()?.lowercase() ?: return ChangeKind.OTHER
        return when {
            first.startsWith("fixed") || first.startsWith("fix ") -> ChangeKind.FIX
            first.startsWith("added") || first.startsWith("add ") -> ChangeKind.FEATURE
            else -> ChangeKind.OTHER
        }
    }
}
