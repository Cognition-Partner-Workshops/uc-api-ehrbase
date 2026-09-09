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
package org.ehrbase.rest.openehr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.ehrbase.api.dto.fhir.FhirBundle;
import org.ehrbase.api.dto.fhir.FhirExplanationOfBenefit;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.ExplanationOfBenefitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

class FhirEobControllerTest {

    private static final String CURRENT_REQUEST =
            "https://host/ehrbase/rest/fhir/r4/ExplanationOfBenefit?patient=Patient/p1&_count=2";

    private final ExplanationOfBenefitService service = mock();
    private final FhirEobController controller = spy(new FhirEobController(service));

    @BeforeEach
    void setUp() {
        reset(service, controller);
        doReturn(UriComponentsBuilder.fromUriString(CURRENT_REQUEST))
                .when(controller)
                .currentRequest();
    }

    @Test
    void delegatesSearchWithPatientIdAndPaging() {
        whenPage(List.of(eob("eob-1")), 2, 4, false);

        controller.search("Patient/p1", 2, 4);

        verify(service).search("p1", 2, 4);
    }

    @Test
    void nullPagingValuesArePassedAsZero() {
        whenRequestedPage(0, 0, List.of(), 20, 0, false);

        controller.search("Patient/p1", null, null);

        verify(service).search("p1", 0, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -10})
    void negativeCountIsRejected(int count) {
        assertThatThrownBy(() -> controller.search("Patient/p1", count, 0))
                .isInstanceOf(InvalidApiParameterException.class)
                .hasMessage("count must not be negative");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -10})
    void negativeOffsetIsRejected(int offset) {
        assertThatThrownBy(() -> controller.search("Patient/p1", 2, offset))
                .isInstanceOf(InvalidApiParameterException.class)
                .hasMessage("offset must not be negative");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"p1", "Patient/", "Observation/p1", "https://x/Patient/p1", ""})
    void invalidPatientReferencesAreRejected(String patient) {
        assertThatThrownBy(() -> controller.search(patient, 2, 0))
                .isInstanceOf(InvalidApiParameterException.class)
                .hasMessage("patient must be a relative Patient/{id} reference");
        verifyNoInteractions(service);
    }

    @Test
    void hasMoreAddsNextLinkAndOmitsTotal() {
        whenPage(List.of(eob("eob-1"), eob("eob-2")), 2, 0, true);

        ResponseEntity<FhirBundle> response = controller.search("Patient/p1", 2, 0);

        FhirBundle bundle = response.getBody();
        assertThat(bundle).isNotNull();
        assertThat(bundle.total()).isNull();
        assertThat(bundle.entry()).hasSize(2);
        assertThat(bundle.entry().getFirst().fullUrl())
                .isEqualTo("https://host/ehrbase/rest/fhir/r4/ExplanationOfBenefit/eob-1");
        assertThat(bundle.link()).extracting("relation").containsExactly("self", "next");
        assertThat(bundle.link().getFirst().url()).isEqualTo(CURRENT_REQUEST);
        var nextQuery = UriComponentsBuilder.fromUriString(bundle.link().get(1).url())
                .build()
                .getQueryParams();
        assertThat(nextQuery.getFirst("patient")).isEqualTo("Patient/p1");
        assertThat(nextQuery.getFirst("_count")).isEqualTo("2");
        assertThat(nextQuery.getFirst("_offset")).isEqualTo("2");
    }

    @Test
    void lastPageHasTotalAndNoNextLink() {
        whenPage(List.of(eob("eob-1")), 2, 4, false);

        ResponseEntity<FhirBundle> response = controller.search("Patient/p1", 2, 4);

        FhirBundle bundle = response.getBody();
        assertThat(bundle).isNotNull();
        assertThat(bundle.total()).isEqualTo(5);
        assertThat(bundle.link()).extracting("relation").containsExactly("self");
    }

    @Test
    void emptyPageContainsNoEntriesAndOnlySelfLink() {
        whenRequestedPage(0, 0, List.of(), 20, 0, false);

        ResponseEntity<FhirBundle> response = controller.search("Patient/p1", null, null);

        FhirBundle bundle = response.getBody();
        assertThat(bundle).isNotNull();
        assertThat(bundle.entry()).isEmpty();
        assertThat(bundle.link()).extracting("relation").containsExactly("self");
        assertThat(bundle.total()).isZero();
    }

    @Test
    void returnsFhirJsonContentType() {
        whenRequestedPage(0, 0, List.of(), 20, 0, false);

        ResponseEntity<FhirBundle> response = controller.search("Patient/p1", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/fhir+json"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/fhir+json");
    }

    private void whenPage(List<FhirExplanationOfBenefit> resources, int count, int offset, boolean hasMore) {
        whenRequestedPage(count, offset, resources, count, offset, hasMore);
    }

    private void whenRequestedPage(
            int requestedCount,
            int requestedOffset,
            List<FhirExplanationOfBenefit> resources,
            int count,
            int offset,
            boolean hasMore) {
        doReturn(new ExplanationOfBenefitService.EobPage(resources, count, offset, hasMore))
                .when(service)
                .search(eq("p1"), eq(requestedCount), eq(requestedOffset));
    }

    private static FhirExplanationOfBenefit eob(String id) {
        return new FhirExplanationOfBenefit(
                "ExplanationOfBenefit",
                id,
                "active",
                null,
                "claim",
                null,
                "2024-01-01",
                null,
                null,
                "complete",
                null,
                null,
                null,
                null,
                null);
    }
}
