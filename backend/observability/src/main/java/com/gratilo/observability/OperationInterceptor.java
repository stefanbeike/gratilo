package com.gratilo.observability;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import java.util.Optional;

@Singleton
@InterceptorBean(ObservedOperation.class)
final class OperationInterceptor implements MethodInterceptor<Object, Object> {
    private final ObservationEvents events;

    OperationInterceptor(ObservationEvents events) {
        this.events = events;
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        String operation = context.stringValue(ObservedOperation.class).orElseThrow();
        long start = System.nanoTime();
        String outcome = "exception";
        try {
            Object result = context.proceed();
            outcome = switch (result) {
                case HttpResponse<?> response -> response.code() / 100 + "xx";
                case Optional<?> optional when optional.isEmpty() -> "empty";
                case Boolean success when !success -> "false";
                case null, default -> "returned";
            };
            return result;
        } finally {
            events.operation(operation, outcome, start);
        }
    }
}
