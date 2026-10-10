package rip.mapple.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra("trace") ?: "(tidak ada detail error)"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val bg = Color.parseColor("#1E1E2E")

        val title = TextView(this)
        title.text = "Aplikasi crash. Screenshot layar ini dan kirim ke developer."
        title.textSize = 16f
        title.setTextColor(Color.WHITE)
        title.setPadding(pad, pad * 2, pad, pad / 2)

        val copy = Button(this)
        copy.text = "Salin error"
        copy.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("crash", trace))
        }

        val body = TextView(this)
        body.text = trace
        body.textSize = 11f
        body.typeface = Typeface.MONOSPACE
        body.setTextColor(Color.parseColor("#FF8A80"))
        body.setPadding(pad, pad / 2, pad, pad)
        body.setTextIsSelectable(true)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setBackgroundColor(bg)
        column.addView(title)
        column.addView(copy)
        column.addView(body)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.addView(column)
        setContentView(scroll)
    }
}
