package com.gw.nsukwander;

import common.cn.kafei.simukraft.entity.CitizenEntity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

/**
 * 闲逛运行管理器（纯内存状态，服务器级）：
 * <ul>
 *   <li>Goal 挂载：市民加入世界时检测 AI 冲突并挂载闲逛 Goal（旧存档/新生成均走此入口）；</li>
 *   <li>冲突检测：goalSelector 出现 NSUK 三大原生 Goal + 本补丁 Goal 之外的 Goal 时，永久跳过该市民；</li>
 *   <li>活跃任务表：同一市民同一时刻最多一个闲逛任务；</li>
 *   <li>每 tick 开启预算：perTickProcessCount；</li>
 *   <li>全局开关关闭时的统一终止下发。</li>
 * </ul>
 */
public final class WanderManager {

    /** 活跃闲逛任务：市民 UUID → Goal */
    private static final Map<UUID, CitizenWanderGoal> ACTIVE = new ConcurrentHashMap<>();

    /** 检测到 AI 冲突的市民（本运行期内永久跳过） */
    private static final Set<UUID> CONFLICTED = ConcurrentHashMap.newKeySet();

    /** 每 tick 可新开启的闲逛任务数（ServerTickEvent.Post 重置） */
    private static volatile int tickBudget = WanderConfig.perTickProcessCount();

    private WanderManager() {
    }

    // ------------------------------------------------------------------
    // 挂载与冲突检测
    // ------------------------------------------------------------------

    /** 市民加入世界时调用：无冲突则挂载闲逛 Goal（幂等） */
    public static void attachIfEligible(CitizenEntity citizen) {
        UUID uuid = citizen.getUUID();
        if (CONFLICTED.contains(uuid)) {
            return;
        }
        // 已挂载则跳过
        boolean hasMine = false;
        for (WrappedGoal wrapped : citizen.goalSelector.getAvailableGoals()) {
            Goal g = wrapped.getGoal();
            if (g instanceof CitizenWanderGoal) {
                hasMine = true;
            } else if (!isNsukNativeGoal(g)) {
                // 其他模组修改了该市民的 AI：跳过，不挂载
                CONFLICTED.add(uuid);
                WanderLogger.warn("市民 {} 检测到第三方 AI Goal（{}），按冲突策略跳过闲逛挂载",
                        uuid.toString().substring(0, 8), g.getClass().getName());
                return;
            }
        }
        if (hasMine) {
            return;
        }
        try {
            citizen.goalSelector.addGoal(CitizenWanderGoal.PRIORITY, new CitizenWanderGoal(citizen));
            // 允许寻路把关闭的木门视为可通行节点（开门动作由 Goal 内的辅助逻辑执行）
            citizen.getNavigation().setCanOpenDoors(true);
            WanderLogger.debug("市民 {} 已挂载闲逛 Goal", uuid.toString().substring(0, 8));
        } catch (Throwable t) {
            WanderLogger.error("市民 " + uuid + " 挂载闲逛 Goal 失败（已跳过）", t);
        }
    }

    /** NSUK 原版 registerGoals 注册的三个 Goal */
    private static boolean isNsukNativeGoal(Goal g) {
        return g instanceof FloatGoal
                || g instanceof LookAtPlayerGoal
                || g instanceof RandomLookAroundGoal;
    }

    public static boolean isConflicted(UUID uuid) {
        return CONFLICTED.contains(uuid);
    }

    /** 定期复核：挂载后被其他模组追加 Goal 的市民按冲突处理 */
    public static void rescanConflicts(ServerLevel level) {
        for (CitizenEntity citizen : level.getEntities(
                net.minecraft.world.level.entity.EntityTypeTest.forClass(CitizenEntity.class),
                e -> !CONFLICTED.contains(e.getUUID()))) {
            for (WrappedGoal wrapped : citizen.goalSelector.getAvailableGoals()) {
                Goal g = wrapped.getGoal();
                if (!(g instanceof CitizenWanderGoal) && !isNsukNativeGoal(g)) {
                    UUID uuid = citizen.getUUID();
                    CONFLICTED.add(uuid);
                    requestTerminate(uuid, TerminateReason.CONFLICT);
                    WanderLogger.warn("市民 {} 运行中检测到第三方 AI Goal（{}），终止闲逛并跳过",
                            uuid.toString().substring(0, 8), g.getClass().getName());
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 活跃任务管理
    // ------------------------------------------------------------------

    /** Goal 启动时登记；已存在活跃任务返回 false（防同 tick 多任务） */
    public static boolean onGoalStarted(UUID uuid, CitizenWanderGoal goal) {
        return ACTIVE.putIfAbsent(uuid, goal) == null;
    }

    public static void onGoalStopped(UUID uuid) {
        ACTIVE.remove(uuid);
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** 请求终止某市民的闲逛（事件入口：受击/交互/冲突等） */
    public static void requestTerminate(UUID uuid, TerminateReason reason) {
        CitizenWanderGoal goal = ACTIVE.get(uuid);
        if (goal != null) {
            goal.requestTerminate(reason);
        }
    }

    /** 全局关闭：所有正在闲逛的市民立刻终止并返回住宅 */
    public static void terminateAll(TerminateReason reason) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        WanderLogger.info("全局闲逛关闭，终止 {} 个进行中的闲逛任务", ACTIVE.size());
        for (CitizenWanderGoal goal : ACTIVE.values()) {
            goal.requestTerminate(reason);
        }
    }

    /** 实体离开世界（卸载/跨维度/死亡）时的兜底清理 */
    public static void onEntityRemoved(UUID uuid, TerminateReason reason) {
        CitizenWanderGoal goal = ACTIVE.remove(uuid);
        if (goal != null) {
            WanderLogger.info("市民 {} 离开世界，清理闲逛任务（{}）",
                    uuid.toString().substring(0, 8), reason.display());
        }
    }

    // ------------------------------------------------------------------
    // 预算与周期维护
    // ------------------------------------------------------------------

    public static void resetTickBudget() {
        tickBudget = WanderConfig.perTickProcessCount();
    }

    public static boolean tryConsumeBudget() {
        if (tickBudget <= 0) {
            return false;
        }
        tickBudget--;
        return true;
    }

    /** 服务器 tick 周期维护：冲突复核 + 存档数据清理 */
    public static void periodicMaintenance(ServerLevel overworld, long gameTime) {
        // 每 400 tick 复核一次第三方 Goal 注入
        if (gameTime % 400L == 0L) {
            rescanConflicts(overworld);
        }
        // 每 6000 tick 清理过期冷却/封禁条目
        if (gameTime % 6000L == 0L) {
            WanderSavedData.get(overworld).prune(gameTime);
        }
    }

    /** 服务器停止/存档卸载时清空运行态 */
    public static void clearAll() {
        ACTIVE.clear();
        CONFLICTED.clear();
        tickBudget = WanderConfig.perTickProcessCount();
    }
}
