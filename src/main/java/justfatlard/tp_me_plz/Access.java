package justfatlard.tp_me_plz;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Who may use the menu. Ops always; everyone when the server says so; otherwise whoever an op has
 * given it to, which is kept on the player and goes where they go, deaths included.
 */
public final class Access {
	private Access() {}

	public static final AttachmentType<Boolean> GRANTED = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.copyOnDeath()
		.buildAndRegister(Identifier.fromNamespaceAndPath(Main.MOD_ID, "granted"));

	/** Loads the class, which is what registers the attachment; it must happen at init. */
	static void init() {}

	public static boolean allowed(ServerPlayer player) {
		return TpConfig.everyone() || isOp(player) || granted(player);
	}

	public static boolean granted(ServerPlayer player) {
		return Boolean.TRUE.equals(player.getAttached(GRANTED));
	}

	public static void set(ServerPlayer player, boolean given) {
		if (given) player.setAttached(GRANTED, true);
		else player.removeAttached(GRANTED);
	}

	static boolean isOp(ServerPlayer player) {
		return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}
}
