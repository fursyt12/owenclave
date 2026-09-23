package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput

/**
 * Holder for the `Creator` member type that the shared Java beans inherit.
 *
 * On Android `Creator` comes from `android.os.Parcelable` and `CREATOR` from the
 * Kotlin `Serializable`, so the two never collide. The desktop replacement has to
 * provide both, and putting them into the same class would be fatal on case
 * insensitive file systems: `Serializable$Creator.class` and
 * `Serializable$CREATOR.class` are the same file on macOS and Windows, so one of
 * the nested types silently disappears and every bean fails to compile with
 * "cannot find symbol". Keeping `Creator` in its own class avoids that.
 */
abstract class SerializableBase {

    /** Mirrors `android.os.Parcelable.Creator`. */
    interface Creator<T> {

        fun newInstance(): T

        fun newArray(size: Int): Array<T>

    }

}
