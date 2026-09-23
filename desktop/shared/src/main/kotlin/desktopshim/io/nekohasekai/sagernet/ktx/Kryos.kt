package io.nekohasekai.sagernet.ktx

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import java.io.InputStream
import java.io.OutputStream

/*
 * Desktop replacement for `ktx/Kryos.kt`.
 *
 * Only the buffer helpers are shared with the Android app. The Parcel helpers
 * cannot exist on desktop because the desktop `Serializable` is not a
 * `Parcelable`.
 */

fun InputStream.byteBuffer() = ByteBufferInput(this)

fun OutputStream.byteBuffer() = ByteBufferOutput(this)

fun ByteBufferInput.readStringList(): List<String> {
    return mutableListOf<String>().apply {
        repeat(readInt()) {
            add(readString())
        }
    }
}

fun ByteBufferInput.readStringSet(): Set<String> {
    return linkedSetOf<String>().apply {
        repeat(readInt()) {
            add(readString())
        }
    }
}

fun ByteBufferOutput.writeStringList(list: List<String>) {
    writeInt(list.size)
    for (str in list) writeString(str)
}

fun ByteBufferOutput.writeStringList(list: Set<String>) {
    writeInt(list.size)
    for (str in list) writeString(str)
}
