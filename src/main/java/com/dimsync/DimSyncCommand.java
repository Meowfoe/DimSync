package com.dimsync;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Map;

import static net.minecraft.server.command.CommandManager.literal;

public final class DimSyncCommand {

	private DimSyncCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(literal("dimsync")
						.then(literal("toggle")
								.requires(source -> source.hasPermissionLevel(2))
								.executes(DimSyncCommand::toggle))
						.then(literal("on")
								.requires(source -> source.hasPermissionLevel(2))
								.executes(ctx -> setEnabled(ctx, true)))
						.then(literal("off")
								.requires(source -> source.hasPermissionLevel(2))
								.executes(ctx -> setEnabled(ctx, false)))
						.then(literal("status")
								.executes(DimSyncCommand::status))
						.then(literal("list")
								.executes(DimSyncCommand::list))
				));
	}

	private static int toggle(CommandContext<ServerCommandSource> ctx) {
		return setEnabled(ctx, !DimSyncMod.enabled);
	}

	private static int setEnabled(CommandContext<ServerCommandSource> ctx, boolean value) {
		DimSyncMod.enabled = value;
		ctx.getSource().getServer().getPlayerManager().broadcast(
				Text.literal("[DimSync] " + (value ? "enabled" : "disabled") + "."),
				false);
		return 1;
	}

	private static int status(CommandContext<ServerCommandSource> ctx) {
		ctx.getSource().sendFeedback(() -> Text.literal(
				"[DimSync] Currently " + (DimSyncMod.enabled ? "ON" : "OFF")
						+ ". " + DimSyncMod.REGISTRY.getAll().size() + " dimension(s) discovered so far."),
				false);
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		Map<String, Integer> known = DimSyncMod.REGISTRY.getAll();
		if (known.isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("[DimSync] No dimensions discovered yet."), false);
			return 1;
		}
		ctx.getSource().sendFeedback(() -> Text.literal("[DimSync] Discovered dimensions:"), false);
		known.forEach((dimensionId, numericId) ->
				ctx.getSource().sendFeedback(() -> Text.literal("  [" + numericId + "] " + dimensionId), false));
		return 1;
	}
}
