package com.cipherwell.notezero.book

/**
 * Natural ("human") ordering for CBZ page entry names, so `page2.jpg` < `page10.jpg`.
 *
 * Each name is split into alternating non-digit/digit chunks: digit chunks are compared by
 * numeric value (so `007` == `7`, ordered by leading zeros only as a tie-break), other chunks
 * case-insensitively. When two names are equivalent under that rule the raw string ordering is
 * used as a deterministic tie-breaker, which keeps the order total and therefore stable for
 * `sortedWith`.
 */
internal object NaturalOrder : Comparator<String> {

    override fun compare(first: String, second: String): Int {
        val natural = compareChunks(first, second)
        if (natural != 0) return natural
        return first.compareTo(second)
    }

    private fun compareChunks(first: String, second: String): Int {
        var firstIndex = 0
        var secondIndex = 0
        while (firstIndex < first.length && secondIndex < second.length) {
            val firstDigit = first[firstIndex].isDigit()
            val secondDigit = second[secondIndex].isDigit()
            if (firstDigit != secondDigit) {
                // Different chunk kinds at this position: order by the folded character and fall
                // back to the raw character, so the comparison stays total.
                val folded = compareChars(first[firstIndex], second[secondIndex])
                return if (folded != 0) folded else first[firstIndex] - second[secondIndex]
            }
            val firstStart = firstIndex
            val secondStart = secondIndex
            if (firstDigit) {
                while (firstIndex < first.length && first[firstIndex].isDigit()) firstIndex++
                while (secondIndex < second.length && second[secondIndex].isDigit()) secondIndex++
            } else {
                while (firstIndex < first.length && !first[firstIndex].isDigit()) firstIndex++
                while (secondIndex < second.length && !second[secondIndex].isDigit()) secondIndex++
            }
            val firstChunk = first.substring(firstStart, firstIndex)
            val secondChunk = second.substring(secondStart, secondIndex)
            val order = if (firstDigit) {
                compareNumber(firstChunk, secondChunk)
            } else {
                firstChunk.compareTo(secondChunk, ignoreCase = true)
            }
            if (order != 0) return order
        }
        // The shared prefix is equivalent; the shorter name comes first.
        return (first.length - firstIndex) - (second.length - secondIndex)
    }

    private fun compareNumber(first: String, second: String): Int {
        val trimmedFirst = first.trimStart('0').ifEmpty { "0" }
        val trimmedSecond = second.trimStart('0').ifEmpty { "0" }
        if (trimmedFirst.length != trimmedSecond.length) {
            return trimmedFirst.length - trimmedSecond.length
        }
        val digits = trimmedFirst.compareTo(trimmedSecond)
        if (digits != 0) return digits
        // Same numeric value: fewer leading zeros sorts first, deterministically.
        return first.length - second.length
    }

    private fun compareChars(first: Char, second: Char): Int {
        val foldedFirst = first.lowercaseChar()
        val foldedSecond = second.lowercaseChar()
        return foldedFirst - foldedSecond
    }
}
