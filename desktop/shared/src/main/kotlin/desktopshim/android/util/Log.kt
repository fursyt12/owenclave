package android.util

import io.nekohasekai.sagernet.DesktopEnv
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private val ringBuffer = ArrayDeque<String>(2048)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val logFile by lazy {
        File(DesktopEnv.dataDir, "logcat.log").apply {
            parentFile?.mkdirs()
        }
    }
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    var externalSink: ((String) -> Unit)? = null

    fun getRecentLines(): List<String> = synchronized(ringBuffer) { ringBuffer.toList() }

    fun clearLines() {
        synchronized(ringBuffer) { ringBuffer.clear() }
        runCatching { logFile.writeText("") }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun emit(level: Char, tag: String?, msg: String?, tr: Throwable? = null): Int {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val body = buildString {
            append("$timestamp $level/${tag ?: "Owenclave"}: ${msg ?: ""}")
            if (tr != null) {
                append("\n")
                append(tr.stackTraceToString().trimEnd())
            }
        }
        synchronized(ringBuffer) {
            for (line in body.lines()) {
                if (ringBuffer.size >= 2000) ringBuffer.removeFirst()
                ringBuffer.addLast(line)
            }
            runCatching { logFile.appendText(body + "\n") }
        }
        externalSink?.invoke(body) ?: println(body)
        for (l in listeners) {
            runCatching { l(body) }
        }
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String?): Int = emit('V', tag, msg)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = emit('V', tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = emit('D', tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = emit('D', tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = emit('I', tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = emit('I', tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = emit('W', tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = emit('W', tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = emit('W', tag, tr?.message, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = emit('E', tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = emit('E', tag, msg, tr)
}

class TypedValue {
    var resourceId: Int = 0
}

