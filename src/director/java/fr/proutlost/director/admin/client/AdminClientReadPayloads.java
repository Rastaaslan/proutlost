package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import java.util.*;

/** Closed F8 form parser; payloads carry requests, never execution authority.
 * IDs are explicit UUIDs/resources. All live scope/permission/admission checks remain server-side. */
public final class AdminClientReadPayloads {
    private AdminClientReadPayloads() {}
    public static boolean readOnly(AdminOperation operation) {
        return switch(Objects.requireNonNull(operation)) {
            case STATUS,EVENT_LIST,EVENT_INSPECT,MUTATION_INSPECT,REGION,PLAYER,CHAIN,REPLAY -> true;
            default -> false;
        };
    }
    public static boolean supported(AdminOperation operation) {
        return switch(Objects.requireNonNull(operation)) {
            case STATUS,EVENT_LIST,EVENT_INSPECT,MUTATION_INSPECT,REGION,PLAYER,CHAIN,
                PAUSE,RESUME,DRAIN,TENSION,INTENSITY,STOP,STABILIZE,RESCUE,REPORT,
                TRIGGER,SIMULATE,REPLAY,AUDIT_EXPORT -> true;
            default -> false;
        };
    }
    private static String[] parts(String raw) {
        Objects.requireNonNull(raw);if(raw.length()>1000)throw new IllegalArgumentException("Input exceeds client limit");
        var value=raw.trim();return value.isEmpty()?new String[0]:value.split("\\s+");
    }
    /** The actual server capture bounds temporary tension. No client clock is used. */
    public static AdminPayload parse(AdminOperation operation,String raw,Tick capturedTick) {
        Objects.requireNonNull(capturedTick);
        if(operation!=AdminOperation.TENSION)return parse(operation,raw);
        var p=parts(raw);exact(p,2);long duration=Long.parseLong(p[1]);
        return new TensionPayload(GlobalTension.valueOf(p[0].toUpperCase(Locale.ROOT)),new DurationTicks(duration),
            new Tick(Math.addExact(capturedTick.value(),duration)));
    }
    /** SIMULATE is resolved by the existing canonical server command, because
     * no client summary DTO contains the operator's real SnapshotBatch. */
    public static ResourceId simulationFixture(String raw) {
        var p=parts(raw);exact(p,1);return new ResourceId(p[0]);
    }
    public static boolean simulationResultMatches(AdminSnapshot snapshot,ResourceId fixture) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(fixture);
        return !snapshot.rows().isEmpty()&&snapshot.rows().stream().allMatch(row->
            row.data() instanceof LaboratoryAdminData data&&data.fixtureId().equals(fixture)
            ||row.data() instanceof LaboratoryResultPageData page&&page.fixtureId().equals(fixture));
    }
    public static AdminPayload parse(AdminOperation operation,String raw) {
        Objects.requireNonNull(operation);if(!supported(operation))throw new IllegalArgumentException("Unsupported F8 operation");
        var p=parts(raw);
        return switch(operation) {
            case PAUSE,RESUME,DRAIN -> {exact(p,0);yield new ControlPayload(operation==AdminOperation.PAUSE?RunControl.PAUSED:
                operation==AdminOperation.RESUME?RunControl.RUNNING:RunControl.DRAINING);}
            case STATUS,EVENT_LIST -> {exact(p,0);yield new EmptyAdminPayload();}
            case EVENT_INSPECT -> {exact(p,1);yield new EventInspectionPayload(EventInstanceId.parse(p[0]));}
            case MUTATION_INSPECT -> {exact(p,1);yield new MutationInspectionPayload(MutationId.parse(p[0]));}
            case REGION -> {exact(p,1);yield new RegionInspectionPayload(RegionId.parse(p[0]));}
            case PLAYER -> {exact(p,1);yield new PlayerInspectionPayload(PlayerId.parse(p[0]));}
            case CHAIN -> {exact(p,1);yield new ChainInspectionPayload(ChainId.parse(p[0]));}
            case INTENSITY -> {exact(p,1);yield new IntensityPayload(new ResourceId(p[0]));}
            case STOP -> {exact(p,1);boolean all=p[0].equals("all");yield new StopPayload(all?Optional.empty():Optional.of(EventInstanceId.parse(p[0])),all,"admin stop");}
            case STABILIZE -> {exact(p,2);if(!p[1].equals("stabilize"))throw new IllegalArgumentException("Expected stabilize");yield new StabilizePayload(DisasterId.parse(p[0]));}
            case RESCUE -> {exact(p,1);boolean self=p[0].equals("self");yield new RescuePayload(self?Optional.empty():Optional.of(PlayerId.parse(p[0])),self);}
            case REPORT -> new ReportPayload(raw.trim(),Set.of());
            case REPLAY -> {if(p.length!=1&&p.length!=2||p.length==2&&!p[1].equals("without_ai"))throw new IllegalArgumentException("decisionId [without_ai]");
                yield new ReplayPayload(DecisionId.parse(p[0]),p.length==2);}
            case AUDIT_EXPORT -> {exact(p,1);yield new AuditExportPayload(DecisionId.parse(p[0]));}
            case TRIGGER -> {
                if(p.length!=3&&p.length!=5||!p[1].equals("region")||p.length==5&&!p[3].equals("targets"))
                    throw new IllegalArgumentException("eventId region UUID [targets UUID[,UUID]]");
                var targets=new HashSet<PlayerId>();
                if(p.length==5)for(var id:p[4].split(",",-1)){
                    if(targets.size()>=50)throw new IllegalArgumentException("Target bound");
                    targets.add(PlayerId.parse(id));
                }
                yield new TriggerPayload(new DefinitionId(p[0]),RegionId.parse(p[2]),targets,Set.of());
            }
            case TENSION -> throw new IllegalArgumentException("Server captured tick required");
            case SIMULATE -> throw new IllegalArgumentException("Use canonical server fixture resolution");
            default -> throw new IllegalArgumentException("Unsupported F8 operation");
        };
    }
    private static void exact(String[] parts,int size) {if(parts.length!=size)throw new IllegalArgumentException("Invalid argument count");}
}
