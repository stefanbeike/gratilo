package com.gratilo.observability;

import io.micronaut.aop.Around;
import java.lang.annotation.*;

@Around
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ObservedOperation {
    String value();
}
