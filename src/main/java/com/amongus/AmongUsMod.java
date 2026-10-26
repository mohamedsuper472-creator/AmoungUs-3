package com.amongus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmongUsMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("amongus");

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			AmongCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(Game::tick);

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
			Game.onAttack(player, world, hand, entity));

		UseBlockCallback.EVENT.register((player, world, hand, hit) ->
			Game.onUseBlock(player, world, hand, hit));

		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
			Game.onUseEntity(player, world, hand, entity));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			Game.server = server;
			Game.onServerJoin(handler.getPlayer());
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Game.server = server;
			Game.leave(handler.getPlayer().getUUID(), true);
		});

		LOGGER.info("Among Us loaded. Use /among");
	}
}
