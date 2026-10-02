package io.github.kingsword09.kwebshell.electron.migration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/** Launches the real migration fixture in an app-bundle process with its normal test classpath. */
public final class MacBundleIntegrationLauncher {
    private static final String CLASS_PATH_PROPERTY = "kweb.migration.launch.classpath";
    private static final String FIXTURE_MAIN =
            "io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationIntegrationMainKt";

    private MacBundleIntegrationLauncher() {}

    public static void main(String[] arguments) throws Exception {
        final String classPath = System.getProperty(CLASS_PATH_PROPERTY);
        if (classPath == null || classPath.isBlank()) {
            throw new IllegalStateException("Missing macOS migration integration classpath.");
        }

        final URL[] entries = Arrays.stream(classPath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator"))))
                .map(Path::of)
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (java.net.MalformedURLException error) {
                        throw new IllegalArgumentException("Invalid macOS migration integration classpath entry.", error);
                    }
                })
                .toArray(URL[]::new);

        final ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(entries, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            final Class<?> fixture = Class.forName(FIXTURE_MAIN, true, loader);
            final Method main = fixture.getMethod("main", String[].class);
            main.invoke(null, (Object) arguments);
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("The macOS migration integration fixture failed.", cause);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
