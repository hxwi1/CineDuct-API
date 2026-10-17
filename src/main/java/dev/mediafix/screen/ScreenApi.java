package dev.mediafix.screen;

import dev.mediafix.CineDuct;
import dev.mediafix.CineDuctRegistry;
import dev.mediafix.api.CineDuctApi;
import dev.mediafix.api.MediaSourceResolver;
import dev.mediafix.api.ScreenHandle;
import dev.mediafix.api.ScreenFit;
import dev.mediafix.api.ScreenInfo;
import dev.mediafix.api.ScreenOptions;
import dev.mediafix.api.event.ScreenCreatedEvent;
import dev.mediafix.api.event.ScreenRemovedEvent;
import dev.mediafix.api.event.ScreenSourceChangedEvent;
import dev.mediafix.source.SourceResolvers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * {@link CineDuctApi} 的实现（内部类，附属 mod 只认 api 包里的接口）。
 *
 * <p>所有改动都自动调度到服务端线程：句柄本身只拿一个 UUID，
 * 每次调用按需在世界里找实体，所以附属 mod 从任意线程（包括网络线程）调用都不会把服务端状态弄乱。
 */
public final class ScreenApi implements CineDuctApi {

    @Override
    public ScreenHandle createScreen(ServerLevel level, Vec3 pos, float yaw, float pitch,
                                     float width, float height, String source) {
        return createScreen(level, ScreenOptions.builder()
                .position(pos).rotation(yaw, pitch).size(width, height).source(source).build());
    }

    @Override
    public ScreenHandle createScreen(ServerLevel level, ScreenOptions options) {
        final ScreenOptions opts = options == null ? ScreenOptions.builder().build() : options;
        if (level.getServer().isSameThread()) {
            return createNow(level, opts, null);
        }
        /*
         * 从别的线程（网络线程/工作线程）调进来：**不能**在这里直接改世界。
         * 先返回一个"预定好 UUID"的句柄，把真正的创建排到服务端线程去执行 ——
         * 附属 mod 拿到句柄后立刻用也没问题：句柄每次调用都是现找实体，创建完就能找到。
         */
        UUID reserved = UUID.randomUUID();
        level.getServer().execute(() -> createNow(level, opts, reserved));
        return new Handle(level, reserved);
    }

    private ScreenHandle createNow(ServerLevel level, ScreenOptions opts, UUID forcedId) {
        ScreenEntity screen = new ScreenEntity(CineDuctRegistry.SCREEN.get(), level);
        if (forcedId != null) screen.setUUID(forcedId);
        final Vec3 pos = opts.position() == null ? Vec3.ZERO : opts.position();
        screen.moveTo(pos.x, pos.y, pos.z, opts.yaw(), opts.pitch());
        // 先静音把一整批参数设好：这些 setter 会在实体进世界前触发，
        // 监听方那会儿还拿不到实体（见 ScreenEntity.setEventSilent）。
        screen.setEventSilent(true);
        screen.setScreenSize(opts.width(), opts.height());
        screen.setVolume(opts.volume());
        screen.setRange(opts.rangeMin(), opts.rangeMax());
        screen.setMuted(opts.muted());
        screen.setLoop(opts.loop());
        screen.setQualityCap(opts.qualityCap());
        screen.setAlpha(opts.alpha());
        screen.setBrightness(opts.brightness());
        screen.setFit(opts.fit());
        screen.setDoubleSided(opts.doubleSided());
        screen.setAudioModeOverride(opts.audioMode());
        if (opts.source() != null && !opts.source().isBlank()) {
            screen.setSourceUri(opts.source());   // 静音期间不发事件，源变更由下面的 Created 事件覆盖
        }
        screen.setPaused(opts.paused());
        if (opts.startAtMs() > 0L) screen.seekTo(opts.startAtMs());
        screen.setEventSilent(false);
        level.addFreshEntity(screen);
        ScreenHandle handle = handleFor(level, screen);
        NeoForge.EVENT_BUS.post(new ScreenCreatedEvent(level, handle, infoOf(screen)));
        CineDuct.LOGGER.info("[CineDuct] 创建屏幕 {}（{}×{}）源={}", screen.getUUID(),
                opts.width(), opts.height(), opts.source());
        return handle;
    }

    @Override
    public Optional<ScreenHandle> screen(UUID id) {
        for (ServerLevel level : serverOf() == null ? List.<ServerLevel>of() : serverOf().getAllLevels()) {
            ScreenEntity found = find(level, id);
            if (found != null) return Optional.of(handleFor(level, found));
        }
        return Optional.empty();
    }

    @Override
    public List<ScreenHandle> screens(Level level) {
        List<ScreenHandle> out = new ArrayList<>();
        if (level instanceof ServerLevel serverLevel) {
            for (ScreenEntity screen : allScreens(serverLevel)) {
                out.add(handleFor(serverLevel, screen));
            }
        }
        return out;
    }

    @Override
    public Optional<ScreenHandle> handleOf(Entity entity) {
        if (entity instanceof ScreenEntity screen && screen.level() instanceof ServerLevel serverLevel) {
            return Optional.of(handleFor(serverLevel, screen));
        }
        return Optional.empty();
    }

    @Override
    public void addResolver(MediaSourceResolver resolver) {
        SourceResolvers.register(resolver);
        CineDuct.LOGGER.info("[CineDuct] 已注册外部源解析器: {}", resolver.name());
    }

    @Override
    public List<String> resolverNames() {
        List<String> names = new ArrayList<>();
        for (MediaSourceResolver r : SourceResolvers.all()) names.add(r.name());
        return names;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 事件构造与句柄创建共用的入口。 */
    public static ScreenHandle handleFor(ServerLevel level, ScreenEntity screen) {
        return new Handle(level, screen.getUUID());
    }

    public static ScreenInfo infoOf(ScreenEntity screen) {
        return new ScreenInfo(screen.getUUID(), screen.sourceUri(),
                screen.getX(), screen.getY(), screen.getZ(), screen.getYRot(), screen.getXRot(),
                screen.screenWidth(), screen.screenHeight(), screen.aspect(),
                screen.isPaused(), screen.isLoop(), screen.isMuted(), screen.volume(),
                screen.rangeMin(), screen.rangeMax(), screen.projectedPositionMs(),
                screen.qualityCap(), screen.alpha(), screen.brightness(), screen.fit(),
                screen.isDoubleSided(), screen.audioModeOverride());
    }

    static ScreenEntity find(ServerLevel level, UUID id) {
        for (Entity e : level.getAllEntities()) {
            if (e instanceof ScreenEntity screen && screen.getUUID().equals(id)) return screen;
        }
        return null;
    }

    static List<ScreenEntity> allScreens(ServerLevel level) {
        List<ScreenEntity> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof ScreenEntity screen) out.add(screen);
        }
        return out;
    }

    private static net.minecraft.server.MinecraftServer serverOf() {
        return net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
    }

    /** 实际句柄：只拿 UUID，调用时现找实体。 */
    private static final class Handle implements ScreenHandle {

        private final ServerLevel level;
        private final UUID id;

        Handle(ServerLevel level, UUID id) {
            this.level = level;
            this.id = id;
        }

        private void with(Consumer<ScreenEntity> action) {
            Runnable task = () -> {
                ScreenEntity screen = find(this.level, this.id);
                if (screen == null) {
                    CineDuct.LOGGER.warn("[CineDuct] 屏幕 {} 已不存在，操作被忽略", this.id);
                    return;
                }
                action.accept(screen);
            };
            if (this.level.getServer().isSameThread()) {
                task.run();
            } else {
                this.level.getServer().execute(task);
            }
        }

        @Override
        public UUID id() {
            return this.id;
        }

        @Override
        public boolean isAlive() {
            return find(this.level, this.id) != null;
        }

        @Override
        public void source(String uri) {
            with(screen -> {
                String old = screen.sourceUri();
                screen.setSourceUri(uri == null ? "" : uri);
                screen.setPaused(false);
                NeoForge.EVENT_BUS.post(new ScreenSourceChangedEvent(this.level, this,
                        infoOf(screen), old, screen.sourceUri()));
            });
        }

        @Override
        public void play() {
            with(screen -> screen.setPaused(false));
        }

        @Override
        public void pause() {
            with(screen -> screen.setPaused(true));
        }

        @Override
        public void stop() {
            with(screen -> {
                screen.setPaused(true);
                screen.seekTo(0L);
            });
        }

        @Override
        public void seek(long ms) {
            with(screen -> screen.seekTo(ms));
        }

        @Override
        public void volume(int percent) {
            with(screen -> screen.setVolume(percent));
        }

        @Override
        public void range(float min, float max) {
            with(screen -> screen.setRange(min, max));
        }

        @Override
        public void loop(boolean on) {
            with(screen -> screen.setLoop(on));
        }

        @Override
        public void muted(boolean muted) {
            with(screen -> screen.setMuted(muted));
        }

        @Override
        public void size(float width, float height) {
            with(screen -> screen.setScreenSize(width, height));
        }

        @Override
        public void quality(int qn) {
            with(screen -> screen.setQualityCap(qn));
        }

        @Override
        public void audioMode(String mode) {
            with(screen -> screen.setAudioModeOverride(mode));
        }

        @Override
        public void setPosition(Vec3 pos) {
            with(screen -> screen.setCenter(pos));
        }

        @Override
        public void translate(double dx, double dy, double dz) {
            with(screen -> screen.translate(dx, dy, dz));
        }

        @Override
        public void rotation(float yaw, float pitch) {
            with(screen -> screen.setRotation(yaw, pitch));
        }

        @Override
        public void lookAt(Vec3 target) {
            with(screen -> screen.lookAt(target));
        }

        @Override
        public void alpha(float value) {
            with(screen -> screen.setAlpha(value));
        }

        @Override
        public void brightness(float value) {
            with(screen -> screen.setBrightness(value));
        }

        @Override
        public void fit(ScreenFit fit) {
            with(screen -> screen.setFit(fit));
        }

        @Override
        public void doubleSided(boolean value) {
            with(screen -> screen.setDoubleSided(value));
        }

        @Override
        public ScreenInfo info() {
            ScreenEntity screen = find(this.level, this.id);
            return screen == null ? null : infoOf(screen);
        }

        @Override
        public void remove() {
            with(screen -> {
                // 事件由 ScreenEntity.remove 统一发（不管是谁删的）
                screen.discard();
            });
        }
    }
}
