package justfatlard.tp_me_plz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Asking to go to another player. The one asked says yes or no, in chat or in their own menu,
 * within a minute or so; yes brings the asker to wherever the one asked is standing then.
 *
 * <p>One ask at a time from each player: asking somebody else takes back the first.
 */
public final class Requests {
	private Requests() {}

	record Ask(UUID asker, UUID host, long until) {}

	/** By asker. */
	private static final Map<UUID, Ask> asks = new ConcurrentHashMap<>();

	public static void ask(ServerPlayer asker, ServerPlayer host) {
		if (asker == host) return;
		String who = asker.getGameProfile().name();
		String them = host.getGameProfile().name();
		long until = asker.level().getGameTime() + 20L * TpConfig.requestSeconds();
		Ask previous = asks.put(asker.getUUID(), new Ask(asker.getUUID(), host.getUUID(), until));
		if (previous != null && !previous.host().equals(host.getUUID())) refreshHost(asker.level().getServer(), previous.host());

		Trips.say(asker, "Asked " + them + " to let you come to them");

		Component accept = Component.literal("[Accept]").withStyle(style -> style
			.withColor(ChatFormatting.GREEN).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand("/tpme accept " + who))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Bring " + who + " to you"))));
		Component deny = Component.literal("[Deny]").withStyle(style -> style
			.withColor(ChatFormatting.RED).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand("/tpme deny " + who))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Leave " + who + " where they are"))));
		host.sendSystemMessage(Component.translatableWithFallback("tp-me-plz.ask", "%s asks to teleport to you. ", who)
			.withStyle(ChatFormatting.LIGHT_PURPLE)
			.append(accept).append(Component.literal("  ")).append(deny));
		TpMenu.refresh(host);
	}

	/** Whether this player is waiting on an answer from that one. */
	public static boolean asking(ServerPlayer asker, UUID host) {
		Ask ask = asks.get(asker.getUUID());
		return ask != null && ask.host().equals(host);
	}

	/** The players asking to come to this one, oldest first. */
	public static List<ServerPlayer> askingOf(ServerPlayer host) {
		List<ServerPlayer> askers = new ArrayList<>();
		asks.values().stream()
			.filter(ask -> ask.host().equals(host.getUUID()))
			.sorted(java.util.Comparator.comparingLong(Ask::until))
			.forEach(ask -> {
				ServerPlayer asker = host.level().getServer().getPlayerList().getPlayer(ask.asker());
				if (asker != null) askers.add(asker);
			});
		return askers;
	}

	public static void accept(ServerPlayer host, ServerPlayer asker) {
		Ask ask = asks.get(asker.getUUID());
		if (ask == null || !ask.host().equals(host.getUUID())) {
			Trips.say(host, asker.getGameProfile().name() + " is not asking to come to you");
			return;
		}
		asks.remove(asker.getUUID());
		Trips.go(asker, host.level(), host.getX(), host.getY(), host.getZ(), host.getYRot(), asker.getXRot(),
			"With " + host.getGameProfile().name());
		Trips.say(host, asker.getGameProfile().name() + " is here");
		TpMenu.refresh(host);
		TpMenu.refresh(asker);
	}

	public static void deny(ServerPlayer host, ServerPlayer asker) {
		Ask ask = asks.get(asker.getUUID());
		if (ask == null || !ask.host().equals(host.getUUID())) return;
		asks.remove(asker.getUUID());
		Trips.say(asker, host.getGameProfile().name() + " said no");
		Trips.say(host, "Told " + asker.getGameProfile().name() + " no");
		TpMenu.refresh(host);
		TpMenu.refresh(asker);
	}

	/** Once a tick: asks past their minute lapse, and both ends hear so. */
	public static void tick(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		for (Ask ask : List.copyOf(asks.values())) {
			if (now < ask.until()) continue;
			asks.remove(ask.asker(), ask);
			ServerPlayer asker = server.getPlayerList().getPlayer(ask.asker());
			ServerPlayer host = server.getPlayerList().getPlayer(ask.host());
			if (asker != null) {
				Trips.say(asker, "Your request" + (host != null ? " to " + host.getGameProfile().name() : "") + " lapsed");
				TpMenu.refresh(asker);
			}
			if (host != null) TpMenu.refresh(host);
		}
	}

	/** A player who left: nothing they asked stands, and nobody waits on them to answer. */
	public static void forget(UUID player) {
		asks.remove(player);
		asks.values().removeIf(ask -> ask.host().equals(player));
	}

	private static void refreshHost(MinecraftServer server, UUID host) {
		ServerPlayer player = server.getPlayerList().getPlayer(host);
		if (player != null) TpMenu.refresh(player);
	}
}
