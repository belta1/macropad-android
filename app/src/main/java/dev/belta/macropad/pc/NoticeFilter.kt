package dev.belta.macropad.pc

/**
 * Which of the computer's notifications show on the tablet: Teams messages and mentions when
 * [teams], and new mail only from [senders] (⚙ → Connection → Notices from the computer).
 */
class NoticeFilter(val teams: Boolean, val senders: List<String>) {

    /** The tablet asks the computer for mail only when someone is on the list. */
    val wantsMail get() = senders.isNotEmpty()

    fun shows(notice: PcNotice): Boolean = when (notice.kind) {
        "teams" -> teams
        "mail" -> senders.any { matches(it, notice) }
        else -> false
    }

    companion object {
        /**
         * The list as typed: one sender per line or comma. An address must be the sender's whole
         * address; a name may be part of the sender's name ("Ana" matches "Ana Pérez").
         */
        fun parseSenders(text: String): List<String> =
            text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        private fun matches(sender: String, notice: PcNotice): Boolean = if ('@' in sender) {
            notice.address.equals(sender, ignoreCase = true) || notice.from.contains("<$sender>", ignoreCase = true)
        } else {
            fold(notice.from).contains(fold(sender))
        }

        /** Lowercase without accents, so "Perez" finds "Pérez". */
        private fun fold(text: String) = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
    }
}
