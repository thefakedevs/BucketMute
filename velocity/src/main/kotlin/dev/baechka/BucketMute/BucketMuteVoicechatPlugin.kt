package dev.baechka.BucketMute

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent
import de.maxhenkel.voicechat.api.events.VoiceDistanceEvent
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import java.util.function.BiConsumer

/**
 * Плагин Simple Voice Chat - серверная часть мута.
 */
class BucketMuteVoicechatPlugin : VoicechatPlugin {

    companion object {
        private const val ADMIN_VOICE_DISTANCE = 1_000_000F

        var voicechatApi: VoicechatServerApi? = null
            private set
    }

    override fun getPluginId(): String = "bucketmute"

    override fun initialize(api: VoicechatApi) {}

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java, this::onServerStarted)
        registration.registerEvent(PlayerConnectedEvent::class.java, this::onPlayerConnected)
        registration.registerEvent(VoiceDistanceEvent::class.java, this::onVoiceDistance)
        registration.registerEvent(MicrophonePacketEvent::class.java, this::onMicrophonePacket)
    }

    private fun onServerStarted(event: VoicechatServerStartedEvent) {
        voicechatApi = event.voicechat

        BucketMute.instance.voicechatStateUpdater = BiConsumer { uuid, muted ->
            voicechatApi?.getConnectionOf(uuid)?.let { connection ->
                connection.isDisabled = muted
                BucketMute.instance.sendMuteStatusToClient(uuid, muted)
            }
        }

        BucketMute.instance.getServer().allPlayers.forEach { player ->
            BucketMute.instance.updateMuteState(player.uniqueId)
        }
    }

    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        val playerUuid = event.connection.player.uuid
        val isMuted = BucketMute.instance.isEffectivelyMuted(playerUuid)

        if (isMuted) {
            event.connection.isDisabled = true
        }

        BucketMute.instance.sendMuteStatusToClient(playerUuid, isMuted)
    }

    private fun onVoiceDistance(event: VoiceDistanceEvent) {
        val playerUuid = event.senderConnection.player.uuid

        if (
            BucketMute.instance.isBroadcastEnabled() &&
            !BucketMute.instance.isMuted(playerUuid) &&
            BucketMute.instance.hasAdminPermission(playerUuid)
        ) {
            event.distance = ADMIN_VOICE_DISTANCE
        }
    }

    private fun onMicrophonePacket(event: MicrophonePacketEvent) {
        val playerUuid = event.senderConnection?.player?.uuid ?: return

        if (BucketMute.instance.isEffectivelyMuted(playerUuid)) {
            event.cancel()
        }
    }
}
