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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.jayway.jsonpath.JsonPath;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.List;
import java.util.Set;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class BillingCodeValidatorTest {

    private static final String ICD10 = "http://hl7.org/fhir/sid/icd-10-cm";

    @Test
    void validatesConfiguredCodesAndIgnoresOtherSystems() {
        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation("http://terminology.local"));
        doReturn(JsonPath.parse("{\"parameter\":[{\"valueBoolean\":true}]}"))
                .when(validation)
                .internalGet(org.mockito.ArgumentMatchers.anyString());
        BillingCodeValidator validator = new BillingCodeValidator(List.of(new BillingValidationProfile(
                "claims", Set.of("billing.claim.v1"), Set.of(ICD10), validation)));

        validator.validate("billing.claim.v1", composition(new CodePhrase(new TerminologyId(ICD10), "A01")));

        verify(validation).internalGet(org.mockito.ArgumentMatchers.contains("code=A01"));
    }

    @Test
    void invalidCodesAreReturnedAsConstraintViolations() {
        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation("http://terminology.local"));
        doReturn(JsonPath.parse("""
                {"parameter":[{"valueBoolean":false},{"valueString":"invalid"}]}
                """))
                .when(validation)
                .internalGet(org.mockito.ArgumentMatchers.anyString());
        BillingCodeValidator validator = new BillingCodeValidator(List.of(new BillingValidationProfile(
                "claims", Set.of("billing.claim.v1"), Set.of(ICD10), validation)));

        assertThatThrownBy(() -> validator.validate(
                        "billing.claim.v1", composition(new CodePhrase(new TerminologyId(ICD10), "A01"))))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("A01", ICD10);
    }

    @Test
    void nonMatchingTemplatesDoNotCallTerminologyValidation() {
        FhirTerminologyValidation validation = spy(new FhirTerminologyValidation("http://terminology.local"));
        BillingCodeValidator validator = new BillingCodeValidator(List.of(new BillingValidationProfile(
                "claims", Set.of("billing.claim.v1"), Set.of(ICD10), validation)));

        validator.validate("other.template", composition(new CodePhrase(new TerminologyId(ICD10), "A01")));

        verify(validation, times(0)).internalGet(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void duplicateTemplateIdsAreRejected() {
        FhirTerminologyValidation validation = new FhirTerminologyValidation("http://terminology.local");

        assertThatThrownBy(() -> new BillingCodeValidator(List.of(
                        new BillingValidationProfile("first", Set.of("billing.claim.v1"), Set.of(ICD10), validation),
                        new BillingValidationProfile("second", Set.of("billing.claim.v1"), Set.of(ICD10), validation))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing.claim.v1", "first", "second");
    }

    private static Composition composition(CodePhrase code) {
        Composition composition = new Composition();
        ItemTree tree = new ItemTree();
        tree.setItems(List.of(new Element("code", new DvText("code"), new DvCodedText("display", code))));
        Evaluation evaluation = new Evaluation();
        evaluation.setData(tree);
        composition.setContent(List.of(evaluation));
        return composition;
    }
}
