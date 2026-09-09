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
package org.ehrbase.configuration.test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;
import org.ehrbase.api.dto.AqlQueryRequest;
import org.ehrbase.api.service.AqlQueryService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.configuration.config.security.SecurityProperties;
import org.ehrbase.configuration.exception.DefaultExceptionHandler;
import org.ehrbase.rest.openehr.FhirEobController;
import org.ehrbase.service.fhir.ExplanationOfBenefitServiceImp;
import org.ehrbase.service.fhir.FhirBillingProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

class FhirEobEndpointIntegrationTest {

    private static final UUID EHR_ID = UUID.fromString("d7a57443-20ee-4950-8c72-9bced2aa9881");
    private static final String AQL = "SELECT e/ehr_id/value AS eob_id, "
            + "e/ehr_status/lifecycle_state/value AS status, "
            + "e/ehr_status/subject/external_ref/id/value AS type_code, "
            + "e/ehr_status/subject/external_ref/namespace AS type_system, "
            + "e/ehr_status/subject/external_ref/id/value AS use, "
            + "e/ehr_status/time_created/value AS created, "
            + "e/ehr_status/subject/external_ref/id/value AS insurer_display, "
            + "e/ehr_status/subject/external_ref/namespace AS provider_display, "
            + "e/ehr_status/lifecycle_state/value AS outcome, "
            + "e/ehr_status/subject/external_ref/id/value AS coverage_reference "
            + "FROM EHR e WHERE e/ehr_id/value = $ehr_id";

    @EhrbaseConfigurationIntegrationTest
    @TestPropertySource(
            properties = {
                "ehrbase.fhir.billing.enabled=true",
                "ehrbase.fhir.billing.subject-namespace=test",
                "ehrbase.fhir.billing.eob-aql=" + AQL,
                "security.authType=BASIC",
                "security.authUser=ehrbase-user",
                "security.authPassword=SuperSecretPassword",
                "security.authAdminUser=ehrbase-admin",
                "security.authAdminPassword=EvenMoreSecretPassword"
            })
    @Import({DefaultExceptionHandler.class, FhirEobController.class, ExplanationOfBenefitServiceImp.class})
    @EnableConfigurationProperties({FhirBillingProperties.class, SecurityProperties.class})
    @Nested
    class BillingEnabled {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private EhrService ehrService;

        @MockBean
        private AqlQueryService aqlQueryService;

        @Test
        void unauthenticatedRequestIsRejected() throws Exception {
            mockMvc.perform(get("/rest/fhir/r4/ExplanationOfBenefit")
                            .param("patient", "Patient/p1")
                            .accept("application/fhir+json"))
                    .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                    result.getResponse().getStatus())
                            .isNotEqualTo(200));
        }

        @Test
        void nonQueryableEhrReturnsEmptyBundleWithoutAqlQuery() throws Exception {
            when(ehrService.findBySubject("p1", "test")).thenReturn(Optional.of(EHR_ID));
            when(ehrService.isQueryable(EHR_ID)).thenReturn(false);

            mockMvc.perform(get("/rest/fhir/r4/ExplanationOfBenefit")
                            .with(httpBasic("ehrbase-user", "SuperSecretPassword"))
                            .param("patient", "Patient/p1")
                            .accept("application/fhir+json"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/fhir+json"))
                    .andExpect(jsonPath("$.resourceType").value("Bundle"))
                    .andExpect(jsonPath("$.type").value("searchset"))
                    .andExpect(jsonPath("$.entry").isEmpty());

            verify(aqlQueryService, never()).query(any(AqlQueryRequest.class));
        }

        @Test
        void xmlAcceptIsNotAcceptable() throws Exception {
            mockMvc.perform(get("/rest/fhir/r4/ExplanationOfBenefit")
                            .with(httpBasic("ehrbase-user", "SuperSecretPassword"))
                            .param("patient", "Patient/p1")
                            .accept(MediaType.APPLICATION_XML))
                    .andExpect(status().isNotAcceptable());
        }

        @Test
        void barePatientIdIsBadRequest() throws Exception {
            mockMvc.perform(get("/rest/fhir/r4/ExplanationOfBenefit")
                            .with(httpBasic("ehrbase-user", "SuperSecretPassword"))
                            .param("patient", "p1")
                            .accept("application/fhir+json"))
                    .andExpect(status().isBadRequest());
        }
    }

    @EhrbaseConfigurationIntegrationTest
    @TestPropertySource(
            properties = {
                "security.authType=BASIC",
                "security.authUser=ehrbase-user",
                "security.authPassword=SuperSecretPassword",
                "security.authAdminUser=ehrbase-admin",
                "security.authAdminPassword=EvenMoreSecretPassword"
            })
    @Import(FhirEobController.class)
    @Nested
    class BillingDisabled {

        @Autowired
        private MockMvc mockMvc;

        @Test
        void endpointIsNotFoundWhenBillingIsDisabled() throws Exception {
            mockMvc.perform(get("/rest/fhir/r4/ExplanationOfBenefit")
                            .with(httpBasic("ehrbase-user", "SuperSecretPassword"))
                            .param("patient", "Patient/p1")
                            .accept("application/fhir+json"))
                    .andExpect(status().isNotFound());
        }
    }
}
