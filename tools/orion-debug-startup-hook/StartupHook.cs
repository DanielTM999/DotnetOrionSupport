public sealed class StartupHook
{
    public static void Initialize()
    {
        string path = System.Environment.GetEnvironmentVariable("ORION_DEBUG_WAIT_FILE");
        if (string.IsNullOrEmpty(path) || !ShouldWaitInThisProcess())
        {
            return;
        }
        WriteProcessId();
        System.Environment.SetEnvironmentVariable("ORION_DEBUG_WAIT_FILE", null);
        System.Environment.SetEnvironmentVariable("ORION_DEBUG_PID_FILE", null);
        System.Environment.SetEnvironmentVariable("DOTNET_STARTUP_HOOKS", null);
        System.DateTime deadline = System.DateTime.UtcNow.AddSeconds(120);
        while (!System.IO.File.Exists(path) && System.DateTime.UtcNow < deadline)
        {
            System.Threading.Thread.Sleep(20);
        }
    }

    private static bool ShouldWaitInThisProcess()
    {
        string command = (System.Environment.CommandLine ?? string.Empty).ToLowerInvariant();
        string expected = (System.Environment.GetEnvironmentVariable("ORION_DEBUG_APP_ASSEMBLY") ?? string.Empty).ToLowerInvariant();
        if (!string.IsNullOrEmpty(expected))
        {
            return IsExpectedApplication(command, expected);
        }

        if (command.Contains(" watch ") || command.Contains(" watch\"")
                || command.Contains(" run ") || command.Contains(" run\"")
                || command.Contains(" build ") || command.Contains(" build\"")
                || command.Contains(" msbuild")
                || command.Contains("vbcscompiler")
                || command.Contains("vbcsc"))
        {
            return false;
        }
        return true;
    }

    private static bool IsExpectedApplication(string command, string expected)
    {
        try
        {
            string entry = System.Reflection.Assembly.GetEntryAssembly()?.GetName()?.Name;
            if (!string.IsNullOrEmpty(entry) && entry.ToLowerInvariant() == expected)
            {
                return true;
            }
        }
        catch
        {
        }
        return command.Contains(expected + ".dll") || command.Contains(expected + ".exe");
    }

    private static void WriteProcessId()
    {
        string pidFile = System.Environment.GetEnvironmentVariable("ORION_DEBUG_PID_FILE");
        if (string.IsNullOrEmpty(pidFile))
        {
            return;
        }
        try
        {
            object pid = typeof(System.Environment).GetProperty("ProcessId")?.GetValue(null);
            if (pid != null)
            {
                System.IO.File.WriteAllText(pidFile, pid.ToString());
            }
        }
        catch
        {
        }
    }
}
