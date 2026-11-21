package fr.proutlost.worldprep.location;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.proutlost.worldprep.area.WorldPrepArea;
import fr.proutlost.worldprep.plan.PlanFingerprint;
import fr.proutlost.worldprep.validation.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Authored location data, independent of Director regions and world generation. */
public final class LocationRegistry {
    public static final int MAX_LOCATIONS=256,MAX_ANCHORS=64,MAX_ZONES=32;
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private final SortedMap<String,LocationDefinition> locations=new TreeMap<>();
    private final SortedMap<String,SortedMap<String,LocationZone>> zones=new TreeMap<>();
    private final Set<String> allowedTransformations=new TreeSet<>();
    private long revision;
    private String cachedProtectionFingerprint;

    public long revision(){return revision;}
    public boolean isEmpty(){return locations.isEmpty();}
    public Map<String,LocationDefinition> locations(){return Collections.unmodifiableSortedMap(new TreeMap<>(locations));}
    public Map<String,LocationZone> zones(String id){require(id);return Collections.unmodifiableSortedMap(new TreeMap<>(zones.getOrDefault(id,Collections.emptySortedMap())));}
    public boolean transformationAllowed(String id){require(id);return allowedTransformations.contains(id);}
    public LocationDefinition require(String id){var value=locations.get(id);if(value==null)throw new IllegalArgumentException("Unknown location "+id);return value;}
    public static void name(String value){if(value==null||!value.matches("[a-z0-9_][a-z0-9_.-]{0,63}"))throw new IllegalArgumentException("Use a short lowercase anchor/zone name");}
    private void changed(){revision=Math.incrementExact(revision);cachedProtectionFingerprint=null;}
    public void create(LocationDefinition location){
        validateDefinition(Objects.requireNonNull(location));if(locations.size()>=MAX_LOCATIONS)throw new IllegalStateException("Location registry capacity reached");
        if(locations.putIfAbsent(location.id(),location)!=null)throw new IllegalArgumentException("Location already exists");changed();
    }
    public void delete(String id){require(id);locations.remove(id);zones.remove(id);allowedTransformations.remove(id);changed();}
    public void anchor(String id,String name,LocationDefinition.Anchor anchor){
        name(name);var location=require(id);Objects.requireNonNull(anchor);
        if(!location.bounds().dimension().equals(anchor.dimension())||!location.bounds().contains(anchor.x(),anchor.z()))throw new IllegalArgumentException("Anchor outside its authored location");
        var values=new TreeMap<>(location.anchors());if(values.containsKey(name))throw new IllegalArgumentException("Anchor already exists");
        if(values.size()>=MAX_ANCHORS)throw new IllegalStateException("Anchor capacity reached");values.put(name,anchor);
        locations.put(id,new LocationDefinition(id,location.type(),location.bounds(),values));changed();
    }
    public void removeAnchor(String id,String name){
        name(name);var location=require(id);var values=new TreeMap<>(location.anchors());
        if(values.remove(name)==null)throw new IllegalArgumentException("Unknown anchor "+name);
        locations.put(id,new LocationDefinition(id,location.type(),location.bounds(),values));changed();
    }
    public void zone(String id,String name,WorldPrepArea bounds,LocationZone.Kind kind){
        name(name);var parent=require(id).bounds();
        if(!parent.dimension().equals(bounds.dimension())||!parent.contains(bounds.minX(),bounds.minZ())||!parent.contains(bounds.maxX(),bounds.maxZ()))throw new IllegalArgumentException("Zone outside its authored location");
        var values=zones.computeIfAbsent(id,k->new TreeMap<>());
        if(values.size()>=MAX_ZONES)throw new IllegalStateException("Zone capacity reached");
        if(values.containsKey(name))throw new IllegalArgumentException("Zone already exists");
        var key=id+"/zone/"+name;values.put(name,new LocationZone(key,new WorldPrepArea(key,bounds.dimension(),bounds.minX(),bounds.minZ(),bounds.maxX(),bounds.maxZ()),kind));changed();
    }
    public void removeZone(String id,String name){require(id);name(name);var values=zones.get(id);if(values==null||values.remove(name)==null)throw new IllegalArgumentException("Unknown zone "+name);if(values.isEmpty())zones.remove(id);changed();}
    /** Explicit author action: unprotect changes the location to transformable
     * and authorizes that transformation; protected/manual subzones still win. */
    public void protect(String id,boolean protect){
        var location=require(id);var type=protect?LocationDefinition.Type.PROTECTED:LocationDefinition.Type.NARRATIVE_TRANSFORMABLE;
        boolean allowed=allowedTransformations.contains(id);
        if(location.type()==type&&allowed==!protect)return;
        locations.put(id,new LocationDefinition(id,type,location.bounds(),location.anchors()));
        if(protect)allowedTransformations.remove(id);else allowedTransformations.add(id);changed();
    }
    public boolean protectedBlock(String dimension,int x,int z){
        for(var location:locations.values())if(location.bounds().dimension().equals(dimension)&&location.bounds().contains(x,z)){
            if(location.blocksWorldPrep(allowedTransformations.contains(location.id())))return true;
            for(var zone:zones.getOrDefault(location.id(),Collections.emptySortedMap()).values())if(zone.kind()!=LocationZone.Kind.TRANSFORMABLE&&zone.contains(dimension,x,z))return true;
        }return false;
    }
    public boolean protectedQuart(String dimension,int qx,int qz){
        int x=Math.multiplyExact(qx,4),z=Math.multiplyExact(qz,4);
        for(var location:locations.values())if(location.bounds().dimension().equals(dimension)&&intersects(location.bounds(),x,z,x+3,z+3)){
            if(location.blocksWorldPrep(allowedTransformations.contains(location.id())))return true;
            for(var zone:zones.getOrDefault(location.id(),Collections.emptySortedMap()).values())if(zone.kind()!=LocationZone.Kind.TRANSFORMABLE&&intersects(zone.bounds(),x,z,x+3,z+3))return true;
        }return false;
    }
    private static boolean intersects(WorldPrepArea a,int x1,int z1,int x2,int z2){return a.minX()<=x2&&a.maxX()>=x1&&a.minZ()<=z2&&a.maxZ()>=z1;}
    public String protectionFingerprint(){
        if(cachedProtectionFingerprint!=null)return cachedProtectionFingerprint;
        var values=new ArrayList<String>();for(var l:locations.values()){
            values.add(l.id()+"/"+l.type()+"/"+l.bounds()+"/allowed="+allowedTransformations.contains(l.id()));
            zones.getOrDefault(l.id(),Collections.emptySortedMap()).forEach((n,z)->values.add(l.id()+"/"+n+"/"+z));
        }return cachedProtectionFingerprint=PlanFingerprint.of(values.toArray(String[]::new)).value();
    }
    public ValidationReport validate(String dimension,int minY,int maxY){
        var findings=new ArrayList<ValidationReport.Finding>();var values=new ArrayList<>(locations.values());
        for(var l:values){
            if(!l.bounds().dimension().equals(dimension))findings.add(finding("DIMENSION",l.id()+" belongs to another dimension"));
            l.anchors().forEach((n,a)->{if(!a.dimension().equals(l.bounds().dimension())||!l.bounds().contains(a.x(),a.z())||a.y()<minY||a.y()>=maxY)findings.add(finding("ANCHOR_BOUNDS",l.id()+"/"+n+" lies outside location/build bounds"));});
            for(var entry:zones(l.id()).entrySet())if(!l.bounds().dimension().equals(entry.getValue().bounds().dimension())||!l.bounds().contains(entry.getValue().bounds().minX(),entry.getValue().bounds().minZ())||!l.bounds().contains(entry.getValue().bounds().maxX(),entry.getValue().bounds().maxZ()))findings.add(finding("ZONE_BOUNDS",l.id()+"/"+entry.getKey()+" lies outside location"));
        }
        for(int i=0;i<values.size();i++)for(int j=i+1;j<values.size();j++)if(values.get(i).bounds().overlaps(values.get(j).bounds()))findings.add(new ValidationReport.Finding(ValidationReport.Severity.WARNING,"OVERLAP",values.get(i).id()+" overlaps "+values.get(j).id()+"; protections take precedence"));
        return new ValidationReport(findings);
    }
    private static ValidationReport.Finding finding(String code,String text){return new ValidationReport.Finding(ValidationReport.Severity.ERROR,code,text);}
    /** Restore only fully validated records; no missing or malformed data is invented. */
    public static LocationRegistry restore(long revision,List<LocationDefinition> locations,Map<String,Map<String,LocationZone>> zones,Set<String> allowed){
        if(revision<0||(!locations.isEmpty()&&revision==0)||locations.size()>MAX_LOCATIONS)throw new IllegalArgumentException("Invalid persisted location registry");var result=new LocationRegistry();
        for(var l:locations){validateDefinition(l);if(result.locations.putIfAbsent(l.id(),l)!=null)throw new IllegalArgumentException("Duplicate persisted location");}
        zones.forEach((id,values)->{var parent=result.require(id).bounds();if(values.size()>MAX_ZONES)throw new IllegalArgumentException("Persisted zone capacity");var copy=new TreeMap<String,LocationZone>();values.forEach((name,zone)->{name(name);if(!zone.id().equals(id+"/zone/"+name)||!parent.dimension().equals(zone.bounds().dimension())||!parent.contains(zone.bounds().minX(),zone.bounds().minZ())||!parent.contains(zone.bounds().maxX(),zone.bounds().maxZ()))throw new IllegalArgumentException("Persisted zone identity/bounds");copy.put(name,zone);});result.zones.put(id,copy);});
        for(var id:allowed){if(result.require(id).type()!=LocationDefinition.Type.NARRATIVE_TRANSFORMABLE)throw new IllegalArgumentException("Persisted transform permission/type mismatch");result.allowedTransformations.add(id);}
        result.revision=revision;return result;
    }
    private static void validateDefinition(LocationDefinition location){
        location.bounds().width();location.bounds().depth();
        if(location.anchors().size()>MAX_ANCHORS)throw new IllegalArgumentException("Anchor size bound");
        if(!location.bounds().dimension().matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Namespaced location dimension required");
        location.anchors().forEach((n,a)->{name(n);if(!a.dimension().equals(location.bounds().dimension())||!location.bounds().contains(a.x(),a.z()))throw new IllegalArgumentException("Anchor outside location bounds");});
    }
    private static Map<String,Object> object(Object... entries){var result=new TreeMap<String,Object>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}
    private static Map<String,Object> bounds(WorldPrepArea a,int minY,int maxY){return object("min_x",a.minX(),"min_y",minY,"min_z",a.minZ(),"max_x_exclusive",Math.addExact(a.maxX(),1),"max_y_exclusive",maxY,"max_z_exclusive",Math.addExact(a.maxZ(),1));}
    private static UUID stable(UUID world,String identity){return UUID.nameUUIDFromBytes((world+"/worldprep-locations/"+identity).getBytes(StandardCharsets.UTF_8));}
    /** Canonical complete authoring export; it carries no Director authority. */
    public byte[] authoringJson(){var entries=new ArrayList<Object>();for(var l:locations.values()){
        var anchors=new TreeMap<String,Object>();new TreeMap<>(l.anchors()).forEach((n,a)->anchors.put(n,object("dimension",a.dimension(),"x",a.x(),"y",a.y(),"z",a.z())));
        var subzones=new TreeMap<String,Object>();zones(l.id()).forEach((n,z)->subzones.put(n,object("kind",z.kind().name(),"bounds",object("dimension",z.bounds().dimension(),"min_x",z.bounds().minX(),"min_z",z.bounds().minZ(),"max_x",z.bounds().maxX(),"max_z",z.bounds().maxZ()))));
        entries.add(object("id",l.id(),"type",l.type().name(),"bounds",object("dimension",l.bounds().dimension(),"min_x",l.bounds().minX(),"min_z",l.bounds().minZ(),"max_x",l.bounds().maxX(),"max_z",l.bounds().maxZ()),"anchors",anchors,"zones",subzones,"transformation_allowed",allowedTransformations.contains(l.id())));
    }byte[] bytes=JSON.toJson(object("schema_version",1,"export_revision",Long.toString(revision),"locations",entries)).getBytes(StandardCharsets.UTF_8);if(bytes.length>4*1024*1024)throw new IllegalStateException("Authoring export byte bound exceeded");return bytes;}
    /** Frozen G0 shape. Locations deliberately do not manufacture future regions. */
    public byte[] directorJson(UUID world,String dimension,int minY,int maxY){
        Objects.requireNonNull(world);if(revision<1)throw new IllegalStateException("No authored location revision to export");
        var checked=validate(dimension,minY,maxY);if(checked.findings().stream().anyMatch(f->f.severity()==ValidationReport.Severity.ERROR||f.severity()==ValidationReport.Severity.FATAL))throw new IllegalStateException("Location validation failed");
        var exported=new ArrayList<Object>();var anchors=new ArrayList<Object>();for(var l:locations.values()){
            UUID id=stable(world,l.id());exported.add(object("location_id",id.toString(),"external_id",l.id(),"region_ids",List.of(),"dimension",dimension,"bounds",bounds(l.bounds(),minY,maxY),"tags",List.of()));
            new TreeMap<>(l.anchors()).forEach((name,a)->anchors.add(object("anchor_id",stable(world,l.id()+"/anchor/"+name).toString(),"location_id",id.toString(),"dimension",a.dimension(),"position",object("x",a.x(),"y",a.y(),"z",a.z()),"tags",List.of())));
        }
        if(exported.size()+anchors.size()>96)throw new IllegalStateException("G0 native import supports at most96 entries per source; export a bounded authored cohort");
        var root=object("schema_version",1,"world_id",world.toString(),"source_namespace","proutlost:locations/"+dimension.replace(':','/'),"export_revision",Long.toString(revision),"regions",List.of(),"locations",exported,"anchors",anchors);
        root.put("export_digest",PlanFingerprint.ofBytes(JSON.toJson(root).getBytes(StandardCharsets.UTF_8)).value());
        byte[] bytes=JSON.toJson(root).getBytes(StandardCharsets.UTF_8);if(bytes.length>32768)throw new IllegalStateException("G0 native input byte bound exceeded");return bytes;
    }
}
