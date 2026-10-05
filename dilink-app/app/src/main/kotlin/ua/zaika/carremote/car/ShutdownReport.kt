package ua.zaika.carremote.car

/** The "car switched off" snapshot sent to Telegram when ignition goes off. */
object ShutdownReport {

    private val WINDOW_NAMES = listOf("вікно водія", "вікно пасажира", "заднє ліве", "заднє праве")
    private val DOOR_NAMES = listOf("водія", "пасажира", "задні ліві", "задні праві")

    fun build(snap: CarSnapshot, time: String): String = "🅿️ Авто заглушено о $time\n\n" + body(snap)

    /** Everything below the header. The shell watchdog adds the header + its own time. */
    fun body(snap: CarSnapshot): String = buildString {
        appendLine("🔋 Заряд: ${snap.soc?.let { "%.1f%%".format(it) } ?: "—"}")
        snap.mileageKm?.let { appendLine("🛣 Пробіг: %,.0f км".format(it).replace(",", " ")) }
        tempsLine(snap)?.let { appendLine("🌡 $it") }
        tiresLine(snap)?.let { appendLine("🛞 Шини: $it бар") }
        snap.locked?.let { appendLine(if (it) "🔒 Зачинено" else "🔓 Відчинено") }
        val warn = openWarnings(snap)
        if (warn.isNotEmpty()) {
            appendLine()
            append("⚠️ Відчинено: ${warn.joinToString(", ")}")
        }
    }.trimEnd()

    private fun tempsLine(s: CarSnapshot): String? = listOfNotNull(
        s.outsideC?.let { "надворі ${sign(it)}°" },
        s.insideC?.let { "у салоні ${sign(it)}°" },
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun tiresLine(s: CarSnapshot): String? {
        if (s.tiresBar.all { it == null }) return null
        val labels = listOf("ПЛ", "ПП", "ЗЛ", "ЗП")
        return s.tiresBar.mapIndexed { i, v -> "${labels[i]} ${v?.let { "%.1f".format(it) } ?: "—"}" }
            .joinToString(" · ")
    }

    private fun openWarnings(s: CarSnapshot): List<String> {
        val out = mutableListOf<String>()
        s.windowsOpenPct.forEachIndexed { i, pct -> if (pct != null && pct in 1..100) out += WINDOW_NAMES[i] }
        s.doorsOpen.forEachIndexed { i, open -> if (open == true) out += "двері ${DOOR_NAMES[i]}" }
        if ((s.sunroofPct ?: 0) > 0) out += "люк"
        if (s.trunkOpen == true) out += "багажник"
        if (s.hoodOpen == true) out += "капот"
        return out
    }

    private fun sign(t: Int) = if (t > 0) "+$t" else "$t"
}
