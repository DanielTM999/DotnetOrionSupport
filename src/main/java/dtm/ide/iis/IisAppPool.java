package dtm.ide.iis;

import java.util.Map;

public record IisAppPool(String name,
                         String state,
                         String managedRuntimeVersion,
                         String managedPipelineMode,
                         String identityType,
                         String userName,
                         boolean enable32Bit,
                         String startMode,
                         String autoStart,
                         long queueLength,
                         long idleTimeoutMinutes,
                         long maxProcesses,
                         long recyclingIntervalMinutes,
                         long recyclingPrivateMemoryKb,
                         long recyclingVirtualMemoryKb,
                         Map<String, String> rawAttributes) {

    public static final String NO_MANAGED_CODE = "";

    public boolean started() {
        return "Started".equalsIgnoreCase(state);
    }

    public boolean noManagedCode() {
        return managedRuntimeVersion == null || managedRuntimeVersion.isBlank();
    }

    public String runtimeLabel() {
        return noManagedCode() ? "No Managed Code" : managedRuntimeVersion;
    }

    public String identityLabel() {
        if ("SpecificUser".equalsIgnoreCase(identityType)) {
            return userName == null || userName.isBlank() ? "SpecificUser" : userName;
        }
        return identityType == null || identityType.isBlank() ? "ApplicationPoolIdentity" : identityType;
    }
}
