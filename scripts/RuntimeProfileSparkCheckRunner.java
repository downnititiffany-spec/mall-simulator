import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Small reflection entry point for the WSL check script. It deliberately constructs no
 * Spring context and never reads a database or credential; checkSpark only launches
 * the explicitly supplied Spark CLI with --version.
 */
public final class RuntimeProfileSparkCheckRunner {
    public static void main(String[] args) throws Exception {
        String executable = requiredProperty("spark.submit.path");
        Path expectedClasses = Paths.get(requiredProperty("runtime.profile.expectedClasses")).toRealPath();

        Class<?> profileClass = Class.forName("com.graduation.analytics.runtime.entity.RuntimeProfile");
        Object profile = profileClass.getConstructor().newInstance();
        profileClass.getMethod("setType", String.class).invoke(profile, "SINGLE_NODE");
        profileClass.getMethod("setSparkSubmitPath", String.class).invoke(profile, executable);

        Class<?> mapperClass = Class.forName("com.graduation.analytics.runtime.mapper.RuntimeProfileMapper");
        Class<?> metricClass = Class.forName("com.graduation.analytics.metric.MetricStore");
        Class<?> credentialClass = Class.forName("com.graduation.analytics.runtime.credential.CredentialService");
        Class<?> serviceClass = Class.forName("com.graduation.analytics.runtime.RuntimeProfileServiceImpl");
        Path actualClasses = Paths.get(serviceClass.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
        if (!expectedClasses.equals(actualClasses)) {
            throw new IllegalStateException("RuntimeProfileServiceImpl did not load from current workspace classes: "
                    + actualClasses);
        }

        Object service = serviceClass.getConstructor(mapperClass, metricClass, credentialClass)
                .newInstance(null, null, null);
        Method check = serviceClass.getDeclaredMethod("checkSpark", profileClass);
        check.setAccessible(true);
        Object result;
        try {
            result = check.invoke(service, profile);
        } catch (InvocationTargetException e) {
            throw new AssertionError("checkSpark invocation failed", e.getCause());
        }

        Class<?> detailClass = Class.forName("com.graduation.analytics.runtime.RuntimeProfileService$CheckDetail");
        boolean applicable = (boolean) detailClass.getMethod("applicable").invoke(result);
        boolean passed = (boolean) detailClass.getMethod("passed").invoke(result);
        String detail = (String) detailClass.getMethod("detail").invoke(result);
        if (!applicable || !passed) throw new IllegalStateException("checkSpark failed: " + detail);
        if (detail.contains(executable) || detail.contains("spark-submit")) {
            throw new IllegalStateException("checkSpark exposed the executable path");
        }

        System.out.println("RuntimeProfileServiceImpl source=" + actualClasses);
        System.out.println("RuntimeProfile checkSpark passed; detail=" + detail);
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing -D" + key);
        return value;
    }
}
