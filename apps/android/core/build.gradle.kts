plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.github.mwiede:jsch:2.28.7")
    // Lightweight BC implementations used by JSch for Ed25519 / X25519 on Android.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation("junit:junit:4.13.2")
}

// Opt-in integration flags are test inputs, so enabling them cannot reuse skipped results.
tasks.test {
    inputs.property("tmuxIntegration", System.getenv("NAS_TMUX_TESTS") ?: "0")
    inputs.property("sshIntegration", System.getenv("NAS_SSH_TESTS") ?: "0")
}
