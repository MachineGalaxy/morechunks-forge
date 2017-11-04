package gjum.minecraft.forge.morechunks;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Env implements IEnv {
    private static final Logger logger = LogManager.getLogger();

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public void log(Level level, String format, Object... args) {
        StackTraceElement[] stacktrace = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stacktrace[2];
        String[] classPath = caller.getClassName().split("\\.");
        String className = classPath[classPath.length - 1];
        String methodName = caller.getMethodName();
        int lineNr = caller.getLineNumber();

        format = String.format("[%s.%s@%s] %s", className, methodName, lineNr, format);
        String msg = String.format(format, args);

        logger.log(level, msg);
    }
}
