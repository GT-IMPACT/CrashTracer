package space.impact.crashtracer.mixins.early.bettercrashes;

import java.io.File;
import java.util.List;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.crash.CrashReport;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import space.impact.crashtracer.bootstrap.TracerBootstrap;
import space.impact.crashtracer.diagnostics.*;

/** Player-facing layer shared by BetterCrashes' in-game and startup error screens. */
@Pseudo
@Mixin(targets = "vfyjxf.bettercrashes.utils.GuiProblemScreen", remap = false)
public abstract class GuiProblemScreenMixin extends GuiScreen {
    @Shadow @Final protected CrashReport report;
    @Unique private File crashtracer$incident;
    @Unique private static final int CRASHTRACER_ARCHIVE = 1734001;
    @Unique private String crashtracer$folderError;

    @Inject(method = {"initGui", "func_73866_w_"}, at = @At("TAIL"), remap = false, require = 1)
    private void crashtracer$init(CallbackInfo ci) {
        crashtracer$attach();
    }

    @Unique private void crashtracer$attach() {
        try {
            crashtracer$incident = TracerBootstrap.incidentFor(report.getCrashCause());
            if (crashtracer$incident == null) return;
            TracerBootstrap.linkMinecraftReport(report.getCrashCause(), report.getFile());
            TracerRuntime.requestSupport(report.getCrashCause());
            buttonList.add(new GuiButton(CRASHTRACER_ARCHIVE, 0, 0, 280, 20, I18n.format("crashtracer.gui.preparing")));
        } catch (Throwable unavailable) { crashtracer$incident = null; }
    }

    @Inject(method = "getButtonY", at = @At("RETURN"), cancellable = true, remap = false, require = 1)
    private void crashtracer$reserveArchiveRow(CallbackInfoReturnable<Integer> ci) {
        // Also applies to the native return/quit and optional issue tracker buttons.
        if (TracerBootstrap.incidentFor(report.getCrashCause()) != null) ci.setReturnValue(ci.getReturnValue() - 24);
    }

    @ModifyArg(method = {"drawScreen", "func_73863_a"}, at = @At(value = "INVOKE",
            target = "Lvfyjxf/bettercrashes/utils/GuiProblemScreen;drawLongString(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;IIII)I",
            remap = false), index = 1, remap = false, require = 1)
    private String crashtracer$summary(String original) {
        if (crashtracer$incident == null) return original;
        try {
            BundleStatus state = SupportBundle.INSTANCE.state(crashtracer$incident);
            if (state == null) return I18n.format("crashtracer.gui.wait");
            List<String> lines = "ru".equals(I18n.format("crashtracer.language"))
                    ? state.getSummary().getRussian() : state.getSummary().getEnglish();
            String cause = lines.get(1);
            String suffix = " " + I18n.format("crashtracer.gui.send");
            String text = lines.get(0) + " " + cause + suffix;
            // Keep the existing compact layout usable with long mod names and small GUI sizes.
            while (fontRendererObj.listFormattedStringToWidth(text, 340).size() > 4 && cause.length() > 32) {
                cause = cause.substring(0, cause.length() - 8);
                text = lines.get(0) + " " + cause + "…" + suffix;
            }
            return text;
        } catch (Throwable unavailable) { return original; }
    }

    @Inject(method = {"drawScreen", "func_73863_a"}, at = @At("HEAD"), remap = false, require = 1)
    private void crashtracer$draw(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        // GuiInitErrorScreen overrides initGui without calling the base implementation.
        boolean attached = false;
        for (Object entry : buttonList) if (((GuiButton) entry).id == CRASHTRACER_ARCHIVE) attached = true;
        if (!attached) crashtracer$attach();
        if (crashtracer$incident == null) return;
        try {
            BundleStatus state = SupportBundle.INSTANCE.state(crashtracer$incident);
            for (Object entry : buttonList) {
                GuiButton button = (GuiButton) entry;
                if (button.id == CRASHTRACER_ARCHIVE) {
                    button.width = Math.min(280, width - 32); button.xPosition = (width - button.width) / 2; button.yPosition = height - 24;
                    button.enabled = state != null && (state.getArchive() != null || state.getFailed());
                    button.displayString = I18n.format(state != null && state.getArchive() != null ? "crashtracer.gui.open"
                            : state != null && state.getFailed() ? "crashtracer.gui.retry" : "crashtracer.gui.preparing");
                }
            }
        } catch (Throwable unavailable) { /* Preserve BetterCrashes rendering if the optional UI adapter fails. */ }
    }

    @Inject(method = {"drawScreen", "func_73863_a"}, at = @At("TAIL"), remap = false, require = 1)
    private void crashtracer$folderError(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (crashtracer$folderError != null) drawCenteredString(fontRendererObj,
                fontRendererObj.trimStringToWidth(crashtracer$folderError, width - 16), width / 2, height - 36, 0xFF9999);
    }

    @Inject(method = {"actionPerformed", "func_146284_a"}, at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void crashtracer$action(GuiButton button, CallbackInfo ci) {
        if (button.id != CRASHTRACER_ARCHIVE || crashtracer$incident == null) return;
        ci.cancel();
        BundleStatus state = SupportBundle.INSTANCE.state(crashtracer$incident);
        if (state == null || state.getArchive() == null) { TracerRuntime.requestSupport(report.getCrashCause()); return; }
        File folder = state.getArchive().getParentFile();
        try {
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(folder);
            else if (!org.lwjgl.Sys.openURL(folder.toURI().toString())) throw new java.io.IOException("Folder open failed");
            crashtracer$folderError = null;
        } catch (Exception failed) { crashtracer$folderError = I18n.format("crashtracer.gui.folderFailed") + " " + folder.getAbsolutePath(); }
    }
}
