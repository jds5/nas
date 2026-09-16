package org.rokano.nasremote.core

import org.junit.Assert.*
import org.junit.Test
import java.net.*

class ConnectionFailureTest {
    @Test fun nestedNetworkErrorsRemainDistinct() {
        val cases = listOf(
            UnknownHostException("private-host") to "E_DNS",
            SocketTimeoutException("private-host") to "E_TCP_TIMEOUT",
            NoRouteToHostException("private-host") to "E_ROUTE",
            ConnectException("ECONNREFUSED private-host") to "E_TCP_REFUSED",
            SocketException("EACCES private-host") to "E_NETWORK_DENIED",
        )
        for ((error, code) in cases) {
            val text = ConnectionFailure.describe(Exception("secret", error), ConnectionStage.TCP)
            assertTrue(text.contains("[$code]"))
            assertFalse(text.contains("secret"))
            assertFalse(text.contains("private-host"))
        }
    }
    @Test fun timeoutReflectsActualConnectionStage() {
        val timeout = SocketTimeoutException()
        assertTrue(ConnectionFailure.describe(timeout, ConnectionStage.SSH).contains("[E_SSH_TIMEOUT]"))
        assertTrue(ConnectionFailure.describe(timeout, ConnectionStage.AUTH).contains("[E_AUTH_TIMEOUT]"))
    }
    @Test fun authenticationAndPinErrorsNeverSuggestDisablingVerification() {
        assertTrue(ConnectionFailure.describe(Exception("Auth fail for methods 'publickey'"), ConnectionStage.AUTH).contains("[E_AUTH]"))
        assertTrue(ConnectionFailure.describe(Exception("secret"), ConnectionStage.AUTH, true).contains("[E_HOST_KEY]"))
        assertTrue(ConnectionFailure.describe(Exception("secret"), ConnectionStage.KEY).contains("[E_PRIVATE_KEY]"))
    }
    @Test fun unknownFailuresAreSanitizedAndDoNotClaimAuthenticationFailure() {
        assertTrue(ConnectionFailure.describe(Exception("secret"), ConnectionStage.SSH).startsWith("[E_SSH_HANDSHAKE]"))
        val text = ConnectionFailure.describe(Exception("secret"), ConnectionStage.AUTH)
        assertTrue(text.startsWith("[E_AUTH_CONNECTION]"))
        assertFalse(text.contains("secret"))
        assertFalse(ConnectionFailure.describe(Exception("Permission denied"), ConnectionStage.AUTH).contains("E_NETWORK_DENIED"))
    }
}
