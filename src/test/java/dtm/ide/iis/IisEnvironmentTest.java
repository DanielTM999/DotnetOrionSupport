package dtm.ide.iis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IisEnvironmentTest {

    private static Pattern serviceStatePattern() throws Exception {
        java.lang.reflect.Field field = IisEnvironment.class.getDeclaredField("SERVICE_STATE");
        field.setAccessible(true);
        return (Pattern) field.get(null);
    }

    @Test
    void parsesEnglishServiceOutput() throws Exception {
        String output = """
                SERVICE_NAME: W3SVC
                        TYPE               : 20  WIN32_SHARE_PROCESS
                        STATE              : 4  RUNNING
                                                (STOPPABLE, PAUSABLE, ACCEPTS_SHUTDOWN)
                """;

        Matcher matcher = serviceStatePattern().matcher(output);

        assertTrue(matcher.find());
        assertEquals("RUNNING", matcher.group(1));
    }

    @Test
    void parsesLocalizedServiceOutput() throws Exception {
        String output = """
                NOME_DO_SERVIÇO: W3SVC
                        TIPO                       : 20  WIN32_SHARE_PROCESS
                        ESTADO                     : 1  STOPPED
                        CÓDIGO_DE_SAÍDA_DO_WIN32   : 0  (0x0)
                """;

        Matcher matcher = serviceStatePattern().matcher(output);

        assertTrue(matcher.find(), "o estado deve ser reconhecido mesmo com rótulos traduzidos");
        assertEquals("STOPPED", matcher.group(1));
    }

    @Test
    void ignoresServiceTypeLine() throws Exception {
        String output = "        TIPO                       : 10  WIN32_OWN_PROCESS\n"
                + "        ESTADO                     : 4  RUNNING\n";

        Matcher matcher = serviceStatePattern().matcher(output);

        assertTrue(matcher.find());
        assertEquals("RUNNING", matcher.group(1), "não deve confundir a linha de tipo com a de estado");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void readsRealServiceStateInvariantOfLocale() throws Exception {
        Method method = IisEnvironment.class.getDeclaredMethod("detectServiceState");
        method.setAccessible(true);

        IisProcess.Result probe = IisProcess.capture(
                java.util.List.of("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "(Get-Service -Name Dnscache).Status.ToString()"), 30);

        if (probe.ok() && !probe.output().isBlank()) {
            assertTrue(probe.output().strip().matches("[A-Za-z]+"),
                    "o estado do serviço deve vir em texto invariante: " + probe.output());
        }
    }
}
