package club.rarlab.classicplugin.nms.entity

import club.rarlab.classicplugin.extension.*
import club.rarlab.classicplugin.nms.GlobalReflection.FetchType.*
import club.rarlab.classicplugin.nms.GlobalReflection.get
import club.rarlab.classicplugin.nms.ReflectionHelper
import club.rarlab.classicplugin.nms.ReflectionHelper.createPacket
import club.rarlab.classicplugin.nms.ReflectionHelper.sendPacket
import club.rarlab.classicplugin.task.schedule
import com.google.common.base.Splitter
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Scoreboard
import java.lang.reflect.Array
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.*

/**
 * [FakePlayer] used to create fake [Player]s.
 */
class FakePlayer private constructor(val uuid: UUID, nameTag: String) {
    /**
     * [Any] built EntityPlayer object.
     */
    private lateinit var entity: Any

    /**
     * [Array] of the single entity.
     */
    private val entityArray = Array.newInstance(get<Class<*>>(CLASS, "EntityPlayer"), 1)

    /**
     * [String] of the actual NPC name.
     */
    private val middleName: String

    /**
     * [Scoreboard] instance to be used for name tag.
     */
    private val scoreboard: Scoreboard = Bukkit.getScoreboardManager()!!.newScoreboard

    /**
     * [Any] of the nms ScoreboardTeam object.
     */
    private val scoreboardTeam: Any

    /**
     * [Equipment] of the [FakePlayer].
     */
    private var equipment: Equipment? = null

    /**
     * Apply an [Equipment] to the [FakePlayer].
     *
     * @param handle receiver to be handled.
     */
    infix fun equipment(handle: EquipmentBuilderDSL.() -> Unit) {
        if (!this::entity.isInitialized) return
        val builder = EquipmentBuilderDSL()
        handle(builder)
        this.equipment = builder.complete()
    }

    /**
     * Apply a skin by [Property] to the [FakePlayer].
     *
     * @param texturesProperty [Property] of textures to be applied to the [FakePlayer].
     */
    fun applySkin(texturesProperty: Property) {
        if (!this::entity.isInitialized) return
        val entityProfile = get<Method>(METHOD, "EntityHuman_getProfile").invoke(this.entity) as? GameProfile ?: return
        entityProfile.properties.put("textures", texturesProperty)
    }

    /**
     * Apply a [Player]'s skin to the [FakePlayer].
     *
     * @param player [Player]'s textures to fetch and then apply to the [FakePlayer].
     */
    fun applySkin(player: Player) {
        val texturesProperty = ReflectionHelper.getProfile(player).properties["textures"].find { it.name == "textures" } ?: return
        this.applySkin(texturesProperty)
    }

    /**
     * Set the [FakePlayer]'s [Location].
     *
     * @param location the [FakePlayer] should be 'teleported' to.
     */
    fun setLocation(location: Location) {
        if (!this::entity.isInitialized) return
        val (x, y, z, yaw, pitch) = location
        get<Method>(METHOD, "Entity_setLocation").invoke(this.entity, x, y, z, yaw, pitch)
    }

    /**
     * Despawn the entity.
     */
    fun despawn() = hideFrom(*Bukkit.getOnlinePlayers().toTypedArray())

    /**
     * Show the entity to an array of [Player].
     *
     * @param players array of [Player] to show to.
     */
    fun showTo(vararg players: Player) {
        if (!this::entity.isInitialized) return

        sendPacket(createPacket("PacketPlayOutPlayerInfo", getInfoAction("ADD_PLAYER"), this.entityArray), *players)
        sendPacket(createPacket("PacketPlayOutNamedEntitySpawn", this.entity), *players)
        equipment?.packets(getEntityId() ?: return)?.forEach { packet -> sendPacket(packet, *players) }

        players.forEach { player -> player.scoreboard = this.scoreboard }
        sendPacket(createPacket("PacketPlayOutScoreboardTeam", this.scoreboardTeam, listOf(this.middleName), 3), *players)
        schedule(20, false, Runnable {
            sendPacket(createPacket("PacketPlayOutPlayerInfo", getInfoAction("REMOVE_PLAYER"), this.entityArray), *players)
        })
    }

    /**
     * Hide the entity from an array of [Player].
     *
     * @param players array of [Player] to hide from.
     */
    fun hideFrom(vararg players: Player) {
        if (!this::entity.isInitialized) return
        val entityId = getEntityId() ?: return

        sendPacket(createPacket("PacketPlayOutPlayerInfo", getInfoAction("REMOVE_PLAYER"), this.entityArray), *players)
        sendPacket(createPacket("PacketPlayOutEntityDestroy", intArrayOf(entityId)), *players)
    }

    /**
     * Get the [FakePlayer]'s entity id.
     *
     * @return [Int] corresponding ID if not null.
     */
    private fun getEntityId(): Int? {
        if (!this::entity.isInitialized) return null
        return get<Method>(METHOD, "Entity_getId").invoke(this.entity) as? Int
    }

    /**
     * Get EnumPlayerInfoAction field from corresponding [Enum] by case-sensitive name.
     *
     * @param var1   name of the action to fetch.
     * @return [Any] corresponding action.
     */
    private fun getInfoAction(var1: String): Any {
        return get<Class<*>>(CLASS, "EnumPlayerInfoAction").getDeclaredField(var1).get(null)
    }

    /**
     * Get a [Player] object by the owner's [UUID].
     *
     * @return [Player] corresponding object.
     */
    private fun getPlayer(): Player? = Bukkit.getPlayer(this.uuid)

    /**
     * Pre initialization.
     */
    init {
        val player = getPlayer() ?: throw NullPointerException("Player is offline/invalid!")
        val worldServer = ReflectionHelper.getWorldServer(player)

        val names = Splitter.fixedLength(16).split(nameTag).toList()
        val prefix = names[0]
        val name = names.getOrNull(1)
        val suffix = names.getOrNull(2)
        val preciseName = (name ?: prefix).also { this.middleName = it }

        get<Constructor<*>>(CONSTRUCTOR, "EntityPlayer").newInstance(
                SERVER, worldServer, GameProfile(UUID.randomUUID(), preciseName),
                get<Constructor<*>>(CONSTRUCTOR, "PlayerInteractManager").newInstance(worldServer)
        ).also { entity -> this.entity = entity }

        val bukkitTeam = scoreboard.registerNewTeam("roam_${player.name}")
        prefix?.let { bukkitTeam.prefix = it }
        suffix?.let { bukkitTeam.suffix = it }

        val nmsScoreboard = get<Constructor<*>>(CONSTRUCTOR, "Scoreboard").newInstance()
        this.scoreboardTeam = get<Constructor<*>>(CONSTRUCTOR, "ScoreboardTeam").newInstance(nmsScoreboard, bukkitTeam.name)

        Array.set(entityArray, 0, this.entity)
    }

    /**
     * Global Stuff.
     */
    companion object {
        /**
         * The corresponding MinecraftServer object.
         */
        private val SERVER: Any by lazy {
            val craftServer = get<Class<*>>(CLASS, "CraftServer").cast(Bukkit.getServer())
            get<Method>(METHOD, "CraftServer_getServer").invoke(craftServer)
        }

        /**
         * Generate a new [FakePlayer] by owner unique id and name tag.
         *
         * @param uniqueId whom to be the owner of this [FakePlayer].
         * @param nameTag  to be set for the [FakePlayer].
         * @return [FakePlayer] corresponding fake player.
         */
        fun generate(uniqueId: UUID, nameTag: String): FakePlayer = FakePlayer(uniqueId, nameTag)
    }
}