package com.yenaly.han1meviewer.logic.model

internal data class AnnouncementLink(
    val start: Int,
    val endExclusive: Int,
    val url: String,
)

private val announcementUrlRegex = Regex(
    """https?://[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?::\d+)?(?:[/?#][A-Za-z0-9._~%!$&'()*+,;=:@/?#\[\]-]*)?""",
    RegexOption.IGNORE_CASE,
)

internal fun findAnnouncementLinks(content: String): List<AnnouncementLink> =
    announcementUrlRegex.findAll(content).mapNotNull { match ->
        val url = match.value.trimEnd(
            '.', ',', '!', '?', ';', ':', ')', ']', '}',
            '。', '，', '！', '？', '；', '：', '）', '】',
        )
        url.takeIf { it.isNotEmpty() }?.let {
            AnnouncementLink(match.range.first, match.range.first + it.length, it)
        }
    }.toList()
