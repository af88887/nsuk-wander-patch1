package com.gw.nsukwander;

import common.cn.kafei.simukraft.entity.CitizenEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge 事件订阅（GAME 总线）。
 * 所有入口仅做快速判定后转发给 {@link WanderManager}，不触碰 NSUK 内部逻辑。
 */
@EventBusSubscriber(modid = NsukWanderMod.MOD_ID)
public final class WanderEvents {

    private WanderEvents() {
    }

    /** 市民加入世界（旧存档加载 + 新生成都会触发）：仅主世界挂载闲逛 Goal */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD
                || !(event.getEntity() instanceof CitizenEntity citizen)) {
            return;
        }
        try {
            WanderManager.attachIfEligible(citizen);
        } catch (Throwable t) {
            WanderLogger.error("市民挂载闲逛 Goal 异常（已跳过）", t);
        }
    }

    /** 市民离开世界：卸载/跨维度/死亡的兜底清理，防任务泄漏 */
    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof CitizenEntity citizen)) {
            return;
        }
        try {
            TerminateReason reason = event.getLevel() instanceof ServerLevel level
                    && level.dimension() != Level.OVERWORLD
                    ? TerminateReason.DIMENSION_CHANGE : TerminateReason.CHUNK_UNLOAD;
            WanderManager.onEntityRemoved(citizen.getUUID(), reason);
        } catch (Throwable t) {
            WanderLogger.error("市民离场清理异常", t);
        }
    }

    /** 服务器 tick：重置每 tick 开启预算 + 周期维护 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        WanderManager.resetTickBudget();
        ServerLevel overworld = event.getServer().getLevel(Level.OVERWORLD);
        if (overworld != null) {
            WanderManager.periodicMaintenance(overworld, overworld.getGameTime());
        }
    }

    /** 遭遇攻击（怪物/玩家伤害）：立刻终止闲逛，交给 NSUK 生存 AI */
    @SubscribeEvent
    public static void onCitizenHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof CitizenEntity citizen) || citizen.level().isClientSide()) {
            return;
        }
        WanderManager.requestTerminate(citizen.getUUID(), TerminateReason.ATTACKED);
    }

    /** 玩家右键交互：立刻终止闲逛并返回住宅 */
    @SubscribeEvent
    public static void onCitizenInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof CitizenEntity citizen)) {
            return;
        }
        WanderManager.requestTerminate(citizen.getUUID(), TerminateReason.PLAYER_INTERACT);
    }

    /** 精确交互同样覆盖（部分交互只触发 Specific） */
    @SubscribeEvent
    public static void onCitizenInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof CitizenEntity citizen)) {
            return;
        }
        WanderManager.requestTerminate(citizen.getUUID(), TerminateReason.PLAYER_INTERACT);
    }

    /** 存档卸载：清空运行态 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        WanderManager.clearAll();
    }

    /** 服务器停止：清空运行态 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WanderManager.clearAll();
    }
}
