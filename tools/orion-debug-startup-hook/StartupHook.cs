internal sealed class StartupHook
{
    public static void Initialize()
    {
        string path = System.Environment.GetEnvironmentVariable("ORION_DEBUG_WAIT_FILE");
        if (string.IsNullOrEmpty(path))
        {
            return;
        }
        System.DateTime deadline = System.DateTime.UtcNow.AddSeconds(120);
        while (!System.IO.File.Exists(path) && System.DateTime.UtcNow < deadline)
        {
            System.Threading.Thread.Sleep(20);
        }
    }
}
