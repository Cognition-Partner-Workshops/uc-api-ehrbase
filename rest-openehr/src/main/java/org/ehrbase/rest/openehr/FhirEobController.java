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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.ehrbase.api.dto.fhir.FhirBundle;
import org.ehrbase.api.dto.fhir.FhirBundleEntry;
import org.ehrbase.api.dto.fhir.FhirBundleLink;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.ExplanationOfBenefitService;
import org.ehrbase.rest.BaseController;
import org.ehrbase.rest.openehr.specification.FhirEobApiSpecification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@ConditionalOnProperty(prefix = "ehrbase.fhir.billing", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(name = "fhirEobController")
@RequestMapping(path = "${ehrbase.fhir.billing.context-path:/ehrbase/rest/fhir/r4}/ExplanationOfBenefit")
public class FhirEobController extends BaseController implements FhirEobApiSpecification {

    public static final String FHIR_JSON = "application/fhir+json";

    private static final Pattern PATIENT_REFERENCE = Pattern.compile("^Patient/([A-Za-z0-9\\-.]{1,64})$");

    private final ExplanationOfBenefitService service;

    public FhirEobController(ExplanationOfBenefitService service) {
        this.service = service;
    }

    @Override
    @GetMapping(produces = FHIR_JSON)
    public ResponseEntity<FhirBundle> search(
            @RequestParam("patient") String patient,
            @RequestParam(value = "_count", required = false) Integer count,
            @RequestParam(value = "_offset", required = false) Integer offset) {

        Matcher matcher = patient == null ? null : PATIENT_REFERENCE.matcher(patient);
        if (matcher == null || !matcher.matches()) {
            throw new InvalidApiParameterException("patient must be a relative Patient/{id} reference");
        }

        int requestedCount = count == null ? 0 : count;
        int requestedOffset = offset == null ? 0 : offset;
        if (requestedCount < 0) {
            throw new InvalidApiParameterException("count must not be negative");
        }
        if (requestedOffset < 0) {
            throw new InvalidApiParameterException("offset must not be negative");
        }

        String subjectId = matcher.group(1);
        ExplanationOfBenefitService.EobPage page = service.search(subjectId, requestedCount, requestedOffset);
        FhirBundle bundle = bundle(patient, page);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(FHIR_JSON))
                .body(bundle);
    }

    protected UriComponentsBuilder currentRequest() {
        return ServletUriComponentsBuilder.fromCurrentRequest();
    }

    private FhirBundle bundle(String patient, ExplanationOfBenefitService.EobPage page) {
        UriComponentsBuilder request = currentRequest().cloneBuilder();
        String self = request.build().toUriString();
        String requestBaseUrl =
                request.cloneBuilder().replaceQuery(null).build().toUriString();

        List<FhirBundleEntry> entries = page.resources().stream()
                .map(resource -> new FhirBundleEntry(
                        UriComponentsBuilder.fromUriString(requestBaseUrl)
                                .pathSegment(resource.id())
                                .build()
                                .toUriString(),
                        resource))
                .toList();

        List<FhirBundleLink> links = new ArrayList<>();
        links.add(new FhirBundleLink("self", self));
        if (page.hasMore()) {
            String next = request.cloneBuilder()
                    .replaceQuery(null)
                    .queryParam("patient", patient)
                    .queryParam("_count", page.count())
                    .queryParam("_offset", page.offset() + page.count())
                    .encode()
                    .build()
                    .toUriString();
            links.add(new FhirBundleLink("next", next));
        }

        Integer total = page.hasMore() ? null : page.offset() + entries.size();
        return new FhirBundle("Bundle", UUID.randomUUID().toString(), "searchset", total, links, entries);
    }
}
