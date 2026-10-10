package dev.jacedeb.jacefriends;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Permission groups for worlds you host: LuckPerms when it's running, otherwise Jace
 * Social's built-in ones (see BuiltinPerms). LuckPerms' Fabric build only runs on
 * dedicated servers, so in singleplayer it's skipped even when it's installed.
 * Only this class decides which, so LuckPermsBridge (and LuckPerms' classes) are never
 * loaded without LuckPerms.
 */
final class Perms {
	/** Groups Jace Social's roles map to (see Roles). */
	static final String[] ROLE_GROUPS = {"jace_visitor", "jace_builder", "jace_admin"};

	private Perms() {}

	static boolean luckPerms() {
		return Compat.isModLoaded("luckperms");
	}

	/** Is the LuckPerms jar in the mods folder even though it isn't running? */
	static boolean luckPermsSkipped() {
		if (luckPerms()) return false;
		try (var files = java.nio.file.Files.list(net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("mods"))) {
			return files.anyMatch(p -> p.getFileName().toString().toLowerCase().startsWith("luckperms"));
		} catch (Exception e) {
			return false;
		}
	}

	/** Other mods see the built-in permissions: FabricPermissions on Fabric, ForgePermissions on NeoForge / Forge. */
	static boolean builtinReachesMods() {
		return true;
	}

	/** Permission ideas for the menu, for the permission mods that are installed. */
	static List<String> suggestions() {
		List<String> out = new java.util.ArrayList<>();
		if (Compat.isModLoaded("vanilla-permissions")) {
			for (String c : new String[]{"gamemode", "tp", "give", "time", "weather", "effect", "kill", "clear", "summon", "fill", "setblock", "difficulty", "gamerule", "*"})
				out.add("minecraft.command." + c);
		}
		if (Compat.isModLoaded("worldedit")) out.add("worldedit.*");
		if (Compat.isModLoaded("luckperms")) out.add("luckperms.*");
		return out;
	}

	static int rank(String g) {
		if (g.equals("default")) return 0;
		for (int i = 0; i < ROLE_GROUPS.length; i++) if (g.equals(ROLE_GROUPS[i])) return i + 1;
		return 10;
	}

	static CompletableFuture<Void> ensureRoleGroups() {
		return luckPerms() ? LuckPermsBridge.ensureRoleGroups() : BuiltinPerms.ensureRoleGroups();
	}

	static List<String> groups() {
		return luckPerms() ? LuckPermsBridge.groups() : BuiltinPerms.groups0();
	}

	static List<String[]> permissions(String group) {
		return luckPerms() ? LuckPermsBridge.permissions(group) : BuiltinPerms.permissions(group);
	}

	static CompletableFuture<Void> setGroupPermission(String group, String node, Boolean value) {
		return luckPerms() ? LuckPermsBridge.setGroupPermission(group, node, value) : BuiltinPerms.setGroupPermission(group, node, value);
	}

	static CompletableFuture<Void> setUserPermission(UUID player, String node, Boolean value) {
		return luckPerms() ? LuckPermsBridge.setUserPermission(player, node, value) : BuiltinPerms.setUserPermission(player, node, value);
	}

	static CompletableFuture<Boolean> userHas(UUID player, String node) {
		return luckPerms() ? LuckPermsBridge.userHas(player, node) : BuiltinPerms.userHas(player, node);
	}

	static CompletableFuture<String> userGroup(UUID player) {
		return luckPerms() ? LuckPermsBridge.userGroup(player) : BuiltinPerms.userGroup(player);
	}

	static CompletableFuture<Void> setUserGroup(UUID player, String group) {
		return luckPerms() ? LuckPermsBridge.setUserGroup(player, group) : BuiltinPerms.setUserGroup(player, group);
	}
}
