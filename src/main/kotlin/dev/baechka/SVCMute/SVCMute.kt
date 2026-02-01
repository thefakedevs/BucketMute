package dev.baechka.SVCMute

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.luckperms.api.LuckPermsProvider
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

@Plugin(
    id = "svcmute",
    name = "SVCMute",
    version = BuildConstants.VERSION,
    dependencies = [
        Dependency(id = "voicechat"),
        Dependency(id = "luckperms")
    ]
)
class SVCMute @Inject constructor(
    val logger: Logger,
    private val server: ProxyServer
) {

    companion object {
        lateinit var instance: SVCMute
            private set

        /**
         * Идентификатор канала для отправки статуса мута клиенту.
         */
        val MUTE_STATUS_CHANNEL: MinecraftChannelIdentifier =
            MinecraftChannelIdentifier.create("svcmute", "mute_status")
    }

    // Хранение мутов: UUID игрока -> время окончания мута (-1 = перманентный)
    private val mutedPlayers = ConcurrentHashMap<UUID, Long>()

    // Задача для проверки истечения мутов
    private var muteCheckTask: ScheduledTask? = null

    @Subscribe
    @Suppress("UNUSED_PARAMETER")
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        instance = this

        // Регистрируем канал для отправки статуса мута клиентам
        server.channelRegistrar.register(MUTE_STATUS_CHANNEL)

        // Регистрация команд
        server.commandManager.register(
            server.commandManager.metaBuilder("svcmute")
                .plugin(this)
                .build(),
            MuteCommand(this)
        )

        server.commandManager.register(
            server.commandManager.metaBuilder("svcunmute")
                .plugin(this)
                .build(),
            UnmuteCommand(this)
        )

        server.commandManager.register(
            server.commandManager.metaBuilder("svcmutelist")
                .plugin(this)
                .build(),
            MuteListCommand(this)
        )


        // Запускаем задачу проверки истечения мутов каждые 1 секунду
        muteCheckTask = server.scheduler
            .buildTask(this, Runnable { checkExpiredMutes() })
            .repeat(1, TimeUnit.SECONDS)
            .schedule()

        logger.info("SVCMute плагин успешно загружен!")
    }

    /**
     * Обработчик события подключения игрока к серверу.
     * Отправляем статус мута игроку при подключении к backend-серверу.
     */
    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        val uuid = player.uniqueId

        // Небольшая задержка, чтобы клиент успел инициализироваться
        server.scheduler.buildTask(this, Runnable {
            if (isMuted(uuid)) {
                logger.info("[SVCMute] Игрок ${player.username} подключился к серверу и замьючен, отправляем пакет")
                sendMuteStatusToClient(uuid, true)
                voicechatStateUpdater?.accept(uuid, true)
            }
        }).delay(1, TimeUnit.SECONDS).schedule()
    }

    /**
     * Проверяет истёкшие муты и снимает их.
     */
    private fun checkExpiredMutes() {
        val currentTime = System.currentTimeMillis()
        val expiredMutes = mutableListOf<UUID>()

        mutedPlayers.forEach { (uuid, endTime) ->
            // Пропускаем перманентные муты
            if (endTime != -1L && currentTime >= endTime) {
                expiredMutes.add(uuid)
            }
        }

        expiredMutes.forEach { uuid ->
            logger.info("[SVCMute] Мут игрока $uuid истёк, снимаем автоматически")

            // Удаляем из списка
            mutedPlayers.remove(uuid)

            // Обновляем состояние voicechat
            voicechatStateUpdater?.accept(uuid, false)

            // Отправляем пакет клиенту
            sendMuteStatusToClient(uuid, false)

            // Уведомляем игрока, если он онлайн
            server.getPlayer(uuid).ifPresent { player ->
                player.sendMessage(
                    Component.text("Ваш мут в голосовом чате истёк. Микрофон включён!", NamedTextColor.GREEN)
                )
            }
        }
    }

    // Callback для обновления состояния VoiceChat (устанавливается из SVCMuteVoicechatPlugin)
    @JvmField
    var voicechatStateUpdater: BiConsumer<UUID, Boolean>? = null

    fun mutePlayer(uuid: UUID, durationSeconds: Long?) {
        val endTime = if (durationSeconds != null) {
            System.currentTimeMillis() + (durationSeconds * 1000)
        } else {
            -1L // Перманентный мут
        }
        mutedPlayers[uuid] = endTime

        // Устанавливаем состояние "disabled" для показа иконки отключённого микрофона
        voicechatStateUpdater?.accept(uuid, true)

        // Отправляем пакет клиенту напрямую
        sendMuteStatusToClient(uuid, true)
    }

    fun unmutePlayer(uuid: UUID): Boolean {
        val removed = mutedPlayers.remove(uuid) != null
        if (removed) {
            logger.info("[SVCMute] Размьютим игрока $uuid, вызываем callback и отправляем пакет")

            // Снимаем состояние "disabled"
            voicechatStateUpdater?.accept(uuid, false)

            // Отправляем пакет клиенту напрямую
            sendMuteStatusToClient(uuid, false)
        }
        return removed
    }

    /**
     * Отправляет пакет статуса мута напрямую клиенту через Velocity.
     */
    private fun sendMuteStatusToClient(uuid: UUID, muted: Boolean) {
        val player = server.getPlayer(uuid).orElse(null)
        if (player == null) {
            logger.warn("[SVCMute] Игрок $uuid не найден на прокси, пакет не отправлен")
            return
        }

        logger.info("[SVCMute] Отправка пакета статуса мута игроку ${player.username}: muted=$muted")

        val status: Byte = if (muted) 0x01 else 0x00
        val data = byteArrayOf(status)

        // Пробуем отправить напрямую клиенту
        val sentToClient = player.sendPluginMessage(MUTE_STATUS_CHANNEL, data)
        logger.info("[SVCMute] Отправка напрямую клиенту: $sentToClient")

        // Также отправляем через backend сервер (на случай если клиент не зарегистрировал канал напрямую)
        player.currentServer.ifPresent { serverConnection ->
            val sentToBackend = serverConnection.sendPluginMessage(MUTE_STATUS_CHANNEL, data)
            logger.info("[SVCMute] Отправка через backend сервер: $sentToBackend")
        }
    }

    fun isMuted(uuid: UUID): Boolean {
        val endTime = mutedPlayers[uuid] ?: return false

        // Если endTime == -1, то это перманентный мут
        if (endTime == -1L) return true

        // Проверяем, не истёк ли временный мут
        if (System.currentTimeMillis() >= endTime) {
            mutedPlayers.remove(uuid)
            return false
        }
        return true
    }

    fun getMutedPlayers(): Map<UUID, Long> = mutedPlayers.toMap()

    fun getServer(): ProxyServer = server

    fun hasPermission(source: CommandSource, permission: String): Boolean {
        if (source !is Player) return true // Консоль имеет все права

        return try {
            val luckPerms = LuckPermsProvider.get()
            val user = luckPerms.userManager.getUser(source.uniqueId)
            user?.cachedData?.permissionData?.checkPermission(permission)?.asBoolean() ?: false
        } catch (e: Exception) {
            logger.warn("Не удалось проверить права через LuckPerms: ${e.message}")
            source.hasPermission(permission)
        }
    }
}

// Команда /mute <player> [время]
class MuteCommand(private val plugin: SVCMute) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        if (!plugin.hasPermission(source, "svcmute.admin")) {
            source.sendMessage(Component.text("У вас нет прав для использования этой команды!", NamedTextColor.RED))
            return
        }

        if (args.isEmpty()) {
            source.sendMessage(Component.text("Использование: /mute <игрок> [время]", NamedTextColor.YELLOW))
            source.sendMessage(Component.text("Примеры времени: 10s, 5m, 2h, 1d", NamedTextColor.GRAY))
            return
        }

        val playerName = args[0]
        val targetPlayer = plugin.getServer().getPlayer(playerName).orElse(null)

        if (targetPlayer == null) {
            source.sendMessage(Component.text("Игрок '$playerName' не найден или не в сети!", NamedTextColor.RED))
            return
        }

        val durationSeconds: Long? = if (args.size > 1) {
            parseTime(args[1])
        } else {
            null
        }

        if (args.size > 1 && durationSeconds == null) {
            source.sendMessage(Component.text("Неверный формат времени! Примеры: 10s, 5m, 2h, 1d", NamedTextColor.RED))
            return
        }

        plugin.mutePlayer(targetPlayer.uniqueId, durationSeconds)

        val durationText = if (durationSeconds != null) {
            formatDuration(durationSeconds)
        } else {
            "навсегда"
        }

        source.sendMessage(
            Component.text("Игрок ", NamedTextColor.GREEN)
                .append(Component.text(targetPlayer.username, NamedTextColor.YELLOW))
                .append(Component.text(" замьючен в голосовом чате на ", NamedTextColor.GREEN))
                .append(Component.text(durationText, NamedTextColor.YELLOW))
        )

        targetPlayer.sendMessage(
            Component.text("Ваш микрофон в голосовом чате был отключён ", NamedTextColor.RED)
                .append(Component.text("на $durationText", NamedTextColor.YELLOW))
        )
    }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): java.util.concurrent.CompletableFuture<List<String>> {
        val args = invocation.arguments()

        return java.util.concurrent.CompletableFuture.supplyAsync {
            when (args.size) {
                0, 1 -> {
                    val prefix = args.getOrElse(0) { "" }.lowercase()
                    plugin.getServer().allPlayers
                        .map { it.username }
                        .filter { it.lowercase().startsWith(prefix) }
                }
                2 -> {
                    listOf("10s", "30s", "1m", "5m", "10m", "30m", "1h", "6h", "12h", "1d", "7d")
                        .filter { it.startsWith(args[1].lowercase()) }
                }
                else -> emptyList()
            }
        }
    }

    private fun parseTime(input: String): Long? {
        val regex = Regex("^(\\d+)([smhd])$", RegexOption.IGNORE_CASE)
        val match = regex.matchEntire(input) ?: return null

        val value = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()

        return when (unit) {
            "s" -> value
            "m" -> value * 60
            "h" -> value * 3600
            "d" -> value * 86400
            else -> null
        }
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}с"
            seconds < 3600 -> "${seconds / 60}м"
            seconds < 86400 -> "${seconds / 3600}ч"
            else -> "${seconds / 86400}д"
        }
    }
}

// Команда /unmute <player>
class UnmuteCommand(private val plugin: SVCMute) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        if (!plugin.hasPermission(source, "svcmute.admin")) {
            source.sendMessage(Component.text("У вас нет прав для использования этой команды!", NamedTextColor.RED))
            return
        }

        if (args.isEmpty()) {
            source.sendMessage(Component.text("Использование: /unmute <игрок>", NamedTextColor.YELLOW))
            return
        }

        val playerName = args[0]
        val targetPlayer = plugin.getServer().getPlayer(playerName).orElse(null)

        if (targetPlayer == null) {
            source.sendMessage(Component.text("Игрок '$playerName' не найден или не в сети!", NamedTextColor.RED))
            return
        }

        if (plugin.unmutePlayer(targetPlayer.uniqueId)) {
            source.sendMessage(
                Component.text("Игрок ", NamedTextColor.GREEN)
                    .append(Component.text(targetPlayer.username, NamedTextColor.YELLOW))
                    .append(Component.text(" размьючен в голосовом чате", NamedTextColor.GREEN))
            )

            targetPlayer.sendMessage(
                Component.text("Ваш микрофон в голосовом чате был включён!", NamedTextColor.GREEN)
            )
        } else {
            source.sendMessage(
                Component.text("Игрок ", NamedTextColor.RED)
                    .append(Component.text(targetPlayer.username, NamedTextColor.YELLOW))
                    .append(Component.text(" не был замьючен", NamedTextColor.RED))
            )
        }
    }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): java.util.concurrent.CompletableFuture<List<String>> {
        val args = invocation.arguments()

        return java.util.concurrent.CompletableFuture.supplyAsync {
            if (args.size <= 1) {
                val prefix = args.getOrElse(0) { "" }.lowercase()
                // Показываем только замьюченных игроков
                plugin.getMutedPlayers().keys
                    .mapNotNull { uuid -> plugin.getServer().getPlayer(uuid).orElse(null)?.username }
                    .filter { it.lowercase().startsWith(prefix) }
            } else {
                emptyList()
            }
        }
    }
}

// Команда /mutelist
class MuteListCommand(private val plugin: SVCMute) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()

        if (!plugin.hasPermission(source, "svcmute.admin")) {
            source.sendMessage(Component.text("У вас нет прав для использования этой команды!", NamedTextColor.RED))
            return
        }

        val mutedPlayers = plugin.getMutedPlayers()

        if (mutedPlayers.isEmpty()) {
            source.sendMessage(Component.text("Нет замьюченных игроков в голосовом чате", NamedTextColor.YELLOW))
            return
        }

        source.sendMessage(Component.text("=== Замьюченные игроки ===", NamedTextColor.GOLD))

        mutedPlayers.forEach { (uuid, endTime) ->
            val player = plugin.getServer().getPlayer(uuid).orElse(null)
            val playerName = player?.username ?: uuid.toString()
            val status = if (player != null) "онлайн" else "офлайн"

            val timeLeft = if (endTime == -1L) {
                "навсегда"
            } else {
                val remaining = (endTime - System.currentTimeMillis()) / 1000
                if (remaining > 0) formatTimeLeft(remaining) else "истекает..."
            }

            source.sendMessage(
                Component.text("• ", NamedTextColor.GRAY)
                    .append(Component.text(playerName, NamedTextColor.WHITE))
                    .append(Component.text(" ($status) ", NamedTextColor.GRAY))
                    .append(Component.text("- $timeLeft", NamedTextColor.YELLOW))
            )
        }
    }

    private fun formatTimeLeft(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}с"
            seconds < 3600 -> "${seconds / 60}м ${seconds % 60}с"
            seconds < 86400 -> "${seconds / 3600}ч ${(seconds % 3600) / 60}м"
            else -> "${seconds / 86400}д ${(seconds % 86400) / 3600}ч"
        }
    }
}
