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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nedap.archie.rm.RMObject;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import com.nedap.archie.rminfo.ArchieRMInfoLookup;
import com.nedap.archie.rminfo.RMAttributeInfo;
import com.nedap.archie.rminfo.RMTypeInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.xmlbeans.XmlException;
import org.ehrbase.api.exception.UnprocessableEntityException;
import org.ehrbase.api.service.TemplateService;
import org.ehrbase.openehr.sdk.serialisation.jsonencoding.CanonicalJson;
import org.ehrbase.openehr.sdk.test_data.composition.CompositionTestDataCanonicalJson;
import org.ehrbase.openehr.sdk.test_data.operationaltemplate.OperationalTemplateTestData;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidation;
import org.ehrbase.openehr.sdk.validation.terminology.ExternalTerminologyValidationException;
import org.ehrbase.openehr.sdk.webtemplate.model.WebTemplate;
import org.ehrbase.openehr.sdk.webtemplate.parser.OPTParser;
import org.ehrbase.service.TerminologyServiceImp;
import org.ehrbase.service.ValidationServiceImp;
import org.junit.jupiter.api.Test;
import org.openehr.schemas.v1.OPERATIONALTEMPLATE;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class BillingTerminologyValidationIT {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesFourCodeSystemsInOneFhirBatchAndReturns422() throws Exception {
        try (MockTerminologyServer terminologyServer = new MockTerminologyServer(false)) {
            Composition composition = loadCoronaComposition();
            List<DvCodedText> codedTexts = codedTexts(composition.getContent());
            assertThat(codedTexts).hasSizeGreaterThanOrEqualTo(4);
            List<CodePhrase> billingCodes = List.of(
                    new CodePhrase(new TerminologyId("ICD10"), "Z99.999"),
                    new CodePhrase(new TerminologyId("CPT"), "00000"),
                    new CodePhrase(new TerminologyId("HCPCS"), "X0000"),
                    new CodePhrase(new TerminologyId("SNOMED-CT"), "99999999"));
            for (int i = 0; i < billingCodes.size(); i++) {
                addMapping(codedTexts.get(i), billingCodes.get(i));
            }

            String templateId =
                    composition.getArchetypeDetails().getTemplateId().getValue();
            TemplateService templateService = mock(TemplateService.class);
            when(templateService.getInternalTemplate(templateId)).thenReturn(webTemplate(templateId));
            FhirTerminologyValidation terminology = new FhirTerminologyValidation(terminologyServer.baseUrl(), true);
            List<BillingCodeSystemMapping> mappings = List.of(
                    mapping(BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null),
                    mapping(BillingCodeSystem.CPT, "http://www.ama-assn.org/go/cpt", Set.of("CPT"), null),
                    mapping(
                            BillingCodeSystem.HCPCS,
                            "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                            Set.of("HCPCS"),
                            null),
                    mapping(BillingCodeSystem.SNOMED_CT, "http://snomed.info/sct", Set.of("SNOMED-CT"), null));
            BillingCodeValidator billingCodeValidator = new BillingCodeValidator(
                    List.of(new BillingValidationProfile("claims", Set.of(templateId), mappings, terminology)));
            ObjectProvider<ExternalTerminologyValidation> externalProvider = mock();
            when(externalProvider.getIfAvailable()).thenReturn(mock(ExternalTerminologyValidation.class));
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerSingleton("billingCodeValidator", billingCodeValidator);
            ObjectProvider<BillingCodeValidator> billingProvider =
                    beanFactory.getBeanProvider(BillingCodeValidator.class);
            ValidationServiceImp validationService = new ValidationServiceImp(
                    templateService,
                    new TerminologyServiceImp(),
                    new ValidationProperties(true, true, true),
                    externalProvider,
                    billingProvider,
                    false);

            UnprocessableEntityException exception =
                    assertThrows(UnprocessableEntityException.class, () -> validationService.check(composition));

            assertThat(exception.getMessage())
                    .contains("codeSystem=ICD10", "codeSystem=CPT", "codeSystem=HCPCS", "codeSystem=SNOMED_CT");
            assertThat(exception.getMessage().lines()).hasSize(4);
            assertThat(terminologyServer.requestCount()).isEqualTo(1);
            assertThat(terminologyServer.requestBody())
                    .contains("\"resourceType\":\"Bundle\"", "\"type\":\"batch\"")
                    .contains("\"url\":\"CodeSystem/$validate-code\"");
            assertThat(terminologyServer.entryCount()).isEqualTo(4);
            assertThat(terminologyServer.contentType()).contains("application/fhir+json");
            assertThat(terminologyServer.accept()).contains("application/fhir+json");
        }
    }

    @Test
    void serverErrorFailsClosedWhenConfigured() throws Exception {
        try (MockTerminologyServer terminologyServer = new MockTerminologyServer(true)) {
            FhirTerminologyValidation terminology = new FhirTerminologyValidation(terminologyServer.baseUrl(), true);
            assertThrows(
                    ExternalTerminologyValidationException.class,
                    () -> terminology.validateCodes(List.of(new CodeValidationRequest("system", "code", null))));
            assertThat(terminologyServer.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void serverErrorReturnsUnavailableWhenConfiguredToFailOpen() throws Exception {
        try (MockTerminologyServer terminologyServer = new MockTerminologyServer(true)) {
            FhirTerminologyValidation terminology = new FhirTerminologyValidation(terminologyServer.baseUrl(), false);
            List<CodeValidationResult> results =
                    terminology.validateCodes(List.of(new CodeValidationRequest("system", "code", null)));
            assertThat(results)
                    .singleElement()
                    .extracting(CodeValidationResult::status)
                    .isEqualTo(CodeValidationResult.Status.UNAVAILABLE);
            assertThat(terminologyServer.requestCount()).isEqualTo(1);
        }
    }

    private static Composition loadCoronaComposition() throws IOException {
        try (var input = CompositionTestDataCanonicalJson.CORONA.getStream()) {
            return CanonicalJson.MARSHAL_OM.readValue(input, Composition.class);
        }
    }

    private static WebTemplate webTemplate(String templateId) throws XmlException {
        OperationalTemplateTestData data = OperationalTemplateTestData.findByTemplateId(templateId);
        OPERATIONALTEMPLATE template = TemplateService.buildOperationalTemplate(data.getStream());
        return new OPTParser(template).parse();
    }

    private static List<DvCodedText> codedTexts(Object root) {
        List<DvCodedText> results = new ArrayList<>();
        collectCodedTexts(root, results, Collections.newSetFromMap(new IdentityHashMap<>()));
        return results;
    }

    private static void addMapping(DvCodedText text, CodePhrase target) throws Exception {
        Type mappingType = ((ParameterizedType)
                        DvCodedText.class.getMethod("getMappings").getGenericReturnType())
                .getActualTypeArguments()[0];
        Class<?> mappingClass = (Class<?>) mappingType;
        Object termMapping = mappingClass.getConstructor().newInstance();
        mappingClass.getMethod("setTarget", CodePhrase.class).invoke(termMapping, target);
        @SuppressWarnings("unchecked")
        List<Object> mappings =
                (List<Object>) DvCodedText.class.getMethod("getMappings").invoke(text);
        if (mappings == null) {
            mappings = new ArrayList<>();
            DvCodedText.class.getMethod("setMappings", List.class).invoke(text, mappings);
        }
        mappings.add(termMapping);
    }

    private static void collectCodedTexts(Object value, List<DvCodedText> results, Set<Object> visited) {
        if (value == null || !visited.add(value)) {
            return;
        }
        if (value instanceof Element element && element.getValue() instanceof DvCodedText codedText) {
            results.add(codedText);
            return;
        }
        if (value instanceof DvCodedText) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(child -> collectCodedTexts(child, results, visited));
            return;
        }
        if (!(value instanceof RMObject)) {
            return;
        }
        RMTypeInfo typeInfo = ArchieRMInfoLookup.getInstance().getTypeInfo(value.getClass());
        if (typeInfo == null) {
            return;
        }
        for (RMAttributeInfo attribute : typeInfo.getAttributes().values()) {
            if (attribute.isComputed()) {
                continue;
            }
            try {
                collectCodedTexts(attribute.getGetMethod().invoke(value), results, visited);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Unable to traverse RM attribute", e);
            }
        }
    }

    private static BillingCodeSystemMapping mapping(
            BillingCodeSystem codeSystem, String system, Set<String> terminologyIds, String valueSetUrl) {
        return new BillingCodeSystemMapping(codeSystem, system, terminologyIds, valueSetUrl);
    }

    private static final class MockTerminologyServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();
        private volatile String requestBody;
        private volatile String contentType;
        private volatile String accept;
        private volatile int entryCount;

        private MockTerminologyServer(boolean fail) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/fhir", exchange -> handle(exchange, fail));
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/fhir";
        }

        private int requestCount() {
            return requestCount.get();
        }

        private String requestBody() {
            return requestBody;
        }

        private String contentType() {
            return contentType;
        }

        private String accept() {
            return accept;
        }

        private int entryCount() {
            return entryCount;
        }

        private void handle(HttpExchange exchange, boolean fail) throws IOException {
            requestCount.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            accept = exchange.getRequestHeaders().getFirst("Accept");
            if (fail) {
                respond(exchange, 500, "{\"error\":\"unavailable\"}");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> request = OBJECT_MAPPER.readValue(requestBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) request.get("entry");
            entryCount = entries.size();
            List<Map<String, Object>> responses = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("resourceType", "Parameters");
                parameters.put(
                        "parameter",
                        List.of(
                                Map.of("name", "result", "valueBoolean", false),
                                Map.of("name", "message", "valueString", "not a billing code")));
                responses.add(Map.of("response", Map.of("status", "200 OK"), "resource", parameters));
            }
            byte[] response = OBJECT_MAPPER.writeValueAsBytes(
                    Map.of("resourceType", "Bundle", "type", "batch-response", "entry", responses));
            respond(exchange, 200, new String(response, StandardCharsets.UTF_8));
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
