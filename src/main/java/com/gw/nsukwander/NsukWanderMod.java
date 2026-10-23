package com.gw.nsukwander;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * NSUK 闲逛补丁主类。
 *
 * <p>设计原则：
 * <ul>
 *   <li>不 Mixin、不改写 NSUK 任何字节码，仅通过 NeoForge 事件总线 + NSUK 公开静态 API 集成；</li>
 *   <li>闲逛驱动挂在实体 goalSelector（优先级 5），NSUK 自身 AI（0~2）优先级天然更高；</li>
 *   <li>NSUK 生存/导航服务一旦接管（isNavigating），本补丁立刻让行。</li>
 * </ul>
 */
@Mod(NsukWanderMod.MOD_ID)
public final class NsukWanderMod {

    public static final String MOD_ID = "nsukwander";

    public NsukWanderMod(ModContainer container) {
        // COMMON 配置：config/nsukwander-common.toml
        container.registerConfig(ModConfig.Type.COMMON, WanderConfig.SPEC);
    }
}
