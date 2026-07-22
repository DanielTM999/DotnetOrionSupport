package dtm.ide.iis;

public record IisWorkerProcess(long pid, String appPoolName) {

    public boolean alive() {
        return pid > 0 && ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent();
    }
}
