/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.opentelemetry;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.apache.camel.CamelContext;
import org.apache.camel.Component;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.NamedNode;
import org.apache.camel.Route;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.StaticService;
import org.apache.camel.api.management.ManagedAttribute;
import org.apache.camel.api.management.ManagedResource;
import org.apache.camel.opentelemetry.propagators.OpenTelemetryGetter;
import org.apache.camel.opentelemetry.propagators.OpenTelemetrySetter;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.CamelLogger;
import org.apache.camel.spi.CamelTracingService;
import org.apache.camel.spi.Configurer;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.spi.LogListener;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.spi.annotations.JdkService;
import org.apache.camel.support.CamelContextHelper;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.EndpointHelper;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.support.RoutePolicySupport;
import org.apache.camel.support.service.ServiceHelper;
import org.apache.camel.support.service.ServiceSupport;
import org.apache.camel.tracing.ExtractAdapter;
import org.apache.camel.tracing.InjectAdapter;
import org.apache.camel.tracing.SpanAdapter;
import org.apache.camel.tracing.SpanDecorator;
import org.apache.camel.util.ObjectHelper;
import org.apache.camel.util.StringHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.apache.camel.tracing.ActiveSpanManager.MDC_SPAN_ID;
import static org.apache.camel.tracing.ActiveSpanManager.MDC_TRACE_ID;

@JdkService("opentelemetry-tracer")
@Configurer
@ManagedResource(description = "OpenTelemetryTracer")
public class OpenTelemetryTracer extends ServiceSupport implements CamelTracingService, RoutePolicyFactory, StaticService {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTelemetryTracer.class);

    private Tracer tracer;
    private String instrumentationName = "camel";
    private ContextPropagators contextPropagators;
    private boolean traceProcessors;

    protected static final Map<String, SpanDecorator> DECORATORS = new HashMap<>();
    static final AutoCloseable NOOP_CLOSEABLE = () -> {
    };

    static {
        ServiceLoader.load(SpanDecorator.class).forEach(d -> {
            SpanDecorator existing = DECORATORS.get(d.getComponent());
            // Add span decorator if no existing decorator for the component,
            // or if derived from the existing decorator's class, allowing
            // custom decorators to be added if they extend the standard
            // decorators
            if (existing == null || existing.getClass().isInstance(d)) {
                DECORATORS.put(d.getComponent(), d);
            }
        });
    }

    protected boolean encoding;
    private final TracingLogListener logListener = new TracingLogListener();
    private final TracingEventNotifier eventNotifier = new TracingEventNotifier();
    private String excludePatterns;
    private InterceptStrategy tracingStrategy;
    private CamelContext camelContext;

    public Tracer getTracer() {
        return tracer;
    }

    public void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @ManagedAttribute(description = "A name uniquely identifying the instrumentation scope, such as the instrumentation library, package, or fully qualified class name")
    public String getInstrumentationName() {
        return instrumentationName;
    }

    /**
     * A name uniquely identifying the instrumentation scope, such as the instrumentation library, package, or fully
     * qualified class name. Must not be null.
     */
    public void setInstrumentationName(String instrumentationName) {
        this.instrumentationName = instrumentationName;
    }

    @ManagedAttribute(description = "Setting this to true will create new OpenTelemetry Spans for each Camel Processors")
    public boolean isTraceProcessors() {
        return traceProcessors;
    }

    /**
     * Setting this to true will create new OpenTelemetry Spans for each Camel Processors. Use the excludePattern
     * property to filter out Processors.
     */
    public void setTraceProcessors(boolean traceProcessors) {
        this.traceProcessors = traceProcessors;
    }

    public ContextPropagators getContextPropagators() {
        return contextPropagators;
    }

    public void setContextPropagators(ContextPropagators contextPropagators) {
        this.contextPropagators = contextPropagators;
    }

    /**
     * Returns the currently used tracing strategy which is responsible for tracking invoked EIP or beans.
     *
     * @return The currently used tracing strategy
     */
    public InterceptStrategy getTracingStrategy() {
        return tracingStrategy;
    }

    /**
     * Specifies the instance responsible for tracking invoked EIP and beans with Tracing.
     *
     * @param tracingStrategy The instance which tracks invoked EIP and beans
     */
    public void setTracingStrategy(InterceptStrategy tracingStrategy) {
        this.tracingStrategy = tracingStrategy;
    }

    public void addDecorator(SpanDecorator decorator) {
        DECORATORS.put(decorator.getComponent(), decorator);
    }

    @Override
    public CamelContext getCamelContext() {
        return camelContext;
    }

    @Override
    public void setCamelContext(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @ManagedAttribute
    public String getExcludePatterns() {
        return excludePatterns;
    }

    public void setExcludePatterns(String excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    @ManagedAttribute
    public boolean isEncoding() {
        return encoding;
    }

    public void setEncoding(boolean encoding) {
        this.encoding = encoding;
    }

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        init(camelContext);
        return new TracingRoutePolicy();
    }

    @Override
    protected void doInit() {
        ObjectHelper.notNull(camelContext, "CamelContext", this);

        camelContext.getManagementStrategy().addEventNotifier(eventNotifier);
        if (!camelContext.getRoutePolicyFactories().contains(this)) {
            camelContext.addRoutePolicyFactory(this);
        }
        camelContext.getCamelContextExtension().addLogListener(logListener);

        if (tracingStrategy != null) {
            camelContext.getCamelContextExtension().addInterceptStrategy(tracingStrategy);
        }
        initTracer();
        initContextPropagators();
        ServiceHelper.startService(eventNotifier);
    }

    /**
     * Registers this {@link org.apache.camel.tracing.Tracer} on the {@link CamelContext} if not already registered.
     */
    public void init(CamelContext camelContext) {
        if (!camelContext.hasService(this)) {
            try {
                // start this service eager so we init before Camel is starting up
                camelContext.addService(this, true, true);
            } catch (Exception e) {
                throw RuntimeCamelException.wrapRuntimeCamelException(e);
            }
        }
    }

    private SpanKind mapToSpanKind(org.apache.camel.tracing.SpanKind kind) {
        switch (kind) {
            case SPAN_KIND_CLIENT:
                return SpanKind.CLIENT;
            case SPAN_KIND_SERVER:
                return SpanKind.SERVER;
            case CONSUMER:
                return SpanKind.CONSUMER;
            case PRODUCER:
                return SpanKind.PRODUCER;
            default:
                return SpanKind.SERVER;
        }
    }

    protected void initTracer() {
        if (tracer == null) {
            tracer = CamelContextHelper.findSingleByType(getCamelContext(), Tracer.class);
        }
        if (tracer == null) {
            // GlobalOpenTelemetry.get() is always NotNull, falls back to OpenTelemetry.noop()
            tracer = GlobalOpenTelemetry.get().getTracer(instrumentationName);
        }
        if (traceProcessors && (getTracingStrategy() == null
                || getTracingStrategy().getClass().isAssignableFrom(NoopTracingStrategy.class))) {
            OpenTelemetryTracingStrategy tracingStrategy = new OpenTelemetryTracingStrategy(this);
            tracingStrategy.setPropagateContext(true);
            setTracingStrategy(tracingStrategy);
        }
    }

    protected void initContextPropagators() {
        if (contextPropagators == null) {
            contextPropagators = CamelContextHelper.findSingleByType(getCamelContext(), ContextPropagators.class);
        }
        if (contextPropagators == null) {
            // GlobalOpenTelemetry.get() is always NotNull, falls back to OpenTelemetry.noop()
            contextPropagators = GlobalOpenTelemetry.get().getPropagators();
        }
    }

    protected Context startSpan(Exchange exchange, SpanDecorator sd, Endpoint endpoint, SpanKind kind) {
        Holder holder = getHolder(exchange);
        String operationName = sd.getOperationName(exchange, endpoint);
        SpanBuilder builder = tracer.spanBuilder(operationName).setSpanKind(kind);
        Context context = null;
        if (holder != null) {
            context = holder.getContext();
        }
        if (context == null) {
            ExtractAdapter adapter = sd.getExtractAdapter(exchange.getIn().getHeaders(), encoding);
            context = GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator().extract(Context.root(), adapter,
                    new OpenTelemetryGetter(adapter));
        }
        if (context == null) {
            context = Context.root();
        }
        // start span
        Span span = builder.setParent(context).startSpan();
        // create new Context
        return context.with(span);
    }

    protected void inject(Holder holder, InjectAdapter adapter) {
        GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator().inject(holder.getContext(), adapter,
                new OpenTelemetrySetter());
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        LOG.info("OpenTelemetryTracer enabled using instrumentation-name: {}", instrumentationName);
    }

    @Override
    protected void doShutdown() {
        // stop event notifier
        camelContext.getManagementStrategy().removeEventNotifier(eventNotifier);
        ServiceHelper.stopService(eventNotifier);

        // remove route policy
        camelContext.getRoutePolicyFactories().remove(this);
    }

    protected SpanDecorator getSpanDecorator(Endpoint endpoint) {
        SpanDecorator sd = null;

        String uri = endpoint.getEndpointUri();
        String[] splitURI = StringHelper.splitOnCharacter(uri, ":", 2);
        if (splitURI[1] != null) {
            String scheme = splitURI[0];
            sd = DECORATORS.get(scheme);
        }
        if (sd == null && endpoint instanceof DefaultEndpoint de) {
            Component comp = de.getComponent();
            String fqn = comp.getClass().getName();
            // lookup via FQN
            sd = DECORATORS.values().stream().filter(d -> fqn.equals(d.getComponentClassName())).findFirst()
                    .orElse(null);
        }
        if (sd == null) {
            sd = SpanDecorator.DEFAULT;
        }

        return sd;
    }

    private boolean isExcluded(Exchange exchange, Endpoint endpoint) {
        String url = endpoint.getEndpointUri();
        if (url != null && excludePatterns != null) {
            for (String pattern : excludePatterns.split(",")) {
                pattern = pattern.trim();
                if (EndpointHelper.matchEndpoint(exchange.getContext(), url, pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    static OpenTelemetrySpanAdapter getAdapter(Holder holder) {
        return new OpenTelemetrySpanAdapter(holder);
    }

    static OpenTelemetrySpanAdapter getAdapter(Exchange exchange) {
        return getAdapter(getHolder(exchange));
    }

    private void finishSpan(Exchange exchange) {
        Holder holder = getHolder(exchange);
        if (holder != null) {
            OpenTelemetrySpanAdapter span = getAdapter(holder);
            span.getOpenTelemetrySpan().end();
            unsetHolder(exchange, holder);
        } else {
            LOG.warn("No span found in exchange {}", exchange);
        }
    }

    static void unsetHolder(Exchange exchange, Holder holder) {
        Holder parent = holder.getParent();
        exchange.setProperty(ExchangePropertyKey.ACTIVE_SPAN, parent);
        if (Boolean.TRUE.equals(exchange.getContext().isUseMDCLogging())) {
            if (parent != null) {
                SpanAdapter adapter = getAdapter(parent);
                MDC.put(MDC_TRACE_ID, adapter.traceId());
                MDC.put(MDC_SPAN_ID, adapter.spanId());
            } else {
                MDC.remove(MDC_TRACE_ID);
                MDC.remove(MDC_SPAN_ID);
            }
        }
    }

    public static Holder getHolder(Exchange exchange) {
        return exchange.getProperty(ExchangePropertyKey.ACTIVE_SPAN, Holder.class);
    }

    static void setHolder(Exchange exchange, Holder holder) {
        exchange.setProperty(ExchangePropertyKey.ACTIVE_SPAN, holder);
        Span span = Span.fromContext(holder.getContext());
        if (Boolean.TRUE.equals(exchange.getContext().isUseMDCLogging())) {
            MDC.put(MDC_TRACE_ID, span.getSpanContext().getTraceId());
            MDC.put(MDC_SPAN_ID, span.getSpanContext().getSpanId());
        }
    }

    private final class TracingEventNotifier extends EventNotifierSupport {

        public TracingEventNotifier() {
            // ignore these
            setIgnoreCamelContextEvents(true);
            setIgnoreCamelContextInitEvents(true);
            setIgnoreRouteEvents(true);
            // we need also async processing started events
            setIgnoreExchangeAsyncProcessingStartedEvents(false);
        }

        @Override
        public void notify(CamelEvent event) {
            try {
                if (event instanceof CamelEvent.ExchangeSendingEvent ese) {
                    SpanDecorator sd = getSpanDecorator(ese.getEndpoint());
                    Exchange exchange = ese.getExchange();
                    if (shouldExclude(sd, exchange, ese.getEndpoint())) {
                        return;
                    }
                    Context context = startSpan(exchange, sd, ese.getEndpoint(), mapToSpanKind(sd.getInitiatorSpanKind()));
                    Holder child = new Holder(getHolder(exchange), context);
                    setHolder(exchange, child);
                    SpanAdapter adapter = getAdapter(child);
                    sd.pre(adapter, exchange, ese.getEndpoint());
                    InjectAdapter injectAdapter = sd.getInjectAdapter(exchange.getIn().getHeaders(), encoding);
                    inject(child, injectAdapter);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Tracing: start client span: {}", Span.fromContext(context));
                    }
                } else if (event instanceof CamelEvent.ExchangeSentEvent ese) {
                    SpanDecorator sd = getSpanDecorator(ese.getEndpoint());
                    if (shouldExclude(sd, ese.getExchange(), ese.getEndpoint())) {
                        return;
                    }

                    Holder holder = getHolder(ese.getExchange());
                    if (holder != null) {
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Tracing: stop client context: {}", holder.getContext());
                        }
                        SpanAdapter adapter = getAdapter(holder);
                        sd.post(adapter, ese.getExchange(), ese.getEndpoint());
                        finishSpan(ese.getExchange());
                    } else {
                        LOG.warn("Tracing: could not find managed span for exchange: {}", ese.getExchange());
                    }
                } else if (event instanceof CamelEvent.ExchangeAsyncProcessingStartedEvent eap) {

                    // no need to filter scopes here. It's ok to close a scope multiple times and
                    // implementations check if the scope being disposed is current
                    // and should not do anything if scopes don't match.
                    //                    ActiveSpanManager.endScope(eap.getExchange());
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
        }

        private boolean shouldExclude(SpanDecorator sd, Exchange exchange, Endpoint endpoint) {
            return !sd.newSpan()
                    || isExcluded(exchange, endpoint);
        }
    }

    private final class TracingRoutePolicy extends RoutePolicySupport {

        @Override
        public void onExchangeBegin(Route route, Exchange exchange) {
            try {
                if (isExcluded(exchange, route.getEndpoint())) {
                    return;
                }
                SpanDecorator sd = getSpanDecorator(route.getEndpoint());
                SpanKind kind = getHolder(exchange) == null ? mapToSpanKind(sd.getReceiverSpanKind()) : SpanKind.INTERNAL;
                Context context = startSpan(exchange, sd, route.getEndpoint(), kind);
                Holder child = new Holder(getHolder(exchange), context);
                setHolder(exchange, child);
                SpanAdapter adapter = getAdapter(child);
                sd.pre(adapter, exchange, route.getEndpoint());
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Tracing: start server span={}", Span.fromContext(child.getContext()));
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
        }

        @Override
        public void onExchangeDone(Route route, Exchange exchange) {
            try {
                if (isExcluded(exchange, route.getEndpoint())) {
                    return;
                }
                SpanAdapter span = getAdapter(exchange);
                if (span != null) {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Tracing: finish server span={}", span);
                    }
                    SpanDecorator sd = getSpanDecorator(route.getEndpoint());
                    sd.post(span, exchange, route.getEndpoint());
                    finishSpan(exchange);
                } else {
                    LOG.warn("Tracing: could not find managed span for exchange: {}", exchange);
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
        }
    }

    private static final class TracingLogListener implements LogListener {

        @Override
        public String onLog(Exchange exchange, CamelLogger camelLogger, String message) {
            try {
                SpanAdapter span = getAdapter(exchange);
                if (span != null) {
                    Map<String, String> fields = new HashMap<>();
                    fields.put("message", message);
                    span.log(fields);
                }
            } catch (Exception t) {
                // This exception is ignored
                LOG.warn("Tracing: Failed to capture tracing data. This exception is ignored.", t);
            }
            return message;
        }
    }

    public static class Holder {
        private final Holder parent;

        private Context context;

        public Holder(Holder parent, Context context) {
            this.parent = parent;
            this.context = context;
        }

        public Holder getParent() {
            return parent;
        }

        public Context getContext() {
            return context;
        }

        public void setContext(Context context) {
            this.context = context;
        }

        public Baggage getBaggage() {
            return Baggage.fromContext(this.context);
        }

        public void setBaggage(Baggage baggage) {
            this.context = this.context.with(baggage);
        }
    }
}
