package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput

/**
 * Desktop replacement for the Android `Parcelable` based `Serializable`.
 *
 * The Android version is a `Parcelable` so that beans can travel through
 * Binder/AIDL. Desktop has no Binder, but the protocol beans still need a common
 * base with Kryo (de)serialisation, therefore the `Parcelable` part is dropped
 * while the nested `Creator`/`CREATOR` types are kept so the shared Java beans
 * (which declare `public static final Creator<X> CREATOR = new CREATOR<X>() ...`)
 * keep compiling unchanged.
 */
abstract class Serializable {

    abstract fun initializeDefaultValues()

    abstract fun serializeToBuffer(output: ByteBufferOutput)

    abstract fun deserializeFromBuffer(input: ByteBufferInput)

    /** Mirrors `android.os.Parcelable.Creator`. */
    interface Creator<T> {

        fun newInstance(): T

        fun newArray(size: Int): Array<T>

    }

    /** Mirrors the `Serializable.CREATOR` helper of the Android implementation. */
    abstract class CREATOR<T : Serializable> : Creator<T> {

        abstract override fun newInstance(): T

        override fun newArray(size: Int): Array<T> =
            throw UnsupportedOperationException("Parcelable is not available on desktop")

    }

}
