package dtm.ide.run;

import java.nio.file.Path;

public interface DotnetDebugView {

    void onDebugStopped(Path file, int line, DebugExceptionInfo exceptionInfo);

    void onDebugCleared();

    void onDebugFinished();
}
