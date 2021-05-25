package com.splunk.rum;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class CrashReporterTest {
    @Rule
    public OpenTelemetryRule otelTesting = OpenTelemetryRule.create();
    private Tracer tracer;

    @Before
    public void setup() {
        tracer = otelTesting.getOpenTelemetry().getTracer("testTracer");
    }

    @Test
    public void crashReportingSpan() {
        TestDelegateHandler existingHandler = new TestDelegateHandler();
        SdkTracerProvider sdkTracerProvider = mock(SdkTracerProvider.class);
        CrashReporter.CrashReportingExceptionHandler crashReporter = new CrashReporter.CrashReportingExceptionHandler(tracer, sdkTracerProvider, existingHandler);

        NullPointerException oopsie = new NullPointerException("oopsie");
        Thread crashThread = new Thread("badThread");

        crashReporter.uncaughtException(crashThread, oopsie);

        List<SpanData> spans = otelTesting.getSpans();
        assertEquals(1, spans.size());
        SpanData crashSpan = spans.get(0);
        assertEquals("crash.report", crashSpan.getName());
        assertEquals(crashThread.getId(), (long) crashSpan.getAttributes().get(SemanticAttributes.THREAD_ID));
        assertEquals(SplunkRum.COMPONENT_ERROR, crashSpan.getAttributes().get(SplunkRum.COMPONENT_KEY));
        assertEquals("badThread", crashSpan.getAttributes().get(SemanticAttributes.THREAD_NAME));
        assertTrue(crashSpan.getAttributes().get(SemanticAttributes.EXCEPTION_ESCAPED));

        assertNotNull(crashSpan.getAttributes().get(SemanticAttributes.EXCEPTION_STACKTRACE));
        assertTrue(crashSpan.getAttributes().get(SemanticAttributes.EXCEPTION_STACKTRACE).contains("NullPointerException"));
        assertTrue(crashSpan.getAttributes().get(SemanticAttributes.EXCEPTION_STACKTRACE).contains("oopsie"));

        assertEquals(StatusCode.ERROR, crashSpan.getStatus().getStatusCode());

        assertTrue(existingHandler.wasDelegatedTo.get());
        verify(sdkTracerProvider).forceFlush();
    }

    private static class TestDelegateHandler implements Thread.UncaughtExceptionHandler {
        final AtomicBoolean wasDelegatedTo = new AtomicBoolean(false);

        @Override
        public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
            wasDelegatedTo.set(true);
        }
    }
}