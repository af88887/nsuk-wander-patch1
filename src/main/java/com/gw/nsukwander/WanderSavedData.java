package com.gw.nsukwander;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 闲逛补丁的持久化数据（存放在主世界维度）：
 * <ul>
 *   <li>cooldownEnd：各市民闲逛冷却结束的 gameTime；玩家传送市民不会重置；</li>
 *   <li>failBanEnd：连续寻路失败后的临时禁用截止 gameTime（防死循环耗性能）；</li>
 *   <li>consecutiveFails：连续失败次数（用于触发临时禁用）。</li>
 * </ul>
 * 跨重启存活，重进存档不会丢失冷却。
 */
public final class WanderSavedData extends SavedData {

    private static final String DATA_NAME = "nsukwander_data";

    private static final String TAG_COOLDOWN = "cooldown";
    private static final String TAG_BAN = "failban";
    private static final String TAG_FAILS = "fails";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_TICK = "tick";
    private static final String TAG_COUNT = "count";

    private final Map<UUID, Long> cooldownEnd = new HashMap<>();
    private final Map<UUID, Long> failBanEnd = new HashMap<>();
    private final Map<UUID, Integer> consecutiveFails = new HashMap<>();

    public static WanderSavedData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        ServerLevel store = overworld != null ? overworld : level;
        return store.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WanderSavedData::new, WanderSavedData::load, null),
                DATA_NAME);
    }

    private static WanderSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        WanderSavedData data = new WanderSavedData();
        readMap(tag.getList(TAG_COOLDOWN, Tag.TAG_COMPOUND), data.cooldownEnd);
        readMap(tag.getList(TAG_BAN, Tag.TAG_COMPOUND), data.failBanEnd);
        ListTag fails = tag.getList(TAG_FAILS, Tag.TAG_COMPOUND);
        for (int i = 0; i < fails.size(); i++) {
            CompoundTag entry = fails.getCompound(i);
            data.consecutiveFails.put(entry.getUUID(TAG_UUID), entry.getInt(TAG_COUNT));
        }
        return data;
    }

    private static void readMap(ListTag list, Map<UUID, Long> out) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            out.put(entry.getUUID(TAG_UUID), entry.getLong(TAG_TICK));
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.put(TAG_COOLDOWN, writeMap(cooldownEnd));
        tag.put(TAG_BAN, writeMap(failBanEnd));
        ListTag fails = new ListTag();
        consecutiveFails.forEach((uuid, count) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, uuid);
            entry.putInt(TAG_COUNT, count);
            fails.add(entry);
        });
        tag.put(TAG_FAILS, fails);
        return tag;
    }

    private static ListTag writeMap(Map<UUID, Long> map) {
        ListTag list = new ListTag();
        map.forEach((uuid, tick) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, uuid);
            entry.putLong(TAG_TICK, tick);
            list.add(entry);
        });
        return list;
    }

    // ---------- 冷却 ----------

    public boolean isCoolingDown(UUID uuid, long gameTime) {
        Long end = cooldownEnd.get(uuid);
        return end != null && gameTime < end;
    }

    public void setCooldown(UUID uuid, long gameTime, int durationTicks) {
        cooldownEnd.put(uuid, gameTime + Math.max(0, durationTicks));
        setDirty();
    }

    // ---------- 失败禁用 ----------

    public boolean isBanned(UUID uuid, long gameTime) {
        Long end = failBanEnd.get(uuid);
        return end != null && gameTime < end;
    }

    /** 记录一次寻路/移动失败；连续 2 次失败触发临时禁用（24000 tick ≈ 一个 MC 日） */
    public void registerFailure(UUID uuid, long gameTime) {
        int fails = consecutiveFails.getOrDefault(uuid, 0) + 1;
        consecutiveFails.put(uuid, fails);
        if (fails >= 2) {
            failBanEnd.put(uuid, gameTime + 24000L);
            consecutiveFails.put(uuid, 0);
            WanderLogger.warn("市民 {} 连续寻路失败，临时禁用闲逛 24000 tick（防死循环）", uuid);
        }
        setDirty();
    }

    public void resetFailures(UUID uuid) {
        if (consecutiveFails.remove(uuid) != null) {
            setDirty();
        }
    }

    // ---------- 维护 ----------

    /** 定期清理过期条目，避免老存档数据无限增长 */
    public void prune(long gameTime) {
        boolean changed = false;
        changed |= cooldownEnd.entrySet().removeIf(e -> e.getValue() < gameTime - 72000L);
        changed |= failBanEnd.entrySet().removeIf(e -> e.getValue() < gameTime - 72000L);
        if (changed) {
            setDirty();
        }
    }

    /** 遍历冷却表（指令 status 用） */
    public int trackedCount() {
        return cooldownEnd.size();
    }

    /** 仅供内部测试/指令读取 */
    public Map<UUID, Long> cooldownView() {
        return java.util.Collections.unmodifiableMap(cooldownEnd);
    }
}
