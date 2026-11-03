package org.worldspeaker.ability;

import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Requester-scoped policy metadata; conditional grant summaries are never execution authorization. */
public final class AbilityDiscovery {
    public static final int MAX_GRANTS_PER_ENTRY = 32;
    private AbilityDiscovery() {}

    public static Snapshot build(AbilityValidation.AbilityTrustedContextHandle context,
                                 AbilityRequestAuthority.ExpectedContext expected) {
        AbilityRequestAuthority.ContextSnapshot trusted = AbilityRequestAuthority.currentSnapshot(context, expected);
        if (trusted == null) {
            throw new IllegalArgumentException("ability discovery requires a current admitted request and policy");
        }
        AbilityValidation.Binding binding = trusted.binding();
        String requestId = trusted.requestId();
        String invocationId = trusted.invocationId();
        String requesterId = trusted.requesterId();
        long policyRevision = trusted.policyRevision();
        AbilityPolicy policy = trusted.policy();
        if (!canonicalUuid(requestId) || !canonicalUuid(invocationId) || requestId.equals(invocationId) ||
                !canonicalUuid(requesterId) || policy == null || policy.revision() != policyRevision ||
                policyRevision < 0 || policyRevision > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("ability discovery requires a current admitted request and policy");
        }
        List<Entry> entries = AbilityCatalogue.selected().contracts().stream()
                .map(contract -> entry(contract, trusted)).toList();
        Snapshot result = new Snapshot(1, binding, requestId, invocationId, policyRevision,
                AbilitySchemaResources.selected(), entries);
        if (result.toJson().getBytes(StandardCharsets.UTF_8).length > AbilityCatalogue.MAX_DISCOVERY_BYTES) {
            throw new IllegalArgumentException("ability discovery exceeds its encoded metadata limit");
        }
        return result;
    }

    private static Entry entry(AbilityCatalogue.Contract contract,
                               AbilityRequestAuthority.ContextSnapshot trusted) {
        AbilityPolicyResolver.Decision resolution = AbilityPolicyResolver.resolveDiscovery(trusted, contract.abilityId());
        List<AbilityPolicy.Grant> matching = resolution.candidateGrants().stream().limit(MAX_GRANTS_PER_ENTRY).toList();
        List<DisabledReason> disabled = new ArrayList<>(List.of(DisabledReason.UNIMPLEMENTED, DisabledReason.UNQUALIFIED));
        if (resolution.reason() == AbilityPolicyResolver.Reason.EXPLICIT_DENIAL) disabled.add(DisabledReason.EXPLICIT_DENIAL);
        else if (resolution.reason() == AbilityPolicyResolver.Reason.NO_COMPLETE_GRANT) disabled.add(DisabledReason.NO_GRANT);
        else if (resolution.reason() == AbilityPolicyResolver.Reason.REQUESTER_NOT_GRANTED ||
                resolution.reason() == AbilityPolicyResolver.Reason.STALE_CONTEXT) disabled.add(DisabledReason.REQUESTER_NOT_GRANTED);
        List<GrantSummary> summaries = matching.stream().map(grant -> new GrantSummary(grant.id(), true,
                grant.otherTargetIds(), grant.dimensions(), grant.maxRangeBlocks(), grant.region(),
                grant.observeLimits(), grant.lightningLimits(), grant.allowVisual(), grant.allowDamage(), grant.maxDamage())).toList();
        return new Entry(contract, AbilityCatalogue.ImplementationStatus.UNIMPLEMENTED,
                AbilityCatalogue.QualificationStatus.UNQUALIFIED, AbilityCatalogue.Availability.UNAVAILABLE,
                List.copyOf(disabled), resolution.status() == AbilityPolicyResolver.Status.CONDITIONAL
                        ? PolicyDisposition.CONDITIONAL : PolicyDisposition.DENIED, summaries);
    }

    private static boolean canonicalUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public enum DisabledReason {
        UNIMPLEMENTED, UNQUALIFIED, NO_GRANT, EXPLICIT_DENIAL, REQUESTER_NOT_GRANTED
    }
    public enum PolicyDisposition {
        DENIED, CONDITIONAL
    }
    public record GrantSummary(String grantId, boolean selfTargeting, List<String> otherTargetIds,
                               List<String> dimensions, double maxRangeBlocks, AbilityPolicy.Region region,
                               AbilityPolicy.ObserveLimits observeLimits, AbilityPolicy.LightningLimits lightningLimits,
                               boolean allowVisual, boolean allowDamage, Double maxDamage) {
        public GrantSummary {
            otherTargetIds = List.copyOf(otherTargetIds);
            dimensions = List.copyOf(dimensions);
        }
    }
    public record SchemaResource(String id, String sha256, String schemaJson) {
        public SchemaResource {
            if (id == null || sha256 == null || schemaJson == null) {
                throw new IllegalArgumentException("invalid ability discovery schema resource");
            }
        }
    }
    public record Entry(AbilityCatalogue.Contract contract, AbilityCatalogue.ImplementationStatus implementationStatus,
                        AbilityCatalogue.QualificationStatus qualificationStatus, AbilityCatalogue.Availability availability,
                        List<DisabledReason> disabledReasons, PolicyDisposition policyDisposition,
                        List<GrantSummary> effectiveGrants) {
        public Entry {
            disabledReasons = List.copyOf(disabledReasons);
            effectiveGrants = List.copyOf(effectiveGrants);
        }
    }
    public record Snapshot(int catalogueVersion, AbilityValidation.Binding binding, String requestId,
                           String invocationId, long policyRevision, List<SchemaResource> schemaResources,
                           List<Entry> entries) {
        public Snapshot {
            schemaResources = List.copyOf(schemaResources);
            if (!schemaResources.equals(AbilitySchemaResources.selected())) {
                throw new IllegalArgumentException("ability discovery schema dependencies are incomplete or changed");
            }
            entries = List.copyOf(entries);
        }
        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("catalogueVersion", catalogueVersion);
            JsonObject configured = new JsonObject();
            configured.addProperty("serverId", binding.serverId());
            configured.addProperty("worldId", binding.worldId());
            configured.addProperty("characterId", binding.characterId());
            configured.addProperty("agentId", binding.agentId());
            root.add("binding", configured);
            root.addProperty("requestId", requestId);
            root.addProperty("invocationId", invocationId);
            root.addProperty("policyRevision", policyRevision);
            root.add("schemaResources", AbilitySchemaResources.json(schemaResources));
            JsonArray serializedEntries = new JsonArray();
            for (Entry entry : entries) serializedEntries.add(entryJson(entry));
            root.add("entries", serializedEntries);
            return root.toString();
        }
    }

    private static JsonObject entryJson(Entry entry) {
        AbilityCatalogue.Contract contract = entry.contract();
        JsonObject contractJson = new JsonObject();
        contractJson.addProperty("abilityId", contract.abilityId());
        contractJson.addProperty("contractVersion", contract.contractVersion());
        contractJson.addProperty("kind", contract.kind());
        contractJson.addProperty("purpose", contract.purpose());
        contractJson.add("inputSchema", contract.inputSchema().deepCopy());
        contractJson.add("resultSchema", contract.resultSchema().deepCopy());
        contractJson.add("permission", contract.permission().deepCopy());
        contractJson.add("bounds", contract.bounds().deepCopy());
        contractJson.add("preconditions", contract.preconditions().deepCopy());
        contractJson.add("execution", contract.execution().deepCopy());
        contractJson.add("reliability", contract.reliability().deepCopy());
        contractJson.add("privacy", contract.privacy().deepCopy());
        contractJson.add("presentation", contract.presentation().deepCopy());

        JsonObject value = new JsonObject();
        value.add("contract", contractJson);
        value.addProperty("implementationStatus", entry.implementationStatus().name());
        value.addProperty("qualificationStatus", entry.qualificationStatus().name());
        value.addProperty("availability", entry.availability().name());
        JsonArray disabled = new JsonArray();
        for (DisabledReason reason : entry.disabledReasons()) disabled.add(reason.name().toLowerCase(java.util.Locale.ROOT));
        value.add("disabledReasons", disabled);
        value.addProperty("policyDisposition", entry.policyDisposition().name().toLowerCase(java.util.Locale.ROOT));
        JsonArray grants = new JsonArray();
        for (GrantSummary summary : entry.effectiveGrants()) {
            JsonObject grant = new JsonObject();
            grant.addProperty("grantId", summary.grantId());
            grant.addProperty("selfTargeting", summary.selfTargeting());
            JsonArray otherTargets = new JsonArray();
            summary.otherTargetIds().forEach(otherTargets::add);
            grant.add("otherTargetIds", otherTargets);
            JsonArray dimensions = new JsonArray();
            summary.dimensions().forEach(dimensions::add);
            grant.add("dimensions", dimensions);
            grant.addProperty("maxRangeBlocks", summary.maxRangeBlocks());
            if (summary.region() == null) grant.add("region", com.google.gson.JsonNull.INSTANCE);
            else {
                JsonObject region = new JsonObject();
                region.addProperty("minX", summary.region().minX());
                region.addProperty("maxXExclusive", summary.region().maxXExclusive());
                region.addProperty("minZ", summary.region().minZ());
                region.addProperty("maxZExclusive", summary.region().maxZExclusive());
                grant.add("region", region);
            }
            JsonObject limits = new JsonObject();
            if (summary.observeLimits() != null) {
                limits.addProperty("requesterIntervalMs", summary.observeLimits().requesterIntervalMs());
                limits.addProperty("characterIntervalMs", summary.observeLimits().characterIntervalMs());
            } else {
                limits.addProperty("requesterCooldownMs", summary.lightningLimits().requesterCooldownMs());
                limits.addProperty("targetCooldownMs", summary.lightningLimits().targetCooldownMs());
                limits.addProperty("characterIntervalMs", summary.lightningLimits().characterIntervalMs());
            }
            grant.add("limits", limits);
            grant.addProperty("allowVisual", summary.allowVisual());
            grant.addProperty("allowDamage", summary.allowDamage());
            if (summary.maxDamage() == null) grant.add("maxDamage", com.google.gson.JsonNull.INSTANCE);
            else grant.addProperty("maxDamage", summary.maxDamage());
            grants.add(grant);
        }
        value.add("effectiveGrants", grants);
        return value;
    }
}
