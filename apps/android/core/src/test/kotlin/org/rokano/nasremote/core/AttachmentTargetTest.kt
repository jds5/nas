package org.rokano.nasremote.core

import org.junit.Assert.*
import org.junit.Test

class AttachmentTargetTest {
    private val target = AttachmentTarget("host-user-pin", "server-pane-pid", "executor-start-inode")

    @Test fun disconnectDoesNotInvalidateSelectionButBlocksSendingUntilVerified() {
        assertTrue(target.canRestore("host-user-pin", "server-pane-pid"))
        assertFalse(target.canSend("host-user-pin", "server-pane-pid", null))
        assertTrue(target.canSend("host-user-pin", "server-pane-pid", "executor-start-inode"))
    }
    @Test fun anotherServerCannotInheritSamePaneAndExecutorIdentifiers() {
        assertFalse(target.canRestore("other-host-user-pin", "server-pane-pid"))
        assertFalse(target.canSend("other-host-user-pin", "server-pane-pid", "executor-start-inode"))
    }
    @Test fun replacedExecutorInSamePaneCannotReceivePreviousFiles() {
        assertTrue(target.canRestore("host-user-pin", "server-pane-pid"))
        assertFalse(target.canSend("host-user-pin", "server-pane-pid", "replacement"))
    }
    @Test fun anotherPaneCannotReceivePreviousFiles() {
        assertFalse(target.canRestore("host-user-pin", "other-pane"))
        assertFalse(target.canSend("host-user-pin", "other-pane", "executor-start-inode"))
    }
}
