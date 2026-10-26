package ua.zaika.carremote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.zaika.carremote.adb.AdbShell
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.car.Controls
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.ui.AirAlertActivity
import ua.zaika.carremote.ui.DashboardView
import ua.zaika.carremote.ui.SettingsActivity
import ua.zaika.carremote.ui.TripsActivity
import ua.zaika.carremote.ui.Ui
import ua.zaika.carremote.ui.AdBanner
import ua.zaika.carremote.ui.dpv
import ua.zaika.carremote.ui.filledPill
import ua.zaika.carremote.ui.rounded
import ua.zaika.carremote.ui.sfSemibold
import ua.zaika.carremote.ui.tintedPill
import ua.zaika.carremote.telegram.InlineKeyboard
import ua.zaika.carremote.telegram.Reply
import ua.zaika.carremote.ui.sf

/** Live dashboard on the head unit. The remote itself runs in [CarRemoteService] + Telegram. */
class MainActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private lateinit var adb: AdbShell
    private lateinit var car: Autoservice
    private lateinit var dashboard: DashboardView
    private lateinit var statusLine: TextView
    private lateinit var adBanner: AdBanner
    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = AdbShell(this)
        car = Autoservice(adb)
        dashboard = DashboardView(this)
        adBanner = AdBanner(this)

        statusLine = TextView(this).apply {
            setTextColor(Ui.TEXT2); textSize = 11f; typeface = sf
            setPadding(dpv(18), dpv(2), dpv(18), dpv(8))
        }
        dashboard.onTile = { tile ->
            when (tile) {
                DashboardView.Tile.CLIMATE -> sheet("Клімат", listOf(
                    "Увімкнути" to "climate_on", "Вимкнути" to "climate_off",
                    "Температура −1°" to "temp_down", "Температура +1°" to "temp_up",
                ))
                DashboardView.Tile.LOCKS -> sheet("Замки", listOf("Замкнути" to "lock", "Відімкнути" to "unlock"))
                DashboardView.Tile.TRUNK -> sheet("Багажник", listOf("Відкрити" to "trunk_open", "Закрити" to "trunk_close"))
                DashboardView.Tile.WINDOWS -> sheet("Вікна", listOf("Відкрити" to "windows_open", "Закрити" to "windows_close"))
                DashboardView.Tile.SUNROOF -> sheet("Люк", listOf("Відкрити" to "sunroof_open", "Закрити" to "sunroof_close"))
                DashboardView.Tile.CLEAN_AIR -> command("clean_air")
                DashboardView.Tile.CHARGING -> startActivity(Intent(this, TripsActivity::class.java))
                DashboardView.Tile.AIR_ALERT -> openAirMap()
                DashboardView.Tile.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF151C28.toInt())
            addView(dashboard, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(adBanner, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dpv(18), dpv(4), dpv(18), dpv(2)) })
            addView(statusLine)
        })

        CarRemoteService.start(this)
    }

    override fun onResume() {
        super.onResume()
        pollJob = ui.launch {
            while (isActive) {
                val snap = withContext(Dispatchers.IO) { CarSnapshot.read(car) }
                dashboard.snapshot = snap
                statusLine.text = "Car Remote v${BuildConfig.VERSION_NAME}   ·   " +
                    "${CarRemoteService.adbStatus}   ·   ${CarRemoteService.telegramStatus}   ·   Varan7777"
                dashboard.air = CarRemoteService.airState
                adBanner.render(prefs)
                delay(3_000)
            }
        }
    }

    override fun onPause() { pollJob?.cancel(); super.onPause() }

    override fun onDestroy() { ui.cancel(); adb.close(); super.onDestroy() }

    /**
     * Tile actions run through the very same [CommandRouter] as the Telegram bot, so the safety
     * rules (Park only for moving parts, unlock / locked-trunk confirmation, readback) match.
     */
    private val router by lazy { CommandRouter(car, LOCAL_CHAT, prefs) }

    private fun command(id: String) {
        ui.launch {
            val reply = withContext(Dispatchers.IO) { runCatching { router.handle(LOCAL_CHAT, id) }.getOrNull() }
            if (reply != null) showReply(reply) else toast("Авто не на зв'язку")
        }
    }

    /** A DiLink-style pop-up with the tile's actions. */
    private fun sheet(title: String, actions: List<Pair<String, String>>) {
        lateinit var dialog: AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(24), dpv(22), dpv(24), dpv(22))
            background = rounded(Ui.CARD, 22)
            addView(TextView(context).apply {
                text = title; setTextColor(Ui.TEXT); textSize = 22f; typeface = sfSemibold
                setPadding(0, 0, 0, dpv(14))
            })
            actions.chunked(2).forEach { row ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEach { (label, id) ->
                        addView(tintedPill(label, Ui.TEXT) { dialog.dismiss(); command(id) },
                            LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dpv(5), dpv(5), dpv(5), dpv(5)) })
                    }
                })
            }
            addView(filledPill("Закрити", 0xFF3A4556.toInt()) { dialog.dismiss() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dpv(5), dpv(12), dpv(5), 0) })
        }
        dialog = AlertDialog.Builder(this).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /** Shows the router's answer; its inline buttons (confirmations) work here too. */
    private fun showReply(reply: Reply) {
        val buttons = (reply.keyboard as? InlineKeyboard)?.rows?.flatten().orEmpty().filter { it.data != "menu" }
        if (buttons.isEmpty()) { toast(reply.text); return }
        lateinit var dialog: AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(24), dpv(22), dpv(24), dpv(22))
            background = rounded(Ui.CARD, 22)
            addView(TextView(context).apply {
                text = reply.text; setTextColor(Ui.TEXT); textSize = 18f; typeface = sf
                setPadding(0, 0, 0, dpv(16))
            })
            buttons.forEach { b ->
                addView(filledPill(b.label, Ui.BLUE) { dialog.dismiss(); command(b.data) },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dpv(5), 0, dpv(5)) })
            }
            addView(filledPill("Скасувати", 0xFF3A4556.toInt()) { dialog.dismiss() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dpv(5), 0, 0) })
        }
        dialog = AlertDialog.Builder(this).setView(box).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun openAirMap() = startActivity(Intent(this, AirAlertActivity::class.java))

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val LOCAL_CHAT = 1L // the head unit itself acts as the owner for its own screen
    }
}
