package fr.proutlost.worldprep.physical;

import fr.proutlost.worldprep.orchestration.MultiSpeciesFaunaPlan;
import fr.proutlost.worldprep.orchestration.PagedBackupVerifier;
import fr.proutlost.worldprep.orchestration.QualificationBinding;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import java.io.IOException;
import java.nio.file.Path;

/** Fail-closed V5 request-to-species manifest gate, before any world IO.
 *
 * The physical v5 engine accepts ONLY exact post-backup typed runtime bytes,
 * never an unsealed PRE-COPY intention or a substituted species plan.
 */
public final class NativeV5ContractGate {
    public static NativeV5QualificationIntent requireSealedRequest(
            String bytes,MultiSpeciesFaunaPlan.Manifest species,
            WorldPrepSavedData.Area area,boolean production) throws IOException {
        if(bytes==null||bytes.isBlank()||bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)
            throw new IOException("Missing bounded v5 runtime request");
        if(species==null||area==null)throw new IOException("Missing manifest or exact area");
        NativeV5QualificationIntent request=QualificationBinding.decode(
            bytes,NativeV5QualificationIntent.class);
        try {MultiSpeciesFaunaPlan.verify(species);}
        catch(IllegalArgumentException ex) {throw new IOException("Invalid immutable multi-species manifest",ex);}
        if(request.version()!=5||!request.enabled()
            ||!request.batch().equals(species.parentBatch())
            ||!request.area().equals(area.id())
            ||!ContraV5SectorLineage.isExactOrPinnedSector(request.ecology(),area)
            ||!request.ecology().dimension().equals(area.dimension())
            ||request.ecology().minX()>area.minX()
            ||request.ecology().minZ()>area.minZ()
            ||request.ecology().maxX()<area.maxX()
            ||request.ecology().maxZ()<area.maxZ()
            ||!request.multiSpecies().equals(species))
            throw new IOException("V5 runtime request / ecology / species substitution");
        if(request.backupSha256()==null
            ||!request.backupSha256().matches("[0-9a-f]{64}")
            ||request.backupSha256().equals(PagedBackupVerifier.ZERO))
            throw new IOException("Unsealed v5 pre-copy input cannot run");
        QualificationRuntimeGate.requireAllowed(request.directorReceipt(),production);
        return request;
    }
    /** Verify the actual immutable WAL and both backup headers. An SHA claim alone is insufficient. */
    public static NativeV5QualificationIntent requireActualSealedBinding(
            Path world,Path directory,String exactRequest,
            MultiSpeciesFaunaPlan.Manifest species,WorldPrepSavedData.Area area,
            boolean production)throws IOException {
        var request=requireSealedRequest(exactRequest,species,area,production);
        if(world==null||directory==null)
            throw new IOException("Explicit S2 world and v5 journal required");
        Path expected=world.toAbsolutePath().normalize().resolve("worldprep-v2/physical");
        Path candidate=directory.toAbsolutePath().normalize();
        if(!candidate.startsWith(expected)
            ||!candidate.getFileName().toString().equals("qualification-"+request.batch()))
            throw new IOException("Foreign v5 qualification journal");
        var binder=new QualificationBinding(world,directory,NativeV5QualificationIntent.class);
        String verified;
        if(QualificationBinding.exactContraSegmented(request)) {
            if(!binder.segmentedBound())throw new IOException("Unsealed segmented Contra V5 journal");
            verified=binder.verifySegmented().request();
        }else{
            if(!binder.bound())throw new IOException("Unsealed v5 journal is not runnable");
            verified=binder.verify().request();
        }
        if(!verified.equals(exactRequest))
            throw new IOException("V5 request differs from the sealed world-backed WAL");
        return request;
    }
    private NativeV5ContractGate() {}
}
