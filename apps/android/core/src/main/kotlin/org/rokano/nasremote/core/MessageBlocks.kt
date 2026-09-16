package org.rokano.nasremote.core

/** Bounded display-only Markdown subset. Unsupported syntax remains literal. */
sealed interface MessageBlock {
    data class Text(val value: String) : MessageBlock
    data class Code(val language: String, val value: String) : MessageBlock
    data class Table(val rows: List<List<String>>, val alignment: List<Int> = emptyList()) : MessageBlock
}

fun messageBlocks(source: String): List<MessageBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MessageBlock>()
    var index = 0
    fun cells(line: String): List<String> {
        val result = mutableListOf<String>()
        val cell = StringBuilder()
        val text = line.trim()
        var position = 0
        var ticks = 0
        while (position < text.length) {
            val c = text[position]
            when {
                c == '\\' && position + 1 < text.length && text[position + 1] in "|\\" -> {
                    cell.append(text[position + 1]); position += 2
                }
                c == '`' -> {
                    var end = position
                    while (end < text.length && text[end] == '`') end++
                    val count = end - position
                    if (ticks == 0) ticks = count else if (ticks == count) ticks = 0
                    cell.append(text.substring(position, end)); position = end
                }
                c == '|' && ticks == 0 -> { result += cell.toString().trim(); cell.clear(); position++ }
                else -> { cell.append(c); position++ }
            }
        }
        result += cell.toString().trim()
        if (text.startsWith('|') && result.firstOrNull() == "") result.removeAt(0)
        if (result.size > 1 && result.last() == "" && text.endsWith('|')) result.removeAt(result.lastIndex)
        return result
    }
    while (index < lines.size) {
        val line = lines[index]
        if (line.trimStart().startsWith("```")) {
            val language = line.trim().removePrefix("```").take(40)
            val content = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) content += lines[index++]
            if (index < lines.size) index++
            blocks += MessageBlock.Code(language, content.joinToString("\n"))
        } else if (line.contains('|') && cells(line).size in 2..12 && index + 1 < lines.size &&
            cells(lines[index + 1]).let { it.size == cells(line).size && it.all { cell -> Regex(":?-{3,}:?").matches(cell) } }) {
            val alignment = cells(lines[index + 1]).map { when {
                it.startsWith(':') && it.endsWith(':') -> 0
                it.endsWith(':') -> 1
                else -> -1
            } }
            val rows = mutableListOf(cells(line))
            index += 2
            while (index < lines.size && rows.size < 200 && lines[index].contains('|') && lines[index].isNotBlank() && cells(lines[index]).size <= 12) rows += cells(lines[index++])
            blocks += MessageBlock.Table(rows, alignment)
        } else {
            if (line.isNotBlank()) blocks += MessageBlock.Text(line)
            index++
        }
    }
    return blocks
}
