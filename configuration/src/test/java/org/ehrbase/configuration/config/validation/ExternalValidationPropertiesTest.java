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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.ehrbase.cache.CacheProvider;
import org.ehrbase.service.validation.BillingCodeValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ExternalValidationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationConfiguration.class)
            .withBean(CacheProvider.class, () -> mock(CacheProvider.class));

    @Test
    void billingProfilesBindAndCreateValidator() {
        contextRunner
                .withPropertyValues(
                        "validation.external-terminology.enabled=true",
                        "validation.external-terminology.provider.fhir.type=FHIR",
                        "validation.external-terminology.provider.fhir.url=http://terminology.local",
                        "validation.external-terminology.billing-profiles.claims.template-ids[0]=billing.claim.v1",
                        "validation.external-terminology.billing-profiles.claims.code-systems[0]=http://hl7.org/fhir/sid/icd-10-cm")
                .run(context -> {
                    BillingCodeValidator validator = context.getBean(BillingCodeValidator.class);
                    assertThat(validator.findProfile("billing.claim.v1"))
                            .isPresent()
                            .get()
                            .extracting(profile -> profile.name())
                            .isEqualTo("claims");
                });
    }

    @Test
    void disabledProfileDoesNotCreateBillingValidator() {
        contextRunner
                .withPropertyValues(
                        "validation.external-terminology.enabled=true",
                        "validation.external-terminology.provider.fhir.type=FHIR",
                        "validation.external-terminology.provider.fhir.url=http://terminology.local",
                        "validation.external-terminology.billing-profiles.claims.enabled=false",
                        "validation.external-terminology.billing-profiles.claims.template-ids[0]=billing.claim.v1",
                        "validation.external-terminology.billing-profiles.claims.code-systems[0]=http://hl7.org/fhir/sid/icd-10-cm")
                .run(context -> assertThat(context).doesNotHaveBean(BillingCodeValidator.class));
    }

    @Test
    void unknownProviderFailsContext() {
        contextRunner
                .withPropertyValues(
                        "validation.external-terminology.enabled=true",
                        "validation.external-terminology.provider.fhir.type=FHIR",
                        "validation.external-terminology.provider.fhir.url=http://terminology.local",
                        "validation.external-terminology.billing-profiles.claims.provider=missing",
                        "validation.external-terminology.billing-profiles.claims.template-ids[0]=billing.claim.v1",
                        "validation.external-terminology.billing-profiles.claims.code-systems[0]=http://hl7.org/fhir/sid/icd-10-cm")
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("missing"));
    }

    @Test
    void multipleProvidersRequireExplicitProvider() {
        contextRunner
                .withPropertyValues(
                        "validation.external-terminology.enabled=true",
                        "validation.external-terminology.provider.fhir.type=FHIR",
                        "validation.external-terminology.provider.fhir.url=http://terminology.local",
                        "validation.external-terminology.provider.other.type=FHIR",
                        "validation.external-terminology.provider.other.url=http://terminology.other",
                        "validation.external-terminology.billing-profiles.claims.template-ids[0]=billing.claim.v1",
                        "validation.external-terminology.billing-profiles.claims.code-systems[0]=http://hl7.org/fhir/sid/icd-10-cm")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("must specify an explicit terminology provider"));
    }

    @Test
    void missingTemplateIdsFailsContext() {
        contextRunner
                .withPropertyValues(
                        "validation.external-terminology.enabled=true",
                        "validation.external-terminology.provider.fhir.type=FHIR",
                        "validation.external-terminology.provider.fhir.url=http://terminology.local",
                        "validation.external-terminology.billing-profiles.claims.code-systems[0]=http://hl7.org/fhir/sid/icd-10-cm")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("claims")
                        .hasStackTraceContaining("template IDs"));
    }

    @Test
    void disabledExternalValidationDoesNotCreateBillingValidator() {
        contextRunner
                .withPropertyValues(
                        "validation.external-terminology.enabled=false",
                        "validation.external-terminology.billing-profiles.claims.template-ids[0]=billing.claim.v1",
                        "validation.external-terminology.billing-profiles.claims.code-systems[0]=http://hl7.org/fhir/sid/icd-10-cm")
                .run(context -> assertThat(context).doesNotHaveBean(BillingCodeValidator.class));
    }
}
