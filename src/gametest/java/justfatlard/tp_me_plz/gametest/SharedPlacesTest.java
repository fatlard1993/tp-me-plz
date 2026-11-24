package justfatlard.tp_me_plz.gametest;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import justfatlard.tp_me_plz.Places;
import justfatlard.tp_me_plz.SharedPlaces;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * A place is private until its maker shares it; then everyone can go there, only its maker and ops
 * can change it, and one an op makes private while its maker is away goes back to them when they come.
 */
public final class SharedPlacesTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			world.getConnection().waitForChunksRender();
			server.runOnServer(s -> {
				FakePlayer maker = FakePlayer.get(s.overworld(), new GameProfile(UUID.randomUUID(), "Maker"));
				FakePlayer stranger = FakePlayer.get(s.overworld(), new GameProfile(UUID.randomUUID(), "Stranger"));
				Places.add(maker, new Places.Spot(s.overworld().dimension(), 0.5, 100, 0.5, 0F, 0F), "minecraft:oak_door", "Base");
				check(Places.mine(maker).size() == 1 && !Places.mine(maker).get(0).shared(), "a new place is private");
				check(Places.sharedByOthers(stranger).isEmpty(), "a private place is nobody else's to see");

				Places.toggleShared(maker, 0);
				check(Places.mine(maker).size() == 1 && Places.mine(maker).get(0).shared(), "shared, it is still the maker's own");
				SharedPlaces.Shared shared = Places.sharedNamed(stranger, "Base");
				check(shared != null && shared.ownerName().equals("Maker"), "shared, everyone else can find it by name");
				check(SharedPlaces.listed(s).contains(shared), "shared, it is listed for the map");

				check(!Places.mayChange(stranger, shared), "a stranger may not change it");
				Places.changeShared(stranger, shared, true);
				check(SharedPlaces.listed(s).contains(shared), "a stranger's delete does nothing");

				Places.toggleShared(maker, 0);
				check(Places.sharedByOthers(stranger).isEmpty() && !Places.mine(maker).get(0).shared(), "its maker can make it private again");

				var host = world.getConnection().getServerPlayer();
				if (host.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
					Places.toggleShared(maker, 0);
					SharedPlaces.Shared again = Places.sharedNamed(stranger, "Base");
					check(Places.mayChange(host, again), "an op may change anyone's shared place");
					// The maker is not on the server's list, so the place waits for them, shown to nobody
					Places.changeShared(host, again, false);
					check(SharedPlaces.listed(s).isEmpty(), "made private by an op, it is shown to nobody");
					check(Places.mine(maker).size() == 1 && !Places.mine(maker).get(0).shared(), "and it is the maker's private place when they next look");
					Places.toggleShared(maker, 0);
					Places.changeShared(host, Places.sharedNamed(stranger, "Base"), true);
					check(Places.mine(maker).isEmpty(), "an op can delete it");
				} else {
					System.out.println("[tp-me-plz-test] the host is not an op here, so the op half is not tried");
				}
			});
			System.out.println("[tp-me-plz-test] shared places passed");
		}
	}

	private static void check(boolean holds, String otherwise) {
		if (!holds) throw new AssertionError(otherwise);
	}
}
