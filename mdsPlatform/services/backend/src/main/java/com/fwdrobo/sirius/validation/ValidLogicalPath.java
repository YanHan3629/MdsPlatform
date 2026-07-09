package com.fwdrobo.sirius.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = LogicalPathValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@NotBlank(message = "路径不能为空")
@Size(max = 513, message = "路径太长")
public @interface ValidLogicalPath {
    String message() default "路径格式不合法";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}