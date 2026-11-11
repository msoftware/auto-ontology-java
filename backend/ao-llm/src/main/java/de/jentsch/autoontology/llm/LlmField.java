// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Field description of a structured LLM output record, taken over from the Pydantic models of the
 * original (FR-LLM-03). {@code nullable} renders as {@code ["type", "null"]}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface LlmField {
    String value() default "";

    boolean nullable() default false;
}
