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

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.ehrbase.cache.CacheProvider;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidation;
import org.ehrbase.service.validation.BillingCodeValidator;
import org.ehrbase.service.validation.BillingValidationProfile;
import org.ehrbase.service.validation.FhirTerminologyValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ValidationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationConfiguration.class)
            .withBean(CacheProvider.class, () -> mock(CacheProvider.class));

    @Test
    void noBillingProfilesLeavesExternalTerminologyValidationAvailable() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(BillingCodeValidator.class);
            assertThat(context.getBean(ExternalTerminologyValidation.class))
                    .isInstanceOf(NopExternalTerminologyValidation.class);
        });
    }

    @Test
    void disabledBillingProfileDoesNotCreateBillingValidator() {
        runner.withPropertyValues("validation.external-terminology.billing-profiles.claims.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BillingCodeValidator.class));
    }

    @Test
    void enabledBillingProfileCreatesValidatorAndSharesProviderInstance() throws Exception {
        runner.withPropertyValues(validProfileProperties()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(BillingCodeValidator.class);
            ExternalTerminologyValidation external = context.getBean(ExternalTerminologyValidation.class);
            BillingCodeValidator validator = context.getBean(BillingCodeValidator.class);
            Field profilesField = BillingCodeValidator.class.getDeclaredField("profiles");
            profilesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BillingValidationProfile> profiles = (List<BillingValidationProfile>) profilesField.get(validator);
            assertThat(external).isInstanceOf(FhirTerminologyValidation.class);
            assertThat(profiles).hasSize(1);
            assertThat(profiles.getFirst().terminology()).isSameAs(external);
        });
    }

    @ParameterizedTest
    @MethodSource("startupValidationRules")
    void enabledBillingProfilesFailStartupWithExactMessage(String[] additionalProperties, String message) {
        String[] properties = Stream.concat(
                        Stream.of(
                                "validation.external-terminology.provider.fhir.type=FHIR",
                                "validation.external-terminology.provider.fhir.url=http://terminology.local/fhir",
                                "validation.external-terminology.billing-profiles.claims.enabled=true"),
                        Arrays.stream(additionalProperties))
                .toArray(String[]::new);
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Invalid billing profile 'claims': " + message);
        });
    }

    @Test
    void snomedValueSetMustBeAnAbsoluteUri() {
        runner.withPropertyValues(
                        "validation.external-terminology.enabled=true",
                        "validation.external-terminology.provider.fhir.type=FHIR",
                        "validation.external-terminology.provider.fhir.url=http://terminology.local/fhir",
                        "validation.external-terminology.billing-profiles.claims.enabled=true",
                        "validation.external-terminology.billing-profiles.claims.provider=fhir",
                        "validation.external-terminology.billing-profiles.claims.code-systems.snomed-ct.value-set=relative")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "Invalid billing profile 'claims': snomed-ct value-set must be an absolute URI");
                });
    }

    private static Stream<Arguments> startupValidationRules() {
        return Stream.of(
                Arguments.of(
                        new String[] {"validation.external-terminology.enabled=false"},
                        "validation.external-terminology.enabled must be true"),
                Arguments.of(new String[] {"validation.external-terminology.enabled=true"}, "provider is required"),
                Arguments.of(
                        new String[] {
                            "validation.external-terminology.enabled=true",
                            "validation.external-terminology.billing-profiles.claims.provider=unknown"
                        },
                        "unknown provider 'unknown'"),
                Arguments.of(
                        new String[] {
                            "validation.external-terminology.enabled=true",
                            "validation.external-terminology.billing-profiles.claims.provider=fhir",
                            "validation.external-terminology.billing-profiles.claims.code-systems.icd10.enabled=false",
                            "validation.external-terminology.billing-profiles.claims.code-systems.cpt.enabled=false",
                            "validation.external-terminology.billing-profiles.claims.code-systems.hcpcs.enabled=false",
                            "validation.external-terminology.billing-profiles.claims.code-systems.snomed-ct.enabled=false"
                        },
                        "at least one code system must be enabled"),
                Arguments.of(
                        new String[] {
                            "validation.external-terminology.enabled=true",
                            "validation.external-terminology.billing-profiles.claims.provider=fhir",
                            "validation.external-terminology.billing-profiles.claims.code-systems.icd10.system=relative"
                        },
                        "icd10 system must be an absolute URI"),
                Arguments.of(
                        new String[] {
                            "validation.external-terminology.enabled=true",
                            "validation.external-terminology.billing-profiles.claims.provider=fhir",
                            "validation.external-terminology.billing-profiles.claims.code-systems.icd10.terminology-ids="
                        },
                        "icd10 terminology-ids must not be empty"),
                Arguments.of(
                        new String[] {
                            "validation.external-terminology.enabled=true",
                            "validation.external-terminology.billing-profiles.claims.provider=fhir",
                            "validation.external-terminology.billing-profiles.claims.code-systems.cpt.terminology-ids[0]=ICD10"
                        },
                        "terminology id 'ICD10' is mapped to both icd10 and cpt"));
    }

    private static String[] validProfileProperties() {
        return new String[] {
            "validation.external-terminology.enabled=true",
            "validation.external-terminology.provider.fhir.type=FHIR",
            "validation.external-terminology.provider.fhir.url=http://terminology.local/fhir",
            "validation.external-terminology.billing-profiles.claims.enabled=true",
            "validation.external-terminology.billing-profiles.claims.provider=fhir"
        };
    }
}
