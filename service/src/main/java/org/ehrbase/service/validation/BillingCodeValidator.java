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

import com.nedap.archie.rm.RMObject;
import com.nedap.archie.rm.archetyped.Locatable;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rminfo.ArchieRMInfoLookup;
import com.nedap.archie.rminfo.RMAttributeInfo;
import com.nedap.archie.rminfo.RMTypeInfo;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BillingCodeValidator {

    private static final Logger LOG = LoggerFactory.getLogger(BillingCodeValidator.class);

    private final List<BillingValidationProfile> profiles;

    public BillingCodeValidator(List<BillingValidationProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    public List<ConstraintViolation> validate(String templateId, Composition composition) {
        List<LocatedCode> locatedCodes = new ArrayList<>();
        collect(composition, "", true, locatedCodes, Collections.newSetFromMap(new IdentityHashMap<>()));

        LinkedHashMap<CheckKey, Check> checks = new LinkedHashMap<>();
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            BillingValidationProfile profile = profiles.get(profileIndex);
            if (!profile.appliesTo(templateId)) {
                continue;
            }
            for (LocatedCode locatedCode : locatedCodes) {
                for (BillingCodeSystemMapping mapping : profile.codeSystems()) {
                    if (!mapping.matches(locatedCode.terminologyId())) {
                        continue;
                    }
                    CheckKey key = new CheckKey(profileIndex, mapping, locatedCode.code());
                    Check check =
                            checks.computeIfAbsent(key, ignored -> new Check(profile, mapping, locatedCode.code()));
                    check.paths().add(locatedCode.path());
                }
            }
        }

        if (checks.isEmpty()) {
            return List.of();
        }

        List<ProviderBatch> batches = new ArrayList<>();
        Map<FhirTerminologyValidation, ProviderBatch> batchesByProvider = new IdentityHashMap<>();
        for (Check check : checks.values()) {
            ProviderBatch batch = batchesByProvider.get(check.profile().terminology());
            if (batch == null) {
                batch = new ProviderBatch(check.profile().terminology());
                batchesByProvider.put(batch.validation(), batch);
                batches.add(batch);
            }
            batch.checks().add(check);
        }

        for (ProviderBatch batch : batches) {
            List<CodeValidationRequest> requests = batch.checks().stream()
                    .map(check -> new CodeValidationRequest(
                            check.mapping().system(),
                            check.code(),
                            check.mapping().valueSetUrl()))
                    .toList();
            List<CodeValidationResult> results = batch.validation().validateCodes(requests);
            if (results == null || results.size() != batch.checks().size()) {
                throw new IllegalStateException("FHIR terminology validation returned an unexpected result count");
            }
            for (int i = 0; i < results.size(); i++) {
                batch.checks().get(i).result(results.get(i));
            }
        }

        List<ConstraintViolation> violations = new ArrayList<>();
        for (Check check : checks.values()) {
            CodeValidationResult result = check.result();
            if (result.status() == CodeValidationResult.Status.UNAVAILABLE) {
                LOG.warn(
                        "Billing terminology validation unavailable for profile '{}' and code '{}'",
                        check.profile().name(),
                        check.code());
                continue;
            }
            if (result.status() != CodeValidationResult.Status.INVALID) {
                continue;
            }
            for (String path : check.paths()) {
                String message = "Invalid billing code: profile=%s, codeSystem=%s, system=%s, code=%s, path=%s"
                        .formatted(
                                check.profile().name(),
                                check.mapping().codeSystem().displayName(),
                                check.mapping().system(),
                                check.code(),
                                path);
                violations.add(new ConstraintViolation(path, message));
            }
        }
        return violations;
    }

    private static void collect(
            Object object, String path, boolean root, List<LocatedCode> locatedCodes, Set<Object> visited) {
        if (object == null) {
            return;
        }
        if (!visited.add(object)) {
            return;
        }
        if (object instanceof DvCodedText codedText) {
            addCode(codedText.getDefiningCode(), path, locatedCodes);
            collectMappings(codedText, path, locatedCodes);
            return;
        }
        if (object instanceof DvText text) {
            collectMappings(text, path, locatedCodes);
            return;
        }
        if (object instanceof Collection<?> collection) {
            for (Object child : collection) {
                collect(child, path, false, locatedCodes, visited);
            }
            return;
        }
        if (!(object instanceof RMObject)) {
            return;
        }

        String currentPath = path;
        if (!root && object instanceof Locatable locatable && locatable.getArchetypeNodeId() != null) {
            currentPath += "[" + locatable.getArchetypeNodeId() + "]";
        }

        RMTypeInfo typeInfo = ArchieRMInfoLookup.getInstance().getTypeInfo(object.getClass());
        if (typeInfo == null) {
            return;
        }
        for (RMAttributeInfo attribute : typeInfo.getAttributes().values()) {
            if (attribute.isComputed()) {
                continue;
            }
            try {
                Object value = attribute.getGetMethod().invoke(object);
                String childPath = appendPath(currentPath, attribute.getRmName());
                if (value instanceof List<?> list && "mappings".equals(attribute.getRmName())) {
                    for (int i = 0; i < list.size(); i++) {
                        collect(list.get(i), childPath + "[" + i + "]", false, locatedCodes, visited);
                    }
                } else {
                    collect(value, childPath, false, locatedCodes, visited);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(
                        "Unable to traverse RM attribute '%s'".formatted(attribute.getRmName()), e);
            }
        }
    }

    private static void collectMappings(DvText text, String path, List<LocatedCode> locatedCodes) {
        List<?> mappings = text.getMappings();
        if (mappings == null) {
            return;
        }
        for (int i = 0; i < mappings.size(); i++) {
            Object mapping = mappings.get(i);
            if (mapping == null) {
                continue;
            }
            try {
                Object target = mapping.getClass().getMethod("getTarget").invoke(mapping);
                if (target instanceof CodePhrase codePhrase) {
                    addCode(codePhrase, path + "/mappings[" + i + "]/target", locatedCodes);
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Unable to read DvText mapping target", e);
            }
        }
    }

    private static void addCode(CodePhrase codePhrase, String path, List<LocatedCode> locatedCodes) {
        if (codePhrase == null || codePhrase.getTerminologyId() == null) {
            return;
        }
        locatedCodes.add(new LocatedCode(codePhrase.getTerminologyId().getValue(), codePhrase.getCodeString(), path));
    }

    private static String appendPath(String path, String attributeName) {
        return path.isEmpty() ? "/" + attributeName : path + "/" + attributeName;
    }

    private record LocatedCode(String terminologyId, String code, String path) {}

    private record CheckKey(int profileIndex, BillingCodeSystemMapping mapping, String code) {}

    private static final class Check {
        private final BillingValidationProfile profile;
        private final BillingCodeSystemMapping mapping;
        private final String code;
        private final Set<String> paths = new LinkedHashSet<>();
        private CodeValidationResult result;

        private Check(BillingValidationProfile profile, BillingCodeSystemMapping mapping, String code) {
            this.profile = profile;
            this.mapping = mapping;
            this.code = code;
        }

        private BillingValidationProfile profile() {
            return profile;
        }

        private BillingCodeSystemMapping mapping() {
            return mapping;
        }

        private String code() {
            return code;
        }

        private Set<String> paths() {
            return paths;
        }

        private CodeValidationResult result() {
            return result;
        }

        private void result(CodeValidationResult result) {
            this.result = result;
        }
    }

    private record ProviderBatch(FhirTerminologyValidation validation, List<Check> checks) {
        private ProviderBatch(FhirTerminologyValidation validation) {
            this(validation, new ArrayList<>());
        }
    }
}
