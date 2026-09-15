package dev.adven.sockit.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggerEssentialTest {
    @Test
    fun essentialForwardsLevels() {
        val entries = mutableListOf<String>()
        val logger = Logger.essential { _, level, message, _ ->
            entries += "$level:$message"
        }

        logger.debug(Logger.TAG, "debug-msg")
        logger.info(Logger.TAG, "info-msg")
        logger.error(Logger.TAG, "error-msg")

        assertEquals(listOf("debug:debug-msg", "info:info-msg", "error:error-msg"), entries)
    }

    @Test
    fun noOpProducesNoOutput() {
        val entries = mutableListOf<String>()
        Logger.NoOp.debug("Tag", "hidden")
        Logger.NoOp.info("Tag", "hidden")
        Logger.NoOp.error("Tag", "hidden")
        assertTrue(entries.isEmpty())
    }
}
