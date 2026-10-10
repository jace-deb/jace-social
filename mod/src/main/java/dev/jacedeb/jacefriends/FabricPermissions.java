package dev.jacedeb.jacefriends;

//? if fabric {
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Answers other mods' permission checks from Jace Social's built-in groups (see BuiltinPerms),
 * but only while LuckPerms isn't running: LuckPerms' Fabric build is skipped outside dedicated
 * servers. Two permission APIs exist on Fabric, and addons use either:
 *  - lucko's fabric-permissions-api (me.lucko...v0): most mods, e.g. Vanilla Permissions
 *    (minecraft.command.gamemode ...), WorldEdit, Essential Commands. Mods bundle it, so it's
 *    hooked up only when it's there, and by name, so any version of it works.
 *  - Fabric API's own permission API (Minecraft 26.1+).
 * "No answer" (nothing set in the groups) leaves the mod's own default, usually the op level.
 */
final class FabricPermissions {
	private FabricPermissions() {}

	/** "worldedit.navigation.jumpto" for this player: true / false / null (no opinion). */
	static Boolean check(UUID player, String node) {
		if (player == null || Perms.luckPerms()) return null;
		return BuiltinPerms.check(player, node);
	}

	static void register() {
		//? if >=26.1
		registerFabricApi();
		registerLucko();
	}

	//? if >=26.1 {
	private static void registerFabricApi() {
		net.fabricmc.fabric.api.permission.v1.PermissionEvents.ON_REQUEST.register(new net.fabricmc.fabric.api.permission.v1.PermissionEvents.OnRequest() {
			@Override
			public <T> T handlePermissionRequest(net.fabricmc.fabric.api.permission.v1.PermissionContext ctx,
												 net.fabricmc.fabric.api.permission.v1.PermissionNode<T> node) {
				if (ctx.type() != net.fabricmc.fabric.api.permission.v1.PermissionContext.Type.PLAYER) return null;
				// worldedit:navigation.jumpto -> worldedit.navigation.jumpto, like LuckPerms nodes
				String name = node.key().getNamespace() + "." + node.key().getPath().replace('/', '.');
				Boolean value = check(ctx.uuid(), name);
				return value == null ? null : node.cast(value);
			}
		});
	}
	//?}

	/** Mods that use lucko's API ask through two events: for a command source, and for an offline player. */
	private static void registerLucko() {
		if (!Compat.isModLoaded("fabric-permissions-api-v0")) return;
		try {
			ClassLoader cl = FabricPermissions.class.getClassLoader();
			hook(cl, "me.lucko.fabric.api.permissions.v0.PermissionCheckEvent", (source, node) -> {
				Boolean v = source instanceof net.minecraft.commands.CommandSourceStack s
						&& s.getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? check(p.getUUID(), node) : null;
				return triState(v);
			});
			hook(cl, "me.lucko.fabric.api.permissions.v0.OfflinePermissionCheckEvent",
					(uuid, node) -> CompletableFuture.completedFuture(triState(uuid instanceof UUID u ? check(u, node) : null)));
			System.out.println("[Jace Social] Answering permission checks from mods that use fabric-permissions-api");
		} catch (Throwable e) {
			System.out.println("[Jace Social] Couldn't hook fabric-permissions-api: " + e);
		}
	}

	private interface Answer {
		Object answer(Object who, String node);
	}

	/** EVENT.register(a callback implementing the event's one-method interface). */
	private static void hook(ClassLoader cl, String eventClass, Answer answer) throws Exception {
		Class<?> type = Class.forName(eventClass, true, cl);
		Object event = type.getField("EVENT").get(null);
		Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{type}, (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> "JaceSocialPermissions";
				};
			}
			return answer.answer(args[0], (String) args[1]);
		});
		event.getClass().getMethod("register", Object.class).invoke(event, callback);
	}

	private static net.fabricmc.fabric.api.util.TriState triState(Boolean v) {
		return v == null ? net.fabricmc.fabric.api.util.TriState.DEFAULT : net.fabricmc.fabric.api.util.TriState.of(v);
	}
}
//?}
