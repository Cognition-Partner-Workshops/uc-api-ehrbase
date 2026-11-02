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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class BillingCodeValidatorTest {

    private static final String TEMPLATE_ID = "billing_claim.v1";
    private static final String CONTENT_NODE_ID = "openEHR-EHR-ADMIN_ENTRY.billing_claim.v0";
    private static final String SYSTEM_VALUE_SET = "http://ehrbase.org/fhir/ValueSet/billing-snomed";

    @ParameterizedTest
    @MethodSource("billingMappingsAndStatuses")
    void validatesValidAndInvalidCodesForEachSystem(MappingCase mappingCase, CodeValidationResult.Status status) {
        BillingCodeSystemMapping mapping = new BillingCodeSystemMapping(
                mappingCase.codeSystem(),
                mappingCase.system(),
                mappingCase.terminologyIds(),
                mappingCase.valueSetUrl());
        FhirTerminologyValidation terminology = validationReturning(status, "not a billing code");
        BillingValidationProfile profile = profile("claims", Set.of(TEMPLATE_ID), List.of(mapping), terminology);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(profile));
        String code = mappingCase.code();

        List<ConstraintViolation> violations = validator.validate(
                TEMPLATE_ID,
                composition(new DvCodedText(
                        "billing code", new CodePhrase(new TerminologyId(mappingCase.inputTerminologyId()), code))));

        ArgumentCaptor<List<CodeValidationRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(terminology).validateCodes(requests.capture());
        assertThat(requests.getValue())
                .containsExactly(new CodeValidationRequest(mappingCase.system(), code, mappingCase.valueSetUrl()));
        String path = valuePath("at0002");
        if (status == CodeValidationResult.Status.INVALID) {
            assertThat(violations).singleElement().satisfies(violation -> {
                assertThat(violation.getAqlPath()).isEqualTo(path);
                assertThat(violation.getMessage())
                        .isEqualTo("Invalid billing code: profile=claims, codeSystem=%s, system=%s, code=%s, path=%s"
                                .formatted(mappingCase.codeSystem().displayName(), mappingCase.system(), code, path));
            });
        } else {
            assertThat(violations).isEmpty();
        }
    }

    @Test
    void ignoresNonBillingTerminologies() {
        FhirTerminologyValidation terminology = mock(FhirTerminologyValidation.class);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(profile(
                "claims",
                Set.of(TEMPLATE_ID),
                List.of(mapping(BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null)),
                terminology)));

        List<ConstraintViolation> violations = validator.validate(
                TEMPLATE_ID,
                composition(
                        codedText("openehr", "1"),
                        codedText("local", "2"),
                        codedText("LOINC", "3"),
                        codedText("http://loinc.org", "4")));

        assertThat(violations).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(terminology);
    }

    @Test
    void matchesAliasesCaseInsensitivelyAndSystemUriAsTerminologyId() {
        BillingCodeSystemMapping mapping = mapping(
                BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10", "ICD-10-CM"), null);
        FhirTerminologyValidation terminology = validationReturning(CodeValidationResult.Status.VALID, null);
        BillingCodeValidator validator = new BillingCodeValidator(
                List.of(profile("claims", Set.of(TEMPLATE_ID), List.of(mapping), terminology)));

        validator.validate(TEMPLATE_ID, composition(codedText("iCd-10-cM", "A01")));
        validator.validate(TEMPLATE_ID, composition(codedText("HTTP://HL7.ORG/FHIR/SID/ICD-10-CM", "A02")));

        ArgumentCaptor<List<CodeValidationRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(terminology, org.mockito.Mockito.times(2)).validateCodes(requests.capture());
        assertThat(requests.getAllValues())
                .allSatisfy(batch -> assertThat(batch).hasSize(1));
    }

    @Test
    void appliesTemplateScopingAndEmptyTemplateIdsApplyToAll() {
        FhirTerminologyValidation listedTerminology = validationReturning(CodeValidationResult.Status.VALID, null);
        BillingCodeSystemMapping icd10 =
                mapping(BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null);
        BillingCodeValidator listedValidator = new BillingCodeValidator(
                List.of(profile("listed", Set.of(TEMPLATE_ID), List.of(icd10), listedTerminology)));

        listedValidator.validate(TEMPLATE_ID, composition(codedText("ICD10", "E11.9")));
        listedValidator.validate("other.template", composition(codedText("ICD10", "E11.9")));

        verify(listedTerminology).validateCodes(anyList());

        FhirTerminologyValidation allTemplatesTerminology =
                validationReturning(CodeValidationResult.Status.VALID, null);
        BillingCodeValidator allTemplatesValidator =
                new BillingCodeValidator(List.of(profile("all", Set.of(), List.of(icd10), allTemplatesTerminology)));
        allTemplatesValidator.validate("other.template", composition(codedText("ICD10", "E11.9")));
        verify(allTemplatesTerminology).validateCodes(anyList());
    }

    @Test
    void deduplicatesRequestsButReportsEachDistinctPath() {
        FhirTerminologyValidation terminology = validationReturning(CodeValidationResult.Status.INVALID, null);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(profile(
                "claims",
                Set.of(TEMPLATE_ID),
                List.of(mapping(BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null)),
                terminology)));

        List<ConstraintViolation> violations = validator.validate(
                TEMPLATE_ID, composition(codedText("ICD10", "Z99.999"), codedText("ICD10", "Z99.999")));

        ArgumentCaptor<List<CodeValidationRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(terminology).validateCodes(requests.capture());
        assertThat(requests.getValue())
                .containsExactly(new CodeValidationRequest("http://hl7.org/fhir/sid/icd-10-cm", "Z99.999", null));
        assertThat(violations)
                .extracting(ConstraintViolation::getAqlPath)
                .containsExactly(valuePath("at0002"), valuePath("at0003"));
    }

    @Test
    void collectsTermMappingsWithOpenEhrPath() throws Exception {
        FhirTerminologyValidation terminology = validationReturning(CodeValidationResult.Status.INVALID, null);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(profile(
                "claims",
                Set.of(TEMPLATE_ID),
                List.of(mapping(BillingCodeSystem.CPT, "http://www.ama-assn.org/go/cpt", Set.of("CPT", "CPT4"), null)),
                terminology)));
        DvText text = textWithMapping(new CodePhrase(new TerminologyId("CPT4"), "99213"));

        List<ConstraintViolation> violations = validator.validate(TEMPLATE_ID, composition(text));

        assertThat(violations)
                .singleElement()
                .extracting(ConstraintViolation::getAqlPath)
                .isEqualTo(valuePath("at0002") + "/mappings[0]/target");
        ArgumentCaptor<List<CodeValidationRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(terminology).validateCodes(requests.capture());
        assertThat(requests.getValue())
                .containsExactly(new CodeValidationRequest("http://www.ama-assn.org/go/cpt", "99213", null));
    }

    @Test
    void ignoresCodeSystemsNotConfiguredAsEnabledMappings() {
        FhirTerminologyValidation terminology = mock(FhirTerminologyValidation.class);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(profile(
                "claims",
                Set.of(TEMPLATE_ID),
                List.of(mapping(BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null)),
                terminology)));

        assertThat(validator.validate(TEMPLATE_ID, composition(codedText("CPT", "99213"))))
                .isEmpty();

        org.mockito.Mockito.verifyNoInteractions(terminology);
    }

    @Test
    void unavailableResultsDoNotProduceViolations() {
        FhirTerminologyValidation terminology = validationReturning(CodeValidationResult.Status.UNAVAILABLE, null);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(profile(
                "claims",
                Set.of(TEMPLATE_ID),
                List.of(mapping(BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null)),
                terminology)));

        assertThat(validator.validate(TEMPLATE_ID, composition(codedText("ICD10", "Z99.999"))))
                .isEmpty();
    }

    @Test
    void noMatchingCodesDoesNotCallProvider() {
        FhirTerminologyValidation terminology = mock(FhirTerminologyValidation.class);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(profile(
                "claims",
                Set.of(TEMPLATE_ID),
                List.of(mapping(BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null)),
                terminology)));

        assertThat(validator.validate(TEMPLATE_ID, composition(new DvText("plain text"))))
                .isEmpty();

        org.mockito.Mockito.verifyNoInteractions(terminology);
    }

    @Test
    void profilesSharingProviderUseOneBatchRequest() {
        FhirTerminologyValidation terminology = validationReturning(CodeValidationResult.Status.VALID, null);
        BillingCodeValidator validator = new BillingCodeValidator(List.of(
                profile(
                        "diagnosis",
                        Set.of(TEMPLATE_ID),
                        List.of(mapping(
                                BillingCodeSystem.ICD10, "http://hl7.org/fhir/sid/icd-10-cm", Set.of("ICD10"), null)),
                        terminology),
                profile(
                        "procedure",
                        Set.of(TEMPLATE_ID),
                        List.of(mapping(BillingCodeSystem.CPT, "http://www.ama-assn.org/go/cpt", Set.of("CPT"), null)),
                        terminology)));

        validator.validate(TEMPLATE_ID, composition(codedText("ICD10", "E11.9"), codedText("CPT", "99213")));

        ArgumentCaptor<List<CodeValidationRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(terminology).validateCodes(requests.capture());
        assertThat(requests.getValue())
                .containsExactly(
                        new CodeValidationRequest("http://hl7.org/fhir/sid/icd-10-cm", "E11.9", null),
                        new CodeValidationRequest("http://www.ama-assn.org/go/cpt", "99213", null));
    }

    private static Stream<Arguments> billingMappingsAndStatuses() {
        return mappingCases()
                .flatMap(mappingCase -> Stream.of(
                        Arguments.of(mappingCase, CodeValidationResult.Status.VALID),
                        Arguments.of(mappingCase, CodeValidationResult.Status.INVALID)));
    }

    private static Stream<MappingCase> mappingCases() {
        return Stream.of(
                new MappingCase(
                        BillingCodeSystem.ICD10,
                        "http://hl7.org/fhir/sid/icd-10-cm",
                        Set.of("ICD10", "ICD-10", "ICD10CM", "ICD-10-CM"),
                        "ICD-10",
                        "E11.9",
                        null),
                new MappingCase(
                        BillingCodeSystem.CPT,
                        "http://www.ama-assn.org/go/cpt",
                        Set.of("CPT", "CPT4", "CPT-4"),
                        "CPT4",
                        "99213",
                        null),
                new MappingCase(
                        BillingCodeSystem.HCPCS,
                        "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets",
                        Set.of("HCPCS"),
                        "HCPCS",
                        "G0438",
                        null),
                new MappingCase(
                        BillingCodeSystem.SNOMED_CT,
                        "http://snomed.info/sct",
                        Set.of("SNOMED-CT", "SNOMED CT", "SNOMEDCT", "SNOMED"),
                        "SNOMED-CT",
                        "44054006",
                        null),
                new MappingCase(
                        BillingCodeSystem.SNOMED_CT,
                        "http://snomed.info/sct",
                        Set.of("SNOMED-CT", "SNOMED CT", "SNOMEDCT", "SNOMED"),
                        "SNOMED",
                        "44054006",
                        SYSTEM_VALUE_SET));
    }

    private static FhirTerminologyValidation validationReturning(CodeValidationResult.Status status, String message) {
        FhirTerminologyValidation terminology = mock(FhirTerminologyValidation.class);
        when(terminology.validateCodes(anyList())).thenAnswer(invocation -> {
            List<CodeValidationRequest> requests = invocation.getArgument(0);
            return requests.stream()
                    .map(request -> new CodeValidationResult(request, status, message))
                    .toList();
        });
        return terminology;
    }

    private static BillingValidationProfile profile(
            String name,
            Set<String> templateIds,
            List<BillingCodeSystemMapping> mappings,
            FhirTerminologyValidation terminology) {
        return new BillingValidationProfile(name, templateIds, mappings, terminology);
    }

    private static BillingCodeSystemMapping mapping(
            BillingCodeSystem codeSystem, String system, Set<String> aliases, String valueSetUrl) {
        return new BillingCodeSystemMapping(codeSystem, system, aliases, valueSetUrl);
    }

    private static DvCodedText codedText(String terminologyId, String code) {
        return new DvCodedText("billing code", new CodePhrase(new TerminologyId(terminologyId), code));
    }

    private static Composition composition(DvText... values) {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.test.v1");
        Evaluation evaluation = new Evaluation();
        evaluation.setArchetypeNodeId(CONTENT_NODE_ID);
        ItemTree data = new ItemTree();
        data.setArchetypeNodeId("at0001");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            Element element = new Element("billing code", new DvText("billing code"), values[i]);
            element.setArchetypeNodeId("at000" + (i + 2));
            items.add(element);
        }
        data.setItems(items);
        evaluation.setData(data);
        composition.setContent(List.of(evaluation));
        return composition;
    }

    private static DvText textWithMapping(CodePhrase target) throws Exception {
        DvText text = new DvText("billing code text");
        Type mappingType = ((ParameterizedType)
                        DvText.class.getMethod("getMappings").getGenericReturnType())
                .getActualTypeArguments()[0];
        Class<?> mappingClass = (Class<?>) mappingType;
        Object termMapping = mappingClass.getConstructor().newInstance();
        mappingClass.getMethod("setTarget", CodePhrase.class).invoke(termMapping, target);
        @SuppressWarnings("unchecked")
        List<Object> mappings =
                (List<Object>) DvText.class.getMethod("getMappings").invoke(text);
        if (mappings == null) {
            mappings = new ArrayList<>();
            DvText.class.getMethod("setMappings", List.class).invoke(text, mappings);
        }
        mappings.add(termMapping);
        return text;
    }

    private static String valuePath(String elementNodeId) {
        return "/content[" + CONTENT_NODE_ID + "]/data[at0001]/items[" + elementNodeId + "]/value";
    }

    private record MappingCase(
            BillingCodeSystem codeSystem,
            String system,
            Set<String> terminologyIds,
            String inputTerminologyId,
            String code,
            String valueSetUrl) {}
}
