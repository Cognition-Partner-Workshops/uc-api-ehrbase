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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class BillingTerminologyValidationIT {

    private static final String ICD10 = "http://hl7.org/fhir/sid/icd-10-cm";
    private static final String CPT = "http://www.ama-assn.org/go/cpt";
    private static final String HCPCS = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";

    private HttpServer server;
    private List<String> requests;

    @BeforeEach
    void setUp() throws IOException {
        requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validatesCodesAgainstFhirServer() {
        FhirTerminologyValidation terminologyValidation =
                new FhirTerminologyValidation("http://localhost:" + server.getAddress().getPort(), true, WebClient.create());
        BillingCodeValidator validator = new BillingCodeValidator(List.of(new BillingValidationProfile(
                "claims", java.util.Set.of("billing.claim.v1"), java.util.Set.of(ICD10, CPT, HCPCS), terminologyValidation)));

        validator.validate(
                "billing.claim.v1",
                composition(
                        new CodePhrase(new TerminologyId(ICD10), "A01"),
                        new CodePhrase(new TerminologyId(CPT), "99213"),
                        new CodePhrase(new TerminologyId(HCPCS), "J1234")));

        assertThat(requests)
                .containsExactlyInAnyOrder(
                        "/CodeSystem/$validate-code?url=http://hl7.org/fhir/sid/icd-10-cm&code=A01",
                        "/CodeSystem/$validate-code?url=http://www.ama-assn.org/go/cpt&code=99213",
                        "/CodeSystem/$validate-code?url=https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets&code=J1234");
    }

    @Test
    void invalidCodeIncludesSystemAndTemplateMismatchDoesNotRequest() {
        FhirTerminologyValidation terminologyValidation =
                new FhirTerminologyValidation("http://localhost:" + server.getAddress().getPort(), true, WebClient.create());
        BillingCodeValidator validator = new BillingCodeValidator(List.of(new BillingValidationProfile(
                "claims", java.util.Set.of("billing.claim.v1"), java.util.Set.of(ICD10), terminologyValidation)));

        assertThatThrownBy(() -> validator.validate(
                        "billing.claim.v1", composition(new CodePhrase(new TerminologyId(ICD10), "BAD"))))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("BAD", ICD10);

        requests.clear();
        validator.validate("other.template", composition(new CodePhrase(new TerminologyId(ICD10), "BAD")));
        assertThat(requests).isEmpty();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI().toString());
        assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("application/fhir+json");
        boolean invalid = exchange.getRequestURI().getQuery().contains("code=BAD");
        String response = invalid
                ? "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":false},{\"name\":\"message\",\"valueString\":\"not found\"}]}"
                : "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true}]}";
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(200, body.length);
        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    private static Composition composition(CodePhrase... codes) {
        Composition composition = new Composition();
        ItemTree tree = new ItemTree();
        List<Item> elements = new ArrayList<>();
        for (CodePhrase code : codes) {
            elements.add(new Element("code", new DvText("code"), new DvCodedText("display", code)));
        }
        tree.setItems(elements);
        Evaluation evaluation = new Evaluation();
        evaluation.setData(tree);
        composition.setContent(List.of(evaluation));
        return composition;
    }
}
