package justfatlard.tp_me_plz.integration;

import justfatlard.amethyst_door.AmethystDoorBlock;
import net.minecraft.server.level.ServerPlayer;

/**
 * Into the player's geode, as through an amethyst door where they stand. Names amethyst-door's
 * types, so it is only ever reached behind the isModLoaded guard in TpMenu.
 */
public final class GeodeTrip {
	private GeodeTrip() {}

	public static void go(ServerPlayer player) {
		AmethystDoorBlock.visit(player);
	}
}
