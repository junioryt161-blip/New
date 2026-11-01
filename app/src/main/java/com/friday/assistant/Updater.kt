package com.friday.assistant
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Checks the GitHub Releases page of the project for a newer build and installs it on tap. */
object Updater {
    const val REPO = "junioryt161-blip/New"
    data class Info(val code: Long, val url: String)

    fun current(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** cb(info, error): info = newer build or null; error = text if the check itself failed. */
    fun check(c: Context, cb: (Info?, String?) -> Unit) = thread {
        try {
            val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            con.setRequestProperty("Accept", "application/vnd.github+json"); con.connectTimeout = 8000; con.readTimeout = 8000
            if (con.responseCode == 404) return@thread cb(null, "No release found. Is the GitHub repository public?")
            val j = JSONObject(con.inputStream.bufferedReader().readText())
            val code = j.getString("tag_name").filter { it.isDigit() }.toLong()
            val a = j.getJSONArray("assets"); var url = ""
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.getString("name").endsWith(".apk")) url = o.getString("browser_download_url") }
            cb(if (code > current(c) && url.isNotEmpty()) Info(code, url) else null, null)
        } catch (e: Exception) { cb(null, "Could not check: ${e.javaClass.simpleName}") }
    }

    /** Downloads and opens Android's installer. onStatus is called from a background thread. */
    fun install(c: Context, info: Info, onStatus: (String) -> Unit) = thread {
        try {
            if (!c.packageManager.canRequestPackageInstalls()) {
                onStatus("Allow “install unknown apps” for Friday, then tap Update again")
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@thread
            }
            val f = File(c.cacheDir, "Friday-update.apk"); if (f.exists()) f.delete()
            val con = URL(info.url).openConnection() as HttpURLConnection; con.connectTimeout = 15000; con.readTimeout = 30000
            val total = con.contentLength.toLong(); var done = 0L; var last = 0
            con.inputStream.use { i -> f.outputStream().use { o ->
                val b = ByteArray(32768); while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); done += n
                    val pc = if (total > 0) (done * 100 / total).toInt() else 0; if (pc >= last + 5) { last = pc; onStatus("Downloading… $pc%") } } } }
            onStatus("Installing…")
            val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { onStatus("Update failed: ${e.javaClass.simpleName}") }
    }
}
