package dev.baechka.SVCMute

import de.maxhenkel.voicechat.api.VoicechatApi
import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import java.util.function.BiConsumer

/**
 * Отдельный класс для интеграции с Simple Voice Chat API.
 * Регистрируется через SPI (META-INF/services).
 *
 * ============================================================================
 * ПРОТОКОЛ ПАКЕТА МУТА ДЛЯ КЛИЕНТСКОГО МОДА
 * ============================================================================
 *
 * Канал: "svcmute:mute_status"
 *
 * Формат пакета (байты):
 * +--------+------------------+
 * | Байт 0 | Статус мута      |
 * +--------+------------------+
 * | 0x00   | Игрок НЕ в муте  |
 * | 0x01   | Игрок В МУТЕ     |
 * +--------+------------------+
 *
 * Пример использования на клиенте (Fabric/Forge):
 *
 * 1. Зарегистрировать обработчик канала "svcmute:mute_status"
 * 2. При получении пакета прочитать 1 байт
 * 3. Если байт == 0x01 - показать иконку мута (перечёркнутый микрофон)
 * 4. Если байт == 0x00 - скрыть иконку мута
 *
 * Иконка должна использовать готовую иконку mute
 *
 * ============================================================================
 */
class SVCMuteVoicechatPlugin : VoicechatPlugin {

    companion object {
        var voicechatApi: VoicechatServerApi? = null
            private set

        /**
         * Идентификатор канала для отправки статуса мута клиенту.
         * Формат: "namespace:channel"
         */
        const val MUTE_STATUS_CHANNEL = "svcmute:mute_status"

        /**
         * Байт, означающий что игрок НЕ замьючен
         */
        const val STATUS_UNMUTED: Byte = 0x00

        /**
         * Байт, означающий что игрок замьючен
         */
        const val STATUS_MUTED: Byte = 0x01
    }

    override fun getPluginId(): String = "svcmute"

    override fun initialize(api: VoicechatApi) {
        // Инициализация
    }

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java, this::onVoicechatServerStarted)
        registration.registerEvent(PlayerConnectedEvent::class.java, this::onPlayerConnected)
        registration.registerEvent(MicrophonePacketEvent::class.java, this::onMicrophonePacket)
    }

    private fun onVoicechatServerStarted(event: VoicechatServerStartedEvent) {
        voicechatApi = event.voicechat

        // Устанавливаем callback для обновления состояния игроков
        SVCMute.instance.voicechatStateUpdater = BiConsumer { uuid, muted ->
            val connection = voicechatApi?.getConnectionOf(uuid)
            if (connection != null) {
                connection.isDisabled = muted
                // Отправляем пакет клиенту о статусе мута
                sendMuteStatusPacket(connection, muted)
            }
        }

        SVCMute.instance.logger.info("SVCMute: VoiceChat сервер запущен")
    }

    private fun onPlayerConnected(event: PlayerConnectedEvent) {
        // Когда игрок подключается к голосовому чату, проверяем мут
        val playerUuid = event.connection.player.uuid
        val isMuted = SVCMute.instance.isMuted(playerUuid)

        if (isMuted) {
            event.connection.isDisabled = true
        }

        // Всегда отправляем текущий статус мута при подключении
        sendMuteStatusPacket(event.connection, isMuted)
    }

    private fun onMicrophonePacket(event: MicrophonePacketEvent) {
        val connection = event.senderConnection ?: return
        val playerUuid = connection.player.uuid

        if (SVCMute.instance.isMuted(playerUuid)) {
            // Блокируем голосовой пакет
            event.cancel()
        }
    }

    /**
     * Отправляет пакет со статусом мута клиенту.
     *
     * Использует Velocity plugin messaging channel.
     * Клиентский мод должен слушать канал "svcmute:mute_status".
     *
     * @param connection Соединение VoiceChat игрока
     * @param muted true если игрок замьючен, false если нет
     */
    private fun sendMuteStatusPacket(connection: VoicechatConnection, muted: Boolean) {
        try {
            val player = connection.player
            val velocityPlayer = SVCMute.instance.getServer().getPlayer(player.uuid).orElse(null)

            if (velocityPlayer != null) {
                val status = if (muted) STATUS_MUTED else STATUS_UNMUTED
                val data = byteArrayOf(status)

                // Пробуем отправить пакет клиенту
                // Примечание: пакет дойдёт только если клиентский мод зарегистрировал канал
                val sent = velocityPlayer.sendPluginMessage(SVCMute.MUTE_STATUS_CHANNEL, data)

                // Логируем независимо от результата - для отладки
                SVCMute.instance.logger.info("[SVCMute] Попытка отправки пакета -> ${velocityPlayer.username} | muted=$muted | sent=$sent")

                if (!sent) {
                    // Если клиент не зарегистрировал канал, пробуем отправить через backend сервер
                    velocityPlayer.currentServer.ifPresent { serverConnection ->
                        val backendSent = serverConnection.sendPluginMessage(SVCMute.MUTE_STATUS_CHANNEL, data)
                        SVCMute.instance.logger.info("[SVCMute] Отправка через backend -> ${serverConnection.serverInfo.name} | sent=$backendSent")
                    }
                }
            } else {
                SVCMute.instance.logger.warn("[SVCMute] Игрок не найден на прокси: ${player.uuid}")
            }
        } catch (e: Exception) {
            SVCMute.instance.logger.error("[SVCMute] Ошибка отправки пакета мута: ${e.message}", e)
        }
    }
}
