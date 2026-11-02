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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.ehrbase.cache.CacheProvider;
import org.ehrbase.service.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ExternalValidationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationConfiguration.class)
            .withBean(CacheProvider.class, () -> mock(CacheProvider.class));

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues(
                "validation.external-terminology.provider.fhir.type=FHIR",
                "validation.external-terminology.provider.fhir.url=http://terminology.local",
                "validation.external-terminology.billing-profiles.claims.enabled=true",
                "validation.external-terminology.billing-profiles.claims.provider=fhir",
                "validation.external-terminology.billing-profiles.claims.code-systems.icd10.system=http://icd10",
                "validation.external-terminology.billing-profiles.claims.code-systems.icd10.terminology-ids[0]=ICD10",
                "validation.external-terminology.billing-profiles.claims.code-systems.icd10.terminology-ids[1]=ICD-10-CM");
    }

    @Test
    void bindsProfilesIndependentlyOfExternalValidation() {
        configured().run(context -> {
            assertThat(context).hasSingleBean(BillingCodeValidator.class);
            var profile = context.getBean(ExternalValidationProperties.class)
                    .getBillingProfiles()
                    .get("claims");
            assertThat(profile.getTemplateIds()).isEmpty();
            assertThat(profile.getCodeSystems().get("icd10").getTerminologyIds())
                    .containsExactly("ICD10", "ICD-10-CM");
        });
    }

    @Test
    void disabledByDefaultAndAbsentMeansNoBean() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BillingCodeValidator.class));
        runner.withPropertyValues("validation.external-terminology.billing-profiles.claims.provider=missing")
                .run(context -> assertThat(context).doesNotHaveBean(BillingCodeValidator.class));
        configured()
                .withPropertyValues("validation.external-terminology.billing-profiles.claims.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BillingCodeValidator.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "provider=missing",
                "provider=",
                "code-systems.icd10.system=",
                "code-systems.icd10.terminology-ids[1]=ICD10",
                "code-systems.icd10.value-set=http://not-snomed"
            })
    void badProfilesFailStartup(String property) {
        configured()
                .withPropertyValues("validation.external-terminology.billing-profiles.claims." + property)
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("claims"));
    }

    @Test
    void enabledProfileRequiresSystems() {
        runner.withPropertyValues(
                        "validation.external-terminology.provider.fhir.type=FHIR",
                        "validation.external-terminology.provider.fhir.url=http://terminology.local",
                        "validation.external-terminology.billing-profiles.claims.enabled=true",
                        "validation.external-terminology.billing-profiles.claims.provider=fhir")
                .run(context ->
                        assertThat(context.getStartupFailure()).hasStackTraceContaining("must define code systems"));
    }

    @Test
    void providerIsSharedWithExistingExternalValidation() {
        configured()
                .withPropertyValues(
                        "validation.external-terminology.enabled=true",
                        "validation.external-terminology.billing-profiles.claims.template-ids[0]=billing")
                .run(context -> {
                    Map<String, ?> providers = (Map<String, ?>) context.getBean("externalTerminologyValidations");
                    assertThat(providers.get("fhir")).isSameAs(context.getBean("externalTerminologyValidator"));
                    assertThat(context.getBean(ExternalValidationProperties.class)
                                    .getBillingProfiles()
                                    .get("claims")
                                    .getTemplateIds())
                            .containsExactly("billing");
                });
    }
}
