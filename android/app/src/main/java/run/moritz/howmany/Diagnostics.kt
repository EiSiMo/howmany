package run.moritz.howmany

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.time.Instant
import java.util.Locale
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
// The app's log, kept in a file that starts over, keeping its old part, once this large.
private const val LOG_FILE = "log.txt"
private const val OLD_LOG_FILE = "log.old.txt"
private const val LOG_FILE_BYTES = 512 * 1024
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
     * Keeps the app's log, and records Java crashes, as Android keeps only native traces, for the
     * next start; call once as the app starts.
     */
    fun install(context: Context) {
        val preferences = preferences(context)
        // Crashes before the first start with diagnostics are none of the user's concern.
        if (!preferences.contains(SEEN_UNTIL)) {
            preferences.edit { putLong(SEEN_UNTIL, System.currentTimeMillis()) }
        }
        keepLog(context)
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
            // A native crash's trace is a binary tombstone; its text is in the log, from DEBUG.
            if (crash.info.reason == ApplicationExitInfo.REASON_ANR) {
                crash.info.traceInputStream?.use { append(it.reader().readText()) }
            }
        }
        val javaCrash = File(context.filesDir, JAVA_CRASH_FILE)
        if (javaCrash.exists()) {
            section("Last Java crash")
            append(javaCrash.readText())
        }
        section("Recent exits")
        exits(context).take(EXITS_SHOWN).forEach { appendLine(describe(it)) }
        section("Log")
        append(log(context))
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

    /**
     * Copies the app's log into a file, while the app runs. Android hardly lets apps read their
     * past log, so the log up to a crash is gone unless read all along. The copy ends with the app,
     * as Android kills its processes together, but only after writing a native crash's trace.
     */
    private fun keepLog(context: Context) {
        val file = File(context.filesDir, LOG_FILE)
        if (
            file.length() > LOG_FILE_BYTES && !file.renameTo(File(context.filesDir, OLD_LOG_FILE))
        ) {
            Log.w(TAG, "Cannot start a new log")
        }
        try {
            // Earlier runs' log is in the file already.
            val started =
                System.currentTimeMillis() -
                    (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            val since = "%.3f".format(Locale.ROOT, started / 1e3)
            // logcat writes its own files in blocks, which a crash cuts off, but standard output
            // line by line.
            ProcessBuilder("logcat", "-v", "threadtime", "-T", since)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(file))
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot keep the log", e)
        }
    }

    /** The app's recent log, of this and earlier runs, oldest first. */
    private fun log(context: Context): String =
        listOf(OLD_LOG_FILE, LOG_FILE)
            .map { File(context.filesDir, it) }
            .filter { it.exists() }
            .joinToString("") { it.readText() }
            .ifEmpty { "No log kept\n" }

    /**
     * Sends [report] to us with the user's mail app, where they may add what they did; lets them
     * choose if they have several. Only mail apps, not everything that takes a text file.
     */
    private fun mail(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", report)
        // Mail apps take attachments only with SEND, but declare themselves only with SENDTO, and
        // Android no longer lets a SENDTO selector pick the app for a SEND.
        val intents =
            context.packageManager
                .queryIntentActivities(Intent(Intent.ACTION_SENDTO, "mailto:".toUri()), 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .map { mailApp ->
                    Intent(Intent.ACTION_SEND).apply {
                        setPackage(mailApp)
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
                    }
                }
        if (intents.isEmpty()) throw ActivityNotFoundException("No mail app")
        return intents.singleOrNull()
            ?: Intent.createChooser(intents.first(), null)
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
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
