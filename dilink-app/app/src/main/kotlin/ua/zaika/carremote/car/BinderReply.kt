package ua.zaika.carremote.car

/** The first word is the Binder status, the optional second word is the return value. */
data class BinderReply(val status: Int, val value: Int?) {
    val writeAccepted: Boolean get() = status >= 0 && (value == null || value >= 0)

    companion object {
        private val words = Regex("""Parcel\(\s*([0-9a-fA-F]{8})(?:\s+([0-9a-fA-F]{8}))?""")
        fun parse(output: String): BinderReply? {
            val match = words.find(output) ?: return null
            return BinderReply(
                match.groupValues[1].toLong(16).toInt(),
                match.groupValues[2].takeIf { it.isNotEmpty() }?.toLong(16)?.toInt(),
            )
        }
    }
}
