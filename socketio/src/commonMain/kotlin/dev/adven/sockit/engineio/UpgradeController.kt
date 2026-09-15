package dev.adven.sockit.engineio

internal enum class UpgradePhase {
    IDLE,
    PROBING,
    PAUSING_POLL,
    UPGRADING,
    DONE,
}

internal class UpgradeController {
    var phase: UpgradePhase = UpgradePhase.IDLE
        private set

    private var pollingPaused = false
    private var upgradeDrained = false

    fun startProbing() {
        phase = UpgradePhase.PROBING
        pollingPaused = false
        upgradeDrained = false
    }

    fun onProbePong() {
        if (phase == UpgradePhase.PROBING) {
            phase = UpgradePhase.PAUSING_POLL
        }
    }

    fun onPollingPaused() {
        pollingPaused = true
        checkReadyToSwitch()
    }

    fun onUpgradeDrained() {
        upgradeDrained = true
        checkReadyToSwitch()
    }

    fun canSwitchTransport(): Boolean = phase == UpgradePhase.UPGRADING

    fun completeSwitch() {
        phase = UpgradePhase.DONE
    }

    fun isActive(): Boolean = phase != UpgradePhase.IDLE && phase != UpgradePhase.DONE

    fun reset() {
        phase = UpgradePhase.IDLE
        pollingPaused = false
        upgradeDrained = false
    }

    private fun checkReadyToSwitch() {
        if (phase == UpgradePhase.PAUSING_POLL && pollingPaused && upgradeDrained) {
            phase = UpgradePhase.UPGRADING
        }
    }
}
