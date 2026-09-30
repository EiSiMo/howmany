package run.moritz.howmany

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Diagnostics"
/** Where people reach us, and where reports go. */
internal const val CONTACT = "dev@moritz.run"
private const val PREFERENCES = "diagnostics"
// Crashes up to this time have been offered to the user, as milliseconds since the epoch.
private const val SEEN_UNTIL = "seenUntil"
private const val JAVA_CRASH_FILE = "java-crash.txt"
private const val REPORT_FILE = "reports/how-many-report.txt"
private const val LOG_LINES = 2000
private const val EXITS_SHOWN = 10
private const val BYTES_PER_GB = 1e9

/**
 * The app ending abnormally in the foreground, as Android recorded it: a crash in Java or native
 * code, not responding, or killed for lack of memory while in use.
 */
class Crash internal constructor(internal val info: ApplicationExitInfo)

/** How the app's process ended, as far as it tells whether that was a crash. */
internal data class ProcessExit(val timestamp: Long, val reason: Int, val importance: Int)

/**
 * What goes wrong in the app, for people to send us by mail: after a crash, or whenever they like.
 * A report holds the app version, the device, how the app ended recently, the crash's stack trace
 * or native trace, and the app's recent log; never photos. The app has no internet access, so the
 * user's mail app sends it.
 */
object Diagnostics {
    /**
     * Records Java crashes for the next start, as Android keeps only native traces; call once as
     * the app starts.
     */
    fun install(context: Context) {
        val preferences = preferences(context)
        // Crashes before the first start with diagnostics are none of the user's concern.
        if (!preferences.contains(SEEN_UNTIL)) {
            preferences.edit { putLong(SEEN_UNTIL, System.currentTimeMillis()) }
        }
        val file = File(context.filesDir, JAVA_CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText(
                    "${Instant.now()} on thread ${thread.name}\n${error.stackTraceToString()}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Cannot record the crash", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * The app's last crash, unless the user has already been asked about it; blocks, so call it in
     * the background.
     */
    fun unseenCrash(context: Context): Crash? {
        val seenUntil = preferences(context).getLong(SEEN_UNTIL, Long.MAX_VALUE)
        val exits = exits(context)
        val crash =
            lastCrash(exits.map { ProcessExit(it.timestamp, it.reason, it.importance) }, seenUntil)
                ?: return null
        return Crash(exits.first { it.timestamp == crash.timestamp })
    }

    /** Remembers that the user has been asked about [crash] and all before it. */
    fun seen(context: Context, crash: Crash) =
        preferences(context).edit { putLong(SEEN_UNTIL, crash.info.timestamp) }

    /**
     * Opens the mail app with a report, about [crash] if given, to send us. Tells the user if that
     * fails.
     */
    suspend fun send(context: Context, crash: Crash? = null) {
        try {
            val file =
                withContext(Dispatchers.IO) {
                    File(context.cacheDir, REPORT_FILE).apply {
                        parentFile?.mkdirs()
                        writeText(report(context, crash))
                    }
                }
            context.startActivity(mail(context, file))
            Log.i(TAG, "Opened the mail app with a report of ${file.length()} bytes")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No mail app to send the report", e)
            Toast.makeText(context, R.string.error_no_mail_app, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot create the report", e)
            Toast.makeText(context, R.string.error_report_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** The report about [crash], or about how the app runs if null, as plain text. */
    internal fun report(context: Context, crash: Crash?): String = buildString {
        val memory =
            ActivityManager.MemoryInfo().also {
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
            }
        appendLine(
            "how many? ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                "${BuildConfig.BUILD_TYPE} build"
        )
        appendLine(
            "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android " +
                "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SOC_MODEL}"
        )
        appendLine(
            "Memory: %.1f GB, %.1f GB available"
                .format(memory.totalMem / BYTES_PER_GB, memory.availMem / BYTES_PER_GB)
        )
        appendLine("Created ${Instant.now()}")
        if (crash != null) {
            section("Crash")
            appendLine(describe(crash.info))
            crash.info.traceInputStream?.use { append(it.reader().readText()) }
        }
        val javaCrash = File(context.filesDir, JAVA_CRASH_FILE)
        if (javaCrash.exists()) {
            section("Last Java crash")
            append(javaCrash.readText())
        }
        section("Recent exits")
        exits(context).take(EXITS_SHOWN).forEach { appendLine(describe(it)) }
        section("Log")
        append(log())
    }

    private fun StringBuilder.section(title: String) = appendLine().appendLine("== $title ==")

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** How the app's processes ended recently, newest first. */
    private fun exits(context: Context): List<ApplicationExitInfo> =
        context
            .getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 0)

    private fun describe(exit: ApplicationExitInfo) =
        "${Instant.ofEpochMilli(exit.timestamp)} ${reasonName(exit.reason)} " +
            "(importance ${exit.importance}, pss ${exit.pss} kB, rss ${exit.rss} kB): " +
            "${exit.description}"

    /** The app's own recent log lines, of this process and earlier ones. */
    private fun log(): String =
        try {
            val process =
                ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "$LOG_LINES")
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read the log", e)
            "Cannot read the log: $e\n"
        }

    /** Sends [report] to us with the user's mail app, where they may add what they did. */
    private fun mail(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", report)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT))
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(R.string.report_subject, BuildConfig.VERSION_NAME),
            )
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.report_text))
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Only mail apps, not everything that takes a text file.
            selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
        }
    }
}

/**
 * The newest of [exits] that is a crash, ended after [seenUntil]: in Java or native code, not
 * responding, or killed for lack of memory while the user saw the app. Being killed in the
 * background is normal on Android, as is the user closing the app.
 */
internal fun lastCrash(exits: List<ProcessExit>, seenUntil: Long): ProcessExit? =
    exits
        .filter { it.timestamp > seenUntil }
        .filter {
            when (it.reason) {
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR -> true
                ApplicationExitInfo.REASON_LOW_MEMORY ->
                    it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                else -> false
            }
        }
        .maxByOrNull { it.timestamp }

private fun reasonName(reason: Int) =
    when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "reason $reason"
    }
