package space.impact.crashtracer.bootstrap;

import org.junit.Test;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler.ErrorAction;
import space.impact.crashtracer.mixinplugin.TracerMixinErrors;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class MixinErrorHandlerTest {
    @Test public void prepareAndApplyFailuresPreserveTheOriginalAction() {
        TracerMixinErrors handler = new TracerMixinErrors();
        IMixinConfig config = (IMixinConfig) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IMixinConfig.class},
                (proxy, method, args) -> method.getName().equals("getName") ? "probe.mixins.json" : null);
        for (ErrorAction action : ErrorAction.values()) {
            assertSame(action, handler.onPrepareError(config, new IllegalStateException("prepare"), null, action));
            assertSame(action, handler.onApplyError("example.MixinTarget", new VerifyError("apply"), null, action));
        }
        assertTrue(TracerBootstrap.events().stream().anyMatch(event -> event[1].equals("MIXIN")
                && event[2].equals("probe.mixins.json") && event[4].equals("PREPARE_FAILED")));
        assertTrue(TracerBootstrap.events().stream().anyMatch(event -> event[1].equals("MIXIN")
                && event[2].equals("example.MixinTarget") && event[4].equals("APPLY_FAILED")));
    }
}
