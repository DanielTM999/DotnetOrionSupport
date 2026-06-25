package dtm.ide.run;

public record DotnetHotReloadResult(Status status, String message) {

    public enum Status {
        APPLIED,
        NO_CHANGES,
        BLOCKED,
        ERROR
    }

    public static DotnetHotReloadResult applied(String message) {
        return new DotnetHotReloadResult(Status.APPLIED, message);
    }

    public static DotnetHotReloadResult noChanges(String message) {
        return new DotnetHotReloadResult(Status.NO_CHANGES, message);
    }

    public static DotnetHotReloadResult blocked(String message) {
        return new DotnetHotReloadResult(Status.BLOCKED, message);
    }

    public static DotnetHotReloadResult error(String message) {
        return new DotnetHotReloadResult(Status.ERROR, message);
    }
}