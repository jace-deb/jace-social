package dev.jacedeb.jacefriends;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * NeoForge's and Forge's permission API (mods ask PermissionAPI.getPermission for nodes like
 * "somemod.command.home"): the answers come from the server's chosen handler, by default "op
 * level or not". When a world starts, we wrap that handler so Jace Social's built-in groups
 * (see BuiltinPerms) answer first; nodes the groups don't mention go to the original handler.
 * Not while LuckPerms runs (it's the handler then). Done by name, because the API's types
 * differ between versions (and between NeoForge and Forge).
 */
final class ForgePermissions {
	private ForgePermissions() {}

	/** At game start: can we reach the handler (and its type)? Logged, so problems show before anyone hosts. */
	static void selfCheck(String api) {
		try {
			ClassLoader cl = ForgePermissions.class.getClassLoader();
			Class.forName(api, true, cl).getDeclaredField("activeHandler").setAccessible(true);
			Class.forName(api.substring(0, api.lastIndexOf('.')) + ".handler.IPermissionHandler", true, cl);
			System.out.println("[Jace Social] PermissionAPI hook: ready");
		} catch (Throwable e) {
			System.out.println("[Jace Social] PermissionAPI hook: NOT AVAILABLE (" + e + ")");
		}
	}

	/** api: "net.neoforged.neoforge.server.permission.PermissionAPI" or the Forge one. Call when the server is starting. */
	static void wrap(String api) {
		if (Perms.luckPerms()) return;
		try {
			ClassLoader cl = ForgePermissions.class.getClassLoader();
			Class<?> apiClass = Class.forName(api, true, cl);
			Field active = apiClass.getDeclaredField("activeHandler");
			active.setAccessible(true);
			Object original = active.get(null);
			if (original == null || Proxy.isProxyClass(original.getClass())) return;
			Class<?> handlerType = Class.forName(api.substring(0, api.lastIndexOf('.')) + ".handler.IPermissionHandler", true, cl);
			Object wrapped = Proxy.newProxyInstance(cl, new Class<?>[]{handlerType}, (proxy, method, args) -> {
				Object result;
				try {
					result = method.invoke(original, args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
				String m = method.getName();
				if ((m.equals("getPermission") || m.equals("getOfflinePermission")) && result instanceof Boolean && args != null && args.length >= 2) {
					UUID who = args[0] instanceof net.minecraft.server.level.ServerPlayer p ? p.getUUID() : args[0] instanceof UUID u ? u : null;
					String node = (String) args[1].getClass().getMethod("getNodeName").invoke(args[1]);
					Boolean ours = who == null ? null : BuiltinPerms.check(who, node);
					if (ours != null) return ours;
				}
				return result;
			});
			active.set(null, wrapped);
			System.out.println("[Jace Social] Answering permission checks from mods (PermissionAPI) with Jace Social's groups");
		} catch (Throwable e) {
			System.out.println("[Jace Social] Couldn't hook the PermissionAPI: " + e);
		}
	}
}
