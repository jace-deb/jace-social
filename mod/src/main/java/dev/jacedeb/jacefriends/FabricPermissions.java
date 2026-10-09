package dev.jacedeb.jacefriends;

//? if fabric && >=26.1 {
import net.fabricmc.fabric.api.permission.v1.PermissionContext;
import net.fabricmc.fabric.api.permission.v1.PermissionEvents;
import net.fabricmc.fabric.api.permission.v1.PermissionNode;

/**
 * Answers other mods' permission checks (WorldEdit and anything else on Fabric's
 * permission API) from Jace Social's built-in groups, but only while LuckPerms isn't
 * running: LuckPerms' Fabric build is skipped outside dedicated servers. Returning null
 * means "no opinion", so the mod's own default (usually the op level) still applies.
 */
final class FabricPermissions {
	private FabricPermissions() {}

	static void register() {
		PermissionEvents.ON_REQUEST.register(new PermissionEvents.OnRequest() {
			@Override
			public <T> T handlePermissionRequest(PermissionContext ctx, PermissionNode<T> node) {
				if (Perms.luckPerms() || ctx.type() != PermissionContext.Type.PLAYER) return null;
				// worldedit:navigation.jumpto -> worldedit.navigation.jumpto, like LuckPerms nodes
				String name = node.key().getNamespace() + "." + node.key().getPath().replace('/', '.');
				Boolean value = BuiltinPerms.check(ctx.uuid(), name);
				return value == null ? null : node.cast(value);
			}
		});
	}
}
//?}
