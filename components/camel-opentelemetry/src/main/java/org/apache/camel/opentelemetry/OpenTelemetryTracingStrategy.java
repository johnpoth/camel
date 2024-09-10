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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Processor;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.support.PatternHelper;
import org.apache.camel.support.processor.DelegateAsyncProcessor;
import org.apache.camel.tracing.SpanDecorator;

import static org.apache.camel.opentelemetry.OpenTelemetryTracer.Holder;
import static org.apache.camel.opentelemetry.OpenTelemetryTracer.getHolder;
import static org.apache.camel.opentelemetry.OpenTelemetryTracer.setHolder;
import static org.apache.camel.opentelemetry.OpenTelemetryTracer.unsetHolder;

public class OpenTelemetryTracingStrategy implements InterceptStrategy {

    private static final String UNNAMED = "unnamed";

    private final OpenTelemetryTracer tracer;
    private boolean propagateContext;

    public OpenTelemetryTracingStrategy(OpenTelemetryTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Processor wrapProcessorInInterceptors(
            CamelContext camelContext,
            NamedNode processorDefinition, Processor target, Processor nextTarget)
            throws Exception {
        if (shouldTrace(processorDefinition)) {
            return new PropagateContextAndCreateSpan(processorDefinition, target);
        } else {
            return new DelegateAsyncProcessor(target);
        }
    }

    public boolean isPropagateContext() {
        return propagateContext;
    }

    public void setPropagateContext(boolean propagateContext) {
        this.propagateContext = propagateContext;
    }

    private class PropagateContextAndCreateSpan implements Processor {
        private final NamedNode processorDefinition;
        private final Processor target;

        public PropagateContextAndCreateSpan(NamedNode processorDefinition, Processor target) {
            this.processorDefinition = processorDefinition;
            this.target = target;
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            Context context = null;
            Holder holder = getHolder(exchange);
            if (holder != null) {
                context = holder.getContext();
            }

            if (context == null) {
                context = Context.root();
            }

            final Span processorSpan = tracer.getTracer().spanBuilder(getOperationName(processorDefinition))
                    .setParent(context)
                    .setAttribute("component", getComponentName(processorDefinition))
                    .startSpan();

            boolean activateExchange = !(target instanceof GetCorrelationContextProcessor
                    || target instanceof SetCorrelationContextProcessor);

            Holder child = new Holder(holder, context.with(processorSpan));
            if (activateExchange) {
                setHolder(exchange, child);
            }

            try (Scope ignored = processorSpan.makeCurrent()) {
                target.process(exchange);
            } catch (Exception ex) {
                processorSpan.setStatus(StatusCode.ERROR);
                processorSpan.recordException(ex);
                throw ex;
            } finally {
                if (activateExchange) {
                    unsetHolder(exchange, child);
                }
                processorSpan.end();
            }
        }
    }

    private static String getComponentName(NamedNode processorDefinition) {
        return SpanDecorator.CAMEL_COMPONENT + processorDefinition.getShortName();
    }

    private static String getOperationName(NamedNode processorDefinition) {
        final String name = processorDefinition.getId();
        return name == null ? UNNAMED : name;
    }

    // Adapted from org.apache.camel.impl.engine.DefaultTracer.shouldTrace
    // org.apache.camel.impl.engine.DefaultTracer.shouldTracePattern
    private boolean shouldTrace(NamedNode definition) {
        if (tracer.getExcludePatterns() != null) {
            for (String pattern : tracer.getExcludePatterns().split(",")) {
                pattern = pattern.trim();
                // use matchPattern method from endpoint helper that has a good matcher we use in Camel
                if (PatternHelper.matchPattern(definition.getId(), pattern)) {
                    return false;
                }
            }
        }

        return true;
    }
}
