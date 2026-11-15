package fr.proutlost.worldprep.preview;

import com.google.gson.*;
import fr.proutlost.worldprep.persistence.WorldPrepSavedData;
import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.runtime.RuntimePlanStore;
import fr.proutlost.worldprep.storage.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import javax.imageio.ImageIO;

/** Headless projection of immutable, sealed PREVIEW pages. Never reads or writes a Minecraft world. */
public final class PreviewRenderer {
    public static final long MAX_MAP_PIXELS=262144,MAX_IMAGE_PIXELS=2097152;
    public static final int MAX_SIDE=1024,MAX_CATEGORIES=64,MAX_REGISTRY_IDS=65536,MAX_POLICY_ENTRIES=16384;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int BACKGROUND=0x252a30,HEADER=156,LEGEND_WIDTH=400,PADDING=16;
    public record Request(Path planDirectory,Path outputRoot,UUID planId,PassId pass,String area,String dimension,
            String profile,long seed,int minX,int minZ,int maxX,int maxZ,int minY,int maxY,String fingerprint,
            String inputDigest,long entryCount,Map<String,String> inputDetails,Set<String> registryIds,
            List<WorldPrepSavedData.ProtectedZone> protections,List<WorldPrepSavedData.OverrideCell> overrides,String locationRegistryJson) {
        public Request {
            Objects.requireNonNull(planDirectory);Objects.requireNonNull(outputRoot);Objects.requireNonNull(planId);Objects.requireNonNull(pass);
            if(pass!=PassId.BIOMES&&pass!=PassId.GEOLOGY&&pass!=PassId.ORES&&pass!=PassId.SOILS&&pass!=PassId.FLORA&&pass!=PassId.TREES&&pass!=PassId.AQUATIC)throw new IllegalArgumentException("No sealed PNG source for this pass");
            if(minX>maxX||minZ>maxZ||minY>=maxY||entryCount<0)throw new IllegalArgumentException("Invalid preview bounds");
            Math.toIntExact((long)maxX-minX+1);Math.toIntExact((long)maxZ-minZ+1);
            inputDetails=Map.copyOf(inputDetails);registryIds=Set.copyOf(registryIds);protections=List.copyOf(protections);overrides=List.copyOf(overrides);
            if(registryIds.size()>MAX_REGISTRY_IDS||protections.size()>MAX_POLICY_ENTRIES||overrides.size()>MAX_POLICY_ENTRIES
                    ||locationRegistryJson.length()>4194304||inputDetails.size()>64)throw new IllegalArgumentException("Preview metadata bound exceeded");
            for(var id:registryIds)bounded(id,256);
            for(var entry:inputDetails.entrySet()){bounded(entry.getKey(),128);bounded(entry.getValue(),4096);}
            for(var zone:protections){bounded(zone.id(),256);bounded(zone.dimension(),256);}
            for(var override:overrides){if(override.exactBiome()!=null)bounded(override.exactBiome(),256);if(override.palette()!=null)bounded(override.palette(),256);}
            bounded(area,256);bounded(dimension,256);bounded(profile,256);bounded(fingerprint,128);bounded(inputDigest,128);
        }
    }
    public record Report(Path directory,Path image,Path legend,Path metadata,boolean cached) {}
    private PreviewRenderer(){}
    private static void bounded(String value,int maximum){if(value==null||value.length()>maximum)throw new IllegalArgumentException("PNG metadata string bound exceeded");}
    public static PreviewScale scale(Request r){
        int width=Math.toIntExact((long)r.maxX-r.minX+1),height=Math.toIntExact((long)r.maxZ-r.minZ+1);
        var fitted=PreviewScale.fit(width,height,MAX_MAP_PIXELS);
        int step=Math.max(r.pass==PassId.ORES?16:4,Math.max(fitted.blocksPerPixel(),Math.max(ceil(width,MAX_SIDE),ceil(height,MAX_SIDE))));
        return new PreviewScale(width,height,ceil(width,step),ceil(height,step),step);
    }
    private static int ceil(int value,int divisor){return Math.floorDiv(value-1,divisor)+1;}
    private static String filename(PassId pass){return switch(pass){case BIOMES->"biomes.png";case GEOLOGY->"geology.png";case ORES->"ore_heatmap_admin.png";case SOILS->"soils.png";case FLORA->"flora.png";case TREES->"trees.png";case AQUATIC->"aquatic.png";default->throw new IllegalArgumentException();};}
    private static void cancelled(){if(Thread.currentThread().isInterrupted())throw new CancellationException("PNG export interrupted");}
    public static Report render(Request request)throws IOException {
        cancelled();var manifest=PagedPlanStore.readManifest(request.planDirectory);manifest.requireApplicable();
        long entries=manifest.pages().stream().mapToLong(PlanManifest.PageReference::entryCount).reduce(0,Math::addExact);
        if(!manifest.engineVersion().equals(RuntimePlanStore.ENGINE_VERSION)||!manifest.plannerVersion().equals(RuntimePlanStore.PLANNER_VERSION)
                ||!manifest.planId().equals(request.planId)||manifest.passId()!=request.pass||!manifest.areaId().equals(request.area)
                ||!manifest.dimension().equals(request.dimension)||!manifest.profile().equals(request.profile)
                ||!manifest.semanticInputDigest().equals(request.inputDigest)||!manifest.rootFingerprint().equals(request.fingerprint)||entries!=request.entryCount)
            throw new IOException("Exact sealed preview identity mismatch");
        var output=request.outputRoot.resolve(request.planId.toString());String imageName=filename(request.pass);
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)){
            if(request.pass==PassId.BIOMES)PagedPlanStore.read(request.planDirectory,manifest,RuntimePlanStore.BIOME_CODEC,ignored->cancelled());
            else PagedPlanStore.read(request.planDirectory,manifest,RuntimePlanStore.BLOCK_CODEC,ignored->cancelled());
            return existing(output,imageName,request);
        }
        var scale=scale(request);int size=Math.multiplyExact(scale.outputWidth(),scale.outputHeight());
        var counts=new TreeMap<String,Long>();var missing=new TreeSet<String>();String[] pixels=request.pass==PassId.ORES?null:new String[size];
        long[] ranks=new long[size];Arrays.fill(ranks,request.pass==PassId.BIOMES?Long.MAX_VALUE:request.pass==PassId.ORES?0:Long.MIN_VALUE);
        if(request.pass==PassId.BIOMES)PagedPlanStore.read(request.planDirectory,manifest,RuntimePlanStore.BIOME_CODEC,cell->{
            cancelled();int x=Math.multiplyExact(cell.quartX(),4),z=Math.multiplyExact(cell.quartZ(),4);
            inside(request,x,z);inside(request,Math.addExact(x,3),Math.addExact(z,3));count(counts,cell.biome());
            if(!request.registryIds.contains(cell.biome()))missing.add(cell.biome());
            for(int py=(z-request.minZ)/scale.blocksPerPixel();py<=(z+3-request.minZ)/scale.blocksPerPixel();py++)
                for(int px=(x-request.minX)/scale.blocksPerPixel();px<=(x+3-request.minX)/scale.blocksPerPixel();px++){
                    long dx=2L*(x+2)-(2L*request.minX+(2L*px+1)*scale.blocksPerPixel());
                    long dz=2L*(z+2)-(2L*request.minZ+(2L*py+1)*scale.blocksPerPixel());
                    long rank=Math.addExact(Math.multiplyExact(dx,dx),Math.multiplyExact(dz,dz));int at=py*scale.outputWidth()+px;
                    if(rank<ranks[at]||rank==ranks[at]&&(pixels[at]==null||cell.biome().compareTo(pixels[at])<0)){ranks[at]=rank;pixels[at]=cell.biome();}
                }
        });
        else PagedPlanStore.read(request.planDirectory,manifest,RuntimePlanStore.BLOCK_CODEC,change->{
            cancelled();inside(request,change.x(),change.z());if(change.y()<request.minY||change.y()>=request.maxY)throw new IllegalArgumentException("Plan Y outside captured world bounds");
            String block=change.applied().split("\\[",2)[0],key=request.pass==PassId.GEOLOGY?change.province()+" | "+block:block;
            count(counts,key);if(!request.registryIds.contains(block))missing.add(block);
            int at=(change.z()-request.minZ)/scale.blocksPerPixel()*scale.outputWidth()+(change.x()-request.minX)/scale.blocksPerPixel();
            if(request.pass==PassId.ORES)ranks[at]=Math.addExact(ranks[at],1);
            else if(change.y()>ranks[at]||change.y()==ranks[at]&&(pixels[at]==null||key.compareTo(pixels[at])<0)){ranks[at]=change.y();pixels[at]=key;}
        });
        var palette=new LinkedHashMap<String,Integer>();palette.put("No planned change / excluded or partial cell",BACKGROUND);
        if(request.pass==PassId.ORES){for(int i=0;i<8;i++)palette.put(i==0?"0 planned replacements":i==7?"64+ planned replacements":(1L<<(i-1))+".."+((1L<<i)-1)+" planned replacements",heat(i));}
        else {int n=counts.size(),i=0;for(var key:counts.keySet())palette.put(key,Color.HSBtoRGB((float)i++/Math.max(1,n),.65f,.88f)&0xffffff);}
        int zoom=request.pass==PassId.ORES?1:Math.min(16,Math.max(1,256/Math.max(scale.outputWidth(),scale.outputHeight())));
        int mapWidth=Math.multiplyExact(scale.outputWidth(),zoom),mapHeight=Math.multiplyExact(scale.outputHeight(),zoom);
        int width=mapWidth+LEGEND_WIDTH+PADDING*3,height=HEADER+Math.max(mapHeight,palette.size()*16+40)+PADDING*2;
        if((long)width*height>MAX_IMAGE_PIXELS)throw new IOException("PNG canvas allocation bound");
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();
        try{
            graphics.setColor(Color.WHITE);graphics.fillRect(0,0,width,height);graphics.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));graphics.setColor(Color.BLACK);
            graphics.drawString("WorldPrep "+request.pass+" - SEALED PREVIEW",PADDING,22);
            graphics.drawString("Plan "+request.planId,PADDING,42);
            graphics.drawString("Profile "+shortLabel(request.profile,Math.max(20,(width-PADDING*2)/8-8)),PADDING,62);
            graphics.drawString("North: min Z at top; east: +X right.",PADDING,82);
            graphics.drawString(scale.blocksPerPixel()+" blocks/sample; display x"+zoom+".",PADDING,100);
            graphics.drawString(request.pass==PassId.ORES?"ADMIN ONLY: coarse counts, not an ore survey.":request.pass==PassId.BIOMES?"Planned quart columns; nearest-centre bins.":"Highest planned target block in each bin.",PADDING,118);
            graphics.setColor(missing.isEmpty()?Color.DARK_GRAY:Color.RED);graphics.drawString("Registry: "+(missing.isEmpty()?"verified":"MISSING "+missing.size())+"; no world data changed.",PADDING,138);
            int[] row=new int[mapWidth];
            for(int y=0;y<scale.outputHeight();y++){cancelled();for(int x=0;x<scale.outputWidth();x++){
                int at=y*scale.outputWidth()+x,color=request.pass==PassId.ORES?heat(bucket(ranks[at])):pixels[at]==null?BACKGROUND:palette.get(pixels[at]);
                Arrays.fill(row,x*zoom,(x+1)*zoom,color);
            }for(int dy=0;dy<zoom;dy++)image.setRGB(PADDING,HEADER+y*zoom+dy,mapWidth,1,row,0,mapWidth);}
            int y=HEADER+12,index=0;graphics.setFont(new Font(Font.MONOSPACED,Font.PLAIN,11));
            for(var entry:palette.entrySet()){
                graphics.setColor(new Color(entry.getValue()));graphics.fillRect(PADDING*2+mapWidth,y-10,12,12);graphics.setColor(Color.BLACK);
                String label=entry.getKey();if(label.length()>48)label=label.substring(0,25)+"..."+label.substring(label.length()-20);
                graphics.drawString(String.format(Locale.ROOT,"%02d ",index++)+label,PADDING*2+mapWidth+18,y);y+=16;
            }
            graphics.drawString("Full legend and sealed-plan metadata accompany this PNG.",PADDING,height-12);
        }finally{graphics.dispose();}
        cancelled();Files.createDirectories(request.outputRoot);var staging=request.outputRoot.resolve("."+request.planId+".tmp-"+UUID.randomUUID());Files.createDirectory(staging);
        try{
            var png=staging.resolve(imageName);if(!ImageIO.write(image,"PNG",png.toFile()))throw new IOException("PNG writer unavailable");
            var legend=new LinkedHashMap<String,String>();palette.forEach((key,color)->legend.put(key,String.format(Locale.ROOT,"#%06x",color)));
            writeJson(staging.resolve("legend.json"),legend);
            var metadata=new LinkedHashMap<String,Object>();metadata.put("schema",1);metadata.put("render_context_digest",contextDigest(request));metadata.put("source","SEALED_PREVIEW_PLAN");metadata.put("plan_id",request.planId.toString());metadata.put("profile",request.profile);
            metadata.put("seed",request.seed);metadata.put("area",request.area);metadata.put("dimension",request.dimension);metadata.put("bounds",List.of(request.minX,request.minZ,request.maxX,request.maxZ));
            metadata.put("fingerprint",request.fingerprint);metadata.put("semantic_input_digest",request.inputDigest);metadata.put("pass",request.pass.name());metadata.put("entries",entries);
            metadata.put("engine_version",manifest.engineVersion());metadata.put("planner_version",manifest.plannerVersion());metadata.put("plan_format_version",manifest.planFormatVersion());metadata.put("area_mask",request.inputDetails.get("area_mask"));metadata.put("tree_mode",request.inputDetails.get("tree_mode"));
            metadata.put("scale",scale);metadata.put("display_pixels_per_sample",zoom);metadata.put("display_resampling","nearest-neighbour; no additional terrain information");metadata.put("distribution_statistics",counts);metadata.put("registry_validation",Map.of("valid",missing.isEmpty(),"missing",missing,"scope","actor registry snapshot before export"));
            metadata.put("fallback_usage",Map.of("exporter",0,"planner","NOT_ENCODED_IN_SEALED_PLAN"));metadata.put("protected_zones",request.protections);metadata.put("overrides",request.overrides);
            metadata.put("location_registry",JsonParser.parseString(request.locationRegistryJson));metadata.put("input_details",new TreeMap<>(request.inputDetails));
            metadata.put("protected_zone_sources",List.of("protected_zones: direct rectangles intersecting the bounding rectangle","location_registry: authored locations, zones and policies"));metadata.put("overrides_scope","quart cells intersecting the bounding rectangle; mask retained separately");
            metadata.put("terrain_revalidation","NOT_PERFORMED: this is the immutable PREVIEW snapshot, never APPLY authority");
            metadata.put("files",Map.of(imageName,sha256(png),"legend.json",sha256(staging.resolve("legend.json"))));writeJson(staging.resolve("preview.json"),metadata);
            Files.writeString(staging.resolve("preview.sha256"),sha256(staging.resolve("preview.json"))+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            cancelled();try{Files.move(staging,output,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(staging,output);}
            return new Report(output,output.resolve(imageName),output.resolve("legend.json"),output.resolve("preview.json"),false);
        }finally{
            image.flush();if(Files.exists(staging,LinkOption.NOFOLLOW_LINKS)){try(var paths=Files.list(staging)){for(var path:paths.toList())Files.deleteIfExists(path);}Files.deleteIfExists(staging);}
        }
    }
    private static String shortLabel(String value,int maximum){return value.length()<=maximum?value:value.substring(0,maximum-3)+"...";}
    private static int bucket(long n){return n<=0?0:Math.min(7,64-Long.numberOfLeadingZeros(n));}
    private static int heat(int bucket){return bucket==0?BACKGROUND:Color.HSBtoRGB((float)(.65-.65*(bucket-1)/6),.8f,.9f)&0xffffff;}
    private static void inside(Request r,int x,int z){if(x<r.minX||x>r.maxX||z<r.minZ||z>r.maxZ)throw new IllegalArgumentException("Plan entry outside captured area");}
    private static void count(Map<String,Long> counts,String key){bounded(key,512);if(!counts.containsKey(key)&&counts.size()>=MAX_CATEGORIES)throw new IllegalArgumentException("PNG categorical legend bound exceeded");counts.merge(key,1L,Math::addExact);}
    private static String contextDigest(Request request){return fr.proutlost.worldprep.plan.PlanFingerprint.of(JSON.toJson(new TreeMap<>(request.inputDetails)),request.locationRegistryJson,String.join("\n",request.registryIds.stream().sorted().toList())).value();}
    private static void writeJson(Path path,Object value)throws IOException{cancelled();byte[] bytes=JSON.toJson(value).getBytes(StandardCharsets.UTF_8);if(bytes.length>16777216)throw new IOException("PNG metadata byte bound");cancelled();Files.write(path,bytes,StandardOpenOption.CREATE_NEW);}
    private static String sha256(Path path)throws IOException{try{var hash=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1){cancelled();hash.update(bytes,0,n);}}return HexFormat.of().formatHex(hash.digest());}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static Report existing(Path directory,String image,Request request)throws IOException{
        var metadata=directory.resolve("preview.json");if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)||Files.size(metadata)>16777216)throw new IOException("Incomplete or incompatible existing PNG export");
        var checksum=directory.resolve("preview.sha256");
        if(!Files.isRegularFile(checksum,LinkOption.NOFOLLOW_LINKS)||Files.size(checksum)>65||!Files.readString(checksum).strip().equals(sha256(metadata)))throw new IOException("Existing PNG metadata checksum mismatch");
        var json=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        if(json.get("schema").getAsInt()!=1||!json.get("render_context_digest").getAsString().equals(contextDigest(request))||!json.get("fingerprint").getAsString().equals(request.fingerprint)||!json.get("plan_id").getAsString().equals(request.planId.toString()))throw new IOException("Existing PNG ownership mismatch");
        for(var file:List.of(image,"legend.json")){var path=directory.resolve(file);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!sha256(path).equals(json.getAsJsonObject("files").get(file).getAsString()))throw new IOException("Existing PNG checksum mismatch");}
        return new Report(directory,directory.resolve(image),directory.resolve("legend.json"),metadata,true);
    }
}
