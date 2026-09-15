package dev.adven.sockit.engineio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpgradeControllerTest {
    @Test
    fun startsIdle() {
        val controller = UpgradeController()
        assertEquals(UpgradePhase.IDLE, controller.phase)
        assertFalse(controller.canSwitchTransport())
    }

    @Test
    fun probePongMovesToPausingPoll() {
        val controller = UpgradeController()
        controller.startProbing()
        assertEquals(UpgradePhase.PROBING, controller.phase)

        controller.onProbePong()
        assertEquals(UpgradePhase.PAUSING_POLL, controller.phase)
        assertFalse(controller.canSwitchTransport())
    }

    @Test
    fun requiresPollingPausedAndUpgradeDrainBeforeSwitch() {
        val controller = UpgradeController()
        controller.startProbing()
        controller.onProbePong()

        controller.onPollingPaused()
        assertFalse(controller.canSwitchTransport())

        controller.onUpgradeDrained()
        assertTrue(controller.canSwitchTransport())
        assertEquals(UpgradePhase.UPGRADING, controller.phase)
    }

    @Test
    fun upgradeDrainBeforePollingPauseStillWaits() {
        val controller = UpgradeController()
        controller.startProbing()
        controller.onProbePong()

        controller.onUpgradeDrained()
        assertFalse(controller.canSwitchTransport())

        controller.onPollingPaused()
        assertTrue(controller.canSwitchTransport())
    }

    @Test
    fun completeSwitchMovesToDone() {
        val controller = UpgradeController()
        controller.startProbing()
        controller.onProbePong()
        controller.onPollingPaused()
        controller.onUpgradeDrained()

        controller.completeSwitch()
        assertEquals(UpgradePhase.DONE, controller.phase)
        assertFalse(controller.canSwitchTransport())
    }

    @Test
    fun resetReturnsToIdle() {
        val controller = UpgradeController()
        controller.startProbing()
        controller.onProbePong()
        controller.reset()

        assertEquals(UpgradePhase.IDLE, controller.phase)
        assertFalse(controller.canSwitchTransport())
    }
}
