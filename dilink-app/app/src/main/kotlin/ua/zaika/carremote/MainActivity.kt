package ua.zaika.carremote

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Bare status screen. The real work happens in [CarRemoteService] and Telegram. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView

    private val refresh = object : Runnable {
        override fun run() {
            statusView.text = statusText()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply { textSize = 20f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(statusView)
            addView(button("Запустити") { CarRemoteService.start(this@MainActivity) })
            addView(button("Зупинити") { CarRemoteService.stop(this@MainActivity) })
        })
        CarRemoteService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun statusText() = buildString {
        appendLine("Car Remote ${BuildConfig.VERSION_NAME}")
        appendLine()
        appendLine("Токен бота: ${if (BuildConfig.TELEGRAM_BOT_TOKEN.isBlank()) "не задано" else "задано"}")
        appendLine("Власник: ${if (BuildConfig.OWNER_CHAT_ID == 0L) "не задано" else "задано"}")
        appendLine()
        appendLine(CarRemoteService.adbStatus)
        append(CarRemoteService.telegramStatus)
    }
}
