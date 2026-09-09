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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.ehrbase.api.dto.AqlQueryRequest;
import org.ehrbase.api.dto.fhir.FhirCodeableConcept;
import org.ehrbase.api.dto.fhir.FhirExplanationOfBenefit;
import org.ehrbase.api.dto.fhir.FhirMoney;
import org.ehrbase.api.dto.fhir.FhirPeriod;
import org.ehrbase.api.dto.fhir.FhirReference;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.api.service.AqlQueryService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.api.service.ExplanationOfBenefitService;
import org.ehrbase.openehr.sdk.aql.dto.AqlQuery;
import org.ehrbase.openehr.sdk.aql.dto.select.SelectExpression;
import org.ehrbase.openehr.sdk.aql.parser.AqlQueryParser;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryResultDto;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.query.ResultHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Reads FHIR ExplanationOfBenefit resources from an AQL result.
 *
 * <p>The configured AQL must select one row per EOB and use these aliases:
 *
 * <ul>
 *   <li>Required: {@code eob_id}, {@code status}, {@code type_code}, {@code type_system}, {@code use},
 *       {@code created}, {@code insurer_display}, {@code provider_display}, {@code outcome},
 *       {@code coverage_reference}
 *   <li>Optional: {@code diagnosis_code}, {@code diagnosis_system}, {@code procedure_code},
 *       {@code procedure_system}, {@code item_code}, {@code item_system}, {@code item_net},
 *       {@code item_currency}, {@code billable_start}, {@code billable_end}
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "ehrbase.fhir.billing", name = "enabled", havingValue = "true")
public class ExplanationOfBenefitServiceImp implements ExplanationOfBenefitService {

    private static final Set<String> REQUIRED_ALIASES = Set.of(
            "eob_id",
            "status",
            "type_code",
            "type_system",
            "use",
            "created",
            "insurer_display",
            "provider_display",
            "outcome",
            "coverage_reference");

    private final EhrService ehrService;
    private final AqlQueryService aqlQueryService;
    private final FhirBillingProperties properties;

    public ExplanationOfBenefitServiceImp(
            EhrService ehrService, AqlQueryService aqlQueryService, FhirBillingProperties properties) {
        this.ehrService = ehrService;
        this.aqlQueryService = aqlQueryService;
        this.properties = properties;

        requiredProperty(properties.subjectNamespace(), "subjectNamespace");
        AqlQuery parsed = AqlQueryParser.parse(requiredProperty(properties.eobAql(), "eobAql"));
        Set<String> aliases = parsed.getSelect().getStatement().stream()
                .map(SelectExpression::getAlias)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> missingAliases = new HashSet<>(REQUIRED_ALIASES);
        missingAliases.removeAll(aliases);
        if (!missingAliases.isEmpty()) {
            throw new IllegalStateException("FHIR EOB AQL is missing required aliases: " + missingAliases);
        }
        if (!properties.eobAql().contains("$ehr_id")) {
            throw new IllegalStateException("FHIR EOB AQL must reference the $ehr_id parameter");
        }
    }

    @Override
    public EobPage search(String subjectId, int count, int offset) {
        int effectiveCount = normalizeCount(count);
        int effectiveOffset = Math.max(0, offset);

        return ehrService
                .findBySubject(subjectId, properties.subjectNamespace())
                .filter(ehrService::isQueryable)
                .map(ehrId -> query(subjectId, ehrId.toString(), effectiveCount, effectiveOffset))
                .orElseGet(() -> new EobPage(List.of(), effectiveCount, effectiveOffset, false));
    }

    private EobPage query(String subjectId, String ehrId, int count, int offset) {
        AqlQueryRequest request = AqlQueryRequest.prepareNamed(
                properties.eobAql(), "fhir-eob", Map.of("ehr_id", ehrId), (long) count + 1, (long) offset);
        QueryResultDto result = aqlQueryService.query(request);
        List<ResultHolder> rows = result.getResultSet() == null ? List.of() : result.getResultSet();
        boolean hasMore = rows.size() > count;
        List<FhirExplanationOfBenefit> resources =
                rows.stream().limit(count).map(row -> mapRow(subjectId, row)).toList();
        return new EobPage(resources, count, offset, hasMore);
    }

    private FhirExplanationOfBenefit mapRow(String subjectId, ResultHolder row) {
        Map<String, Object> values = rowValues(row);
        String eobId = required(values, "eob_id").toString();
        String status = required(values, "status").toString();
        String typeCode = required(values, "type_code").toString();
        String typeSystem = required(values, "type_system").toString();
        String use = required(values, "use").toString();
        String created = required(values, "created").toString();
        String insurerDisplay = required(values, "insurer_display").toString();
        String providerDisplay = required(values, "provider_display").toString();
        String outcome = required(values, "outcome").toString();
        String coverageReference = required(values, "coverage_reference").toString();

        return new FhirExplanationOfBenefit(
                "ExplanationOfBenefit",
                eobId,
                status,
                FhirCodeableConcept.of(typeSystem, typeCode),
                use,
                new FhirReference("Patient/" + subjectId, null),
                created,
                new FhirReference(null, insurerDisplay),
                new FhirReference(null, providerDisplay),
                outcome,
                List.of(new FhirExplanationOfBenefit.Insurance(true, new FhirReference(coverageReference, null))),
                optionalCodeableConcept(values, "diagnosis_code", "diagnosis_system")
                        .map(code -> List.of(new FhirExplanationOfBenefit.Diagnosis(1, code)))
                        .orElse(null),
                optionalCodeableConcept(values, "procedure_code", "procedure_system")
                        .map(code -> List.of(new FhirExplanationOfBenefit.Procedure(1, code)))
                        .orElse(null),
                optionalItem(values),
                optionalPeriod(values));
    }

    private List<FhirExplanationOfBenefit.Item> optionalItem(Map<String, Object> values) {
        FhirCodeableConcept product =
                optionalCodeableConcept(values, "item_code", "item_system").orElse(null);
        Object net = values.get("item_net");
        Object currency = values.get("item_currency");
        FhirMoney money = net == null && currency == null
                ? null
                : new FhirMoney(
                        net instanceof BigDecimal decimal
                                ? decimal
                                : net == null ? null : new BigDecimal(net.toString()),
                        currency == null ? null : currency.toString());
        if (product == null && money == null) {
            return null;
        }
        return List.of(new FhirExplanationOfBenefit.Item(1, product, money));
    }

    private Optional<FhirCodeableConcept> optionalCodeableConcept(
            Map<String, Object> values, String codeAlias, String systemAlias) {
        Object code = values.get(codeAlias);
        Object system = values.get(systemAlias);
        if (code == null && system == null) {
            return Optional.empty();
        }
        return Optional.of(FhirCodeableConcept.of(
                system == null ? null : system.toString(), code == null ? null : code.toString()));
    }

    private FhirPeriod optionalPeriod(Map<String, Object> values) {
        Object start = values.get("billable_start");
        Object end = values.get("billable_end");
        return start == null && end == null
                ? null
                : new FhirPeriod(start == null ? null : start.toString(), end == null ? null : end.toString());
    }

    private static Map<String, Object> rowValues(ResultHolder row) {
        List<String> columns = new ArrayList<>(row.columnIds());
        List<Object> values = row.values();
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < columns.size() && i < values.size(); i++) {
            result.put(columns.get(i), values.get(i));
        }
        return result;
    }

    private static Object required(Map<String, Object> values, String alias) {
        Object value = values.get(alias);
        if (value == null) {
            throw new InternalServerException("FHIR EOB AQL result is missing required column '%s'".formatted(alias));
        }
        return value;
    }

    private int normalizeCount(int count) {
        int requested = count <= 0 ? properties.defaultCount() : count;
        return Math.min(Math.max(1, properties.maxCount()), Math.max(1, requested));
    }

    private static String requiredProperty(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "FHIR billing property '%s' is required when billing is enabled".formatted(name));
        }
        return value;
    }
}
