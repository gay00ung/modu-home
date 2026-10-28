package io.moduhome.app.data.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.moduhome.app.core.vehicle.DrivingInteraction
import io.moduhome.app.core.vehicle.DrivingRestrictions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pass the UI's Context so the platform resolves restrictions for that display. */
internal class PleosDrivingRestrictions(private val context: Context) : DrivingRestrictions {
    private val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<DrivingInteraction>(
        if (automotive) DrivingInteraction.Unknown() else DrivingInteraction.NotAutomotive,
    )
    override val state = mutableState.asStateFlow()
    @Volatile private var closed = false
    private var observer: AutomotiveObserver? = null
    private var observerGeneration = 0L

    override fun connect() {
        if (!automotive || closed) return
        onMain {
            if (closed || observer?.needsReconnect == false) return@onMain
            clearObserver()
            val generation = observerGeneration
            mutableState.value = DrivingInteraction.Unknown()
            try {
                val current = AutomotiveObserver(context, main) { next ->
                    onMain {
                        if (!closed && generation == observerGeneration) mutableState.value = next
                    }
                }
                observer = current
                current.connect()
            } catch (_: LinkageError) {
                clearObserver()
                mutableState.value = DrivingInteraction.Unknown("이 차량의 입력 제한 서비스에 접근할 수 없어요")
            } catch (_: RuntimeException) {
                clearObserver()
                mutableState.value = DrivingInteraction.Unknown("차량의 입력 제한 상태를 확인하지 못했어요")
            }
        }
    }

    override fun close() {
        closed = true
        if (automotive) mutableState.value = DrivingInteraction.Unknown("차량의 입력 제한 연결이 종료됐어요")
        onMain {
            clearObserver()
            main.removeCallbacksAndMessages(null)
        }
    }

    private fun clearObserver() {
        observerGeneration += 1
        val previous = observer
        observer = null
        try {
            previous?.close()
        } catch (_: LinkageError) {
            // Missing platform classes must not keep a failed observer as the active connection.
        } catch (_: RuntimeException) {
            // A disconnected service cannot publish through the previous generation.
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    // Keep android.car resolution behind the automotive check when the APK also runs on phones.
    private class AutomotiveObserver(
        private val context: Context,
        private val handler: Handler,
        private val publish: (DrivingInteraction) -> Unit,
    ) {
        @Volatile private var closed = false
        private var car: Car? = null
        private var manager: CarUxRestrictionsManager? = null
        private var managerGeneration = 0L
        @Volatile var needsReconnect = false
            private set

        fun connect() {
            if (closed || car != null) return
            val created = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { service, ready ->
                if (closed) return@createCar
                car = service
                detachManager()
                if (!ready) {
                    publishUnknown("차량의 입력 제한 서비스가 연결되지 않았어요")
                    return@createCar
                }
                try {
                    val current = service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                    if (current == null) {
                        publishUnknown("차량의 입력 제한 정보를 제공하지 않아요")
                        return@createCar
                    }
                    manager = current
                    val registration = managerGeneration
                    current.registerListener { restrictions ->
                        onHandler {
                            if (!closed && manager === current && registration == managerGeneration) {
                                publishRestrictions(restrictions)
                            }
                        }
                    }
                    // Register first, then read, so a change during initialization is also observed.
                    publishRestrictions(current.currentCarUxRestrictions)
                } catch (_: RuntimeException) {
                    detachManager()
                    publishUnknown("차량의 입력 제한 상태를 읽지 못했어요")
                }
            }
            if (closed) {
                try { created?.disconnect() } catch (_: RuntimeException) { /* This observer is already closed. */ }
                return
            }
            // The ready callback may run before createCar returns. Preserve its service instance.
            if (car == null) car = created
            if (created == null) publishUnknown("차량 서비스에 연결하지 못했어요")
        }

        fun close() {
            closed = true
            detachManager()
            val current = car
            car = null
            try { current?.disconnect() } catch (_: RuntimeException) { /* State remains unknown. */ }
        }

        private fun detachManager() {
            managerGeneration += 1
            val current = manager
            manager = null
            try { current?.unregisterListener() } catch (_: RuntimeException) { /* Ignore a dead service. */ }
        }

        private fun publishRestrictions(restrictions: CarUxRestrictions?) {
            if (closed) return
            if (restrictions == null) {
                publishUnknown("차량의 입력 제한 정보를 받지 못했어요")
                return
            }
            try {
                val required = restrictions.isRequiresDistractionOptimization
                if (!required) {
                    needsReconnect = false
                    publish(DrivingInteraction.Known(distractionOptimizationRequired = false))
                    return
                }
                val flags = restrictions.activeRestrictions
                fun restricted(flag: Int): Boolean = flags and flag != 0
                val next = DrivingInteraction.Known(
                    distractionOptimizationRequired = true,
                    keyboardRestricted = restricted(CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD),
                    setupRestricted = restricted(CarUxRestrictions.UX_RESTRICTIONS_NO_SETUP),
                    visibleVoiceTranscriptionRestricted = restricted(CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION),
                    maximumTextLength = restrictions.maxRestrictedStringLength.takeIf {
                        restricted(CarUxRestrictions.UX_RESTRICTIONS_LIMIT_STRING_LENGTH)
                    },
                    maximumContentItems = restrictions.maxCumulativeContentItems.takeIf {
                        restricted(CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT)
                    },
                    maximumContentDepth = restrictions.maxContentDepth.takeIf {
                        restricted(CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT)
                    },
                )
                needsReconnect = false
                publish(next)
            } catch (_: RuntimeException) {
                publishUnknown("차량의 입력 제한 정보를 확인하지 못했어요")
            }
        }

        private fun publishUnknown(reason: String) {
            if (closed) return
            needsReconnect = true
            publish(DrivingInteraction.Unknown(reason))
        }

        private fun onHandler(action: () -> Unit) {
            if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
        }
    }
}
