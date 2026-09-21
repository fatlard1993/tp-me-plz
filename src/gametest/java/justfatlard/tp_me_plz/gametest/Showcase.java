package justfatlard.tp_me_plz.gametest;

import justfatlard.pandorical.api.PandoricalApi;
import justfatlard.tp_me_plz.TpMenu;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;

/**
 * The pictures for the readme and the mod page: the menu itself, which is the whole mod. A bed and
 * a few saved places first, so it has something in it worth looking at.
 *
 * <p>Two frames. {@code menu} is the mod as most people will meet it, a handful of places saved.
 * {@code menu-full} is every place the config allows, which is the one that catches the dialog
 * outgrowing the window: a layout that only ever photographs well half empty is not laid out.
 *
 * <p>Run it under xvfb-run; the frames land in build/run/clientGameTest/screenshots.
 */
public final class Showcase implements FabricClientGameTest {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			server.waitFor(s -> PandoricalApi.isAvailable(connection.getServerPlayer()));

			server.runCommand("gamerule doDaylightCycle false");
			server.runCommand("time set noon");
			server.runCommand("gamemode creative @a");
			server.runCommand("recipe give @a *");
			// The menu is for ops or for players given the run of it. An /op inside a test does not
			// reach the check in time, so the player is given the run of it the mod's own way, and
			// every tpme goes through "execute as" because a console command is not a player.
			server.runOnServer(s -> justfatlard.tp_me_plz.Access.set(connection.getServerPlayer(), true));

			BlockPos origin = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			int x = origin.getX();
			int y = origin.getY();
			int z = origin.getZ();

			// A bed to have a home to go to, and places saved from where the player stands, so the
			// menu has its own tiles in it rather than only the two the world gives everyone.
			server.runCommand("setblock %d %d %d minecraft:red_bed[facing=east,part=foot]".formatted(x + 2, y, z));
			server.runCommand("setblock %d %d %d minecraft:red_bed[facing=east,part=head]".formatted(x + 3, y, z));
			// A place is saved where you stand and pictured by what is in your hand, so each one
			// is a swap of the held item and a walk of a few blocks.
			String[][] places = {
				{"Mine", "minecraft:torch"}, {"Farm", "minecraft:wheat"},
				{"Dock", "minecraft:oak_boat"}, {"Bell", "minecraft:bell"},
			};
			for (int i = 0; i < places.length; i++) {
				server.runCommand("tp @a %d %d %d".formatted(x + 6 + i * 4, y, z));
				server.runCommand("item replace entity @a weapon.mainhand with " + places[i][1]);
				server.runCommand("execute as @a at @a run tpme newplace " + places[i][0]);
			}
			server.runCommand("tp @a %d %d %d".formatted(x, y, z));
			server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");

			// Long enough for the recipe and advancement toasts to clear the corner first.
			context.waitTicks(220);
			context.waitTicks(220);
			server.runOnServer(s -> TpMenu.openFor(connection.getServerPlayer()));
			context.waitTicks(60);
			shoot(context, "menu");

			// And again with the shelf as full as the config allows, which is where the old dialog
			// ran off the bottom of the screen. Saving them all from this one spot is fine: what
			// the picture is of is the tiles.
			String[] rest = {
				"minecraft:diamond", "minecraft:cake", "minecraft:oak_door", "minecraft:rail",
				"minecraft:emerald",
			};
			for (String picture : rest) {
				server.runCommand("item replace entity @a weapon.mainhand with " + picture);
				server.runCommand("execute as @a at @a run tpme newplace");
			}
			server.runCommand("item replace entity @a weapon.mainhand with minecraft:air");
			context.waitTicks(20);
			server.runOnServer(s -> TpMenu.openFor(connection.getServerPlayer()));
			context.waitTicks(60);
			shoot(context, "menu-full");
		}
	}
	private void shoot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name)
			.withSize(WIDTH, HEIGHT)
			.disableCounterPrefix());
	}
}
