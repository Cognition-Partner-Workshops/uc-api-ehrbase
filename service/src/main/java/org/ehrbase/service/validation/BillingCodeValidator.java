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
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rminfo.ArchieRMInfoLookup;
import com.nedap.archie.rminfo.RMAttributeInfo;
import com.nedap.archie.rminfo.RMTypeInfo;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.ConstraintViolationException;

public class BillingCodeValidator {

    private final Map<String, BillingValidationProfile> profilesByTemplateId;

    public BillingCodeValidator(Collection<BillingValidationProfile> profiles) {
        Map<String, BillingValidationProfile> byTemplateId = new java.util.LinkedHashMap<>();
        for (BillingValidationProfile profile : profiles) {
            for (String templateId : profile.templateIds()) {
                BillingValidationProfile previous = byTemplateId.putIfAbsent(templateId, profile);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Billing template ID '%s' is configured in profiles '%s' and '%s'"
                                    .formatted(templateId, previous.name(), profile.name()));
                }
            }
        }
        profilesByTemplateId = Map.copyOf(byTemplateId);
    }

    public Optional<BillingValidationProfile> findProfile(String templateId) {
        return Optional.ofNullable(profilesByTemplateId.get(templateId));
    }

    public void validate(String templateId, Composition composition) {
        Optional<BillingValidationProfile> profile = findProfile(templateId);
        if (profile.isEmpty()) {
            return;
        }

        List<CodePhrase> codes = collectCodes(composition, profile.get().codeSystems());
        if (codes.isEmpty()) {
            return;
        }

        List<ConstraintViolation> violations = profile.get().terminologyValidation().validateCodes(codes);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    static List<CodePhrase> collectCodes(Composition composition, Set<String> codeSystems) {
        List<CodePhrase> codes = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectValue(composition, codeSystems, codes, visited);
        return codes;
    }

    private static void collectValue(
            Object object, Set<String> codeSystems, List<CodePhrase> codes, Set<Object> visited) {
        if (object == null) {
            return;
        }
        if (object instanceof DvCodedText codedText) {
            CodePhrase definingCode = codedText.getDefiningCode();
            if (definingCode != null
                    && definingCode.getTerminologyId() != null
                    && codeSystems.contains(definingCode.getTerminologyId().getValue())) {
                codes.add(definingCode);
            }
            return;
        }
        if (object instanceof Collection<?> collection) {
            if (!visited.add(object)) {
                return;
            }
            for (Object value : collection) {
                collectValue(value, codeSystems, codes, visited);
            }
            return;
        }
        if (object instanceof Map<?, ?> map) {
            if (!visited.add(object)) {
                return;
            }
            for (Object value : map.values()) {
                collectValue(value, codeSystems, codes, visited);
            }
            return;
        }
        if (!(object instanceof RMObject) || !visited.add(object)) {
            return;
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
                collectValue(attribute.getGetMethod().invoke(object), codeSystems, codes, visited);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(
                        "Unable to traverse RM attribute '%s'".formatted(attribute.getRmName()), e);
            }
        }
    }
}
