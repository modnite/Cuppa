package com.cuppa.app

import com.cuppa.app.data.CupsRepository
import com.cuppa.app.data.ServerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CupsRepositoryTest {

    @Test
    fun testInitialServerState() {
        val repo = CupsRepository(null)
        val state = repo.serverState.value
        // Server is not running in pure unit test environment
        assertTrue(state is ServerState.Stopped || state is ServerState.Running)
    }

    @Test
    fun testServerStateSealedHierarchy() {
        val stopped: ServerState = ServerState.Stopped
        val starting: ServerState = ServerState.Starting
        val running: ServerState = ServerState.Running(port = 631, version = "CUPS v2.2.9")
        val error: ServerState = ServerState.Error("Network unreachable")

        assertEquals(ServerState.Stopped, stopped)
        assertEquals(ServerState.Starting, starting)
        assertTrue(running is ServerState.Running)
        assertEquals(631, (running as ServerState.Running).port)
        assertEquals("CUPS v2.2.9", running.version)
        assertTrue(error is ServerState.Error)
        assertEquals("Network unreachable", (error as ServerState.Error).message)
    }
}
