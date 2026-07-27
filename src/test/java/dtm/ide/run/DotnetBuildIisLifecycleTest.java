package dtm.ide.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetBuildIisLifecycleTest {

    @Test
    void keepsDedicatedPoolRunningByDefault() {
        IisLaunchRequest request = IisLaunchRequest.builder()
                .dedicatedAppPool(true)
                .stopPoolOnExit(false)
                .build();

        assertFalse(DotnetBuild.shouldStopPoolOnExit(request));
    }

    @Test
    void stopsPoolOnlyWhenUserEnabledTheSetting() {
        IisLaunchRequest request = IisLaunchRequest.builder()
                .stopPoolOnExit(true)
                .build();

        assertTrue(DotnetBuild.shouldStopPoolOnExit(request));
    }
}
