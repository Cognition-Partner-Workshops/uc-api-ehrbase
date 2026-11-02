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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;

/** Collects billing-coded text from compositions for opt-in terminology validation. */
public class BillingCodeValidator {

    private final List<BillingValidationProfile> profiles;

    public BillingCodeValidator(Collection<BillingValidationProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    public List<ConstraintViolation> validate(String templateId, Composition composition) {
        Map<FhirTerminologyValidation, List<FhirTerminologyValidation.BillingCode>> requests = new LinkedHashMap<>();
        for (BillingValidationProfile profile : profiles) {
            if (!profile.templateIds().isEmpty() && !profile.templateIds().contains(templateId)) {
                continue;
            }
            Map<String, BillingValidationProfile.CodeSystem> aliases = new LinkedHashMap<>();
            profile.codeSystems()
                    .forEach(system -> system.terminologyIds().forEach(alias -> aliases.put(alias, system)));
            List<FhirTerminologyValidation.BillingCode> codes =
                    requests.computeIfAbsent(profile.terminologyValidation(), ignored -> new ArrayList<>());
            collectValue(
                    composition,
                    aliases,
                    profile.name(),
                    "",
                    codes,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        List<ConstraintViolation> violations = new ArrayList<>();
        requests.forEach((provider, codes) -> violations.addAll(provider.validateCodes(codes)));
        return violations;
    }

    private static void collectValue(
            Object object,
            Map<String, BillingValidationProfile.CodeSystem> aliases,
            String profile,
            String path,
            List<FhirTerminologyValidation.BillingCode> codes,
            Set<Object> visited) {
        if (object == null || !visited.add(object)) {
            return;
        }
        if (object instanceof DvCodedText codedText) {
            CodePhrase code = codedText.getDefiningCode();
            if (code != null && code.getTerminologyId() != null) {
                BillingValidationProfile.CodeSystem system =
                        aliases.get(code.getTerminologyId().getValue());
                if (system != null) {
                    codes.add(new FhirTerminologyValidation.BillingCode(
                            profile, system.name(), system.system(), code.getCodeString(), path, system.valueSet()));
                }
            }
        } else if (object instanceof Collection<?> collection) {
            int index = 0;
            for (Object value : collection) {
                collectValue(value, aliases, profile, path + "[" + index++ + "]", codes, visited);
            }
        } else if (object instanceof RMObject) {
            RMTypeInfo typeInfo = ArchieRMInfoLookup.getInstance().getTypeInfo(object.getClass());
            if (typeInfo == null) {
                return;
            }
            for (RMAttributeInfo attribute : typeInfo.getAttributes().values()) {
                if (attribute.isComputed()) {
                    continue;
                }
                try {
                    collectValue(
                            attribute.getGetMethod().invoke(object),
                            aliases,
                            profile,
                            path + "/" + attribute.getRmName(),
                            codes,
                            visited);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException(
                            "Unable to traverse RM attribute '%s'".formatted(attribute.getRmName()), e);
                }
            }
        }
    }
}
