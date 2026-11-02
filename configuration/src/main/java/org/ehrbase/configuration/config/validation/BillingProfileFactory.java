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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidation;
import org.ehrbase.service.validation.BillingCodeSystem;
import org.ehrbase.service.validation.BillingCodeSystemMapping;
import org.ehrbase.service.validation.BillingValidationProfile;
import org.ehrbase.service.validation.FhirTerminologyValidation;

public class BillingProfileFactory {

    public List<BillingValidationProfile> create(
            ExternalValidationProperties properties, Map<String, ExternalTerminologyValidation> providerValidations) {
        List<BillingValidationProfile> result = new ArrayList<>();
        for (Map.Entry<String, ExternalValidationProperties.BillingProfile> entry :
                properties.getBillingProfiles().entrySet()) {
            String profileName = entry.getKey();
            ExternalValidationProperties.BillingProfile profile = entry.getValue();
            if (!profile.isEnabled()) {
                continue;
            }
            if (!properties.isEnabled()) {
                throw invalid(profileName, "validation.external-terminology.enabled must be true");
            }
            String providerName = profile.getProvider();
            if (providerName == null || providerName.isBlank()) {
                throw invalid(profileName, "provider is required");
            }
            if (!properties.getProvider().containsKey(providerName)) {
                throw invalid(profileName, "unknown provider '%s'".formatted(providerName));
            }

            ExternalTerminologyValidation validation = providerValidations.get(providerName);
            if (!(validation instanceof FhirTerminologyValidation fhirValidation)) {
                throw invalid(profileName, "provider '%s' must be a FHIR provider".formatted(providerName));
            }

            List<CodeSystemDefinition> definitions = codeSystems(profile.getCodeSystems());
            if (definitions.stream()
                    .noneMatch(definition -> definition.properties().isEnabled())) {
                throw invalid(profileName, "at least one code system must be enabled");
            }

            Map<String, String> aliases = new HashMap<>();
            List<BillingCodeSystemMapping> mappings = new ArrayList<>();
            for (CodeSystemDefinition definition : definitions) {
                ExternalValidationProperties.CodeSystem codeSystem = definition.properties();
                if (!codeSystem.isEnabled()) {
                    continue;
                }
                if (!isAbsoluteUri(codeSystem.getSystem())) {
                    throw invalid(profileName, "%s system must be an absolute URI".formatted(definition.name()));
                }
                if (definition.codeSystem() == BillingCodeSystem.SNOMED_CT
                        && codeSystem.getValueSet() != null
                        && !isAbsoluteUri(codeSystem.getValueSet())) {
                    throw invalid(profileName, "snomed-ct value-set must be an absolute URI");
                }
                if (codeSystem.getTerminologyIds() == null
                        || codeSystem.getTerminologyIds().isEmpty()) {
                    throw invalid(profileName, "%s terminology-ids must not be empty".formatted(definition.name()));
                }
                for (String alias : codeSystem.getTerminologyIds()) {
                    String normalizedAlias = alias.toLowerCase(Locale.ROOT);
                    String previous = aliases.putIfAbsent(normalizedAlias, definition.name());
                    if (previous != null && !previous.equals(definition.name())) {
                        throw invalid(
                                profileName,
                                "terminology id '%s' is mapped to both %s and %s"
                                        .formatted(alias, previous, definition.name()));
                    }
                }
                mappings.add(new BillingCodeSystemMapping(
                        definition.codeSystem(),
                        codeSystem.getSystem(),
                        new LinkedHashSet<>(codeSystem.getTerminologyIds()),
                        definition.codeSystem() == BillingCodeSystem.SNOMED_CT ? codeSystem.getValueSet() : null));
            }

            result.add(new BillingValidationProfile(
                    profileName, new LinkedHashSet<>(profile.getTemplateIds()), mappings, fhirValidation));
        }
        return List.copyOf(result);
    }

    private static List<CodeSystemDefinition> codeSystems(ExternalValidationProperties.CodeSystems codeSystems) {
        return List.of(
                new CodeSystemDefinition("icd10", BillingCodeSystem.ICD10, codeSystems.getIcd10()),
                new CodeSystemDefinition("cpt", BillingCodeSystem.CPT, codeSystems.getCpt()),
                new CodeSystemDefinition("hcpcs", BillingCodeSystem.HCPCS, codeSystems.getHcpcs()),
                new CodeSystemDefinition("snomed-ct", BillingCodeSystem.SNOMED_CT, codeSystems.getSnomedCt()));
    }

    private static boolean isAbsoluteUri(String value) {
        try {
            return value != null && URI.create(value).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static IllegalStateException invalid(String profileName, String message) {
        return new IllegalStateException("Invalid billing profile '%s': %s".formatted(profileName, message));
    }

    private record CodeSystemDefinition(
            String name, BillingCodeSystem codeSystem, ExternalValidationProperties.CodeSystem properties) {}
}
