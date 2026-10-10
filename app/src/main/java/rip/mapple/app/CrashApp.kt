package rip.mapple.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class CrashApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Jangan pasang handler di proses layar crash (cegah loop)
        if (Build.VERSION.SDK_INT >= 28) {
            val name = Application.getProcessName()
            if (name != null && name.endsWith(":crash")) return
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val intent = Intent(this, CrashActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                intent.putExtra("trace", sw.toString().take(20000))
                startActivity(intent)
            } catch (t: Throwable) {
                previous?.uncaughtException(thread, error)
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
