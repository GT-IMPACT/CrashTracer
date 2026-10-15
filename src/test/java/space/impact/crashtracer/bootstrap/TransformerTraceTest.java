package space.impact.crashtracer.bootstrap;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ILegacyClassTransformer;
import org.junit.Test;
import java.lang.reflect.Constructor;
import static org.junit.Assert.*;

public class TransformerTraceTest {
    private static class PlainTransformer implements IClassTransformer {
        public byte[] transform(String name, String mapped, byte[] bytes) { bytes[0]++; return bytes; }
    }
    private static class RegistryDispatcher implements IClassTransformer {
        public byte[] transform(String name, String mapped, byte[] bytes) {
            for (IClassTransformer transformer : Launch.classLoader.getTransformers()) {
                if (!(transformer instanceof RegistryDispatcher)) bytes = transformer.transform(name, mapped, bytes);
            }
            return bytes;
        }
    }

    @Test public void selfDispatchingTransformerKeepsIdentityAndDoesNotRecurse() throws Exception {
        LaunchClassLoader previous = Launch.classLoader;
        LaunchClassLoader loader = new LaunchClassLoader(new java.net.URL[0]);
        try {
            Launch.classLoader = loader;
            RegistryDispatcher dispatcher = new RegistryDispatcher();
            IClassTransformer adapted = TransformerTrace.adapt(dispatcher);
            assertSame(dispatcher, adapted);
            java.lang.reflect.Field field = LaunchClassLoader.class.getDeclaredField("transformers");
            field.setAccessible(true);
            field.set(loader, java.util.Arrays.asList(adapted, TransformerTrace.adapt(new PlainTransformer())));
            byte[] bytes = {1};
            assertSame(bytes, adapted.transform("probe.Registry", "probe.Registry", bytes));
            assertEquals(2, bytes[0]);
        } finally { Launch.classLoader = previous; loader.close(); }
    }

    private static class NameTransformer implements IClassTransformer, IClassNameTransformer {
        public byte[] transform(String name, String mapped, byte[] bytes) { return bytes; }
        public String remapClassName(String name) { return "mapped." + name; }
        public String unmapClassName(String name) { return "original." + name; }
    }
    private static class MixinTransformer implements IClassTransformer, ITransformer {
        public byte[] transform(String name, String mapped, byte[] bytes) { return bytes; }
        public String getName() { return "mixin.proxy"; }
        public boolean isDelegationExcluded() { return true; }
    }

    @Test public void nameRemapperAndMixinProxyRetainIdentityAndInterfaces() {
        NameTransformer remapper = new NameTransformer();
        MixinTransformer proxy = new MixinTransformer();
        assertSame(remapper, TransformerTrace.adapt(remapper));
        assertSame(proxy, TransformerTrace.adapt(proxy));
        assertEquals("original.Block", ((IClassNameTransformer) TransformerTrace.adapt(remapper)).unmapClassName("Block"));
        assertTrue(((ITransformer) TransformerTrace.adapt(proxy)).isDelegationExcluded());
    }

    @Test public void mixinMetadataDelegationKeepsOriginalTransformerNames() {
        IClassTransformer first = new NameTransformer() { };
        IClassTransformer plain = new PlainTransformer();
        IClassTransformer wrapped = TransformerTrace.adapt(plain);
        assertTrue(wrapped instanceof ILegacyClassTransformer);
        assertEquals(plain.getClass().getName(), ((ITransformer) wrapped).getName());
        assertFalse(((ITransformer) wrapped).isDelegationExcluded());
        byte[] bytes = {1, 2};
        assertSame(bytes, ((ILegacyClassTransformer) wrapped).transformClassBytes("x", "x", bytes));
        assertSame(first, TransformerTrace.adapt(first));
        assertSame(wrapped, TransformerTrace.adapt(wrapped));
    }

    private TransformerTrace wrap(IClassTransformer delegate) throws Exception {
        Constructor<TransformerTrace> constructor = TransformerTrace.class.getDeclaredConstructor(IClassTransformer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(delegate);
    }

    @Test public void inPlaceMutationRecordsDifferentHashesAndPreservesOutputIdentity() throws Exception {
        byte[] bytes = {1, 2, 3};
        TransformerTrace transformer = wrap((name, mapped, input) -> { input[0] = 9; return input; });
        assertSame(bytes, transformer.transform("example.HashProbe", "example.HashProbe", bytes));
        String[] event = TracerBootstrap.events().stream().filter(value -> value[2].equals("example.HashProbe")).findFirst().get();
        assertTrue(event[4].startsWith("CHANGED:"));
        String[] hashes = event[4].substring("CHANGED:".length()).split("->");
        assertEquals(64, hashes[0].length());
        assertEquals(64, hashes[1].length());
        assertNotEquals(hashes[0], hashes[1]);
    }

    @Test public void originalTransformerFailureIsRethrownUnchanged() throws Exception {
        VerifyError failure = new VerifyError("probe invalid bytecode");
        TransformerTrace transformer = wrap((name, mapped, input) -> { throw failure; });
        try {
            transformer.transform("example.FailedProbe", "example.FailedProbe", new byte[]{1});
            fail("Expected original error");
        } catch (VerifyError actual) { assertSame(failure, actual); }
        assertTrue(TracerBootstrap.events().stream().anyMatch(event -> event[2].equals("example.FailedProbe")
                && event[4].equals("FAILED:java.lang.VerifyError")));
    }

    @Test public void nullInputAndOutputRemainSupported() throws Exception {
        byte[] generated = {1};
        assertSame(generated, wrap((name, mapped, input) -> generated).transform("a", "example.Generated", null));
        assertNull(wrap((name, mapped, input) -> null).transform("a", "example.Removed", generated));
    }
}
