package io.nekohasekai.sagernet.ktx

/**
 * Desktop replacement for the `android.util.Log` backed logger.
 *
 * Log lines go to stdout by default; the desktop client installs its own
 * [sink] to route them into the UI log view.
 */
object Logs {

    /** Optional sink installed by the UI. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    private fun tag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace.getOrNull(4)?.className?.substringAfterLast(".") ?: "Owenclave"
    }

    private fun emit(level: String, message: String, exception: Throwable? = null) {
        val line = if (exception == null) "$level/${tag()}: $message"
        else "$level/${tag()}: $message: ${exception.javaClass.simpleName}: ${exception.message}"
        sink?.invoke(line) ?: println(line)
    }

    fun v(message: String) = emit("V", message)
    fun v(message: String, exception: Throwable) = emit("V", message, exception)
    fun d(message: String) = emit("D", message)
    fun d(message: String, exception: Throwable) = emit("D", message, exception)
    fun i(message: String) = emit("I", message)
    fun i(message: String, exception: Throwable) = emit("I", message, exception)
    fun w(message: String) = emit("W", message)
    fun w(message: String, exception: Throwable) = emit("W", message, exception)
    fun w(exception: Throwable) = emit("W", exception.javaClass.name, exception)
    fun e(message: String) = emit("E", message)
    fun e(message: String, exception: Throwable) = emit("E", message, exception)
    fun e(exception: Throwable) = emit("E", exception.javaClass.name, exception)

}
