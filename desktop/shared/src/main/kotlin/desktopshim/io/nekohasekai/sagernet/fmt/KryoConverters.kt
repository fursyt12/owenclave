package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.KryoException
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.byteBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Desktop replacement for the Room flavoured `KryoConverters`.
 *
 * Written in Kotlin and exposed through `@JvmStatic` because the shared Java
 * beans call `KryoConverters.serialize(...)` statically. Only the two generic
 * entry points are shared; the per protocol `@TypeConverter` helpers of the
 * Android version exist purely for Room, which the desktop client does not use
 * (profiles are stored as JSON).
 */
object KryoConverters {

    private val NULL = ByteArray(0)

    @JvmStatic
    fun serialize(bean: Serializable?): ByteArray {
        if (bean == null) return NULL
        val out = ByteArrayOutputStream()
        val buffer = out.byteBuffer()
        bean.serializeToBuffer(buffer)
        buffer.flush()
        buffer.close()
        return out.toByteArray()
    }

    @JvmStatic
    fun <T : Serializable> deserialize(bean: T, bytes: ByteArray?): T {
        if (bytes == null) return bean
        val input = ByteArrayInputStream(bytes)
        val buffer = input.byteBuffer()
        try {
            bean.deserializeFromBuffer(buffer)
        } catch (e: KryoException) {
            Logs.w(e)
        }
        bean.initializeDefaultValues()
        return bean
    }

}
