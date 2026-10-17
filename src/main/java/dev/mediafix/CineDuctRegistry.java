package dev.mediafix;

import dev.mediafix.screen.ScreenEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 注册表：目前只有屏幕实体。 */
public final class CineDuctRegistry {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CineDuct.MODID);

    /**
     * 屏幕实体。
     *
     * <p>包围盒固定成 2×1.5（只用于视锥剔除与射线判定 —— 屏幕本来就不可交互），
     * 实际渲染尺寸取自实体同步数据里的 width/height，所以非整格、各种比例的屏幕都能放。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ScreenEntity>> SCREEN =
            ENTITY_TYPES.register("screen", () -> EntityType.Builder
                    .<ScreenEntity>of(ScreenEntity::new, MobCategory.MISC)
                    .sized(2.0f, 1.5f)
                    .clientTrackingRange(16)
                    .updateInterval(2)
                    .build("screen"));

    private CineDuctRegistry() {
    }
}
