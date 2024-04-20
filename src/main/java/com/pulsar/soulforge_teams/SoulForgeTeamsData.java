package com.pulsar.soulforge_teams;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class SoulForgeTeamsData extends PersistentState {
    public List<SoulForgeTeams.Team> TEAMS = new ArrayList<>();

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList teamList = new NbtList();
        for (SoulForgeTeams.Team team : TEAMS) {
            teamList.add(team.toNbt());
        }
        nbt.put("sfteams", teamList);
        return nbt;
    }

    public static SoulForgeTeamsData createFromNbt(NbtCompound tag) {
        SoulForgeTeamsData data = new SoulForgeTeamsData();
        NbtList list = tag.getList("sfteams", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            data.TEAMS.add(new SoulForgeTeams.Team(list.getCompound(i)));
        }
        return data;
    }

    public static SoulForgeTeamsData getServerState(MinecraftServer server) {
        PersistentStateManager persistentStateManager = server.getWorld(World.OVERWORLD).getPersistentStateManager();

        SoulForgeTeamsData teamData = persistentStateManager.getOrCreate(SoulForgeTeamsData::createFromNbt, SoulForgeTeamsData::new, "soulforge-teams");
        teamData.markDirty();
        return teamData;
    }
}
