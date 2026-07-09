package com.fwdrobo.sirius.validation;

import com.fwdrobo.sirius.util.PathUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class LogicalPathValidator implements ConstraintValidator<ValidLogicalPath, String> {
    private static final int MAX_PATH_LENGTH = 513;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Base constraints are handled by @NotBlank and @Size on the annotation.
        if (value == null || value.isBlank() || value.length() > MAX_PATH_LENGTH) {
            return true;
        }
        try {
            // validation remains aligned with service-side path normalization rules.
            PathUtils.normalizePath(value);
        } catch (RuntimeException ex) {
            String msg = ex.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "路径格式不合法";
            }
            return reject(context, msg);
        }
        return true;
    }

    private boolean reject(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
