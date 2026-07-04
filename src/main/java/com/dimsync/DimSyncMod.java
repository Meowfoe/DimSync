package com.dimsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DimSyncMod implements ModInitializer {

	public static final String MOD_ID = "dimsync";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final DimensionRegistry REGISTRY = new DimensionRegistry();
	private static final PlayerStateStore PLAYER_STORE = new PlayerStateStore();

	/** Master on/off switch, toggled with /dimsync toggle. */
	public static volatile boolean enabled = true;

	/**
	 * Players who've opted out of being dragged along with the group via
	 * /dimsync solo. A solo player is never pulled by anyone else's
	 * movement, AND their own movement never drags anyone else along either -
	 * they move around independently while solo mode is on. Persisted to
	 * disk so it survives restarts.
	 */
	public static final Set<UUID> soloPlayers = ConcurrentHashMap.newKeySet();

	/**
	 * Each player's last known Overworld position. Updated continuously every
	 * tick for anyone currently standing in the Overworld, so it always holds
	 * an up-to-date (within one tick) position right up until the moment they
	 * leave. When the group later syncs back into the Overworld, each player
	 * (including whoever triggered the return) is restored to their own spot
	 * here instead of being matched to wherever the trigger player landed.
	 * Persisted to disk so it survives restarts.
	 */
	private static final Map<UUID, OverworldPosition> overworldPositions = new ConcurrentHashMap<>();

	/**
	 * Re-entrancy guard: while we're teleporting the rest of the group to
	 * follow the player who moved, those teleports would themselves fire
	 * MORE "player changed world" events. This flag tells our own listener
	 * to ignore events caused by DimSync itself, so it doesn't chain into an
	 * infinite loop of mutual teleporting.
	 */
	private static final AtomicBoolean syncing = new AtomicBoolean(false);

	/** Counts ticks since the last autosave of player state, see onServerTick. */
	private int ticksSinceAutosave = 0;
	private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60; // ~1 minute

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(this::onPlayerChangedWorld);

		DimSyncCommand.register();

		LOGGER.info("[DimSync] initialized.");
	}

	private void onServerStarted(MinecraftServer server) {
		REGISTRY.load(server);
		LOGGER.info("[DimSync] Loaded {} previously discovered dimension(s) from disk.", REGISTRY.getAll().size());

		// Eagerly register every dimension that's already loaded (vanilla + all
		// mods' dimensions) rather than waiting for a player to visit each one.
		REGISTRY.discoverAll(server);
		LOGGER.info("[DimSync] {} dimension(s) known in total after startup scan.", REGISTRY.getAll().size());

		PLAYER_STORE.load(server, soloPlayers, overworldPositions);
		LOGGER.info("[DimSync] Loaded {} solo player(s) and {} saved Overworld position(s) from disk.",
				soloPlayers.size(), overworldPositions.size());
	}

	private void onServerStopping(MinecraftServer server) {
		savePlayerState(server);
	}

	private void onServerTick(MinecraftServer server) {
		// Continuously remember where everyone is in the Overworld, so we
		// always have an up-to-date spot to send them back to later.
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (player.getWorld().getRegistryKey().equals(World.OVERWORLD)) {
				overworldPositions.put(player.getUuid(), OverworldPosition.of(player));
			}
		}

		ticksSinceAutosave++;
		if (ticksSinceAutosave >= AUTOSAVE_INTERVAL_TICKS) {
			ticksSinceAutosave = 0;
			savePlayerState(server);
		}
	}

	/** Exposed so DimSyncCommand can force an immediate save after /dimsync solo. */
	public static void savePlayerState(MinecraftServer server) {
		PLAYER_STORE.save(soloPlayers, overworldPositions);
	}

	private void onPlayerChangedWorld(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination) {
		if (!enabled) {
			return;
		}
		if (syncing.get()) {
			// This event was caused by our own sync teleport below - ignore it.
			return;
		}

		DimensionRegistry.Result result = REGISTRY.observe(destination);

		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}

		if (result.newlyDiscovered()) {
			LOGGER.info("[DimSync] Discovered new dimension '{}', assigned ID {}.",
					result.dimensionId(), result.numericId());
			server.getPlayerManager().broadcast(
					Text.literal("[DimSync] New dimension discovered: " + result.dimensionId()
							+ " (id " + result.numericId() + ")"),
					false);
		}

		PrettyName prettyName = prettifyDimensionId(result.dimensionId());
		server.getPlayerManager().broadcast(
				Text.literal(player.getName().getString() + " entered " + prettyName.path()
						+ " from " + prettyName.namespace()),
				false);

		if (soloPlayers.contains(player.getUuid())) {
			// This player is in solo mode - they move on their own and don't
			// drag anyone else along with them.
			return;
		}

		syncing.set(true);
		try {
			boolean destinationIsOverworld = destination.getRegistryKey().equals(World.OVERWORLD);
			double x = player.getX();
			double y = player.getY();
			double z = player.getZ();
			float yaw = player.getYaw();
			float pitch = player.getPitch();

			for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
				if (other == player) {
					continue;
				}
				if (soloPlayers.contains(other.getUuid())) {
					// This player opted out of being dragged along - leave them be.
					continue;
				}

				if (destinationIsOverworld) {
					if (other.getWorld().getRegistryKey().equals(World.OVERWORLD)) {
						// Already home - nothing to do for this player.
						continue;
					}
					teleportToOwnSpotOrFallback(other, destination, x, y, z, yaw, pitch);
				} else {
					teleportAndProtect(other, destination, x, y, z, yaw, pitch);
				}
			}

			// The player who triggered the move should also land on their own
			// remembered Overworld spot, instead of wherever the vanilla
			// portal/command happened to place them.
			if (destinationIsOverworld) {
				teleportToOwnSpotOrFallback(player, destination, x, y, z, yaw, pitch);
			}
		} finally {
			syncing.set(false);
		}
	}

	/** How long the post-teleport Resistance buff lasts. */
	private static final int RESISTANCE_DURATION_TICKS = 15 * 20;
	/** Amplifier 9 = Resistance X (levels are amplifier + 1). */
	private static final int RESISTANCE_AMPLIFIER = 9;

	private void teleportToOwnSpotOrFallback(ServerPlayerEntity target, ServerWorld destination,
			double fallbackX, double fallbackY, double fallbackZ, float fallbackYaw, float fallbackPitch) {
		OverworldPosition saved = overworldPositions.get(target.getUuid());
		if (saved != null) {
			teleportAndProtect(target, destination, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
		} else {
			teleportAndProtect(target, destination, fallbackX, fallbackY, fallbackZ, fallbackYaw, fallbackPitch);
		}
	}

	/**
	 * Teleports a player and grants a brief Resistance X buff afterward, so
	 * landing somewhere unexpected (mid-air, in lava, etc.) doesn't get anyone
	 * killed by fall damage or similar right after a forced sync teleport.
	 */
	private void teleportAndProtect(ServerPlayerEntity target, ServerWorld world,
			double x, double y, double z, float yaw, float pitch) {
		target.teleport(world, x, y, z, yaw, pitch);
		target.addStatusEffect(new StatusEffectInstance(
				StatusEffects.RESISTANCE, RESISTANCE_DURATION_TICKS, RESISTANCE_AMPLIFIER, false, false, true));
	}

	/**
	 * Turns a raw dimension identifier like "dreamshift:familiar_eye_exam"
	 * into a friendly pair: namespace "Dreamshift", path "Familiar Eye Exam".
	 * Underscores become spaces and every word is capitalized.
	 */
	private static PrettyName prettifyDimensionId(String dimensionId) {
		String[] parts = dimensionId.split(":", 2);
		String namespace = parts.length > 0 ? parts[0] : dimensionId;
		String path = parts.length > 1 ? parts[1] : "";
		return new PrettyName(titleCase(namespace), titleCase(path));
	}

	private static String titleCase(String raw) {
		String[] words = raw.split("_");
		StringBuilder result = new StringBuilder();
		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			if (result.length() > 0) {
				result.append(' ');
			}
			result.append(Character.toUpperCase(word.charAt(0)));
			if (word.length() > 1) {
				result.append(word.substring(1));
			}
		}
		return result.toString();
	}

	private record PrettyName(String namespace, String path) {
	}

	/** A remembered position + facing direction in the Overworld. */
	public record OverworldPosition(double x, double y, double z, float yaw, float pitch) {
		static OverworldPosition of(ServerPlayerEntity player) {
			return new OverworldPosition(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
		}
	}
}
