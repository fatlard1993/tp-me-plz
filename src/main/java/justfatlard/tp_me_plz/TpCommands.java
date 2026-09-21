package justfatlard.tp_me_plz;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /tpme}: the menu, and each of its buttons as a word, for a player without the key bound
 * or without Pandorical at all. {@code accept} and {@code deny} are anybody's, since the answer
 * belongs to whoever was asked; {@code grant} and {@code revoke} are for ops.
 */
public final class TpCommands {
	private TpCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("tpme")
			.executes(context -> run(context, TpMenu::openFor))
			.then(Commands.literal("home").executes(context -> run(context, Places::home)))
			.then(Commands.literal("place")
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(context -> run(context, player -> {
						int index = Places.indexOf(player, StringArgumentType.getString(context, "name"));
						if (index < 0) Trips.say(player, "You have no place by that name");
						else Places.go(player, index);
					}))))
			// For a player without the menu: saved here, pictured by what is in their hand.
			.then(Commands.literal("newplace")
				.executes(context -> run(context, player -> newPlace(player, "")))
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(context -> run(context, player -> newPlace(player, StringArgumentType.getString(context, "name"))))))
			.then(Commands.literal("delplace")
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(context -> run(context, player -> {
						int index = Places.indexOf(player, StringArgumentType.getString(context, "name"));
						if (index < 0) Trips.say(player, "You have no place by that name");
						else Places.delete(player, index);
					}))))
			.then(Commands.literal("spawn").executes(context -> run(context, Trips::spawn)))
			.then(Commands.literal("death").executes(context -> run(context, TpMenu::lastDeath)))
			.then(Commands.literal("geode").executes(context -> run(context, TpMenu::geode)))
			.then(Commands.literal("ask")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> {
						ServerPlayer asker = context.getSource().getPlayerOrException();
						if (!Access.allowed(asker)) {
							TpMenu.refuse(asker);
							return 0;
						}
						Requests.ask(asker, EntityArgument.getPlayer(context, "player"));
						return 1;
					})))
			// The other way about: they travel, so it is their teleporting that is asked about,
			// inside Requests.bring.
			.then(Commands.literal("bring")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> {
						ServerPlayer asker = context.getSource().getPlayerOrException();
						if (!Access.allowed(asker)) {
							TpMenu.refuse(asker);
							return 0;
						}
						Requests.bring(asker, EntityArgument.getPlayer(context, "player"));
						return 1;
					})))
			.then(Commands.literal("accept")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> {
						Requests.accept(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"));
						return 1;
					})))
			.then(Commands.literal("deny")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(context -> {
						Requests.deny(context.getSource().getPlayerOrException(), EntityArgument.getPlayer(context, "player"));
						return 1;
					})))
			.then(Commands.literal("grant")
				.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
				.then(Commands.argument("players", EntityArgument.players())
					.executes(context -> give(context.getSource(), EntityArgument.getPlayers(context, "players"), true))))
			.then(Commands.literal("revoke")
				.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
				.then(Commands.argument("players", EntityArgument.players())
					.executes(context -> give(context.getSource(), EntityArgument.getPlayers(context, "players"), false)))));
	}

	/** Anything that is a use of the menu, behind the same door the menu is. */
	private static int run(CommandContext<CommandSourceStack> context,
			java.util.function.Consumer<ServerPlayer> action) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		if (!Access.allowed(player)) {
			TpMenu.refuse(player);
			return 0;
		}
		action.accept(player);
		return 1;
	}

	private static void newPlace(ServerPlayer player, String name) {
		var held = player.getMainHandItem();
		String icon = held.isEmpty() ? "minecraft:writable_book"
			: net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
		Places.add(player, Places.Spot.of(player), icon, name);
	}

	private static int give(CommandSourceStack source, Collection<ServerPlayer> players, boolean given) {
		for (ServerPlayer player : players) {
			Access.set(player, given);
			if (given) {
				player.sendSystemMessage(Component.literal("You can teleport now: /tpme, or bind \"Teleport Menu\" in your controls"));
			}
		}
		String names = String.join(", ", players.stream().map(p -> p.getGameProfile().name()).toList());
		source.sendSuccess(() -> Component.literal((given ? "Gave teleporting to " : "Took teleporting from ") + names), true);
		if (TpConfig.everyone()) {
			source.sendSuccess(() -> Component.literal("Everyone has it right now anyway: the access setting is \"everyone\""), false);
		}
		return players.size();
	}
}
