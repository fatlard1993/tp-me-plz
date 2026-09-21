package justfatlard.tp_me_plz;

import justfatlard.pandorical.api.PandoricalApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A teleport menu on a key. Home, set home, spawn, the last death and your geode where the mods
 * for those are installed, and every other player online, to ask to go to.
 *
 * <p>Who has it is the server's call: by default only players an op has given it to, since a
 * teleport for everyone changes what a long walk means on a server; one setting gives it to all.
 */
public class Main implements ModInitializer {
	public static final String MOD_ID = "tp-me-plz";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** H, in the game's own key table (SDL scancodes: A is 4). The server has no InputConstants to name it by. */
	private static final int KEY_H = 11;

	@Override
	public void onInitialize() {
		TpConfig.load();
		Access.init();
		Places.init();
		TpMenu.register();
		Requests.register();

		// H, bound for everyone who has not put something else on the slot: a menu a child is
		// meant to use cannot start behind a trip to the controls screen. Nothing in vanilla is on
		// H. Moving or clearing it sticks; /tpme opens the same menu either way.
		PandoricalApi.keybinds().register(MOD_ID + ":menu", KEY_H, "Teleport Menu", TpMenu::openFor);
		PandoricalApi.keybinds().bindByDefault(MOD_ID + ":menu");
		// The command rather than the keybind: /tpme opens a screen of big buttons for every
		// target, so one way in is all this needs. Promoting home, spawn and the rest would be a
		// menu of buttons that opens a menu of buttons.
		PandoricalApi.actionMenus().suggestButton(justfatlard.pandorical.api.ActionMenuApi.Button
			.runs("minecraft:ender_pearl", "Teleport", "tpme"));
		PandoricalApi.commandHelp().describe("/tpme",
			"Open the teleport menu: home, spawn, your last death, and places you have saved.");
		PandoricalApi.commandHelp().describe("/tpme newplace",
			"Save where you are standing as a place you can come back to.");
		PandoricalApi.commandHelp().describe("/tpme ask <player>",
			"Ask somebody to let you teleport to them.");
		PandoricalApi.commandHelp().describe("/tpme bring <player>",
			"Ask somebody to teleport to you. They decide, the same as you would.");

		// And a pearl on the inventory's header row, left of Chest Utils' sort: a controller has
		// no button for a pooled key past the fourth, and every screen has the inventory.
		Identifier inventory = Identifier.fromNamespaceAndPath(MOD_ID, "menu");
		PandoricalApi.playerInventory().registerButton(inventory, "open", 142, 4, 12, MOD_ID + ":icon_pearl");
		PandoricalApi.playerInventory().onButton(inventory, "open", TpMenu::openFor);

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
			TpCommands.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(Requests::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> TpMenu.refreshAll(server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Requests.forget(handler.getPlayer().level().getServer(), handler.getPlayer().getUUID());
			TpMenu.forget(handler.getPlayer().getUUID());
			// A frame later, so the list no longer has the player who is leaving.
			server.execute(() -> TpMenu.refreshAll(server));
		});

		TpConfig.menu();
		LOGGER.info("TP Me Plz loaded");
	}
}
