package com.autocve.agent;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

import java.util.Arrays;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Offline launcher used when Maven Central is unavailable during local verification. */
public final class TestSuiteLauncher {

    private TestSuiteLauncher() {}

    public static void main(String[] args) {
        var builder = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectPackage("com.autocve.agent"));
        if (Arrays.asList(args).contains("--no-web")) {
            builder.filters(ClassNameFilter.excludeClassNamePatterns(
                    ".*HealthEndpointIntegrationTest"));
        }
        LauncherDiscoveryRequest request = builder.build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out, true));
        if (listener.getSummary().getTotalFailureCount() > 0) {
            listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.err, true));
            throw new IllegalStateException(
                    "Java test suite failed: " + listener.getSummary().getTotalFailureCount());
        }
    }
}
