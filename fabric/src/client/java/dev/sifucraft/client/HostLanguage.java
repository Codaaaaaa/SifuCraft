package dev.sifucraft.client;

import dev.sifucraft.ui.LanguageMatch;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs on the render thread, serializing culture changes with vanilla resource reloads. */
final class HostLanguage {
    private static final Logger LOG = LoggerFactory.getLogger("sifucraft");
    private final Set<String> unavailable = new HashSet<>();
    private CompletableFuture<Void> reload;
    private String failedTarget = "";

    void update(Minecraft mc, String culture) {
        if (culture == null || culture.isEmpty() || reload != null || mc.gui.overlay() != null || mc.level == null) return;
        var manager = mc.getLanguageManager();
        String target = LanguageMatch.resolve(culture, manager.getLanguages().keySet());
        if (target.isEmpty()) {
            if (unavailable.add(culture)) LOG.warn("[language] no installed translation for Sifu culture={}; keeping {}", culture, manager.getSelected());
            return;
        }
        if (!target.equals(failedTarget)) failedTarget = "";
        if (target.equals(manager.getSelected()) || target.equals(failedTarget)) return;
        String previous = manager.getSelected();
        String previousOption = mc.options.languageCode;
        manager.setSelected(target);
        mc.options.languageCode = target;
        LOG.info("[language] Sifu culture={} -> MC {} (was {})", culture, target, previous);
        reload = mc.reloadResourcePacks();
        reload.whenComplete((unused, error) -> mc.execute(() -> {
            reload = null;
            if (error != null) {
                failedTarget = target;
                manager.setSelected(previous);
                mc.options.languageCode = previousOption;
                LOG.error("[language] reload failed; restored language selection {}", previous, error);
                return;
            }
            mc.options.save();
            LOG.info("[language] applied {}; inventory='{}'", target, I18n.get("container.inventory"));
        }));
    }
}
