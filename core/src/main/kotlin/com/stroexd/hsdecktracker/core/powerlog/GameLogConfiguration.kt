package com.stroexd.hsdecktracker.core.powerlog

private val Sections = listOf("Power", "Decks")
private val Required = linkedMapOf("LogLevel" to "1", "FilePrinting" to "true")
private val Section = Regex("""^\s*\[([^]]+)]\s*(?:[;#].*)?$""")
private val Setting = Regex("""^\s*([\w-]+)\s*=\s*([^\s;#]*)(?:\s*[;#].*)?\s*$""")
private val Line = Regex("""[^\r\n]*(?:\r\n|\n|\r|$)""")

/** Enables the two log files used by the constructed tracker without replacing unrelated settings. */
fun enableConstructedGameLogs(original: String): String {
    val newline = when {
        "\r\n" in original -> "\r\n"
        '\n' in original || '\r' !in original -> "\n"
        else -> "\r"
    }
    val bom = original.take(1).takeIf { it == "\uFEFF" }.orEmpty()
    val output = StringBuilder(original.length + 128).append(bom)
    val seenSections = mutableSetOf<String>()
    val seenSettings = mutableSetOf<String>()
    var targetSection = false

    fun appendLine(line: String) {
        if (output.length > bom.length && output.last() !in "\r\n") output.append(newline)
        output.append(line).append(newline)
    }

    fun finishSection() {
        if (targetSection) {
            for ((key, value) in Required) {
                if (key !in seenSettings) appendLine("$key=$value")
            }
        }
        seenSettings.clear()
    }

    for (match in Line.findAll(original.removePrefix(bom))) {
        val line = match.value
        if (line.isEmpty()) continue
        val content = line.trimEnd('\r', '\n')
        val header = Section.matchEntire(content)
        if (header != null) {
            finishSection()
            val name = header.groupValues[1].trim()
            val section = Sections.firstOrNull { it.equals(name, ignoreCase = true) }
            targetSection = section != null
            section?.let(seenSections::add)
        }
        if (targetSection) {
            val setting = Setting.matchEntire(content)
            val key = setting?.groupValues?.get(1)
            val required = Required.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            val value = setting?.groups?.get(2)
            if (required != null && value != null) {
                seenSettings.add(required.key)
                if (!value.value.equals(required.value, ignoreCase = true)) {
                    output.append(line.replaceRange(value.range, required.value))
                    continue
                }
            }
        }
        output.append(line)
    }
    finishSection()
    for (section in Sections) {
        if (section in seenSections) continue
        if (output.length > bom.length) appendLine("")
        appendLine("[$section]")
        for ((key, value) in Required) appendLine("$key=$value")
        appendLine("ConsolePrinting=false")
        appendLine("ScreenPrinting=false")
    }
    return output.toString()
}
