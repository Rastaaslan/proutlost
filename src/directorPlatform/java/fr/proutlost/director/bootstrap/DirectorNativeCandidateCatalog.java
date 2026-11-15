package fr.proutlost.director.bootstrap;

import com.google.gson.*;
import fr.proutlost.director.catalog.*;
import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * J02 candidate pack integrity evidence from actual NeoForge bundled resources.
 * This does NOT create or publish a production CatalogPort. All definitions
 * are CANDIDATE / DEFAULT_FOR_TESTING, and J05/J06 real primitive providers
 * must be bound and qualified before a DefinitionLoader may install them.
 */
public final class DirectorNativeCandidateCatalog implements DirectorBootstrap.TestOnlyBackend {
    public record View(int verifiedEvents,int verifiedChains,String manifestSha256,
                       String testProfileSha256,String bindingStatus,
                       boolean validatedForProduction,boolean gameplayAdmitted) {
        public View {
            Objects.requireNonNull(manifestSha256);
            Objects.requireNonNull(testProfileSha256);
            Objects.requireNonNull(bindingStatus);
            if(verifiedEvents<0||verifiedChains<0 ||
               validatedForProduction||gameplayAdmitted)
                throw new IllegalArgumentException("unqualified candidate catalog promoted");
        }
    }
    private static final String PREFIX="/data/proutlost_director/director/";
    private static final int MAX_MANIFEST_BYTES=65536;
    private static final int MAX_DOCUMENT_BYTES=65536;
    /** Hash-verified, static candidate dependency graph. A descriptor name
     * does not prove that a runtime J05/J06 provider is installed, and this
     * graph is never a Production CatalogPort or an effect permission. */
    public record DependencyIndex(
            Map<DefinitionId,Set<PrimitiveId>> eventPrimitives,
            Map<DefinitionId,Set<DefinitionId>> chainEvents,
            Set<PrimitiveId> requiredPrimitives,
            Set<CapabilityId> requiredCapabilities) {
        public DependencyIndex {
            Objects.requireNonNull(eventPrimitives);
            Objects.requireNonNull(chainEvents);
            Objects.requireNonNull(requiredPrimitives);
            Objects.requireNonNull(requiredCapabilities);
            var events=new TreeMap<DefinitionId,Set<PrimitiveId>>();
            eventPrimitives.forEach((key,value)->events.put(
                Objects.requireNonNull(key),Set.copyOf(value)));
            var chains=new TreeMap<DefinitionId,Set<DefinitionId>>();
            chainEvents.forEach((key,value)->chains.put(
                Objects.requireNonNull(key),Set.copyOf(value)));
            eventPrimitives=Collections.unmodifiableMap(events);
            chainEvents=Collections.unmodifiableMap(chains);
            requiredPrimitives=Set.copyOf(requiredPrimitives);
            requiredCapabilities=Set.copyOf(requiredCapabilities);
            if(events.size()!=9||chains.size()!=2)
                throw new IllegalArgumentException("candidate graph size");
            if(!events.values().stream().flatMap(Set::stream)
                    .collect(java.util.stream.Collectors.toSet())
                    .equals(requiredPrimitives) ||
                chains.values().stream().flatMap(Set::stream)
                    .anyMatch(id->!events.containsKey(id)))
                throw new IllegalArgumentException("unresolved candidate edges");
        }
        /** Only descriptor presence is checked, NOT live provider readiness. */
        public Set<PrimitiveId> missingDescriptors(PrimitiveRegistry registered) {
            Objects.requireNonNull(registered);
            var missing=new TreeSet<>(requiredPrimitives);
            missing.removeAll(registered.descriptors().keySet());
            return Collections.unmodifiableSet(missing);
        }
        public Set<DefinitionId> affectedEvents(PrimitiveId primitive) {
            Objects.requireNonNull(primitive);
            var affected=new TreeSet<DefinitionId>();
            eventPrimitives.forEach((event,steps)->{
                if(steps.contains(primitive))affected.add(event);
            });
            return Collections.unmodifiableSet(affected);
        }
    }
    private final List<DefinitionLoader.Source> sources;
    private final View status;
    private final DependencyIndex dependencies;

    public DirectorNativeCandidateCatalog(ConfigView testProfile) {
        Objects.requireNonNull(testProfile);
        if(testProfile.calibration()!=Calibration.DEFAULT_FOR_TESTING)
            throw new IllegalArgumentException("not a candidate testing profile");
        var raw=read(PREFIX+"candidate-manifest.json",MAX_MANIFEST_BYTES);
        var root=JsonParser.parseString(new String(raw,StandardCharsets.UTF_8))
            .getAsJsonObject();
        if(root.get("schema_version").getAsInt()!=1 ||
           !root.get("status").getAsString().equals("CANDIDATE") ||
           !root.get("calibration").getAsString().equals("DEFAULT_FOR_TESTING") ||
           root.get("production_approved").getAsBoolean() ||
           !root.get("spec_version").getAsString().equals("1.0.0") ||
           !root.get("binding_status").getAsString()
               .equals("REQUIRES_REAL_J05_J06_REGISTRIES_AND_QUALIFICATION"))
            throw new IllegalStateException("J02 candidate metadata not fail-closed");
        String profilePath=root.get("profile_resource").getAsString();
        if(!profilePath.equals(PREFIX.substring(1)+"profiles/default_for_testing.json"))
            throw new IllegalArgumentException("unknown J02 profile resource");
        String digest=sha(read("/"+profilePath,MAX_MANIFEST_BYTES));
        String manifestDigest=root.get("profile_sha256").getAsString();
        // Raw bundled JSON still matches the pinned manifest. A runtime
        // override must match the exact verified, opt-in LAB derivation.
        if(!digest.equals(manifestDigest) ||
           !DirectorLabObservationProfile.isVerifiedRuntime(testProfile))
            throw new IllegalStateException("J02 profile digest does not match J01");
        var entries=root.getAsJsonArray("definitions");
        if(entries==null || entries.size()!=11)
            throw new IllegalStateException("expected exactly 11 candidate definitions");
        int events=0,chains=0;
        Set<String> paths=new HashSet<>(),ids=new HashSet<>();
        List<DefinitionLoader.Source> candidates=new ArrayList<>();
        Map<DefinitionId,Set<PrimitiveId>> eventPrimitives=new TreeMap<>();
        Map<DefinitionId,Set<DefinitionId>> chainEvents=new TreeMap<>();
        Set<CapabilityId> capabilities=new HashSet<>();
        for(var item:entries) {
            var def=item.getAsJsonObject();
            String p=def.get("resource").getAsString();
            String id=def.get("definition_id").getAsString();
            String hash=def.get("sha256").getAsString();
            boolean event=p.matches("data/proutlost_director/director/events/candidate/[a-z0-9_]+\\.json");
            boolean chain=p.matches("data/proutlost_director/director/chains/candidate/[a-z0-9_]+\\.json");
            if(!(event||chain) || !paths.add(p)||!ids.add(id) ||
               !id.equals("proutlost_director:candidate/"+
                    p.substring(p.lastIndexOf('/')+1,p.length()-5)) ||
               !hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("unexpected J02 candidate identity");
            byte[] bytes=read("/"+p,MAX_DOCUMENT_BYTES);
            if(!hash.equals(sha(bytes)))
                throw new IllegalStateException("J02 candidate SHA256 mismatch: "+id);
            // Dependencies are extracted only after matching the manifest
            // SHA, not from arbitrary world datapacks or network contents.
            var document=JsonParser.parseString(
                new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            if(document.get("schema_version").getAsInt()!=1 ||
               !id.equals(document.get("id").getAsString()) ||
               !document.get("calibration").getAsString()
                   .equals("DEFAULT_FOR_TESTING"))
                throw new IllegalStateException("candidate document identity drift");
            var definition=new DefinitionId(id);
            if(event) {
                var steps=document.getAsJsonArray("steps");
                if(steps==null||steps.isEmpty()||steps.size()>64)
                    throw new IllegalStateException("candidate step bounds");
                Set<PrimitiveId> used=new HashSet<>();
                for(var step:steps) {
                    var node=step.getAsJsonObject();
                    used.add(new PrimitiveId(node.get("primitive").getAsString()));
                }
                if(used.isEmpty())throw new IllegalStateException("empty candidate steps");
                eventPrimitives.put(definition,Set.copyOf(used));
                var safety=document.getAsJsonObject("safety");
                if(safety==null || safety.getAsJsonArray("required_capabilities")==null)
                    throw new IllegalStateException("missing candidate safety declaration");
                for(var cap:safety.getAsJsonArray("required_capabilities"))
                    capabilities.add(new CapabilityId(cap.getAsString()));
            } else {
                var transitions=document.getAsJsonArray("transitions");
                if(transitions==null||transitions.isEmpty()||transitions.size()>128)
                    throw new IllegalStateException("candidate chain transition bounds");
                Set<DefinitionId> used=new HashSet<>();
                for(var transition:transitions) {
                    var reference=transition.getAsJsonObject().get("event_id");
                    if(reference!=null&&!reference.isJsonNull())
                        used.add(new DefinitionId(reference.getAsString()));
                }
                chainEvents.put(definition,Set.copyOf(used));
            }
            candidates.add(new DefinitionLoader.Source(p,bytes));
            if(event) events++;else chains++;
        }
        if(events!=9||chains!=2)throw new IllegalStateException("J02 counts incorrect");
        Set<PrimitiveId> required=new HashSet<>();
        eventPrimitives.values().forEach(required::addAll);
        dependencies=new DependencyIndex(eventPrimitives,chainEvents,required,capabilities);
        sources=List.copyOf(candidates);
        status=new View(events,chains,sha(raw),digest,
            root.get("binding_status").getAsString(),false,false);
    }
    private static byte[] read(String path,int maximum) {
        if(!path.startsWith(PREFIX))
            throw new IllegalArgumentException("untrusted J02 packaged path");
        try(var stream=DirectorNativeCandidateCatalog.class.getResourceAsStream(path)) {
            if(stream==null)throw new IllegalStateException("missing J02 asset "+path);
            var bytes=stream.readNBytes(maximum+1);
            if(bytes.length>maximum)
                throw new IllegalArgumentException("oversized J02 asset");
            return bytes;
        } catch(IOException e) {
            throw new IllegalStateException("cannot read J02 packaged content",e);
        }
    }
    private static String sha(byte[] bytes) {
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(bytes));
        }catch(NoSuchAlgorithmException impossible){
            throw new AssertionError(impossible);
        }
    }
    public View status(){return status;}
    public DependencyIndex dependencies(){return dependencies;}
    public List<DefinitionLoader.Source> candidateDocuments(){return sources;}

    /** Native composition passes no fake primitives. The loader must reject
     * candidate definitions referencing as-yet unregistered providers.
     * Atomic failure does not turn an empty catalogue into known-empty gameplay.
     */
    public boolean rejectsUnqualifiedProviders(ConfigView testProfile) {
        var unqualified=new PrimitiveRegistry(List.of(),ProviderMode.TEST_ONLY);
        var references=new ContentReferences(ProviderMode.TEST_ONLY,Map.of(),
            Map.of(),Map.of(),Map.of(),Set.of(),Set.of());
        var loader=new DefinitionLoader(testProfile,unqualified,references,
            ProviderMode.TEST_ONLY,
            new DefinitionLoader.Limits(256,65536,4*1024*1024,512,512));
        var before=loader.current();
        var outcome=loader.reload(sources);
        return !outcome.installed() && !outcome.diagnostics().isEmpty()
            && loader.current()==before
            && loader.current().events().isEmpty()
            && loader.current().chains().isEmpty();
    }
}
