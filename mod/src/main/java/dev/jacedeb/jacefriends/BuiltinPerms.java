package dev.jacedeb.jacefriends;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Jace Social's own permission groups, for when LuckPerms can't run. LuckPerms' Fabric
 * build only runs on dedicated servers, so in singleplayer and in worlds you host it is
 * skipped even when it's in the mods folder. Same groups and menu as with LuckPerms;
 * on Fabric other mods (Vanilla Permissions, WorldEdit...) see these through the permission
 * APIs (see FabricPermissions).
 *
 * Saved in config/jacefriends-permissions.json:
 *   {"groups": {"default": {"some.node": true}}, "users": {"<uuid>": {"group": "jace_builder", "nodes": {...}}}}
 */
final class BuiltinPerms {
	private static JsonObject data;

	private BuiltinPerms() {}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("jacefriends-permissions.json");
	}

	private static synchronized JsonObject data() {
		if (data == null) {
			try {
				data = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
			} catch (Exception e) {
				data = new JsonObject();
			}
			if (!data.has("groups")) data.add("groups", new JsonObject());
			if (!data.has("users")) data.add("users", new JsonObject());
			obj(groups(), "default");
		}
		return data;
	}

	private static synchronized void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), data().toString());
		} catch (Exception ignored) {
			// changes just won't persist this time
		}
	}

	private static JsonObject groups() { return data().getAsJsonObject("groups"); }
	private static JsonObject users() { return data().getAsJsonObject("users"); }

	private static JsonObject obj(JsonObject parent, String key) {
		if (!parent.has(key) || !parent.get(key).isJsonObject()) parent.add(key, new JsonObject());
		return parent.getAsJsonObject(key);
	}

	private static JsonObject user(UUID player) { return obj(users(), player.toString()); }

	static synchronized CompletableFuture<Void> ensureRoleGroups() {
		for (String g : Perms.ROLE_GROUPS) obj(groups(), g);
		save();
		return CompletableFuture.completedFuture(null);
	}

	static synchronized List<String> groups0() {
		List<String> out = new ArrayList<>(groups().keySet());
		out.sort((a, b) -> Perms.rank(a) != Perms.rank(b)
				? Perms.rank(a) - Perms.rank(b) : a.compareTo(b));
		return out;
	}

	static synchronized List<String[]> permissions(String group) {
		List<String[]> out = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : obj(groups(), group).entrySet()) {
			out.add(new String[]{e.getKey(), String.valueOf(e.getValue().getAsBoolean())});
		}
		out.sort((a, b) -> a[0].compareTo(b[0]));
		return out;
	}

	static synchronized CompletableFuture<Void> setGroupPermission(String group, String node, Boolean value) {
		JsonObject g = obj(groups(), group);
		if (value == null) g.remove(node);
		else g.addProperty(node, value);
		save();
		return CompletableFuture.completedFuture(null);
	}

	static synchronized CompletableFuture<Void> setUserPermission(UUID player, String node, Boolean value) {
		JsonObject nodes = obj(user(player), "nodes");
		if (value == null) nodes.remove(node);
		else nodes.addProperty(node, value);
		save();
		return CompletableFuture.completedFuture(null);
	}

	static CompletableFuture<Boolean> userHas(UUID player, String node) {
		return CompletableFuture.completedFuture(Boolean.TRUE.equals(check(player, node)));
	}

	static synchronized String group(UUID player) {
		JsonObject u = users().getAsJsonObject(player.toString());
		return u != null && u.has("group") ? u.get("group").getAsString() : "default";
	}

	static CompletableFuture<String> userGroup(UUID player) {
		return CompletableFuture.completedFuture(group(player));
	}

	static synchronized CompletableFuture<Void> setUserGroup(UUID player, String group) {
		obj(groups(), group);
		user(player).addProperty("group", group);
		save();
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * Does this player have the node? The player's own permissions win over their group's,
	 * which win over "default"; within each, the most specific match wins
	 * (a.b.c, then a.b.*, then a.*, then *). Null when nothing says either way.
	 */
	static synchronized Boolean check(UUID player, String node) {
		JsonObject u = users().getAsJsonObject(player.toString());
		JsonObject own = u != null && u.has("nodes") ? u.getAsJsonObject("nodes") : null;
		String group = u != null && u.has("group") ? u.get("group").getAsString() : "default";
		for (JsonObject set : new JsonObject[]{own, groups().getAsJsonObject(group), groups().getAsJsonObject("default")}) {
			Boolean v = match(set, node);
			if (v != null) return v;
		}
		return null;
	}

	private static Boolean match(JsonObject set, String node) {
		if (set == null) return null;
		if (set.has(node)) return set.get(node).getAsBoolean();
		String n = node;
		for (int dot = n.lastIndexOf('.'); dot > 0; dot = n.lastIndexOf('.')) {
			n = n.substring(0, dot);
			if (set.has(n + ".*")) return set.get(n + ".*").getAsBoolean();
		}
		return set.has("*") ? set.get("*").getAsBoolean() : null;
	}
}
