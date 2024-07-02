package com.pulsar.soulforge_teams;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class SoulForgeTeamsClient implements ClientModInitializer {
    public static List<SoulForgeTeams.Team> TEAMS = new ArrayList<>();
    public static HashMap<UUID, Pair<Float, Float>> PLAYER_HEALTHS = new HashMap<>();

    private static boolean up = false;
    private static int lastScrollAmount = 0;
    private static int scrollAmount = 0;
    private static int scrollTimer = 0;
    private static int switchTimer = 0;
    private static boolean switching = false;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SoulForgeTeams.TEAM_PACKET, ((client, handler, buf, responseSender) -> {
            TEAMS = new ArrayList<>();
            int teamCount = buf.readVarInt();
            for (int i = 0; i < teamCount; i++) {
                TEAMS.add(new SoulForgeTeams.Team(buf));
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(SoulForgeTeams.HEALTH_PACKET, ((client, handler, buf, responseSender) -> {
            PLAYER_HEALTHS = new HashMap<>();
            int playerCount = buf.readVarInt();
            for (int i = 0; i < playerCount; i++) {
                PLAYER_HEALTHS.put(buf.readUuid(), new Pair<>(buf.readFloat(), buf.readFloat()));
            }
        }));

        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            PlayerEntity player = MinecraftClient.getInstance().player;
            if (player != null) {
                SoulForgeTeams.Team team = getPlayerTeam(player);
                if (team != null) {
                    int screenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();
                    int widgetHeight = 144;

                    List<UUID> onlineMembers = new ArrayList<>();
                    for (UUID memberUUID : team.getSortedMemberList()) {
                        if (MinecraftClient.getInstance().getNetworkHandler().getPlayerUuids().contains(memberUUID)) {
                            onlineMembers.add(memberUUID);
                        }
                    }
                    int memberNum = onlineMembers.size();
                    int totalHeight = memberNum * 30 - 6;
                    if (switching) {
                        switchTimer++;
                        if (switchTimer >= 50) {
                            up = !up;
                            switching = false;
                        }
                    }

                    if (scrollTimer >= 3) {
                        lastScrollAmount = scrollAmount;
                        if (up) scrollAmount = Math.max(0, scrollAmount - 1);
                        else scrollAmount = Math.min(Math.max(0, totalHeight - widgetHeight), scrollAmount + 1);
                        scrollTimer = 0;
                        if (scrollAmount == lastScrollAmount && !switching) {
                            switchTimer = 0;
                            switching = true;
                        }
                    }
                    scrollTimer++;
                    context.enableScissor(0, (screenHeight - widgetHeight)/2, 150, (screenHeight + widgetHeight)/2);
                    for (int i = 0; i < memberNum; i++) {
                        int drawPosition = -scrollAmount + 30 * i;
                        UUID memberUUID = onlineMembers.get(i);
                        context.drawTexture(getPlayerSkin(memberUUID), 10, (screenHeight - widgetHeight)/2 + drawPosition, 24, 24, 24, 24, 192, 192);
                        int nameTextTop = (screenHeight - widgetHeight)/2 + drawPosition + 12 - (int)(MinecraftClient.getInstance().textRenderer.fontHeight * 1.15f);
                        int healthTextTop = (screenHeight - widgetHeight)/2 + drawPosition + 12 + (int)(MinecraftClient.getInstance().textRenderer.fontHeight * 0.15f);
                        //int healthBarTop = (screenHeight - widgetHeight)/2 + drawPosition + 12 + (int)(MinecraftClient.getInstance().textRenderer.fontHeight * 1.15f) - 3;
                        context.drawText(MinecraftClient.getInstance().textRenderer, getPlayerInTeamName(memberUUID), 40, nameTextTop, 0xFFFFFF, true);
                        DecimalFormat df = new DecimalFormat();
                        df.setMaximumFractionDigits(1);
                        context.drawText(MinecraftClient.getInstance().textRenderer, Text.translatable("soulforge-teams.health_prefix").append(df.format(getPlayerHealth(memberUUID))).append("/").append(df.format(getPlayerMaxHealth(memberUUID))), 40, healthTextTop, 0xFFFFFF, true);
                        //context.fill(40, healthTextTop, 90, healthTextTop + 4, 0xAA0000);
                        //context.fill(40, healthBarTop, 40 + (int)(50 * (getPlayerHealth(memberUUID) / getPlayerMaxHealth(memberUUID))), healthBarTop + 4, 0xFF0000);
                    }
                    context.disableScissor();
                }
            }
        });
    }

    public static Identifier getPlayerSkin(UUID playerUUID) {
        try {
            return MinecraftClient.getInstance().getNetworkHandler().getPlayerListEntry(playerUUID).getSkinTexture();
        } catch (Exception e) {
            return new Identifier("soulforge-teams", "icon.png");
        }
    }

    public static float getPlayerHealth(UUID playerUUID) {
        try {
            return PLAYER_HEALTHS.get(playerUUID).getRight();
        } catch (Exception e) {
            return 0f;
        }
    }

    public static float getPlayerMaxHealth(UUID playerUUID) {
        try {
            return PLAYER_HEALTHS.get(playerUUID).getLeft();
        } catch (Exception e) {
            return 0f;
        }
    }

    public static SoulForgeTeams.Team getPlayerTeam(PlayerEntity player) {
        if (TEAMS != null) {
            try {
                for (SoulForgeTeams.Team team : List.copyOf(TEAMS)) {
                    if (team.isTeamMember(player)) {
                        return team;
                    }
                }
            } catch (Exception exception) {
                return null;
            }
        }
        return null;
    }

    public static SoulForgeTeams.Team getPlayerTeam(UUID uuid) {
        if (TEAMS != null) {
            try {
                for (SoulForgeTeams.Team team : List.copyOf(TEAMS)) {
                    if (team.isTeamMember(uuid)) {
                        return team;
                    }
                }
            } catch (Exception exception) {
                return null;
            }
        }
        return null;
    }

    public static Text getPlayerInTeamName(UUID uuid) {
        if (TEAMS != null) {
            try {
                for (SoulForgeTeams.Team team : List.copyOf(TEAMS)) {
                    if (team.isTeamMember(uuid)) {
                        return Text.literal(team.getMemberName(uuid));
                    }
                }
            } catch (Exception exception) {
                return Text.translatable("soulforge-teams.error");
            }
        }
        return Text.translatable("soulforge-teams.error");
    }

    public static boolean isInTeam(PlayerEntity player) {
        return getPlayerTeam(player) != null;
    }

    public static boolean isInTeam(UUID uuid) {
        return getPlayerTeam(uuid) != null;
    }
}
