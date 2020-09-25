package com.evernym.integrationtests.e2e.tag.annotation;

import org.scalatest.TagAnnotation;
import java.lang.annotation.*;

@TagAnnotation("Integration")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Inherited
public @interface Integration {}