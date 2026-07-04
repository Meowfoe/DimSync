package com.dimsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DimensionRegistry {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "dimsync_dimensions.json";
	private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Integer>>() {}.getType();

	private final Map<String, Integer> knownDimensions = new LinkedHashMap<>();
	private Path filePath;

	public void load(MinecraftServer server) {
		filePath = server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME);
		knownDimensions.clear();

		if (!Files.exists(filePath)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
			Map<String, Integer> loaded = GSON.fromJson(reader, MAP_TYPE);
			if (loaded != null) {
				knownDimensions.putAll(loaded);
			}
		} catch (IOException e) {
			DimSyncMod.LOGGER.warn("[DimSync] Couldn't read {}, starting with an empty dimension registry.", FILE_NAME, e);
		}
	}

	private void save() {
		if (filePath == null) {
			return;
		}
		try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
			GSON.toJson(knownDimensions, MAP_TYPE, writer);
		} catch (IOException e) {
			DimSyncMod.LOGGER.warn("[DimSync] Couldn't save {}.", FILE_NAME, e);
		}
	}

	public void discoverAll(MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			Result result = observe(world);
			if (result.newlyDiscovered()) {
				DimSyncMod.LOGGER.info("[DimSync] Discovered dimension '{}' at startup, assigned ID {}.",
						result.dimensionId(), result.numericId());
			}
		}
	}

	public Result observe(ServerWorld world) {
		String key = world.getRegistryKey().getValue().toString();

		Integer existing = knownDimensions.get(key);
		if (existing != null) {
			return new Result(key, existing, false);
		}

		int newId = knownDimensions.size();
		knownDimensions.put(key, newId);
		save();
		return new Result(key, newId, true);
	}

	public Map<String, Integer> getAll() {
		return knownDimensions;
	}

	public record Result(String dimensionId, int numericId, boolean newlyDiscovered) {
	}
}
