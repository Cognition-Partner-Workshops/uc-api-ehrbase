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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ExternalValidationPropertiesTest {

    @Test
    void billingCodeSystemDefaultsAreConfigured() {
        ExternalValidationProperties properties = new ExternalValidationProperties();
        ExternalValidationProperties.BillingProfile profile = properties
                .getBillingProfiles()
                .computeIfAbsent("claims", ignored -> new ExternalValidationProperties.BillingProfile());
        ExternalValidationProperties.CodeSystems codeSystems = profile.getCodeSystems();

        assertThat(profile.isEnabled()).isFalse();
        assertThat(profile.getTemplateIds()).isEmpty();
        assertThat(codeSystems.getIcd10().isEnabled()).isTrue();
        assertThat(codeSystems.getIcd10().getSystem()).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
        assertThat(codeSystems.getIcd10().getTerminologyIds())
                .containsExactly("ICD10", "ICD-10", "ICD10CM", "ICD-10-CM");
        assertThat(codeSystems.getCpt().getSystem()).isEqualTo("http://www.ama-assn.org/go/cpt");
        assertThat(codeSystems.getCpt().getTerminologyIds()).containsExactly("CPT", "CPT4", "CPT-4");
        assertThat(codeSystems.getHcpcs().getSystem())
                .isEqualTo("https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets");
        assertThat(codeSystems.getHcpcs().getTerminologyIds()).containsExactly("HCPCS");
        assertThat(codeSystems.getSnomedCt().getSystem()).isEqualTo("http://snomed.info/sct");
        assertThat(codeSystems.getSnomedCt().getTerminologyIds())
                .containsExactly("SNOMED-CT", "SNOMED CT", "SNOMEDCT", "SNOMED");
        assertThat(codeSystems.getSnomedCt().getValueSet()).isNull();
    }

    @Test
    void bindsSnomedCtKebabCaseAndPreservesOtherDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "validation.external-terminology.billing-profiles.claims.enabled=true",
                        "validation.external-terminology.billing-profiles.claims.provider=fhir",
                        "validation.external-terminology.billing-profiles.claims.code-systems.snomed-ct.enabled=false",
                        "validation.external-terminology.billing-profiles.claims.code-systems.snomed-ct.value-set=https://example.org/snomed")
                .run(context -> {
                    ExternalValidationProperties.BillingProfile profile = context.getBean(
                                    ExternalValidationProperties.class)
                            .getBillingProfiles()
                            .get("claims");
                    assertThat(profile.isEnabled()).isTrue();
                    assertThat(profile.getProvider()).isEqualTo("fhir");
                    assertThat(profile.getCodeSystems().getSnomedCt().isEnabled())
                            .isFalse();
                    assertThat(profile.getCodeSystems().getSnomedCt().getValueSet())
                            .isEqualTo("https://example.org/snomed");
                    assertThat(profile.getCodeSystems().getIcd10().getSystem())
                            .isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExternalValidationProperties.class)
    static class PropertiesConfiguration {}
}
