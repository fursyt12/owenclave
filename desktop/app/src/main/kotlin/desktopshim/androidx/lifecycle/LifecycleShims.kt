package androidx.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CopyOnWriteArrayList

abstract class Lifecycle {
    enum class Event {
        ON_CREATE,
        ON_START,
        ON_RESUME,
        ON_PAUSE,
        ON_STOP,
        ON_DESTROY,
        ON_ANY,
    }

    enum class State {
        DESTROYED,
        INITIALIZED,
        CREATED,
        STARTED,
        RESUMED,
    }

    abstract val currentState: State
    abstract fun addObserver(observer: LifecycleObserver)
    abstract fun removeObserver(observer: LifecycleObserver)
}

interface LifecycleObserver

fun interface LifecycleEventObserver : LifecycleObserver {
    fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event)
}

interface LifecycleOwner {
    val lifecycle: Lifecycle
}

class DesktopLifecycle(private val owner: LifecycleOwner) : Lifecycle() {
    private val observers = CopyOnWriteArrayList<LifecycleObserver>()
    override var currentState: State = State.RESUMED

    override fun addObserver(observer: LifecycleObserver) {
        observers.add(observer)
        if (observer is LifecycleEventObserver) {
            observer.onStateChanged(owner, Event.ON_CREATE)
            observer.onStateChanged(owner, Event.ON_START)
            observer.onStateChanged(owner, Event.ON_RESUME)
        }
    }

    override fun removeObserver(observer: LifecycleObserver) {
        observers.remove(observer)
    }

    fun dispatch(event: Event) {
        currentState = when (event) {
            Event.ON_CREATE -> State.CREATED
            Event.ON_START -> State.STARTED
            Event.ON_RESUME -> State.RESUMED
            Event.ON_PAUSE -> State.STARTED
            Event.ON_STOP -> State.CREATED
            Event.ON_DESTROY -> State.DESTROYED
            Event.ON_ANY -> currentState
        }
        for (obs in observers) {
            if (obs is LifecycleEventObserver) {
                obs.onStateChanged(owner, event)
            }
        }
    }
}

open class ViewModel {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    open fun onCleared() {}
}

open class AndroidViewModel(private val app: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    fun <T : Application> getApplication(): T = app as T
}

val ViewModel.viewModelScope: CoroutineScope
    get() = scope

val LifecycleOwner.lifecycleScope: CoroutineScope
    get() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

open class MutableLiveData<T>(private var _value: T? = null) {
    open var value: T?
        get() = _value
        set(v) {
            _value = v
        }

    open fun postValue(value: T?) {
        _value = value
    }
}

