package dev.baechka.BucketMute

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
    id = "bucketmute",
    name = "BucketMute",
    version = BuildConstants.VERSION,
    dependencies = [
        Dependency(id = "voicechat"),
        Dependency(id = "luckperms")
    ]
)
class BucketMute @Inject constructor(
    val logger: Logger,
    private val server: ProxyServer
) {

    companion object {
        lateinit var instance: BucketMute
            private set

        private const val ADMIN_PERMISSION = "bucketmute.admin"

        val MUTE_STATUS_CHANNEL: MinecraftChannelIdentifier =
            MinecraftChannelIdentifier.create("bucketmute", "mute_status")
    }

    private val mutedPlayers = ConcurrentHashMap<UUID, Long>()
    private var muteCheckTask: ScheduledTask? = null
    private var broadcastEnabled = false

    @JvmField
    var voicechatStateUpdater: BiConsumer<UUID, Boolean>? = null

    @Subscribe
    @Suppress("UNUSED_PARAMETER")
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        instance = this

        Messages.load()

        server.channelRegistrar.register(MUTE_STATUS_CHANNEL)

        server.commandManager.register(
            server.commandManager.metaBuilder("bucketmute").plugin(this).build(),
            MuteCommand(this)
        )

        server.commandManager.register(
            server.commandManager.metaBuilder("bucketunmute").plugin(this).build(),
            UnmuteCommand(this)
        )

        server.commandManager.register(
            server.commandManager.metaBuilder("bucketmutelist").plugin(this).build(),
            MuteListCommand(this)
        )

        server.commandManager.register(
            server.commandManager.metaBuilder("bucketbroadcast").plugin(this).build(),
            BroadcastCommand(this)
        )

        muteCheckTask = server.scheduler
            .buildTask(this, Runnable { checkExpiredMutes() })
            .repeat(1, TimeUnit.SECONDS)
            .schedule()

        logger.info("BucketMute plugin loaded")
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val player = event.player
        val uuid = player.uniqueId

        server.scheduler.buildTask(this, Runnable {
            updateMuteState(uuid)
        }).delay(1, TimeUnit.SECONDS).schedule()
    }

    private fun checkExpiredMutes() {
        val currentTime = System.currentTimeMillis()
        val expiredMutes = mutableListOf<UUID>()

        mutedPlayers.forEach { (uuid, endTime) ->
            if (endTime != -1L && currentTime >= endTime) {
                expiredMutes.add(uuid)
            }
        }

        expiredMutes.forEach { uuid ->
            mutedPlayers.remove(uuid)
            updateMuteState(uuid)

            server.getPlayer(uuid).ifPresent { player ->
                if (!isEffectivelyMuted(uuid)) {
                    player.sendMessage(
                        Component.text(Messages.get(player, "mute-expired"), NamedTextColor.GREEN)
                    )
                }
            }
        }
    }

    fun mutePlayer(uuid: UUID, durationSeconds: Long?) {
        val endTime = if (durationSeconds != null) {
            System.currentTimeMillis() + (durationSeconds * 1000)
        } else {
            -1L
        }
        mutedPlayers[uuid] = endTime
        voicechatStateUpdater?.accept(uuid, true)
        sendMuteStatusToClient(uuid, true)
    }

    fun unmutePlayer(uuid: UUID): Boolean {
        val removed = mutedPlayers.remove(uuid) != null
        if (removed) {
            updateMuteState(uuid)
        }
        return removed
    }

    fun sendMuteStatusToClient(uuid: UUID, muted: Boolean) {
        val player = server.getPlayer(uuid).orElse(null) ?: return

        val data = byteArrayOf(if (muted) 0x01 else 0x00)
        player.sendPluginMessage(MUTE_STATUS_CHANNEL, data)

        player.currentServer.ifPresent { serverConnection ->
            serverConnection.sendPluginMessage(MUTE_STATUS_CHANNEL, data)
        }
    }

    fun isMuted(uuid: UUID): Boolean {
        val endTime = mutedPlayers[uuid] ?: return false
        if (endTime == -1L) return true

        if (System.currentTimeMillis() >= endTime) {
            mutedPlayers.remove(uuid)
            return false
        }
        return true
    }

    fun isEffectivelyMuted(uuid: UUID): Boolean {
        return isMuted(uuid) || (broadcastEnabled && !hasAdminPermission(uuid))
    }

    fun isBroadcastEnabled(): Boolean = broadcastEnabled

    fun setBroadcastEnabled(enabled: Boolean) {
        if (broadcastEnabled == enabled) return

        broadcastEnabled = enabled
        server.allPlayers.forEach { updateMuteState(it.uniqueId) }
    }

    fun updateMuteState(uuid: UUID) {
        val muted = isEffectivelyMuted(uuid)
        voicechatStateUpdater?.accept(uuid, muted)
        sendMuteStatusToClient(uuid, muted)
    }

    fun getMutedPlayers(): Map<UUID, Long> = mutedPlayers.toMap()

    fun getServer(): ProxyServer = server

    fun hasPermission(source: CommandSource, permission: String): Boolean {
        if (source !is Player) return true

        return try {
            val luckPerms = LuckPermsProvider.get()
            val user = luckPerms.userManager.getUser(source.uniqueId)
            user?.cachedData?.permissionData?.checkPermission(permission)?.asBoolean() ?: false
        } catch (_: Exception) {
            source.hasPermission(permission)
        }
    }

    fun hasAdminPermission(uuid: UUID): Boolean {
        val player = server.getPlayer(uuid).orElse(null) ?: return false
        return hasPermission(player, ADMIN_PERMISSION)
    }
}

class MuteCommand(private val plugin: BucketMute) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()
        val sourcePlayer = source as? Player

        if (!plugin.hasPermission(source, "bucketmute.admin")) {
            val msg = sourcePlayer?.let { Messages.get(it, "no-permission") } ?: Messages.get("no-permission")
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
            return
        }

        if (args.isEmpty()) {
            val usage = sourcePlayer?.let { Messages.get(it, "usage-mute") } ?: Messages.get("usage-mute")
            val examples = sourcePlayer?.let { Messages.get(it, "usage-mute-examples") } ?: Messages.get("usage-mute-examples")
            source.sendMessage(Component.text(usage, NamedTextColor.YELLOW))
            source.sendMessage(Component.text(examples, NamedTextColor.GRAY))
            return
        }

        val playerName = args[0]
        val targetPlayer = plugin.getServer().getPlayer(playerName).orElse(null)

        if (targetPlayer == null) {
            val msg = sourcePlayer?.let { Messages.get(it, "player-not-found", playerName) } ?: Messages.get("player-not-found", playerName)
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
            return
        }

        val durationSeconds: Long? = if (args.size > 1) {
            parseTime(args[1])
        } else {
            null
        }

        if (args.size > 1 && durationSeconds == null) {
            val msg = sourcePlayer?.let { Messages.get(it, "invalid-time-format") } ?: Messages.get("invalid-time-format")
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
            return
        }

        plugin.mutePlayer(targetPlayer.uniqueId, durationSeconds)

        val sourceDurationText = if (durationSeconds != null) {
            formatDuration(sourcePlayer, durationSeconds)
        } else {
            sourcePlayer?.let { Messages.get(it, "duration-forever") } ?: Messages.get("duration-forever")
        }

        val targetDurationText = if (durationSeconds != null) {
            formatDuration(targetPlayer, durationSeconds)
        } else {
            Messages.get(targetPlayer, "duration-forever")
        }

        val successMsg = sourcePlayer?.let { Messages.get(it, "mute-success", targetPlayer.username, sourceDurationText) }
            ?: Messages.get("mute-success", targetPlayer.username, sourceDurationText)
        source.sendMessage(Component.text(successMsg, NamedTextColor.GREEN))

        targetPlayer.sendMessage(
            Component.text(Messages.get(targetPlayer, "mute-notify", targetDurationText), NamedTextColor.RED)
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

    private fun formatDuration(player: Player?, seconds: Long): String {
        return when {
            seconds < 60 -> player?.let { Messages.get(it, "time-seconds", seconds) } ?: Messages.get("time-seconds", seconds)
            seconds < 3600 -> player?.let { Messages.get(it, "time-minutes", seconds / 60) } ?: Messages.get("time-minutes", seconds / 60)
            seconds < 86400 -> player?.let { Messages.get(it, "time-hours", seconds / 3600) } ?: Messages.get("time-hours", seconds / 3600)
            else -> player?.let { Messages.get(it, "time-days", seconds / 86400) } ?: Messages.get("time-days", seconds / 86400)
        }
    }
}

class BroadcastCommand(private val plugin: BucketMute) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()
        val sourcePlayer = source as? Player

        if (!plugin.hasPermission(source, "bucketmute.admin")) {
            val msg = sourcePlayer?.let { Messages.get(it, "no-permission") } ?: Messages.get("no-permission")
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
            return
        }

        if (args.isEmpty()) {
            sendStatus(source, sourcePlayer)
            return
        }

        when (args[0].lowercase()) {
            "on", "enable", "enabled", "true" -> {
                plugin.setBroadcastEnabled(true)
                val msg = sourcePlayer?.let { Messages.get(it, "broadcast-enabled") } ?: Messages.get("broadcast-enabled")
                source.sendMessage(Component.text(msg, NamedTextColor.GREEN))
            }
            "off", "disable", "disabled", "false" -> {
                plugin.setBroadcastEnabled(false)
                val msg = sourcePlayer?.let { Messages.get(it, "broadcast-disabled") } ?: Messages.get("broadcast-disabled")
                source.sendMessage(Component.text(msg, NamedTextColor.GREEN))
            }
            "status" -> sendStatus(source, sourcePlayer)
            else -> {
                val msg = sourcePlayer?.let { Messages.get(it, "usage-broadcast") } ?: Messages.get("usage-broadcast")
                source.sendMessage(Component.text(msg, NamedTextColor.YELLOW))
            }
        }
    }

    override fun suggestAsync(invocation: SimpleCommand.Invocation): java.util.concurrent.CompletableFuture<List<String>> {
        val args = invocation.arguments()

        return java.util.concurrent.CompletableFuture.supplyAsync {
            if (args.size <= 1) {
                val prefix = args.getOrElse(0) { "" }.lowercase()
                listOf("on", "off", "status").filter { it.startsWith(prefix) }
            } else {
                emptyList()
            }
        }
    }

    private fun sendStatus(source: CommandSource, sourcePlayer: Player?) {
        val key = if (plugin.isBroadcastEnabled()) {
            "broadcast-status-enabled"
        } else {
            "broadcast-status-disabled"
        }
        val msg = sourcePlayer?.let { Messages.get(it, key) } ?: Messages.get(key)
        source.sendMessage(Component.text(msg, NamedTextColor.YELLOW))
    }
}

class UnmuteCommand(private val plugin: BucketMute) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()
        val sourcePlayer = source as? Player

        if (!plugin.hasPermission(source, "bucketmute.admin")) {
            val msg = sourcePlayer?.let { Messages.get(it, "no-permission") } ?: Messages.get("no-permission")
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
            return
        }

        if (args.isEmpty()) {
            val msg = sourcePlayer?.let { Messages.get(it, "usage-unmute") } ?: Messages.get("usage-unmute")
            source.sendMessage(Component.text(msg, NamedTextColor.YELLOW))
            return
        }

        val playerName = args[0]
        val targetPlayer = plugin.getServer().getPlayer(playerName).orElse(null)

        if (targetPlayer == null) {
            val msg = sourcePlayer?.let { Messages.get(it, "player-not-found", playerName) } ?: Messages.get("player-not-found", playerName)
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
            return
        }

        if (plugin.unmutePlayer(targetPlayer.uniqueId)) {
            val successMsg = sourcePlayer?.let { Messages.get(it, "unmute-success", targetPlayer.username) }
                ?: Messages.get("unmute-success", targetPlayer.username)
            source.sendMessage(Component.text(successMsg, NamedTextColor.GREEN))

            targetPlayer.sendMessage(
                Component.text(Messages.get(targetPlayer, "unmute-notify"), NamedTextColor.GREEN)
            )
        } else {
            val msg = sourcePlayer?.let { Messages.get(it, "unmute-not-muted", targetPlayer.username) }
                ?: Messages.get("unmute-not-muted", targetPlayer.username)
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
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

class MuteListCommand(private val plugin: BucketMute) : SimpleCommand {

    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val sourcePlayer = source as? Player

        if (!plugin.hasPermission(source, "bucketmute.admin")) {
            val msg = sourcePlayer?.let { Messages.get(it, "no-permission") } ?: Messages.get("no-permission")
            source.sendMessage(Component.text(msg, NamedTextColor.RED))
            return
        }

        val mutedPlayers = plugin.getMutedPlayers()

        if (mutedPlayers.isEmpty()) {
            val msg = sourcePlayer?.let { Messages.get(it, "mutelist-empty") } ?: Messages.get("mutelist-empty")
            source.sendMessage(Component.text(msg, NamedTextColor.YELLOW))
            return
        }

        val header = sourcePlayer?.let { Messages.get(it, "mutelist-header") } ?: Messages.get("mutelist-header")
        source.sendMessage(Component.text(header, NamedTextColor.GOLD))

        mutedPlayers.forEach { (uuid, endTime) ->
            val player = plugin.getServer().getPlayer(uuid).orElse(null)
            val playerName = player?.username ?: uuid.toString()
            val status = if (player != null) {
                sourcePlayer?.let { Messages.get(it, "status-online") } ?: Messages.get("status-online")
            } else {
                sourcePlayer?.let { Messages.get(it, "status-offline") } ?: Messages.get("status-offline")
            }

            val timeLeft = if (endTime == -1L) {
                sourcePlayer?.let { Messages.get(it, "duration-forever") } ?: Messages.get("duration-forever")
            } else {
                val remaining = (endTime - System.currentTimeMillis()) / 1000
                if (remaining > 0) {
                    formatTimeLeft(sourcePlayer, remaining)
                } else {
                    sourcePlayer?.let { Messages.get(it, "duration-expiring") } ?: Messages.get("duration-expiring")
                }
            }

            val entry = sourcePlayer?.let { Messages.get(it, "mutelist-entry", playerName, status, timeLeft) }
                ?: Messages.get("mutelist-entry", playerName, status, timeLeft)
            source.sendMessage(Component.text(entry, NamedTextColor.GRAY))
        }
    }

    private fun formatTimeLeft(player: Player?, seconds: Long): String {
        return when {
            seconds < 60 -> player?.let { Messages.get(it, "time-seconds", seconds) } ?: Messages.get("time-seconds", seconds)
            seconds < 3600 -> player?.let { Messages.get(it, "time-minutes-seconds", seconds / 60, seconds % 60) }
                ?: Messages.get("time-minutes-seconds", seconds / 60, seconds % 60)
            seconds < 86400 -> player?.let { Messages.get(it, "time-hours-minutes", seconds / 3600, (seconds % 3600) / 60) }
                ?: Messages.get("time-hours-minutes", seconds / 3600, (seconds % 3600) / 60)
            else -> player?.let { Messages.get(it, "time-days-hours", seconds / 86400, (seconds % 86400) / 3600) }
                ?: Messages.get("time-days-hours", seconds / 86400, (seconds % 86400) / 3600)
        }
    }
}
