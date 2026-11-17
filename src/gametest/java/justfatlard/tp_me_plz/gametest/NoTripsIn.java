package justfatlard.tp_me_plz.gametest;

import justfatlard.tp_me_plz.Trips;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;

/**
 * No trip lands in Minicraft's workshop: its way in is a bench, which lends a builder the bench's
 * materials, and a trip would walk past it. A trip anywhere else still goes.
 */
public final class NoTripsIn implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			TestServerConnection connection = world.getConnection();
			connection.waitForChunksRender();
			boolean workshopThere = server.computeOnServer(s -> {
				ServerLevel workshop = s.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("minicraft-justfatlard", "workshop")));
				if (workshop == null) return false;
				Trips.go(connection.getServerPlayer(), workshop, 0.5, 100, 0.5, 0F, 0F, "In");
				return true;
			});
			check(workshopThere, "no workshop to try: Minicraft is not loaded in the tests");
			context.waitTicks(5);
			check(!server.computeOnServer(s -> connection.getServerPlayer().level().dimension().identifier().getNamespace().equals("minicraft-justfatlard")),
				"a trip landed in the workshop");

			double before = server.computeOnServer(s -> connection.getServerPlayer().getX());
			server.runOnServer(s -> Trips.go(connection.getServerPlayer(), s.overworld(), before + 20, 100, 0.5, 0F, 0F, "There"));
			context.waitTicks(5);
			check(server.computeOnServer(s -> Math.abs(connection.getServerPlayer().getX() - before - 20) < 4), "an ordinary trip did not go");
			System.out.println("[tp-me-plz-test] no trips in passed");
		}
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
