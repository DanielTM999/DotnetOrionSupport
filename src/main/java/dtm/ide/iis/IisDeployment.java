package dtm.ide.iis;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

@Slf4j
public final class IisDeployment {

    public record Target(String siteName, String applicationPath, String appPoolName,
                         Path contentRoot, IisBinding binding, boolean aspNetCore) {

        public boolean rootApplication() {
            return IisService.normalizePath(applicationPath).equals("/");
        }

        public String applicationName() {
            return siteName + IisService.normalizePath(applicationPath);
        }

        public String url() {
            String base = binding == null ? "http://localhost" : binding.url();
            return rootApplication() ? base : base + IisService.normalizePath(applicationPath);
        }
    }

    private static final long ICACLS_TIMEOUT_SECONDS = 120;

    private IisDeployment() {
    }

    public static IisService.Result ensure(Target target) {
        if (!IisService.available()) {
            return IisService.Result.fail("O IIS não está instalado nesta máquina.");
        }
        if (target.contentRoot() == null) {
            return IisService.Result.fail("Diretório de conteúdo indisponível para publicar no IIS.");
        }
        IisService.Result pool = ensureAppPool(target);
        if (!pool.success()) {
            return pool;
        }
        IisService.Result site = ensureSite(target);
        if (!site.success()) {
            return site;
        }
        if (!target.rootApplication()) {
            IisService.Result application = ensureApplication(target);
            if (!application.success()) {
                return application;
            }
        }
        grantPoolAccess(target);
        return IisService.Result.ok();
    }

    private static IisService.Result ensureAppPool(Target target) {
        IisAppPool existing = IisService.findAppPool(target.appPoolName());
        if (existing == null) {
            IisService.Result created = IisService.addAppPool(target.appPoolName(),
                    target.aspNetCore() ? IisAppPool.NO_MANAGED_CODE : "v4.0", "Integrated");
            if (!created.success()) {
                return created;
            }
        } else if (target.aspNetCore() && !existing.noManagedCode()) {
            IisService.Result updated = IisService.applyAppPoolSettings(target.appPoolName(),
                    new IisAppPool(existing.name(), existing.state(), IisAppPool.NO_MANAGED_CODE,
                            existing.managedPipelineMode(), existing.identityType(), existing.userName(),
                            existing.enable32Bit(), existing.startMode(), existing.autoStart(),
                            existing.queueLength(), existing.idleTimeoutMinutes(), existing.maxProcesses(),
                            existing.recyclingIntervalMinutes(), existing.recyclingPrivateMemoryKb(),
                            existing.recyclingVirtualMemoryKb(), existing.rawAttributes()));
            if (!updated.success()) {
                return updated;
            }
        }
        return IisService.Result.ok();
    }

    private static IisService.Result ensureSite(Target target) {
        IisSite site = IisService.findSite(target.siteName());
        if (site == null) {
            Path sitePath = target.rootApplication() ? target.contentRoot() : siteRootFor(target);
            IisService.Result created = IisService.addSite(target.siteName(), target.binding(), sitePath);
            if (!created.success()) {
                return created;
            }
            if (target.rootApplication()) {
                return IisService.setApplicationPool(target.siteName() + "/", target.appPoolName());
            }
            return IisService.Result.ok();
        }
        if (target.rootApplication()) {
            IisService.Result path = IisService.setSitePhysicalPath(target.siteName(), target.contentRoot());
            if (!path.success()) {
                return path;
            }
            IisService.Result pool = IisService.setApplicationPool(target.siteName() + "/", target.appPoolName());
            if (!pool.success()) {
                return pool;
            }
        }
        return ensureBinding(site, target.binding());
    }

    private static IisService.Result ensureBinding(IisSite site, IisBinding binding) {
        if (binding == null) {
            return IisService.Result.ok();
        }
        for (IisBinding existing : site.bindings()) {
            if (existing.descriptor().equalsIgnoreCase(binding.descriptor())) {
                return IisService.Result.ok();
            }
        }
        return IisService.addBinding(site.name(), binding);
    }

    private static IisService.Result ensureApplication(Target target) {
        IisApplication existing = IisService.findApplication(target.siteName(), target.applicationPath());
        if (existing == null) {
            return IisService.addApplication(target.siteName(), target.applicationPath(),
                    target.contentRoot(), target.appPoolName());
        }
        IisService.Result path = IisService.setVirtualDirectoryPath(
                target.applicationName() + "/", target.contentRoot());
        if (!path.success()) {
            return path;
        }
        return IisService.setApplicationPool(target.applicationName(), target.appPoolName());
    }

    private static Path siteRootFor(Target target) {
        Path parent = target.contentRoot().getParent();
        return parent == null ? target.contentRoot() : parent;
    }

    public static void grantPoolAccess(Target target) {
        if (target.contentRoot() == null) {
            return;
        }
        String identity = "IIS AppPool\\" + target.appPoolName();
        Path contentRoot = target.contentRoot().toAbsolutePath().normalize();
        IisProcess.Result result = IisBroker.run(IisBroker.Tool.ICACLS,
                List.of(contentRoot.toString(), "/grant", identity + ":(OI)(CI)RX", "/T", "/C", "/Q"),
                ICACLS_TIMEOUT_SECONDS);
        if (!result.ok()) {
            log.debug("Falha ao conceder permissões para {}: {}", identity, result.output());
        }
        grantTraverse(contentRoot.getParent(), identity);
    }

    private static void grantTraverse(Path directory, String identity) {
        for (Path current = directory; current != null && current.getParent() != null;
             current = current.getParent()) {
            IisProcess.Result granted = IisBroker.run(IisBroker.Tool.ICACLS,
                    List.of(current.toString(), "/grant", identity + ":(RX)", "/C", "/Q"),
                    ICACLS_TIMEOUT_SECONDS);
            if (!granted.ok()) {
                log.debug("Falha ao conceder travessia em {} para {}: {}", current, identity, granted.output());
            }
        }
    }

    public static IisService.Result start(Target target) {
        IisService.Result pool = IisService.startAppPoolStarted(target.appPoolName());
        IisService.Result site = IisService.startSite(target.siteName());
        if (!pool.success()) {
            return pool;
        }
        if (!site.success()) {
            return site;
        }
        return IisService.Result.ok();
    }

    public static IisService.Result stop(Target target) {
        return IisService.stopAppPool(target.appPoolName());
    }
}
