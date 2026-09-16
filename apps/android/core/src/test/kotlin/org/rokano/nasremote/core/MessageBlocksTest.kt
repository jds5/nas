package org.rokano.nasremote.core

import org.junit.Assert.*
import org.junit.Test

class MessageBlocksTest {
    @Test fun fencedCodePreservesLiteralCommandsAndLineBreaks() {
        val text = "# 示例\n```bash\nprintf '%s' '\$(id)'\n\n```\n- 项目"
        val blocks = messageBlocks(text)
        assertEquals(MessageBlock.Code("bash", "printf '%s' '\$(id)'\n"), blocks[1])
        assertEquals(MessageBlock.Text("- 项目"), blocks[2])
    }
    @Test fun tablesNeedARealSeparatorAndKeepMissingCells() {
        val blocks = messageBlocks("| A | B |\n| :--- | ---: |\n| 1 | 2 |\n| 3 |\n\na | ordinary text")
        assertEquals(MessageBlock.Table(listOf(listOf("A", "B"), listOf("1", "2"), listOf("3")), listOf(-1, 1)), blocks[0])
        assertEquals(MessageBlock.Text("a | ordinary text"), blocks[1])
    }
    @Test fun escapedPipesCodeSpansAndAlignment() {
        val block = messageBlocks("| A | B | C |\n| :--- | :---: | ---: |\n| x\\|y | `a|b` | z | ").single() as MessageBlock.Table
        assertEquals(listOf("x|y", "`a|b`", "z"), block.rows[1])
        assertEquals(listOf(-1, 0, 1), block.alignment)
    }
    @Test fun mismatchedHeaderAndDelimiterRemainLiteral() {
        assertTrue(messageBlocks("| A | B |\n| --- | --- | --- | ").all { it is MessageBlock.Text })
    }
    @Test fun unfinishedCodeAndHtmlRemainDisplayOnly() {
        assertEquals(listOf(MessageBlock.Code("", "<script>alert(1)</script>")), messageBlocks("```\n<script>alert(1)</script>"))
    }
}
