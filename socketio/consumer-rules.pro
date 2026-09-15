# Consumer keep rules for :socketio (merged into app R8 when minify is enabled).
#
# Public API is kept via app call sites; rules below cover serialization and
# defensive keeps for published-library consumers.

# Engine.IO open handshake — only @Serializable type in this module.
-keep @kotlinx.serialization.Serializable class dev.adven.sockit.protocol.OpenHandshake { *; }
-keepclassmembers class dev.adven.sockit.protocol.OpenHandshake$$serializer { *; }

# Defensive: stable public contract for library consumers.
-keep class dev.adven.sockit.api.** { *; }
