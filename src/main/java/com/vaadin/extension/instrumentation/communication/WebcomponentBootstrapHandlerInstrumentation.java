package com.vaadin.extension.instrumentation.communication;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.vaadin.extension.InstrumentationHelper;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments WebComponentBootstrapHandler to add a span for its execution.
 * Updates main span to reflect serviceUrl for the webcomponent.
 */
public class WebcomponentBootstrapHandlerInstrumentation
        implements TypeInstrumentation {

    static final String REQ_PARAM_URL = "url";
    static final String PATH_PREFIX = "/web-component/web-component";

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
        return hasClassesNamed(
                "com.vaadin.flow.server.communication.WebComponentBootstrapHandler");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(
                "com.vaadin.flow.server.communication.WebComponentBootstrapHandler");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("synchronizedHandleRequest"),
                this.getClass().getName() + "$SynchronizedHandleRequestAdvice");
    }

    @SuppressWarnings("unused")
    public static class SynchronizedHandleRequestAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(1) VaadinRequest request,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            span = InstrumentationHelper
                    .startSpan("WebComponentBootstrapHandler");

            // Rewrite root span to contain route, as web component request is
            // always against application root with a location parameter
            Span localRootSpan = LocalRootSpan
                    .fromContextOrNull(Context.current());
            if (localRootSpan != null) {

                // get service url from 'url' parameter
                String url = request.getParameter(REQ_PARAM_URL);
                // if 'url' parameter was not available, use request url
                if (url == null) {
                    url = ((VaadinServletRequest) request).getRequestURL()
                            .toString();
                }
                url
                        // +1 is to keep the trailing slash
                        .substring(0, url.indexOf(PATH_PREFIX) + 1)
                        // replace 'http://' or 'https://' with '//' to work
                        // with
                        // 'https://' proxies which proxies to the same
                        // 'http://' url
                        .replaceFirst("^" + ".*://", "//");

                localRootSpan.updateName("/ : WebComponentBootstrap");
                localRootSpan.setAttribute("vaadin.webcomponent.url", url);
            }

            Context context = currentContext().with(span);
            scope = context.makeCurrent();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(@Advice.Thrown Throwable throwable,
                @Advice.Local("otelSpan") Span span,
                @Advice.Local("otelScope") Scope scope) {
            InstrumentationHelper.endSpan(span, throwable, scope);
        }
    }
}
