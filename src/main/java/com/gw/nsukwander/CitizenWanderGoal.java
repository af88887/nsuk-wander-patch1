package com.gw.nsukwander;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.entity.CitizenEntity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 市民闲逛 Goal（挂在 goalSelector 优先级 5）。
 *
 * <p>优先级关系：NSUK 生存 AI（0~2 + 导航服务驱动） &gt; 本 Goal(5) &gt; 原版随机游荡(6+)。
 * 让行机制：canUse/运行中检测 NSUK 导航服务接管即终止；血量/受击/睡眠立即终止交还 NSUK。
 *
 * <p>一轮闲逛生命周期：
 * <pre>住宅（冷却结束+概率通过）→ OUT(走向目标点) → BACK(返回住宅) → 完成并进入冷却</pre>
 */
public final class CitizenWanderGoal extends Goal {

    /** 闲逛 Goal 在 goalSelector 中的优先级（低于 NSUK 的 0~2，高于原版游荡的 6~8） */
    public static final int PRIORITY = 5;

    private static final ResourceLocation STEP_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(NsukWanderMod.MOD_ID, "wander_step_climb");

    private enum Phase {
        /** 走向目标点 */
        OUT,
        /** 返回住宅 */
        BACK,
        /** 任务结束（下一 tick 由 selector 停止） */
        DONE
    }

    private final CitizenEntity citizen;
    private final UUID uuid;

    private Phase phase = Phase.DONE;
    private BlockPos target;
    private BlockPos homePos;
    private Vec3 homeTarget;
    private List<CityPoiData> watchedPois = List.of();
    private boolean nightRound;

    /** 由外部（事件/指令/管理器）注入的待处理终止原因 */
    private TerminateReason pendingReason;
    /** 本轮终止原因（返回住宅阶段的日志用） */
    private TerminateReason roundEndReason;

    private long nextEvalTick;
    private long phaseStartTick;
    private int repathCount;
    private int noProgressWindows;
    private Vec3 lastProgressPos = Vec3.ZERO;
    private int ticksSinceCheck;

    /** 闲逛期间打开过的木门（关闭用），最多记录 4 扇 */
    private final List<BlockPos> openedDoors = new ArrayList<>(4);

    private boolean registeredToManager;

    public CitizenWanderGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.uuid = citizen.getUUID();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    public UUID citizenUuid() {
        return uuid;
    }

    public boolean isWandering() {
        return phase != Phase.DONE;
    }

    // ------------------------------------------------------------------
    // 触发判定
    // ------------------------------------------------------------------

    @Override
    public boolean canUse() {
        // 廉价闸门：每 tick 都过
        if (phase != Phase.DONE || !WanderConfig.globalEnable() || WanderManager.isConflicted(uuid)) {
            return false;
        }
        if (!(citizen.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return false;
        }
        if (!citizen.isAlive() || citizen.isRemoved() || citizen.isSleeping() || citizen.hasActiveVisualTask()) {
            return false;
        }
        // 血量过低 / 近期被击：交给 NSUK 避险 AI
        if (citizen.getHealth() < citizen.getMaxHealth() * 0.3F) {
            return false;
        }
        if (citizen.getLastHurtByMob() != null
                && citizen.tickCount - citizen.getLastHurtByMobTimestamp() < 80) {
            return false;
        }
        // 完整判定节流：每 10 tick 一次
        long gameTime = level.getGameTime();
        if (gameTime < nextEvalTick) {
            return false;
        }
        nextEvalTick = gameTime + 10;

        // NSUK 导航服务接管中（工作/回家/避险等）：不闲逛
        if (NsukApi.isNsukNavigating(level, uuid)) {
            return false;
        }
        CitizenData data = NsukApi.citizenData(level, uuid);
        if (data == null || data.child() || data.sick()
                || !NsukApi.isUnemployed(data) || NsukApi.isBusyStatus(data)
                || data.homeId() == null || data.cityId() == null) {
            return false;
        }
        WanderSavedData saved = WanderSavedData.get(level);
        if (saved.isCoolingDown(uuid, gameTime) || saved.isBanned(uuid, gameTime)) {
            return false;
        }
        // 玩家加载区块内才运算
        if (!isChunkPlayerWatched(level, citizen.blockPosition())) {
            return false;
        }
        // 住宅有效且市民已回到住宅
        CityPoiData home = NsukApi.homePoi(level, data).orElse(null);
        if (home == null) {
            return false;
        }
        Vec3 homeT = NsukApi.resolveHomeTarget(level, home.pos());
        if (homeT.distanceToSqr(citizen.position()) > 36.0D) {
            return false;
        }
        // 城市存在且未停用（停用=核心方块被拆但数据仍在：不再生成新闲逛）
        CityData city = NsukApi.city(level, data.cityId());
        if (city == null || NsukApi.isCityDisabled(level, city)) {
            return false;
        }
        // 昼夜人数上限
        boolean night = isNight(level);
        int active = WanderManager.activeCount();
        if (night ? active >= WanderConfig.maxNightCitizen() : active >= WanderConfig.maxDayCitizen()) {
            return false;
        }
        // 概率判定：夜晚独立概率；失败继续等待冷却
        if (night && citizen.getRandom().nextDouble() >= WanderConfig.nightTriggerChance()) {
            saved.setCooldown(uuid, gameTime, WanderConfig.cooldownTick());
            WanderLogger.debug("市民 {} 夜间闲逛概率判定失败，继续冷却", shortId());
            return false;
        }
        // 每 tick 开启数量限流
        if (!WanderManager.tryConsumeBudget()) {
            return false;
        }
        // 目标点生成（绿色边界取当前声明，下一轮自动使用新边界）
        Set<Long> chunks = NsukApi.cityChunks(level, data.cityId());
        BlockPos t = WanderTargetGenerator.generate(level, citizen.blockPosition(), chunks, citizen.getRandom());
        if (t == null) {
            saved.setCooldown(uuid, gameTime, WanderConfig.cooldownTick());
            WanderLogger.info("市民 {} 目标点生成失败，放弃本次闲逛", shortId());
            return false;
        }
        this.target = t;
        this.homePos = home.pos();
        this.homeTarget = homeT;
        this.watchedPois = collectWatchedPois(level, data.cityId(), t);
        this.nightRound = night;
        return true;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void start() {
        ServerLevel level = serverLevel();
        if (level == null) {
            phase = Phase.DONE;
            return;
        }
        if (!WanderManager.onGoalStarted(uuid, this)) {
            // 同一市民已存在活跃任务：拒绝重复任务（防任务重叠/泄漏）
            WanderLogger.warn("市民 {} 已存在活跃闲逛任务，拒绝重复创建", shortId());
            phase = Phase.DONE;
            return;
        }
        registeredToManager = true;
        phase = Phase.OUT;
        phaseStartTick = level.getGameTime();
        repathCount = 0;
        noProgressWindows = 0;
        ticksSinceCheck = 0;
        roundEndReason = null;
        pendingReason = null;
        lastProgressPos = citizen.position();
        applyStepClimb(true);
        citizen.getNavigation().setCanOpenDoors(true);

        boolean ok = citizen.getNavigation().moveTo(
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speedFactor());
        if (!ok) {
            handlePathFailure(level, TerminateReason.PATH_FAIL);
            return;
        }
        WanderLogger.info("市民 {} 开始{}闲逛 -> [{}, {}, {}]",
                shortId(), nightRound ? "（夜间）" : "", target.getX(), target.getY(), target.getZ());
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.DONE && citizen.isAlive() && !citizen.isRemoved();
    }

    @Override
    public boolean isInterruptable() {
        // 允许更高优先级目标（NSUK 的 0~2）随时打断
        return true;
    }

    @Override
    public void tick() {
        ServerLevel level = serverLevel();
        if (level == null) {
            phase = Phase.DONE;
            return;
        }
        // 1) 外部终止请求优先处理
        if (pendingReason != null) {
            TerminateReason reason = pendingReason;
            pendingReason = null;
            applyTerminate(level, reason);
            if (phase == Phase.DONE) {
                return;
            }
        }
        // 2) 全局开关（指令/重载关闭时的兜底，正常由管理器统一下发）
        if (!WanderConfig.globalEnable() && phase == Phase.OUT) {
            applyTerminate(level, TerminateReason.GLOBAL_OFF);
            return;
        }
        // 3) 定期状态检查（10 tick）
        if (++ticksSinceCheck >= 10) {
            ticksSinceCheck = 0;
            if (!periodicSafetyCheck(level)) {
                return;
            }
        }
        // 4) 移动卡死检测（每 20 tick 一个窗口）
        if (!stuckWatchdog(level)) {
            return;
        }
        // 5) 开门辅助（保留 NSUK 市民的开门行为）
        tickDoorHelper(level);
        // 6) 阶段推进
        switch (phase) {
            case OUT -> tickOut(level);
            case BACK -> tickBack(level);
            case DONE -> {
                // 等待 selector 停止
            }
        }
    }

    @Override
    public void stop() {
        ServerLevel level = serverLevel();
        // NSUK 导航未接管时才停止原版导航，避免误停 NSUK 的移动
        if (level != null && !NsukApi.isNsukNavigating(level, uuid)) {
            citizen.getNavigation().stop();
        }
        applyStepClimb(false);
        closeAllOpenedDoors(level);
        if (registeredToManager) {
            registeredToManager = false;
            WanderManager.onGoalStopped(uuid);
        }
        if (roundEndReason != null) {
            WanderLogger.info("市民 {} 闲逛终止：{}", shortId(), roundEndReason.display());
            roundEndReason = null;
        }
        phase = Phase.DONE;
        target = null;
        watchedPois = List.of();
        openedDoors.clear();
    }

    // ------------------------------------------------------------------
    // 阶段逻辑
    // ------------------------------------------------------------------

    private void tickOut(ServerLevel level) {
        if (isNear(target, 2.25D)) {
            WanderLogger.debug("市民 {} 到达闲逛目标点，开始返回住宅", shortId());
            startBackPhase(level, TerminateReason.COMPLETED);
            return;
        }
        // 单次闲逛时长耗尽 → 结束本轮，返回住宅
        if (level.getGameTime() - phaseStartTick >= WanderConfig.singleWalkTickDuration()) {
            applyTerminate(level, TerminateReason.COMPLETED);
        }
    }

    private void tickBack(ServerLevel level) {
        if (homeTarget != null && homeTarget.distanceToSqr(citizen.position()) <= 6.25D) {
            finishRound(level, TerminateReason.COMPLETED);
            return;
        }
        // 返回阶段超时兜底（防僵尸任务）：原地结束并进入冷却
        if (level.getGameTime() - phaseStartTick >= WanderConfig.singleWalkTickDuration() * 2L) {
            WanderLogger.warn("市民 {} 返回住宅超时，原地结束本轮闲逛", shortId());
            finishRound(level, TerminateReason.COMPLETED);
        }
    }

    // ------------------------------------------------------------------
    // 终止处理
    // ------------------------------------------------------------------

    /** 外部请求终止：立即停走，下一阶段 tick 处理收尾（返回住宅或原地静止） */
    public void requestTerminate(TerminateReason reason) {
        this.pendingReason = reason;
        ServerLevel level = serverLevel();
        if (level != null && !NsukApi.isNsukNavigating(level, uuid)) {
            citizen.getNavigation().stop();
        }
    }

    /**
     * 应用终止：返回住宅类原因切换到 BACK 阶段；原地静止类原因直接 DONE。
     */
    private void applyTerminate(ServerLevel level, TerminateReason reason) {
        switch (reason) {
            case ATTACKED, LOW_HP, YIELD_NSUK -> {
                // 交给 NSUK AI：原地结束，不返回住宅；设置冷却避免立刻重触发
                setCooldown(level);
                roundEndReason = reason;
                phase = Phase.DONE;
            }
            case HOME_DEMOLISHED -> {
                // 住宅被拆：原地静止，不设冷却（住宅恢复前 canUse 天然失败）
                roundEndReason = reason;
                phase = Phase.DONE;
            }
            case CONFLICT -> {
                roundEndReason = reason;
                phase = Phase.DONE;
            }
            case STUCK, PATH_FAIL -> {
                WanderSavedData.get(level).registerFailure(uuid, level.getGameTime());
                setCooldown(level);
                roundEndReason = reason;
                phase = Phase.DONE;
            }
            default -> {
                // 其余原因：终止闲逛并返回住宅
                startBackPhase(level, reason);
            }
        }
    }

    /** 切换为返回住宅阶段；住宅不可达则原地结束 */
    private void startBackPhase(ServerLevel level, TerminateReason reason) {
        roundEndReason = reason;
        CitizenData data = NsukApi.citizenData(level, uuid);
        CityPoiData home = data != null ? NsukApi.homePoi(level, data).orElse(null) : null;
        if (home == null) {
            // 住宅已不存在：原地静止
            roundEndReason = reason == TerminateReason.COMPLETED ? TerminateReason.HOME_DEMOLISHED : reason;
            phase = Phase.DONE;
            return;
        }
        homePos = home.pos();
        homeTarget = NsukApi.resolveHomeTarget(level, homePos);
        phase = Phase.BACK;
        phaseStartTick = level.getGameTime();
        repathCount = 0;
        noProgressWindows = 0;
        lastProgressPos = citizen.position();
        boolean ok = citizen.getNavigation().moveTo(
                homeTarget.x, homeTarget.y, homeTarget.z, speedFactor());
        if (!ok) {
            // 回家寻路失败：原地结束（市民滞留由 NSUK 作息服务兜底）
            setCooldown(level);
            roundEndReason = TerminateReason.PATH_FAIL;
            phase = Phase.DONE;
        }
    }

    /** 一轮结束：进入冷却并交给 selector 停止 */
    private void finishRound(ServerLevel level, TerminateReason reason) {
        setCooldown(level);
        WanderSavedData.get(level).resetFailures(uuid);
        if (roundEndReason == null || roundEndReason == TerminateReason.COMPLETED) {
            WanderLogger.info("市民 {} 闲逛结束，进入冷却", shortId());
            roundEndReason = null;
        } else {
            WanderLogger.info("市民 {} 闲逛终止：{}（已返回住宅）", shortId(), roundEndReason.display());
            roundEndReason = null;
        }
        phase = Phase.DONE;
    }

    private void setCooldown(ServerLevel level) {
        WanderSavedData.get(level).setCooldown(uuid, level.getGameTime(), WanderConfig.cooldownTick());
    }

    // ------------------------------------------------------------------
    // 安全与防卡死
    // ------------------------------------------------------------------

    /** 每 10 tick 的状态复核；返回 false 表示已触发终止 */
    private boolean periodicSafetyCheck(ServerLevel level) {
        // NSUK 导航服务接管 → 让行
        if (NsukApi.isNsukNavigating(level, uuid)) {
            applyTerminate(level, TerminateReason.YIELD_NSUK);
            return false;
        }
        // 血量 / 被击 / 睡眠 → 交给 NSUK
        if (citizen.getHealth() < citizen.getMaxHealth() * 0.3F) {
            applyTerminate(level, TerminateReason.LOW_HP);
            return false;
        }
        if (citizen.getLastHurtByMob() != null
                && citizen.tickCount - citizen.getLastHurtByMobTimestamp() < 80) {
            applyTerminate(level, TerminateReason.ATTACKED);
            return false;
        }
        if (citizen.isSleeping()) {
            applyTerminate(level, TerminateReason.YIELD_NSUK);
            return false;
        }
        // 所在区块不再被玩家加载 → 终止并返回住宅
        if (!isChunkPlayerWatched(level, citizen.blockPosition())) {
            applyTerminate(level, TerminateReason.CHUNK_UNLOAD);
            return false;
        }
        CitizenData data = NsukApi.citizenData(level, uuid);
        if (data == null || data.cityId() == null) {
            applyTerminate(level, TerminateReason.CITY_DELETED);
            return false;
        }
        // 所属城市被删除 → 立刻终止（尝试返回住宅）
        if (NsukApi.city(level, data.cityId()) == null) {
            applyTerminate(level, TerminateReason.CITY_DELETED);
            return false;
        }
        // 住宅被拆除 → 原地静止
        if (NsukApi.homePoi(level, data).isEmpty()) {
            applyTerminate(level, TerminateReason.HOME_DEMOLISHED);
            return false;
        }
        // 目标所在建筑被拆除（去程中检查目标建筑 POI）
        if (phase == Phase.OUT && !watchedPois.isEmpty()) {
            List<CityPoiData> currentPois = NsukApi.cityPois(level, data.cityId());
            boolean anyAlive = false;
            for (CityPoiData poi : watchedPois) {
                for (CityPoiData p : currentPois) {
                    if (p.poiId().equals(poi.poiId()) && p.active()) {
                        anyAlive = true;
                        break;
                    }
                }
                if (anyAlive) {
                    break;
                }
            }
            if (!anyAlive) {
                applyTerminate(level, TerminateReason.BUILDING_REMOVED);
                return false;
            }
        }
        // 路径中途方块失效 / 目标点失效
        Path path = citizen.getNavigation().getPath();
        if (phase == Phase.OUT && target != null && (level.getGameTime() & 19L) == 0L) {
            if (!WanderTargetGenerator.isStillValidTarget(level, target)) {
                applyTerminate(level, TerminateReason.PATH_INVALID);
                return false;
            }
        }
        if (path != null && !path.isDone()) {
            int idx = path.getNextNodeIndex();
            for (int k = 0; k < 2 && idx + k < path.getNodeCount(); k++) {
                BlockPos nodePos = path.getNodePos(idx + k);
                if (!level.hasChunkAt(nodePos)) {
                    applyTerminate(level, TerminateReason.CHUNK_UNLOAD);
                    return false;
                }
                if (!WanderTargetGenerator.isNodePassable(level, nodePos)) {
                    applyTerminate(level, TerminateReason.PATH_INVALID);
                    return false;
                }
            }
        }
        return true;
    }

    /** 卡死看门狗：20 tick 无进展记一次，连续 3 次先重寻路一次，再失败按寻路超时处理 */
    private boolean stuckWatchdog(ServerLevel level) {
        if (phase == Phase.DONE) {
            return false;
        }
        if ((level.getGameTime() & 19L) != 0L) {
            return true;
        }
        Vec3 now = citizen.position();
        double dx = now.x - lastProgressPos.x;
        double dz = now.z - lastProgressPos.z;
        boolean navigating = !citizen.getNavigation().isDone();
        if (navigating && dx * dx + dz * dz < 0.49D) {
            noProgressWindows++;
            if (noProgressWindows >= 3) {
                if (repathCount < 1 && tryRepath(level)) {
                    repathCount++;
                    noProgressWindows = 0;
                    lastProgressPos = now;
                    return true;
                }
                WanderLogger.warn("市民 {} 寻路持续超时，原地停止", shortId());
                applyTerminate(level, TerminateReason.STUCK);
                return false;
            }
        } else {
            noProgressWindows = 0;
        }
        lastProgressPos = now;
        return true;
    }

    private boolean tryRepath(ServerLevel level) {
        if (phase == Phase.OUT && target != null) {
            return citizen.getNavigation().moveTo(
                    target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speedFactor());
        }
        if (phase == Phase.BACK && homeTarget != null) {
            return citizen.getNavigation().moveTo(homeTarget.x, homeTarget.y, homeTarget.z, speedFactor());
        }
        return false;
    }

    private void handlePathFailure(ServerLevel level, TerminateReason reason) {
        WanderLogger.warn("市民 {} 寻路失败：{}", shortId(), reason.display());
        applyTerminate(level, reason);
    }

    // ------------------------------------------------------------------
    // 开门辅助（保留 NSUK 市民的开门行为：经过木门时开门，通过后关门）
    // ------------------------------------------------------------------

    private void tickDoorHelper(ServerLevel level) {
        if ((level.getGameTime() & 3L) != 0L) {
            return;
        }
        Path path = citizen.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            int idx = path.getNextNodeIndex();
            for (int k = 0; k < 3 && idx + k < path.getNodeCount(); k++) {
                BlockPos nodePos = path.getNodePos(idx + k);
                if (citizen.position().distanceToSqr(Vec3.atCenterOf(nodePos)) > 4.0D) {
                    continue;
                }
                BlockState state = level.getBlockState(nodePos);
                if (state.getBlock() instanceof DoorBlock door
                        && door.type().canOpenByHand()
                        && !state.getValue(DoorBlock.OPEN)) {
                    BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                            ? nodePos.below() : nodePos;
                    BlockState lowerState = level.getBlockState(lower);
                    if (lowerState.getBlock() instanceof DoorBlock lowerDoor) {
                        lowerDoor.setOpen(citizen, level, lowerState, lower, true);
                        if (openedDoors.size() < 4 && !openedDoors.contains(lower)) {
                            openedDoors.add(lower.immutable());
                        }
                    }
                }
            }
        }
        // 远离后关门
        if (!openedDoors.isEmpty() && (level.getGameTime() & 15L) == 0L) {
            openedDoors.removeIf(pos -> {
                if (citizen.position().distanceToSqr(Vec3.atCenterOf(pos)) <= 12.25D) {
                    return false;
                }
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.OPEN)) {
                    door.setOpen(citizen, level, state, pos, false);
                }
                return true;
            });
        }
    }

    private void closeAllOpenedDoors(ServerLevel level) {
        if (level == null || openedDoors.isEmpty()) {
            return;
        }
        for (BlockPos pos : openedDoors) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            // 关门时门口有其他市民则跳过，防夹人
            AABB doorway = AABB.ofSize(Vec3.atCenterOf(pos), 1.5D, 2.0D, 1.5D);
            if (!level.getEntitiesOfClass(CitizenEntity.class, doorway, e -> e != citizen).isEmpty()) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.OPEN)) {
                door.setOpen(citizen, level, state, pos, false);
            }
        }
        openedDoors.clear();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private ServerLevel serverLevel() {
        return citizen.level() instanceof ServerLevel level ? level : null;
    }

    /** 闲逛移动速度系数：配置速度 / NSUK 基础移速属性(0.28) */
    private double speedFactor() {
        double base = citizen.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (base <= 0.01D) {
            base = 0.28D;
        }
        return net.minecraft.util.Mth.clamp(WanderConfig.walkSpeed() / base, 0.1D, 3.0D);
    }

    private void applyStepClimb(boolean enable) {
        AttributeInstance attr = citizen.getAttribute(Attributes.STEP_HEIGHT);
        if (attr == null) {
            return;
        }
        if (enable && WanderConfig.allowStepClimb()) {
            // 默认 0.6 → +0.4 = 1.0，允许翻越 1 格高差
            attr.addOrUpdateTransientModifier(
                    new AttributeModifier(STEP_MODIFIER_ID, 0.4D, AttributeModifier.Operation.ADD_VALUE));
        } else {
            attr.removeModifier(STEP_MODIFIER_ID);
        }
    }

    private boolean isNear(BlockPos pos, double distSqr) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dx = citizen.getX() - center.x;
        double dz = citizen.getZ() - center.z;
        double dy = citizen.getY() - center.y;
        return dx * dx + dz * dz <= distSqr && Math.abs(dy) <= 2.0D;
    }

    static boolean isNight(ServerLevel level) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        return t >= 13000L && t < 23000L;
    }

    /** 区块是否被至少一名玩家加载观看（未加载区块不运算） */
    static boolean isChunkPlayerWatched(ServerLevel level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        return !level.getChunkSource().chunkMap.getPlayers(chunkPos, false).isEmpty();
    }

    /** 收集目标点附近的建筑 POI（拆除检测用） */
    private static List<CityPoiData> collectWatchedPois(ServerLevel level, UUID cityId, BlockPos target) {
        List<CityPoiData> all = NsukApi.cityPois(level, cityId);
        if (all.isEmpty()) {
            return List.of();
        }
        List<CityPoiData> near = null;
        for (CityPoiData poi : all) {
            if (poi.active() && poi.pos().distSqr(target) <= 100.0D) {
                if (near == null) {
                    near = new ArrayList<>(2);
                }
                near.add(poi);
                if (near.size() >= 4) {
                    break;
                }
            }
        }
        return near == null ? List.of() : near;
    }

    private String shortId() {
        String name = citizen.getCitizenName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return uuid.toString().substring(0, 8);
    }
}
