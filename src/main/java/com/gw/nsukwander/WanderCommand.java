package com.gw.nsukwander;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 全局开关指令：/nsukwander on|off|status
 * <ul>
 *   <li>off：正在闲逛的市民立刻终止并返回住宅，不再生成新闲逛任务；状态持久化到 TOML；</li>
 *   <li>on：恢复闲逛触发；</li>
 *   <li>status：查看开关状态与当前闲逛人数。</li>
 * </ul>
 * 需要 OP 权限等级 2。
 */
@EventBusSubscriber(modid = NsukWanderMod.MOD_ID)
public final class WanderCommand {

    private WanderCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("nsukwander")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("on").executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setEnabled(ctx.getSource(), false)))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource()))));
    }

    private static int setEnabled(CommandSourceStack src, boolean enabled) {
        // 直接改写 TOML 配置项（NeoForge 会持久化并触发热重载）
        WanderConfig.GLOBAL_ENABLE.set(enabled);
        if (!enabled) {
            // 立刻终止所有进行中的闲逛并返回住宅
            WanderManager.terminateAll(TerminateReason.GLOBAL_OFF);
            src.sendSuccess(() -> Component.literal(
                    "[NSUK闲逛] 已全局关闭：进行中的闲逛将立刻终止并返回住宅，不再生成新任务。"), true);
        } else {
            src.sendSuccess(() -> Component.literal("[NSUK闲逛] 已全局开启。"), true);
        }
        WanderLogger.info("全局闲逛开关被 {} 切换为 {}", src.getTextName(), enabled);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        boolean enabled = WanderConfig.globalEnable();
        int active = WanderManager.activeCount();
        int tracked = 0;
        net.minecraft.server.level.ServerLevel overworld = src.getServer()
                .getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld != null) {
            tracked = WanderSavedData.get(overworld).trackedCount();
        }
        int finalTracked = tracked;
        src.sendSuccess(() -> Component.literal(String.format(
                "[NSUK闲逛] 开关=%s，进行中闲逛=%d（白天上限%d/夜晚上限%d），记录冷却市民数=%d，移动速度=%.2f",
                enabled ? "开" : "关", active,
                WanderConfig.maxDayCitizen(), WanderConfig.maxNightCitizen(),
                finalTracked, WanderConfig.walkSpeed())), false);
        return 1;
    }
}
