package com.dimsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerStateStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "dimsync_players.json";

	private Path filePath;

	public void load(MinecraftServer server, Set<UUID> soloPlayers, Map<UUID, DimSyncMod.OverworldPosition> overworldPositions) {
		filePath = server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME);
		soloPlayers.clear();
		overworldPositions.clear();

		if (!Files.exists(filePath)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
			Dto dto = GSON.fromJson(reader, Dto.class);
			if (dto == null) {
				return;
			}

			if (dto.soloPlayers != null) {
				for (String id : dto.soloPlayers) {
					try {
						soloPlayers.add(UUID.fromString(id));
					} catch (IllegalArgumentException ignored) {
					}
				}
			}

			if (dto.overworldPositions != null) {
				dto.overworldPositions.forEach((id, pos) -> {
					try {
						overworldPositions.put(UUID.fromString(id),
								new DimSyncMod.OverworldPosition(pos.x, pos.y, pos.z, pos.yaw, pos.pitch));
					} catch (IllegalArgumentException ignored) {
					}
				});
			}
		} catch (IOException e) {
			DimSyncMod.LOGGER.warn("[DimSync] Couldn't read {}, starting with empty player data.", FILE_NAME, e);
		}
	}

	public void save(Set<UUID> soloPlayers, Map<UUID, DimSyncMod.OverworldPosition> overworldPositions) {
		if (filePath == null) {
			return;
		}

		Dto dto = new Dto();
		for (UUID id : soloPlayers) {
			dto.soloPlayers.add(id.toString());
		}
		overworldPositions.forEach((id, pos) -> {
			PositionDto p = new PositionDto();
			p.x = pos.x();
			p.y = pos.y();
			p.z = pos.z();
			p.yaw = pos.yaw();
			p.pitch = pos.pitch();
			dto.overworldPositions.put(id.toString(), p);
		});

		try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
			GSON.toJson(dto, writer);
		} catch (IOException e) {
			DimSyncMod.LOGGER.warn("[DimSync] Couldn't save {}.", FILE_NAME, e);
		}
	}

	private static final class Dto {
		Set<String> soloPlayers = new HashSet<>();
		Map<String, PositionDto> overworldPositions = new HashMap<>();
	}

	private static final class PositionDto {
		double x;
		double y;
		double z;
		float yaw;
		float pitch;
	}
}
