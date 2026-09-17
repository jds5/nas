package org.rokano.nasremote.core

/** Selection belongs to an executor, not a particular SSH connection attempt. */
data class AttachmentTarget(val profile: String, val pane: String, val binding: String) {
    fun canRestore(profile: String, pane: String) = this.profile == profile && this.pane == pane
    fun canSend(profile: String, pane: String, binding: String?) =
        canRestore(profile, pane) && binding != null && this.binding == binding
}
