package com.aether.airbridgetouch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferProtocolTest {
    @Test fun rejectsUnsafeNames() {
        assertThrows(IllegalArgumentException::class.java) { TransferProtocol.safeName("../secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { TransferProtocol.safeName("folder\\\\secret.txt") }
        assertEquals("photo.jpg", TransferProtocol.safeName("photo.jpg"))
    }
    @Test fun validatesSizeBoundaries() {
        TransferProtocol.validateSize(0); TransferProtocol.validateSize(TransferProtocol.MAX_FILE_BYTES)
        assertThrows(IllegalArgumentException::class.java) { TransferProtocol.validateSize(-1) }
        assertThrows(IllegalArgumentException::class.java) { TransferProtocol.validateSize(TransferProtocol.MAX_FILE_BYTES + 1) }
    }
}
