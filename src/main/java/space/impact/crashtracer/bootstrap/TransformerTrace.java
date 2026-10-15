package space.impact.crashtracer.bootstrap;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ILegacyClassTransformer;

/** Opt-in adapter: preserving a transformer's identity may matter to other coremods. */
public final class TransformerTrace implements IClassTransformer, ILegacyClassTransformer {
    private final IClassTransformer delegate;
    private static final ThreadLocal<Boolean> inside = new ThreadLocal<>();
    private static int exports;
    private static int maxExports = 64;
    private static int maxBytes = 4 * 1024 * 1024;

    private TransformerTrace(IClassTransformer delegate) { this.delegate = delegate; }

    public static void install(int files, int bytes) {
        try {
            maxExports = files; maxBytes = bytes;
            Field field = LaunchClassLoader.class.getDeclaredField("transformers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") List<IClassTransformer> original = (List<IClassTransformer>) field.get(Launch.classLoader);
            List<IClassTransformer> traced = new ArrayList<>();
            for (IClassTransformer transformer : original) {
                traced.add(adapt(transformer));
            }
            field.set(Launch.classLoader, traced);
            TracerBootstrap.event("ADAPTER", "transformers", Integer.toString(traced.size()), "ENABLED");
        } catch (Throwable error) {
            TracerBootstrap.event("ADAPTER", "transformers", error.getClass().getName(), "UNAVAILABLE");
        }
    }

    static IClassTransformer adapt(IClassTransformer transformer) {
        // Mixin discovers name remappers and its own proxies by interface/identity.
        if (transformer instanceof IClassNameTransformer || transformer instanceof ITransformer) return transformer;
        // LegacyTransformerHandle uses this marker to exclude metadata delegation.
        for (java.lang.annotation.Annotation annotation : transformer.getClass().getAnnotations()) {
            if ("javax.annotation.Resource".equals(annotation.annotationType().getName())) return transformer;
        }
        // Registry dispatchers (e.g. AE2 ApiRepairer) exclude themselves by concrete
        // type. Wrapping them makes their nested chain invoke them recursively.
        if (usesTransformerRegistry(transformer.getClass())) {
            TracerBootstrap.event("ADAPTER", transformer.getClass().getName(), "Transformer registry dispatcher; identity retained", "SKIPPED");
            return transformer;
        }
        return new TransformerTrace(transformer);
    }

    private static boolean usesTransformerRegistry(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            String resource = "/" + current.getName().replace('.', '/') + ".class";
            try (java.io.InputStream input = current.getResourceAsStream(resource)) {
                // Unknown/generated implementation: retain its identity conservatively.
                if (input == null) return true;
                ClassNode node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (instruction instanceof MethodInsnNode) {
                            MethodInsnNode call = (MethodInsnNode) instruction;
                            if ("net/minecraft/launchwrapper/LaunchClassLoader".equals(call.owner)
                                    && "getTransformers".equals(call.name)) return true;
                        }
                    }
                }
            } catch (Exception | LinkageError unavailable) { return true; }
        }
        return false;
    }

    @Override public String getName() { return delegate.getClass().getName(); }
    @Override public boolean isDelegationExcluded() { return false; }
    @Override public byte[] transformClassBytes(String name, String transformedName, byte[] input) {
        // Mixin metadata probes must retain original delegation and re-entrance semantics.
        return delegate.transform(name, transformedName, input);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] input) {
        if (Boolean.TRUE.equals(inside.get()) || (transformedName != null && transformedName.startsWith("space.impact.crashtracer."))) {
            return delegate.transform(name, transformedName, input);
        }
        inside.set(true);
        try {
            byte[] before = input;
            boolean comparable = input == null;
            try { if (input != null && input.length <= maxBytes) { before = input.clone(); comparable = true; } }
            catch (Throwable ignored) {}
            byte[] output;
            try { output = delegate.transform(name, transformedName, input); }
            catch (RuntimeException | Error error) {
                TracerBootstrap.event("TRANSFORM", transformedName, delegate.getClass().getName(), "FAILED:" + error.getClass().getName());
                try { TracerBootstrap.capture("Transformer " + delegate.getClass().getName() + " failed on " + transformedName, error, "asm-transformer"); }
                catch (Throwable ignored) {}
                throw error;
            }
            try {
                if (comparable && !Arrays.equals(before, output)) {
                    TracerBootstrap.event("TRANSFORM", transformedName, delegate.getClass().getName(),
                            "CHANGED:" + hash(before) + "->" + hash(output));
                    inspect(transformedName, output);
                    if (TracerBootstrap.getOptions().bytecodeExport) export(transformedName, output);
                }
            } catch (Throwable ignored) {}
            return output;
        } finally { inside.remove(); }
    }

    public String describe() {
        String source = "unknown";
        try { source = String.valueOf(delegate.getClass().getProtectionDomain().getCodeSource().getLocation()); }
        catch (Throwable ignored) {}
        return delegate.getClass().getName() + " @ " + source;
    }

    private static String hash(byte[] bytes) throws Exception {
        if (bytes == null) return "null";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) value.append(String.format("%02x", item & 255));
        return value.toString();
    }

    private static void inspect(String target, byte[] bytes) {
        if (bytes == null) return;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
            if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                if (!"Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;".equals(annotation.desc) || annotation.values == null) continue;
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if ("mixin".equals(annotation.values.get(i))) {
                        TracerBootstrap.event("MIXIN", target, String.valueOf(annotation.values.get(i + 1)), "MERGED_METHOD:" + method.name);
                    }
                }
            }
        }
    }

    private static synchronized void export(String target, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > maxBytes || exports >= maxExports) return;
        File session = TracerBootstrap.getSessionDirectory();
        if (session == null) return;
        File directory = new File(session, "bytecode");
        if (!directory.mkdirs() && !directory.isDirectory()) return;
        String safe = String.valueOf(target).replaceAll("[^a-zA-Z0-9_.-]", "_");
        if (safe.length() > 160) safe = safe.substring(0, 160);
        try (FileOutputStream output = new FileOutputStream(new File(directory, (++exports) + "-" + safe + ".class"))) { output.write(bytes); }
    }
}
