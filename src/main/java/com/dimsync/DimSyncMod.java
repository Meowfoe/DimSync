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

	public static volatile boolean enabled = true;

	public static final Set<UUID> soloPlayers = ConcurrentHashMap.newKeySet();

	private static final Map<UUID, OverworldPosition> overworldPositions = new ConcurrentHashMap<>();

	private static final AtomicBoolean syncing = new AtomicBoolean(false);

	private int ticksSinceAutosave = 0;
	private static final int AUTOSAVE_INTERVAL_TICKS = 20 * 60;

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

	public static void savePlayerState(MinecraftServer server) {
		PLAYER_STORE.save(soloPlayers, overworldPositions);
	}

	private void onPlayerChangedWorld(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination) {
		if (!enabled) {
			return;
		}
		if (syncing.get()) {
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
					continue;
				}

				if (destinationIsOverworld) {
					if (other.getWorld().getRegistryKey().equals(World.OVERWORLD)) {
						continue;
					}
					teleportToOwnSpotOrFallback(other, destination, x, y, z, yaw, pitch);
				} else {
					teleportAndProtect(other, destination, x, y, z, yaw, pitch);
				}
			}

			if (destinationIsOverworld) {
				teleportToOwnSpotOrFallback(player, destination, x, y, z, yaw, pitch);
			}
		} finally {
			syncing.set(false);
		}
	}

	private static final int RESISTANCE_DURATION_TICKS = 15 * 20;
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

	private void teleportAndProtect(ServerPlayerEntity target, ServerWorld world,
			double x, double y, double z, float yaw, float pitch) {
		target.teleport(world, x, y, z, yaw, pitch);
		target.addStatusEffect(new StatusEffectInstance(
				StatusEffects.RESISTANCE, RESISTANCE_DURATION_TICKS, RESISTANCE_AMPLIFIER, false, false, true));
	}

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

	public record OverworldPosition(double x, double y, double z, float yaw, float pitch) {
		static OverworldPosition of(ServerPlayerEntity player) {
			return new OverworldPosition(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
		}
	}
}
