package dev.jacedeb.jacefriends.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Normal Minecraft permissions in worlds opened to LAN: with "Allow Commands" on, vanilla
 * gives every player who joins commands (and ignores the ops list for them). Here only the
 * host and players on the ops list (/op, or the Admin group) get commands, like on a server.
 */
//? if >=26.3 {
@Mixin(net.minecraft.client.server.IntegratedServer.class)
public abstract class LanCommandsMixin {
	@Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
	private void jacefriends$onlyOps(net.minecraft.server.players.NameAndId who,
									 CallbackInfoReturnable<net.minecraft.server.permissions.LevelBasedPermissionSet> cir) {
		net.minecraft.client.server.IntegratedServer self = (net.minecraft.client.server.IntegratedServer) (Object) this;
		if (self.isSingleplayerOwner(who) || !self.getWorldData().isAllowCommands()) return;   // the host; or a world without commands (vanilla is fine)
		net.minecraft.server.players.ServerOpListEntry op = self.getPlayerList().getOps().get(who);
		cir.setReturnValue(op != null ? op.permissions() : net.minecraft.server.permissions.LevelBasedPermissionSet.ALL);
	}
}
//?} else {
/*@Mixin(net.minecraft.server.players.PlayerList.class)
public abstract class LanCommandsMixin {
	@org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.server.MinecraftServer server;

	@org.spongepowered.asm.mixin.Shadow public abstract net.minecraft.server.players.ServerOpList getOps();

	//? if >=1.20.5 {
	@org.spongepowered.asm.mixin.Shadow public abstract boolean isAllowCommandsForAllPlayers();
	//?} else
	/^@org.spongepowered.asm.mixin.Shadow public abstract boolean isAllowCheatsForAllPlayers();^/

	@Inject(method = "isOp", at = @At("HEAD"), cancellable = true)
	//? if >=1.21.9 {
	private void jacefriends$onlyOps(net.minecraft.server.players.NameAndId who, CallbackInfoReturnable<Boolean> cir) {
	//?} else
	/^private void jacefriends$onlyOps(com.mojang.authlib.GameProfile who, CallbackInfoReturnable<Boolean> cir) {^/
		//? if >=1.20.5 {
		boolean everyone = isAllowCommandsForAllPlayers();
		//?} else
		/^boolean everyone = isAllowCheatsForAllPlayers();^/
		// "Allow Commands" when opening to LAN: commands for the host (as vanilla), and for
		// guests only if they're on the ops list
		if (everyone && !server.isSingleplayerOwner(who) && getOps().get(who) == null) cir.setReturnValue(false);
	}
}
*///?}
