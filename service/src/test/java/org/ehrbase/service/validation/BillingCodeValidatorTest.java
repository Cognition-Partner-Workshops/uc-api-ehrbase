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
import static org.mockito.Mockito.*;

import com.nedap.archie.rm.composition.*;
import com.nedap.archie.rm.datastructures.*;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.*;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class BillingCodeValidatorTest {
    @ParameterizedTest
    @ValueSource(strings = {"icd10", "cpt", "hcpcs", "snomed"})
    void mapsAliasesAndCollectsPaths(String name) {
        FhirTerminologyValidation provider = mock(FhirTerminologyValidation.class);
        when(provider.validateCodes(any())).thenReturn(List.of());
        BillingCodeValidator validator = validator(provider, Set.of(), name);
        assertThat(validator.validate("any", composition("alias", "local"))).isEmpty();
        ArgumentCaptor<Collection<FhirTerminologyValidation.BillingCode>> captor =
                ArgumentCaptor.forClass(Collection.class);
        verify(provider).validateCodes(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(code -> {
            assertThat(code.profile()).isEqualTo("claims");
            assertThat(code.codeSystem()).isEqualTo(name);
            assertThat(code.system()).isEqualTo("http://system");
            assertThat(code.path()).isEqualTo("/content[0]/data/items[0]/value");
        });
    }

    @Test
    void templateFilteringAndEmptyCompositionDoNotRequest() {
        FhirTerminologyValidation provider = spy(new FhirTerminologyValidation("http://unused"));
        BillingCodeValidator validator = validator(provider, Set.of("billing"), "cpt");
        assertThat(validator.validate("other", composition("alias"))).isEmpty();
        assertThat(validator.validate("billing", composition("local"))).isEmpty();
        assertThat(validator.validate("billing", new Composition())).isEmpty();
        verify(provider, never()).internalBatch(any());
    }

    static BillingCodeValidator validator(FhirTerminologyValidation provider, Set<String> templates, String name) {
        return new BillingCodeValidator(List.of(new BillingValidationProfile(
                "claims",
                templates,
                List.of(new BillingValidationProfile.CodeSystem(
                        name, "http://system", List.of("alias", "alternate"), null)),
                provider)));
    }

    static Composition composition(String... aliases) {
        Composition composition = new Composition();
        ItemTree tree = new ItemTree();
        List<Item> items = new ArrayList<>();
        for (String alias : aliases) {
            items.add(new Element(
                    "code",
                    new DvText("code"),
                    new DvCodedText("display", new CodePhrase(new TerminologyId(alias), "A01"))));
        }
        tree.setItems(items);
        Evaluation evaluation = new Evaluation();
        evaluation.setData(tree);
        composition.setContent(List.of(evaluation));
        return composition;
    }
}
