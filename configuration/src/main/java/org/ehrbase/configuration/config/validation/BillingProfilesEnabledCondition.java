/*
 * Copyright (c) 2024 vitasystems GmbH.
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehrbase.configuration.config.validation;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

class BillingProfilesEnabledCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ExternalValidationProperties properties = Binder.get(context.getEnvironment())
                .bind("validation.external-terminology", Bindable.of(ExternalValidationProperties.class))
                .orElseGet(ExternalValidationProperties::new);
        if (!properties.isEnabled()) {
            return ConditionOutcome.noMatch("External terminology validation is disabled");
        }
        boolean enabled = properties.getBillingProfiles().values().stream()
                .anyMatch(ExternalValidationProperties.BillingProfile::isEnabled);
        return enabled
                ? ConditionOutcome.match("At least one billing validation profile is enabled")
                : ConditionOutcome.noMatch("No billing validation profile is enabled");
    }
}
