# JSch selects cryptographic implementations and auth handlers by class name.
-keep class com.jcraft.jsch.** { *; }
# Optional desktop integrations are not used: no agents, Unix socket forwarding,
# Kerberos, or external logging adapters. Do not suppress crypto dependency errors.
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
