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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ehrbase.api.dto.AqlQueryRequest;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.api.service.AqlQueryService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.ExplanationOfBenefitService;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryResultDto;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.query.ResultHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExplanationOfBenefitServiceTest {

    private static final UUID EHR_ID = UUID.fromString("8c1f1f12-e9ee-46e9-9a96-5e6d65e6c5d9");
    private static final String SUBJECT_ID = "patient-123";
    private static final String NAMESPACE = "patients";
    private static final String EOB_AQL = """
            SELECT
              e/ehr_id/value AS eob_id,
              e/ehr_status/lifecycle_state/value AS status,
              e/ehr_status/subject/external_ref/id/value AS type_code,
              e/ehr_status/subject/external_ref/namespace AS type_system,
              e/ehr_status/subject/external_ref/id/value AS use,
              e/ehr_status/time_created/value AS created,
              e/ehr_status/subject/external_ref/id/value AS insurer_display,
              e/ehr_status/subject/external_ref/namespace AS provider_display,
              e/ehr_status/lifecycle_state/value AS outcome,
              e/ehr_status/subject/external_ref/id/value AS coverage_reference
            FROM EHR e
            WHERE e/ehr_id/value = $ehr_id
            """;

    private final EhrService ehrService = mock(EhrService.class);
    private final AqlQueryService aqlQueryService = mock(AqlQueryService.class);

    @Test
    void unknownSubjectReturnsEmptyPageWithoutQuery() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.empty());
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 100);

        ExplanationOfBenefitService.EobPage page = service.search(SUBJECT_ID, 10, 0);

        assertThat(page.resources()).isEmpty();
        assertThat(page.count()).isEqualTo(10);
        assertThat(page.offset()).isZero();
        assertThat(page.hasMore()).isFalse();
        verify(aqlQueryService, never()).query(any());
    }

    @Test
    void nonQueryableEhrReturnsEmptyPageWithoutQuery() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.of(EHR_ID));
        when(ehrService.isQueryable(EHR_ID)).thenReturn(false);
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 100);

        ExplanationOfBenefitService.EobPage page = service.search(SUBJECT_ID, 10, 0);

        assertThat(page.resources()).isEmpty();
        verify(aqlQueryService, never()).query(any());
    }

    @Test
    void queryableEhrUsesSubjectParameterAndPaging() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.of(EHR_ID));
        when(ehrService.isQueryable(EHR_ID)).thenReturn(true);
        when(aqlQueryService.query(any())).thenReturn(result(row()));
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 100);

        service.search(SUBJECT_ID, 7, 3);

        ArgumentCaptor<AqlQueryRequest> request = ArgumentCaptor.forClass(AqlQueryRequest.class);
        verify(aqlQueryService).query(request.capture());
        assertThat(request.getValue().parameters()).containsEntry("ehr_id", EHR_ID.toString());
        assertThat(request.getValue().fetch()).isEqualTo(8L);
        assertThat(request.getValue().offset()).isEqualTo(3L);
    }

    @Test
    void pagingTrimsExtraRowAndSetsHasMore() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.of(EHR_ID));
        when(ehrService.isQueryable(EHR_ID)).thenReturn(true);
        when(aqlQueryService.query(any())).thenReturn(result(row("one"), row("two"), row("three")));
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 100);

        ExplanationOfBenefitService.EobPage page = service.search(SUBJECT_ID, 2, 0);

        assertThat(page.resources()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void exactCountDoesNotSetHasMore() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.of(EHR_ID));
        when(ehrService.isQueryable(EHR_ID)).thenReturn(true);
        when(aqlQueryService.query(any())).thenReturn(result(row("one"), row("two")));
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 100);

        ExplanationOfBenefitService.EobPage page = service.search(SUBJECT_ID, 2, 0);

        assertThat(page.resources()).hasSize(2);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void countIsDefaultedAndClamped() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.of(EHR_ID));
        when(ehrService.isQueryable(EHR_ID)).thenReturn(true);
        when(aqlQueryService.query(any())).thenReturn(result());
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 2);

        service.search(SUBJECT_ID, 0, -10);

        ArgumentCaptor<AqlQueryRequest> request = ArgumentCaptor.forClass(AqlQueryRequest.class);
        verify(aqlQueryService).query(request.capture());
        assertThat(request.getValue().fetch()).isEqualTo(3L);
        assertThat(request.getValue().offset()).isZero();
    }

    @Test
    void missingRequiredColumnFailsWithColumnName() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.of(EHR_ID));
        when(ehrService.isQueryable(EHR_ID)).thenReturn(true);
        ResultHolder row = row();
        row.putResult("status", null);
        when(aqlQueryService.query(any())).thenReturn(result(row));
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 100);

        assertThatThrownBy(() -> service.search(SUBJECT_ID, 10, 0))
                .isInstanceOf(InternalServerException.class)
                .hasMessageContaining("status");
    }

    @Test
    void representativeRowMapsAllFields() {
        when(ehrService.findBySubject(SUBJECT_ID, NAMESPACE)).thenReturn(Optional.of(EHR_ID));
        when(ehrService.isQueryable(EHR_ID)).thenReturn(true);
        when(aqlQueryService.query(any())).thenReturn(result(row()));
        ExplanationOfBenefitService service = service(EOB_AQL, 20, 100);

        var eob = service.search(SUBJECT_ID, 10, 0).resources().getFirst();

        assertThat(eob.resourceType()).isEqualTo("ExplanationOfBenefit");
        assertThat(eob.id()).isEqualTo("eob-1");
        assertThat(eob.status()).isEqualTo("active");
        assertThat(eob.type().coding()).singleElement().satisfies(coding -> {
            assertThat(coding.system()).isEqualTo("http://terminology");
            assertThat(coding.code()).isEqualTo("claim");
        });
        assertThat(eob.patient().reference()).isEqualTo("Patient/" + SUBJECT_ID);
        assertThat(eob.insurer().display()).isEqualTo("Acme");
        assertThat(eob.provider().display()).isEqualTo("Provider");
        assertThat(eob.insurance()).singleElement().satisfies(insurance -> {
            assertThat(insurance.focal()).isTrue();
            assertThat(insurance.coverage().reference()).isEqualTo("Coverage/1");
        });
        assertThat(eob.diagnosis()).singleElement().satisfies(diagnosis -> {
            assertThat(diagnosis.sequence()).isEqualTo(1);
            assertThat(diagnosis.diagnosisCodeableConcept().coding())
                    .singleElement()
                    .extracting(coding -> coding.code())
                    .isEqualTo("D1");
        });
        assertThat(eob.procedure())
                .singleElement()
                .satisfies(procedure -> assertThat(
                                procedure.procedureCodeableConcept().coding())
                        .singleElement()
                        .extracting(coding -> coding.code())
                        .isEqualTo("P1"));
        assertThat(eob.item()).singleElement().satisfies(item -> {
            assertThat(item.sequence()).isEqualTo(1);
            assertThat(item.net().value()).isEqualByComparingTo("12.50");
            assertThat(item.net().currency()).isEqualTo("USD");
        });
        assertThat(eob.billablePeriod().start()).isEqualTo("2025-01-01");
        assertThat(eob.billablePeriod().end()).isEqualTo("2025-01-31");
    }

    @Test
    void constructorRejectsMissingAlias() {
        assertThatThrownBy(() -> service(EOB_AQL.replace("AS outcome", ""), 20, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void constructorRejectsMissingEhrIdParameter() {
        assertThatThrownBy(() -> service(EOB_AQL.replace("$ehr_id", "$other"), 20, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("$ehr_id");
    }

    private ExplanationOfBenefitService service(String aql, int defaultCount, int maxCount) {
        return new ExplanationOfBenefitServiceImp(
                ehrService,
                aqlQueryService,
                new FhirBillingProperties(true, "/ehrbase/rest/fhir/r4", NAMESPACE, aql, defaultCount, maxCount));
    }

    private static QueryResultDto result(ResultHolder... rows) {
        QueryResultDto result = new QueryResultDto();
        result.setResultSet(List.of(rows));
        return result;
    }

    private static ResultHolder row() {
        return row("eob-1");
    }

    private static ResultHolder row(String id) {
        ResultHolder row = new ResultHolder();
        row.putResult("eob_id", id);
        row.putResult("status", "active");
        row.putResult("type_code", "claim");
        row.putResult("type_system", "http://terminology");
        row.putResult("use", "claim");
        row.putResult("created", OffsetDateTime.parse("2025-01-01T10:15:30Z"));
        row.putResult("insurer_display", "Acme");
        row.putResult("provider_display", "Provider");
        row.putResult("outcome", "complete");
        row.putResult("coverage_reference", "Coverage/1");
        row.putResult("diagnosis_code", "D1");
        row.putResult("diagnosis_system", "http://diagnosis");
        row.putResult("procedure_code", "P1");
        row.putResult("procedure_system", "http://procedure");
        row.putResult("item_code", "I1");
        row.putResult("item_system", "http://item");
        row.putResult("item_net", new BigDecimal("12.50"));
        row.putResult("item_currency", "USD");
        row.putResult("billable_start", "2025-01-01");
        row.putResult("billable_end", "2025-01-31");
        return row;
    }
}
