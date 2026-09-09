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
package org.ehrbase.service.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.ehrbase.api.dto.fhir.FhirBundle;
import org.ehrbase.api.dto.fhir.FhirBundleEntry;
import org.ehrbase.api.dto.fhir.FhirCodeableConcept;
import org.ehrbase.api.dto.fhir.FhirCoding;
import org.ehrbase.api.dto.fhir.FhirExplanationOfBenefit;
import org.ehrbase.api.dto.fhir.FhirReference;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.junit.jupiter.api.Test;

class FhirEobHapiConformanceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesSearchsetBundleAcceptedByHapi() throws Exception {
        FhirExplanationOfBenefit eob = new FhirExplanationOfBenefit(
                "ExplanationOfBenefit",
                "eob-1",
                "active",
                new FhirCodeableConcept(List.of(new FhirCoding("http://type", "claim", null))),
                "claim",
                new FhirReference("Patient/patient-1", null),
                "2025-01-01",
                new FhirReference(null, "Insurer"),
                new FhirReference(null, "Provider"),
                "complete",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
        FhirBundle bundle = new FhirBundle(
                "Bundle", "bundle-1", "searchset", 1, List.of(), List.of(new FhirBundleEntry("urn:eob:eob-1", eob)));

        Bundle parsed = parse(bundle);

        assertThat(parsed.getResourceType().name()).isEqualTo("Bundle");
        assertThat(parsed.getType().toCode()).isEqualTo("searchset");
        assertThat(parsed.getEntry()).hasSize(1);
        assertThat(parsed.getEntryFirstRep().getResource().getResourceType().name())
                .isEqualTo("ExplanationOfBenefit");
        ExplanationOfBenefit parsedEob =
                (ExplanationOfBenefit) parsed.getEntryFirstRep().getResource();
        assertThat(parsedEob.getPatient().getReference()).isEqualTo("Patient/patient-1");
    }

    @Test
    void serializesEmptySearchsetBundleAcceptedByHapi() throws Exception {
        Bundle parsed = parse(new FhirBundle("Bundle", "bundle-2", "searchset", 0, List.of(), List.of()));

        assertThat(parsed.getType().toCode()).isEqualTo("searchset");
        assertThat(parsed.getEntry()).isEmpty();
    }

    private Bundle parse(FhirBundle bundle) throws Exception {
        String json = objectMapper.writeValueAsString(bundle);
        return FhirContext.forR4().newJsonParser().parseResource(Bundle.class, json);
    }
}
