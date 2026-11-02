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
package org.ehrbase.service.validation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, startup-validated billing terminology profile. */
public record BillingValidationProfile(
        String name,
        Set<String> templateIds,
        List<CodeSystem> codeSystems,
        FhirTerminologyValidation terminologyValidation) {

    public BillingValidationProfile {
        if (codeSystems == null || codeSystems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Billing validation profile '%s' must define code systems".formatted(name));
        }
        if (terminologyValidation == null) {
            throw new IllegalArgumentException(
                    "Billing validation profile '%s' must define terminology validation".formatted(name));
        }
        Set<String> aliases = new HashSet<>();
        for (CodeSystem system : codeSystems) {
            if (!Set.of("icd10", "cpt", "hcpcs", "snomed").contains(system.name())
                    || system.system() == null
                    || system.system().isBlank()) {
                throw new IllegalArgumentException(
                        "Billing profile '%s' has invalid code system '%s'".formatted(name, system.name()));
            }
            if (system.valueSet() != null
                    && (!system.name().equals("snomed") || system.valueSet().isBlank())) {
                throw new IllegalArgumentException(
                        "Billing profile '%s': only SNOMED supports a nonblank ValueSet".formatted(name));
            }
            if (system.terminologyIds().isEmpty()) {
                throw new IllegalArgumentException(
                        "Billing profile '%s' requires terminology-id aliases".formatted(name));
            }
            for (String alias : system.terminologyIds()) {
                if (alias == null || alias.isBlank() || !aliases.add(alias)) {
                    throw new IllegalArgumentException(
                            "Billing profile '%s' has duplicate or blank alias '%s'".formatted(name, alias));
                }
            }
        }
        templateIds = Set.copyOf(templateIds);
        codeSystems = List.copyOf(codeSystems);
    }

    public record CodeSystem(String name, String system, List<String> terminologyIds, String valueSet) {
        public CodeSystem {
            terminologyIds = List.copyOf(terminologyIds);
        }
    }
}
