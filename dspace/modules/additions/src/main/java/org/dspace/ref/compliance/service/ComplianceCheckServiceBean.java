package org.dspace.ref.compliance.service;

import com.atmire.utils.EmbargoUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.dspace.content.DCDate;
import org.dspace.content.Item;
import org.dspace.content.Metadatum;
import org.dspace.core.Context;
import org.dspace.ref.compliance.result.ComplianceResult;
import org.dspace.ref.compliance.rules.CompliancePolicy;
import org.dspace.ref.compliance.rules.exception.ValidationRuleDefinitionException;
import org.dspace.ref.compliance.rules.factory.ComplianceCategoryRulesFactory;
import org.dspace.util.subclasses.Metadata;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link ComplianceCheckService}
 */
public class ComplianceCheckServiceBean implements ComplianceCheckService {

    private static Logger log = Logger.getLogger(ComplianceCheckServiceBean.class);

    private Map<String, String> fakeIfEmptyDuringValidation;
    private Map<String, String> fakeIfEmptyDuringUnarchivedValidation;

    private final String now = "now";
    private final String embargoOrNow = "embargoOrNow";
    private final String fullIso = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    private final String blockWorkflowConfig = ".workflow.block.on.rule.violation.";
    private final String defaultConfig = "default";

    private String identifier;

    private ComplianceCategoryRulesFactory rulesFactory;

    public ComplianceResult checkCompliance(final Context context, final Item item) {
        CompliancePolicy policy = getCompliancePolicy();

        if (policy == null) {
            return new ComplianceResult();

        } else {
            ComplianceResult complianceResult = null;

            // temporarily add fake values for empty fields so validation does not fail on these fields
            List<Metadata> fakeFields = addFakeValues(context, item);

            try {
                complianceResult = policy.validatePreconditionRules(context, item);

                if (complianceResult.isApplicable()) {
                    complianceResult = policy.validate(context, item, complianceResult);

                    complianceResult.addEstimatedValues(fakeFields);

                }

            } catch(Exception ex) {
                context.abort();
                log.warn(ex.getMessage(), ex);

            } finally {
                // Always remove the temporary values
                removeFakeValues(context, fakeFields, item);
            }

            return complianceResult;
        }
    }

    @Override
    public boolean blockOnWorkflow(String collectionHandle) {
        String blockWorkflowOnViolation = org.dspace.core.ConfigurationManager.getProperty("item-compliance",
                identifier + blockWorkflowConfig + collectionHandle);

        if (StringUtils.isBlank(blockWorkflowOnViolation)) {
            blockWorkflowOnViolation = org.dspace.core.ConfigurationManager.getProperty("item-compliance",
                    identifier + blockWorkflowConfig + defaultConfig);
        }

        if (StringUtils.isNotBlank(blockWorkflowOnViolation)) {
            return Boolean.parseBoolean(blockWorkflowOnViolation);
        }

        return false;
    }

    private void removeFakeValues(Context context, List<Metadata> fakeFields, Item item) {
        if(CollectionUtils.isNotEmpty(fakeFields) && context.isValid()) {

            for (Metadata fakeField : fakeFields) {
                item.clearMetadata(fakeField.schema, fakeField.element, fakeField.qualifier, fakeField.language);
            }
            try {
                item.update();
                context.commit();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }

        }
    }

    private List<Metadata> addFakeValues(Context context, Item item) {
        List<Metadata> fakeFields = addFakeValues(context, item, fakeIfEmptyDuringValidation);

        if (!item.isArchived()) {
            fakeFields.addAll(addFakeValues(context, item, fakeIfEmptyDuringUnarchivedValidation));
        }

        return fakeFields;
    }

    private List<Metadata> addFakeValues(Context context, Item item, Map<String, String> fakeFieldMap) {
        List<Metadata> fakeFields = new ArrayList<Metadata>();

        if (MapUtils.isNotEmpty(fakeFieldMap)) {
            for (String field : fakeFieldMap.keySet()) {
                Metadatum[] dcValues = item.getMetadataByMetadataString(field);

                if (dcValues.length == 0) {
                    Metadata metadata = org.dspace.util.MetadataFieldString.encapsulate(field);
                    addFakeValue(context, item, metadata, fakeFieldMap.get(field));
                    fakeFields.add(metadata);
                }
            }
        }

        return fakeFields;
    }

    private void addFakeValue(Context context, Item item, Metadata field, String value) {
        String estimatedValue = estimateValue(context, item, value);

        item.addMetadata(field.schema, field.element, field.qualifier, field.language, estimatedValue);
        field.value = estimatedValue;

        try {
            item.update();
            context.commit();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private String estimateValue(Context context, Item item, String value) {
        String estimatedValue = null;
        if (now.equals(value)) {
            estimatedValue = DCDate.getCurrent().toString();
        } else if (embargoOrNow.equals(value)) {
            Date lastEmbargo = EmbargoUtils.getLastEmbargo(item, context);

            if (lastEmbargo == null) {
                lastEmbargo = new Date();
            }

            estimatedValue = new DCDate(lastEmbargo).toString();
        } else {
            Metadatum[] metadata = item.getMetadataByMetadataString(value);

            if (metadata.length > 0) {
                estimatedValue = metadata[0].value;
            }
        }
        return estimatedValue;
    }

    private CompliancePolicy getCompliancePolicy() {
        try {
            return rulesFactory.createComplianceRulePolicy();
        } catch (ValidationRuleDefinitionException e) {
            log.warn("Unable to load the validation rules: " + e.getMessage(), e);
        }
        return null;
    }

    public void setRulesFactory(ComplianceCategoryRulesFactory rulesFactory) {
        this.rulesFactory = rulesFactory;
    }

    public void setFakeIfEmptyDuringValidation(Map<String, String> fakeIfEmptyDuringValidation) {
        this.fakeIfEmptyDuringValidation = fakeIfEmptyDuringValidation;
    }

    public void setFakeIfEmptyDuringUnarchivedValidation(Map<String, String> fakeIfEmptyDuringUnarchivedValidation) {
        this.fakeIfEmptyDuringUnarchivedValidation = fakeIfEmptyDuringUnarchivedValidation;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }
}
