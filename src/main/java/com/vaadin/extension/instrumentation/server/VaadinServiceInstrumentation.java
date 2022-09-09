package com.vaadin.extension.instrumentation.server;

import static com.vaadin.extension.Constants.FLOW_VERSION;
import static com.vaadin.extension.Constants.REQUEST_TYPE;
import static com.vaadin.extension.Constants.SESSION_ID;
import static com.vaadin.extension.InstrumentationHelper.INSTRUMENTER;
import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.semconv.trace.attributes.SemanticAttributes.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.ContextKeys;
import com.vaadin.extension.InstrumentationRequest;
import com.vaadin.extension.conf.Configuration;
import com.vaadin.extension.conf.TraceLevel;
import com.vaadin.flow.server.HandlerHelper;
import com.vaadin.flow.server.HttpStatusCode;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.Version;
import com.vaadin.flow.shared.ApplicationConstants;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Instruments VaadinService to add the HTTP session id to the context, to be
 * retrieved by nested instrumentations.
 * <p>
 * Also sets the application RootSpan as this is the in point for handling the
 * incoming requests.
 */
public class VaadinServiceInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed("com.vaadin.flow.server.VaadinService");
    }

    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.vaadin.flow.server.VaadinService");
    }

    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("handleRequest"),
                this.getClass().getName() + "$MethodAdvice");
    }

    @SuppressWarnings("unused")
    public static class MethodAdvice {
        @Advice.OnMethodEnter()
        public static void onEnter(
                @Advice.Argument(0) VaadinRequest vaadinRequest,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {
            if (HandlerHelper.isRequestType(vaadinRequest,
                    HandlerHelper.RequestType.HEARTBEAT)
                    && !Configuration.isEnabled(TraceLevel.MAXIMUM)) {
                return;
            }
            String sessionId = vaadinRequest.getWrappedSession().getId();
            Map<String, String> spanMap = new HashMap<>();
            // Add semantic HTTP attributes
            HttpServletRequest servletRequest = ((HttpServletRequest) vaadinRequest);
            spanMap.put(HTTP_SCHEME.getKey(), servletRequest.getScheme());
            spanMap.put(HTTP_METHOD.getKey(), vaadinRequest.getMethod());
            spanMap.put(HTTP_HOST.getKey(), vaadinRequest.getRemoteHost());
            String httpTarget = servletRequest.getPathInfo();
            String queryString = servletRequest.getQueryString();
            if (queryString != null) {
                httpTarget += "?" + queryString;
            }
            spanMap.put(HTTP_TARGET.getKey(), httpTarget);
            spanMap.put(HTTP_ROUTE.getKey(), vaadinRequest.getPathInfo());

            // Add custom Vaadin attributes
            spanMap.put(SESSION_ID, sessionId);
            spanMap.put(REQUEST_TYPE, vaadinRequest
                    .getParameter(ApplicationConstants.REQUEST_TYPE_PARAMETER));
            spanMap.put(FLOW_VERSION, Version.getFullVersion());

            // Using instrumentation to get this as LocalRootSpan!
            InstrumentationRequest request = new InstrumentationRequest(
                    "Request handle", SpanKind.SERVER, spanMap);

            context = INSTRUMENTER.start(currentContext(), request);

            // TODO: add the key automatically to context with instrumenter
            scope = context.with(ContextKeys.SESSION_ID, sessionId)
                    .makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Argument(1) VaadinResponse vaadinResponse,
                @Advice.Local("otelContext") Context context,
                @Advice.Local("otelScope") Scope scope) {
            Span span = Span.fromContextOrNull(context);
            if (span != null) {
                span.setAttribute(HTTP_STATUS_CODE.getKey(),
                        ((HttpServletResponse) vaadinResponse).getStatus());
                if (((HttpServletResponse) vaadinResponse)
                        .getStatus() == HttpStatusCode.NOT_FOUND.getCode()) {
                    span.setStatus(StatusCode.ERROR, "Request was not handled");
                }
            }
            if (scope == null) {
                return;
            }
            scope.close();

            INSTRUMENTER.end(context, null, null, throwable);
        }
    }
}
