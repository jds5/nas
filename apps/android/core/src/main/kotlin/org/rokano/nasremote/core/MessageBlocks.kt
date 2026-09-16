package org.rokano.nasremote.core

/** Bounded display-only Markdown subset. Unsupported syntax remains literal. */
sealed interface MessageBlock {
    data class Text(val value: String) : MessageBlock
    data class Code(val language: String, val value: String) : MessageBlock
    data class Table(val rows: List<List<String>>) : MessageBlock
}

fun messageBlocks(source: String): List<MessageBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MessageBlock>()
    var index = 0
    fun cells(line: String) = line.trim().trim('|').split('|').map { it.trim() }
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
            cells(lines[index + 1]).let { it.size > 1 && it.all { cell -> Regex(":?-{3,}:?").matches(cell) } }) {
            val rows = mutableListOf(cells(line))
            index += 2
            while (index < lines.size && rows.size < 200 && lines[index].contains('|') && lines[index].isNotBlank() && cells(lines[index]).size <= 12) rows += cells(lines[index++])
            blocks += MessageBlock.Table(rows)
        } else {
            if (line.isNotBlank()) blocks += MessageBlock.Text(line)
            index++
        }
    }
    return blocks
}
