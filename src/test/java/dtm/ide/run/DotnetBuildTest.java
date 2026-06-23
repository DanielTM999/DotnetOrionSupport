package dtm.ide.run;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotnetBuildTest {

    @Test
    void parsesIndentedTestNamesAndIgnoresBanner() {
        String output = String.join("\n",
                "Microsoft (R) Test Execution Command Line Tool Version 17.8.0",
                "Copyright (c) Microsoft Corporation. All rights reserved.",
                "",
                "The following Tests are available:",
                "    MyApp.Tests.CalculatorTests.Adds",
                "    MyApp.Tests.CalculatorTests.Subtracts",
                "    MyApp.Tests.StringTests.Trims(value: \"a b\")");

        List<String> tests = DotnetBuild.parseTestList(output);

        assertEquals(List.of(
                "MyApp.Tests.CalculatorTests.Adds",
                "MyApp.Tests.CalculatorTests.Subtracts",
                "MyApp.Tests.StringTests.Trims(value: \"a b\")"), tests);
    }

    @Test
    void deduplicatesAndSkipsNonQualifiedLines() {
        String output = String.join("\n",
                "    MyApp.Tests.A.One",
                "    MyApp.Tests.A.One",
                "NotIndented.Skip.Me",
                "    PlainNameWithoutDot");

        List<String> tests = DotnetBuild.parseTestList(output);

        assertEquals(List.of("MyApp.Tests.A.One"), tests);
        assertTrue(DotnetBuild.parseTestList("").isEmpty());
        assertTrue(DotnetBuild.parseTestList(null).isEmpty());
    }
}
