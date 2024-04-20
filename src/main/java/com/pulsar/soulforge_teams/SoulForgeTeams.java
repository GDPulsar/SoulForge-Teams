package com.pulsar.soulforge_teams;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static net.minecraft.command.argument.EntityArgumentType.player;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class SoulForgeTeams implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("soulforge-teams");
	public static final Identifier TEAM_PACKET = new Identifier("soulforge-teams", "team_packet");
	public static final Identifier HEALTH_PACKET = new Identifier("soulforge-teams", "health_packet");
	public static List<Pair<Team, PlayerEntity>> INVITES = new ArrayList<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing SoulForge Teams");
		if (FabricLoader.getInstance().isDevelopmentEnvironment() && !FabricLoader.getInstance().isModLoaded("soulforge")) {
			LOGGER.info("you lucky bitch well go ahead and test shit");
		}
		if (!FabricLoader.getInstance().isModLoaded("soulforge") && !FabricLoader.getInstance().isDevelopmentEnvironment()) {
			LOGGER.info("SoulForge has not been added! This mod will do nothing. Please add SoulForge!");
		} else {
			CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
				registerCommands(dispatcher);
			});
		}

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeVarInt(server.getPlayerManager().getCurrentPlayerCount());
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				buf.writeUuid(player.getUuid());
				buf.writeFloat(player.getMaxHealth());
				buf.writeFloat(player.getHealth());
			}
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				ServerPlayNetworking.send(player, HEALTH_PACKET, buf);
			}
		});
	}

	public static Team getPlayerTeam(MinecraftServer server, PlayerEntity player) {
		SoulForgeTeamsData teamData = SoulForgeTeamsData.getServerState(server);
		for (Team team : teamData.TEAMS) {
			if (team.isTeamMember(player)) {
				return team;
			}
		}
		return null;
	}

	public static Team getTeamByName(MinecraftServer server, String teamName) {
		SoulForgeTeamsData teamData = SoulForgeTeamsData.getServerState(server);
		for (Team team : teamData.TEAMS) {
			if (team.options.teamName.equals(teamName)) {
				return team;
			}
		}
		return null;
	}

	public static void createNewTeam(MinecraftServer server, Team team) {
		SoulForgeTeamsData teamData = SoulForgeTeamsData.getServerState(server);
		teamData.TEAMS.add(team);
		syncTeams(server);
	}

	public static void addPlayerToTeam(MinecraftServer server, UUID teamId, PlayerEntity player) {
		SoulForgeTeamsData teamData = SoulForgeTeamsData.getServerState(server);
		for (Team team : teamData.TEAMS) {
			if (team.getID().compareTo(teamId) == 0) {
				Team.TeamChanges changes = team.addMember(player);
				if (changes.changeFailed) player.sendMessage(changes.toText());
				else team.broadcastChanges(server, changes);
				syncTeams(server);
				return;
			}
		}
	}

	public static void addFakePlayerToTeam(MinecraftServer server, UUID teamId) {
		SoulForgeTeamsData teamData = SoulForgeTeamsData.getServerState(server);
		for (Team team : teamData.TEAMS) {
			if (team.getID().compareTo(teamId) == 0) {
				team.addFakeMember();
				syncTeams(server);
				return;
			}
		}
	}

	public static boolean isInTeam(MinecraftServer server, PlayerEntity player) {
		return getPlayerTeam(server, player) != null;
	}

	public static void syncTeams(MinecraftServer server) {
		SoulForgeTeamsData teamData = SoulForgeTeamsData.getServerState(server);
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(teamData.TEAMS.size());
		for (Team team : teamData.TEAMS) {
			buf = team.writeBuf(buf);
		}
		PacketByteBuf finalBuf = buf;
		server.execute(() -> {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				ServerPlayNetworking.send(player, TEAM_PACKET, finalBuf);
			}
		});
	}

	public static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(
				literal("sfteams")
						.requires(ServerCommandSource::isExecutedByPlayer)
						.then(literal("create")
								.then(argument("name", greedyString())
										.executes(context -> {
											ServerPlayerEntity player = context.getSource().getPlayer();
											MinecraftServer server = context.getSource().getServer();
											if (player != null && server != null) {
												if (isInTeam(server, player)) {
													player.sendMessage(Text.translatable("soulforge-teams.already_in_team"));
                                                } else {
													Team team = new Team(player);
													team.options.teamName = StringArgumentType.getString(context, "name");
													createNewTeam(server, team);
													player.sendMessage(Text.translatable("soulforge-teams.team_created"));
                                                }
                                            }
											return 1;
										})
								)
						)
						.then(literal("invite")
								.then(argument("player", player())
										.executes(context -> {
											ServerPlayerEntity player = context.getSource().getPlayer();
											ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
											MinecraftServer server = context.getSource().getServer();
											if (player != null && server != null) {
												if (isInTeam(server, player)) {
													Team team = getPlayerTeam(server, player);
													if (team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank != Team.Rank.LEADER && rank != Team.Rank.OWNER) {
															targetPlayer.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
															return 1;
														}
														INVITES.add(new Pair<>(team, targetPlayer));
														targetPlayer.sendMessage(Text.translatable("soulforge-teams.received_invite").append(team.options.teamName));
														player.sendMessage(Text.translatable("soulforge-teams.sent_invite").append(team.options.teamName));
													}
												} else {
													player.sendMessage(Text.translatable("soulforge-teams.not_in_team"));
												}
											}
											return 1;
										})
								)
						)
						.then(literal("accept")
								.then(argument("team", string())
										.executes(context -> {
											ServerPlayerEntity player = context.getSource().getPlayer();
											MinecraftServer server = context.getSource().getServer();
											String teamName = StringArgumentType.getString(context, "team");
											Team team = getTeamByName(server, teamName);
											if (player != null && server != null && team != null) {
												for (Pair<Team, PlayerEntity> invite : INVITES) {
													if (player.getUuid().compareTo(invite.getRight().getUuid()) == 0) {
														if (Objects.equals(invite.getLeft().options.teamName, team.options.teamName)) {
															addPlayerToTeam(server, invite.getLeft().getID(), player);
															return 1;
														}
													}
												}
											}
											if (player != null) player.sendMessage(Text.translatable("soulforge-teams.no_invites").append(teamName));
											return 1;
										})
								)
						)
						.then(literal("info")
								.executes(context -> {
									ServerPlayerEntity player = context.getSource().getPlayer();
									MinecraftServer server = context.getSource().getServer();
									if (player != null && server != null) {
										Team team = getPlayerTeam(server, player);
										if (team != null) {
											team.printInfoText(player);
										} else {
											player.sendMessage(Text.translatable("soulforge-teams.not_in_team"));
										}
									}
									return 1;
								})
						)
						.then(literal("promote")
								.then(argument("player", player())
										.executes(context -> {
											ServerPlayerEntity player = context.getSource().getPlayer();
											ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
											MinecraftServer server = context.getSource().getServer();
											if (player != null && targetPlayer != null && server != null) {
												if (isInTeam(server, player) && isInTeam(server, targetPlayer)) {
													Team team = getPlayerTeam(server, player);
													if (team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank != Team.Rank.OWNER) {
															targetPlayer.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
															return 1;
														}
														Team targetTeam = getPlayerTeam(server, targetPlayer);
														if (targetTeam != null) {
															if (targetTeam.equals(team)) {
																Team.TeamChanges changes = team.promoteMember(targetPlayer);
																if (changes.changeFailed) player.sendMessage(changes.toText());
																else team.broadcastChanges(server, changes);
															} else {
																player.sendMessage(Text.translatable("soulforge-teams.wrong_team"));
															}
														}
													}
												} else {
													player.sendMessage(Text.translatable("soulforge-teams.not_in_team"));
												}
											}
											return 1;
										})
								)
						)
						.then(literal("demote")
								.then(argument("player", player())
										.executes(context -> {
											ServerPlayerEntity player = context.getSource().getPlayer();
											ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
											MinecraftServer server = context.getSource().getServer();
											if (player != null && targetPlayer != null && server != null) {
												if (isInTeam(server, player) && isInTeam(server, targetPlayer)) {
													Team team = getPlayerTeam(server, player);
													if (team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank != Team.Rank.OWNER) {
															targetPlayer.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
															return 1;
														}
														Team targetTeam = getPlayerTeam(server, targetPlayer);
														if (targetTeam != null) {
															if (targetTeam.equals(team)) {
																Team.TeamChanges changes = team.demoteMember(targetPlayer);
																if (changes.changeFailed) player.sendMessage(changes.toText());
																else team.broadcastChanges(server, changes);
															} else {
																player.sendMessage(Text.translatable("soulforge-teams.wrong_team"));
															}
														}
													}
												} else {
													player.sendMessage(Text.translatable("soulforge-teams.not_in_team"));
												}
											}
											return 1;
										})
								)
						)
						.then(literal("kick")
								.then(argument("player", player())
										.executes(context -> {
											ServerPlayerEntity player = context.getSource().getPlayer();
											ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
											MinecraftServer server = context.getSource().getServer();
											if (player != null && targetPlayer != null && server != null) {
												if (isInTeam(server, player) && isInTeam(server, targetPlayer)) {
													Team team = getPlayerTeam(server, player);
													if (team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank != Team.Rank.LEADER && rank != Team.Rank.OWNER) {
															player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
															return 1;
														}
														Team targetTeam = getPlayerTeam(server, targetPlayer);
														if (targetTeam != null) {
															if (targetTeam.equals(team)) {
																Team.Rank targetRank = team.members.get(targetPlayer.getUuid());
																if (targetRank.getIndex() >= rank.getIndex()) {
																	player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
																	return 1;
																}
																Team.TeamChanges changes = team.kickMember(targetPlayer);
																if (changes.changeFailed) player.sendMessage(changes.toText());
																else team.broadcastChanges(server, changes);
															} else {
																player.sendMessage(Text.translatable("soulforge-teams.wrong_team"));
															}
														}
													}
												} else {
													player.sendMessage(Text.translatable("soulforge-teams.not_in_team"));
												}
											}
											return 1;
										})
								)
						)
						.then(literal("declare")
								.then(argument("team", string())
										.then(argument("declaration", word())
												.suggests(new RelationSuggestionProvider())
												.executes(context -> {
													ServerPlayerEntity player = context.getSource().getPlayer();
													MinecraftServer server = context.getSource().getServer();
													String teamName = StringArgumentType.getString(context, "team");
													Team team = getPlayerTeam(server, player);
													Team targetTeam = getTeamByName(server, teamName);
													Team.Relation relation = switch (StringArgumentType.getString(context, "declaration")) {
														case "Ally" -> Team.Relation.ALLY;
														case "Enemy" -> Team.Relation.ENEMY;
                                                        default -> Team.Relation.NEUTRAL;
                                                    };
													if (player != null && server != null && team != null && targetTeam != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank == Team.Rank.LEADER || rank == Team.Rank.OWNER) {
															Team.TeamChanges changes = team.declare(targetTeam, relation);
															if (changes.changeFailed) player.sendMessage(changes.toText());
															else team.broadcastChanges(server, changes);
														} else {
															player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
														}
													}
													return 1;
												})
										)
								)
						)
						.then(literal("options")
								.then(literal("allowTeamPvP")
										.then(argument("enabled", bool())
												.executes(context -> {
													ServerPlayerEntity player = context.getSource().getPlayer();
													MinecraftServer server = context.getSource().getServer();
													boolean enabled = getBool(context, "enabled");
													Team team = getPlayerTeam(server, player);
													if (player != null && server != null && team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank == Team.Rank.LEADER || rank == Team.Rank.OWNER) {
															team.options.allowTeamPvP = enabled;
															player.sendMessage(Text.translatable("soulforge-teams.successfully_changed_options"));
														} else {
															player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
														}
													}
													return 1;
												})
										)
								)
								.then(literal("allowOffensiveAllyTargeting")
										.then(argument("enabled", bool())
												.executes(context -> {
													ServerPlayerEntity player = context.getSource().getPlayer();
													MinecraftServer server = context.getSource().getServer();
													boolean enabled = getBool(context, "enabled");
													Team team = getPlayerTeam(server, player);
													if (player != null && server != null && team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank == Team.Rank.LEADER || rank == Team.Rank.OWNER) {
															team.options.allowOffensiveAllyTargeting = enabled;
															player.sendMessage(Text.translatable("soulforge-teams.succesfully_changed_options"));
														} else {
															player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
														}
													}
													return 1;
												})
										)
								)
								.then(literal("allowDefensiveEnemyTargeting")
										.then(argument("enabled", bool())
												.executes(context -> {
													ServerPlayerEntity player = context.getSource().getPlayer();
													MinecraftServer server = context.getSource().getServer();
													boolean enabled = getBool(context, "enabled");
													Team team = getPlayerTeam(server, player);
													if (player != null && server != null && team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank == Team.Rank.LEADER || rank == Team.Rank.OWNER) {
															team.options.allowDefensiveEnemyTargeting = enabled;
															player.sendMessage(Text.translatable("soulforge-teams.succesfully_changed_options"));
														} else {
															player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
														}
													}
													return 1;
												})
										)
								)
								.then(literal("teamDescription")
										.then(argument("description", greedyString())
												.executes(context -> {
													ServerPlayerEntity player = context.getSource().getPlayer();
													MinecraftServer server = context.getSource().getServer();
													String description = getString(context, "description");
													Team team = getPlayerTeam(server, player);
													if (player != null && server != null && team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank == Team.Rank.LEADER || rank == Team.Rank.OWNER) {
															team.options.teamDescription = description;
															player.sendMessage(Text.translatable("soulforge-teams.succesfully_changed_options"));
														} else {
															player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
														}
													}
													return 1;
												})
										)
								)
								.then(literal("teamName")
										.then(argument("name", greedyString())
												.executes(context -> {
													ServerPlayerEntity player = context.getSource().getPlayer();
													MinecraftServer server = context.getSource().getServer();
													String name = getString(context, "name");
													Team team = getPlayerTeam(server, player);
													if (player != null && server != null && team != null) {
														Team.Rank rank = team.members.get(player.getUuid());
														if (rank == Team.Rank.OWNER) {
															team.options.teamName = name;
															player.sendMessage(Text.translatable("soulforge-teams.succesfully_changed_options"));
														} else {
															player.sendMessage(Text.translatable("soulforge-teams.not_high_enough_rank"));
														}
													}
													return 1;
												})
										)
								)
						)
		);
	}

	public static class RelationSuggestionProvider implements SuggestionProvider<ServerCommandSource> {
		@Override
		public CompletableFuture<Suggestions> getSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) throws CommandSyntaxException {
			if (CommandSource.shouldSuggest(builder.getRemaining(), "Ally")) builder.suggest("Ally");
			if (CommandSource.shouldSuggest(builder.getRemaining(), "Neutral")) builder.suggest("Neutral");
			if (CommandSource.shouldSuggest(builder.getRemaining(), "Enemy")) builder.suggest("Enemy");
			return builder.buildFuture();
		}
	}

	public static class Team {
		private final UUID id;
		private UUID ownerUUID;
		private HashMap<UUID, Rank> members;
		private HashMap<UUID, String> memberNames;
		private Options options;
		private HashMap<UUID, Relation> relations;

		public Team(PlayerEntity owner) {
			this.id = UUID.randomUUID();
			this.ownerUUID = owner.getUuid();
			this.members = new HashMap<>();
			this.members.put(owner.getUuid(), Rank.OWNER);
			this.memberNames = new HashMap<>();
			this.memberNames.put(owner.getUuid(), owner.getName().getString());
			this.options = new Options();
			this.relations = new HashMap<>();
		}

		public Team(PacketByteBuf buf) {
			this.id = buf.readUuid();
			this.ownerUUID = buf.readUuid();
			this.members = new HashMap<>();
			this.memberNames = new HashMap<>();
			int memberSize = buf.readVarInt();
			for (int i = 0; i < memberSize; i++) {
				UUID uuid = buf.readUuid();
				this.members.put(uuid, Rank.fromIndex(buf.readVarInt()));
				this.memberNames.put(uuid, buf.readString());
			}
			this.options = new Options(buf);
			this.relations = new HashMap<>();
			int relationSize = buf.readVarInt();
			for (int i = 0; i < relationSize; i++) {
				this.relations.put(buf.readUuid(), Relation.fromIndex(buf.readVarInt()));
			}
		}

		public Team(NbtCompound nbt) {
			this.id = nbt.getUuid("id");
			this.ownerUUID = nbt.getUuid("ownerUUID");
			this.members = new HashMap<>();
			this.memberNames = new HashMap<>();
			NbtList members = nbt.getList("members", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < members.size(); i++) {
				this.members.put(members.getCompound(i).getUuid("memberUUID"), Rank.fromIndex(members.getCompound(i).getInt("memberRank")));
				this.memberNames.put(members.getCompound(i).getUuid("memberUUID"), members.getCompound(i).getString("memberName"));
			}
			this.options = new Options(nbt.getCompound("options"));
			this.relations = new HashMap<>();
			NbtList relations = nbt.getList("relations", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < relations.size(); i++) {
				this.relations.put(relations.getCompound(i).getUuid("teamUUID"), Relation.fromIndex(relations.getCompound(i).getInt("relationIndex")));
			}
		}

		public PacketByteBuf writeBuf(PacketByteBuf buf) {
			buf.writeUuid(id);
			buf.writeUuid(ownerUUID);
			buf.writeVarInt(members.size());
			for (Map.Entry<UUID, Rank> member : members.entrySet()) {
				buf.writeUuid(member.getKey());
				buf.writeVarInt(member.getValue().getIndex());
				buf.writeString(memberNames.get(member.getKey()));
			}
			buf = options.writeBuf(buf);
			buf.writeVarInt(relations.size());
			for (Map.Entry<UUID, Relation> relation : relations.entrySet()) {
				buf.writeUuid(relation.getKey());
				buf.writeVarInt(relation.getValue().getIndex());
			}
			return buf;
		}

		public UUID getID() {
			return this.id;
		}

		public UUID getOwnerUUID() {
			return ownerUUID;
		}

		public String getOwnerName() {
			return this.memberNames.get(getOwnerUUID());
		}

		public Options getOptions() {
			return this.options;
		}

		private void setOwner(PlayerEntity newOwner) {
			this.members.put(ownerUUID, Rank.LEADER);
			this.ownerUUID = newOwner.getUuid();
		}

		public TeamChanges promoteMember(PlayerEntity member) {
			UUID uuid = member.getUuid();
			if (members.containsKey(uuid)) {
				Rank currentRank = members.get(uuid);
				switch (currentRank) {
					case MEMBER -> {
						members.put(uuid, Rank.LEADER);
						return TeamChanges.promoted(memberNames.get(uuid), Rank.LEADER);
					}
					case LEADER -> {
						String ownerName = getOwnerName();
						setOwner(member);
						return TeamChanges.promoted(getOwnerName(), Rank.OWNER).demotion(ownerName, Rank.LEADER);
					}
					case OWNER -> {
						return TeamChanges.failed("The specified player is already the team owner!");
					}
				}
			} else {
				return TeamChanges.failed("The specified player is not a part of the team!");
			}
			return TeamChanges.failed("An error occurred while attempting to promote the user.");
		}

		public TeamChanges demoteMember(PlayerEntity member) {
			UUID uuid = member.getUuid();
			if (members.containsKey(uuid)) {
				Rank currentRank = members.get(uuid);
				switch (currentRank) {
					case MEMBER -> {
						return TeamChanges.failed("The specified player is already the lowest rank!");
					}
					case LEADER -> {
						members.put(uuid, Rank.MEMBER);
						return TeamChanges.demoted(memberNames.get(uuid), Rank.MEMBER);
					}
					case OWNER -> {
						members.put(uuid, Rank.LEADER);
						return TeamChanges.demoted(memberNames.get(uuid), Rank.LEADER);
					}
				}
			} else {
				return TeamChanges.failed("The specified player is not a part of the team!");
			}
			return TeamChanges.failed("An error occurred while attempting to demote the user.");
		}

		public TeamChanges kickMember(PlayerEntity member) {
			UUID uuid = member.getUuid();
			if (members.containsKey(uuid)) {
				members.remove(uuid);
				memberNames.remove(uuid);
				return TeamChanges.kicked(member.getName().getString());
			} else {
				return TeamChanges.failed("The specified player is not a part of the team!");
			}
        }

		public TeamChanges addMember(PlayerEntity member) {
			UUID uuid = member.getUuid();
			if (!members.containsKey(uuid)) {
				members.put(uuid, Rank.MEMBER);
				memberNames.put(uuid, member.getName().getString());
				return TeamChanges.joined(member.getName().getString());
			} else {
				return TeamChanges.failed("The specified player is not a part of the team!");
			}
		}

		public TeamChanges addFakeMember() {
			String fakeName = "";
			for (int i = 0; i < 8; i++) {
                fakeName += "abcdefghijklmnopqrstuvwxyz".charAt((int) (Math.random() * 26));
			}
			UUID uuid = UUID.randomUUID();
			if (!members.containsKey(uuid)) {
				members.put(uuid, Rank.MEMBER);
				memberNames.put(uuid, fakeName);
				return TeamChanges.joined(fakeName);
			} else {
				return TeamChanges.failed("The specified player is not a part of the team!");
			}
		}

		public TeamChanges declare(Team team, Relation relation) {
			this.relations.put(team.getID(), relation);
			return TeamChanges.declaration(team, relation);
		}

		public List<UUID> getSortedMemberList() {
			List<UUID> list = new ArrayList<>();
			list.add(this.ownerUUID);
			for (UUID memberUUID : this.members.keySet()) {
				if (this.members.get(memberUUID) == Rank.LEADER) list.add(memberUUID);
			}
			for (UUID memberUUID : this.members.keySet()) {
				if (this.members.get(memberUUID) == Rank.MEMBER) list.add(memberUUID);
			}
			return list;
		}

		public int getMemberCount() {
			return this.members.size();
		}

		public List<UUID> getMemberUUIDS() {
			return this.members.keySet().stream().toList();
		}

		public Rank getMemberRank(UUID uuid) {
			return this.members.get(uuid);
		}

		public Rank getMemberRank(PlayerEntity player) {
			return this.members.get(player.getUuid());
		}

		public String getMemberName(UUID uuid) {
			return this.memberNames.get(uuid);
		}

		public String getMemberName(PlayerEntity player) {
			return this.memberNames.get(player.getUuid());
		}

		public boolean isTeamMember(PlayerEntity player) {
			return this.members.containsKey(player.getUuid());
		}

		public boolean isTeamMember(UUID uuid) {
			return this.members.containsKey(uuid);
		}

		public boolean isAllyTeam(Team team) {
			return this.relations.containsKey(team.getID()) && this.relations.get(team.getID()) == Relation.ALLY;
		}

		public boolean isAllyTeam(UUID team) {
			return this.relations.containsKey(team) && this.relations.get(team) == Relation.ALLY;
		}

		public boolean isNeutralTeam(Team team) {
			return !this.relations.containsKey(team.getID()) || this.relations.get(team.getID()) == Relation.NEUTRAL;
		}

		public boolean isNeutralTeam(UUID team) {
			return !this.relations.containsKey(team) || this.relations.get(team) == Relation.NEUTRAL;
		}

		public boolean isEnemyTeam(Team team) {
			return this.relations.containsKey(team.getID()) && this.relations.get(team.getID()) == Relation.ENEMY;
		}

		public boolean isEnemyTeam(UUID team) {
			return this.relations.containsKey(team) && this.relations.get(team) == Relation.ENEMY;
		}

		public void broadcastChanges(MinecraftServer server, TeamChanges changes) {
			for (UUID member : this.members.keySet()) {
				PlayerEntity player = server.getPlayerManager().getPlayer(member);
				if (player != null) {
					player.sendMessage(changes.toText());
				}
			}
		}

		public NbtCompound toNbt() {
			NbtCompound nbt = new NbtCompound();
			nbt.putUuid("id", this.id);
			nbt.putUuid("ownerUUID", this.ownerUUID);
			NbtList members = new NbtList();
			for (Map.Entry<UUID, Rank> member : this.members.entrySet()) {
				NbtCompound memberNbt = new NbtCompound();
				memberNbt.putUuid("memberUUID", member.getKey());
				memberNbt.putInt("memberRank", member.getValue().getIndex());
				memberNbt.putString("memberName", memberNames.get(member.getKey()));
				members.add(memberNbt);
			}
			nbt.put("members", members);
			nbt.put("options", this.options.toNbt());
			NbtList relations = new NbtList();
			for (Map.Entry<UUID, Relation> relation : this.relations.entrySet()) {
				NbtCompound relationNbt = new NbtCompound();
				relationNbt.putUuid("teamUUID", relation.getKey());
				relationNbt.putInt("relationIndex", relation.getValue().getIndex());
				members.add(relationNbt);
			}
			nbt.put("relations", relations);
			return nbt;
		}

		public void printInfoText(PlayerEntity player, int pageNum) {
			MutableText text = Text.translatable("soulforge-teams.team_name").append(this.options.teamName + "\n");
			if (!Objects.equals(this.options.teamDescription, "")) text.append(this.options.teamDescription + "\n");
			text.append(Text.translatable("soulforge-teams.team_members").append("\n"));
			int i = 0;
			for (UUID memberUUID : getSortedMemberList()) {
				if (i < pageNum * 10) {
					i++;
					continue;
				}
				i++;
				Rank rank = this.members.get(memberUUID);
				text.append(rank.getText()).append(" - ").append(this.memberNames.get(memberUUID));
				if (this.members.size() != i) text.append("\n");
				if (i >= (pageNum + 1) * 10) {
					break;
				}
			}
			player.sendMessage(text);
		}

		public void printInfoText(PlayerEntity player) {
			printInfoText(player, 0);
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof Team team) {
				return team.getID().compareTo(this.getID()) == 0;
			}
			return false;
		}

		public enum Rank {
			OWNER(2),
			LEADER(1),
			MEMBER(0);

			final int index;
			Rank(int index) {
				this.index = index;
			}

			int getIndex() {
				return index;
			}

			public static Rank fromIndex(int index) {
				return switch (index) {
					case 2 -> OWNER;
					case 1 -> LEADER;
                    default -> MEMBER;
                };
			}

			public Text getText() {
				return switch (index) {
					case 2 -> Text.translatable("soulforge-teams.owner");
					case 1 -> Text.translatable("soulforge-teams.leader");
					case 0 -> Text.translatable("soulforge-teams.member");
					default -> Text.translatable("soulforge-teams.error");
				};
			}
		}

		public enum Relation {
			ALLY(1),
			NEUTRAL(0),
			ENEMY(-1);

			final int index;
			Relation(int index) {
				this.index = index;
			}

			int getIndex() {
				return index;
			}

			public static Relation fromIndex(int index) {
				return switch (index) {
					case 1 -> ALLY;
					case -1 -> ENEMY;
					default -> NEUTRAL;
				};
			}

			public Text getText() {
				return switch (index) {
					case 2 -> Text.translatable("soulforge-teams.ally");
					case 1 -> Text.translatable("soulforge-teams.neutral");
					case 0 -> Text.translatable("soulforge-teams.enemy");
					default -> Text.translatable("soulforge-teams.error");
				};
			}
		}

		public static class Options {
			public String teamName = "";
			public String teamDescription = "";
			public boolean allowTeamPvP = false;
			public boolean allowOffensiveAllyTargeting = false;
			public boolean allowDefensiveEnemyTargeting = false;

			public PacketByteBuf writeBuf(PacketByteBuf buf) {
				buf.writeString(teamName);
				buf.writeString(teamDescription);
				buf.writeBoolean(allowTeamPvP);
				buf.writeBoolean(allowOffensiveAllyTargeting);
				buf.writeBoolean(allowDefensiveEnemyTargeting);
				return buf;
			}

			public NbtCompound toNbt() {
				NbtCompound nbt = new NbtCompound();
				nbt.putString("teamName", teamName);
				nbt.putString("teamDescription", teamDescription);
				nbt.putBoolean("allowTeamPvp", allowTeamPvP);
				nbt.putBoolean("allowOffensiveAllyTargeting", allowOffensiveAllyTargeting);
				nbt.putBoolean("allowDefensiveEnemyTargeting", allowDefensiveEnemyTargeting);
				return nbt;
			}

			public Options() {}

			public Options(PacketByteBuf buf) {
				teamName = buf.readString();
				teamDescription = buf.readString();
				allowTeamPvP = buf.readBoolean();
				allowOffensiveAllyTargeting = buf.readBoolean();
				allowDefensiveEnemyTargeting = buf.readBoolean();
			}

			public Options(NbtCompound nbt) {
				teamName = nbt.getString("teamName");
				teamDescription = nbt.getString("teamDescription");
				allowTeamPvP = nbt.getBoolean("allowTeamPvP");
				allowOffensiveAllyTargeting = nbt.getBoolean("allowOffensiveAllyTargeting");
				allowDefensiveEnemyTargeting = nbt.getBoolean("allowDefensiveEnemyTargeting");
			}
		}

		public static class TeamChanges {
			public HashMap<String, Rank> promotions = new HashMap<>();
			public HashMap<String, Rank> demotions = new HashMap<>();
			public List<String> kicks = new ArrayList<>();
			public List<String> joins = new ArrayList<>();
			public HashMap<Team, Relation> declarations = new HashMap<>();
			public boolean changeFailed = false;
			public String message = "";

			public static TeamChanges promoted(String username, Rank rank) {
				TeamChanges changes = new TeamChanges();
				changes.promotions.put(username, rank);
				return changes;
			}

			public static TeamChanges promoted(HashMap<String, Rank> promotions) {
				TeamChanges changes = new TeamChanges();
				changes.promotions = promotions;
				return changes;
			}

			public TeamChanges promotion(String username, Rank rank) {
				this.promotions.put(username, rank);
				return this;
			}

			public TeamChanges promotion(HashMap<String, Rank> promotions) {
				this.promotions = promotions;
				return this;
			}

			public static TeamChanges demoted(String username, Rank rank) {
				TeamChanges changes = new TeamChanges();
				changes.demotions.put(username, rank);
				return changes;
			}

			public static TeamChanges demoted(HashMap<String, Rank> demotions) {
				TeamChanges changes = new TeamChanges();
				changes.demotions = demotions;
				return changes;
			}

			public TeamChanges demotion(String username, Rank rank) {
				this.demotions.put(username, rank);
				return this;
			}

			public TeamChanges demotion(HashMap<String, Rank> demotions) {
				this.demotions.putAll(demotions);
				return this;
			}

			public static TeamChanges kicked(String username) {
				TeamChanges changes = new TeamChanges();
				changes.kicks.add(username);
				return changes;
			}

			public static TeamChanges kicked(List<String> uuids) {
				TeamChanges changes = new TeamChanges();
				changes.kicks = uuids;
				return changes;
			}

			public TeamChanges kick(String username) {
				this.kicks.add(username);
				return this;
			}

			public TeamChanges kick(List<String> kicks) {
				this.kicks.addAll(kicks);
				return this;
			}

			public static TeamChanges joined(String username) {
				TeamChanges changes = new TeamChanges();
				changes.joins.add(username);
				return changes;
			}

			public static TeamChanges joined(List<String> uuids) {
				TeamChanges changes = new TeamChanges();
				changes.joins = uuids;
				return changes;
			}

			public TeamChanges join(String username) {
				this.joins.add(username);
				return this;
			}

			public TeamChanges join(List<String> joins) {
				this.joins.addAll(joins);
				return this;
			}

			public static TeamChanges declaration(Team team, Relation relation) {
				TeamChanges changes = new TeamChanges();
				changes.declarations.put(team, relation);
				return changes;
			}

			public TeamChanges declare(Team team, Relation relation) {
				this.declarations.put(team, relation);
				return this;
			}

			public static TeamChanges failed(String reason) {
				TeamChanges changes = new TeamChanges();
				changes.changeFailed = true;
				changes.message = reason;
				return changes;
			}

			public Text toText() {
				MutableText text = Text.empty();
				if (this.changeFailed) return Text.translatable("soulforge-teams.change_failed");
				text.append(Text.translatable("soulforge-teams.change_detected").setStyle(Style.EMPTY.withColor(Formatting.AQUA).withBold(true)).append("\n"));
				if (this.message != null) text.append(Text.translatable("soulforge-teams.message").append(this.message).append("\n"));
				if (!this.promotions.isEmpty()) {
					text.append(Text.translatable("soulforge-teams.promotions_header").append("\n"));
					for (Map.Entry<String, Rank> promotion : this.promotions.entrySet()) {
						text.append(Text.literal("- ").append(promotion.getKey()).append(" -> ").append(promotion.getValue().getText()).append("\n"));
					}
				}
				if (!this.demotions.isEmpty()) {
					text.append(Text.translatable("soulforge-teams.demotions_header").append("\n"));
					for (Map.Entry<String, Rank> demotion : this.demotions.entrySet()) {
						text.append(Text.literal("- ").append(demotion.getKey()).append(" -> ").append(demotion.getValue().getText()).append("\n"));
					}
				}
				if (!this.joins.isEmpty()) {
					text.append(Text.translatable("soulforge-teams.joins_header").append("\n"));
					for (String join : this.joins) {
						text.append(Text.literal("- ").append(join).append("").append("\n"));
					}
				}
				if (!this.kicks.isEmpty()) {
					text.append(Text.translatable("soulforge-teams.kicks_header").append("\n"));
					for (String join : this.kicks) {
						text.append(Text.literal("- ").append(join).append("").append("\n"));
					}
				}
				if (!this.declarations.isEmpty()) {
					text.append(Text.translatable("soulforge-teams.declarations_header").append("\n"));
					for (Map.Entry<Team, Relation> declaration : this.declarations.entrySet()) {
						text.append(Text.literal("- ").append(declaration.getKey().options.teamName).append(" -> ").append(declaration.getValue().getText()).append("\n"));
					}
				}
				return text;
			}
		}
	}
}