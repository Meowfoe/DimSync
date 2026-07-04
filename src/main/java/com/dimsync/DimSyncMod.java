package com.dimsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class DimSyncMod implements ModInitializer {

	public static final String MOD_ID = "dimsync";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final DimensionRegistry REGISTRY = new DimensionRegistry();

	/** Master on/off switch, toggled with /dimsync toggle. */
	public static volatile boolean enabled = true;

	/**
	 * Re-entrancy guard: while we're teleporting the rest of the group to
	 * follow the player who moved, those teleports would themselves fire
	 * MORE "player changed world" events. This flag tells our own listener
	 * to ignore events caused by DimSync itself, so it doesn't chain into an
	 * infinite loop of mutual teleporting.
	 */
	private static final AtomicBoolean syncing = new AtomicBoolean(false);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);

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

		if (result.newlyDiscovered()) {
			LOGGER.info("[DimSync] Discovered new dimension '{}', assigned ID {}.",
					result.dimensionId(), result.numericId());
			player.getServer().getPlayerManager().broadcast(
					Text.literal("[DimSync] New dimension discovered: " + result.dimensionId()
							+ " (id " + result.numericId() + ")"),
					false);
		}

		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}

		String destinationName = result.dimensionId();
		PrettyName prettyName = prettifyDimensionId(destinationName);
		server.getPlayerManager().broadcast(
				Text.literal(player.getName().getString() + " entered " + prettyName.path()
						+ " from " + prettyName.namespace()),
				false);

		syncing.set(true);
		try {
			double x = player.getX();
			double y = player.getY();
			double z = player.getZ();
			float yaw = player.getYaw();
			float pitch = player.getPitch();

			for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
				if (other == player) {
					continue;
				}
				other.teleport(destination, x, y, z, yaw, pitch);
			}
		} finally {
			syncing.set(false);
		}
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
}
