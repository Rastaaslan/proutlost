package fr.proutlost.worldprep.location;

import fr.proutlost.worldprep.area.WorldPrepArea;
import java.util.*;
import net.minecraft.nbt.*;

/** Sorted, versioned authoring state inside the existing WorldPrep SavedData. */
public final class LocationRegistryNbt {
    private LocationRegistryNbt(){}
    public static CompoundTag save(LocationRegistry registry){
        var root=new CompoundTag();root.putInt("schema",1);root.putLong("revision",registry.revision());var locations=new ListTag();
        for(var l:registry.locations().values()){
            var t=new CompoundTag();t.putString("id",l.id());t.putString("type",l.type().name());t.put("bounds",bounds(l.bounds()));t.putBoolean("transformAllowed",registry.transformationAllowed(l.id()));
            var anchors=new ListTag();new TreeMap<>(l.anchors()).forEach((name,a)->{var n=new CompoundTag();n.putString("name",name);n.putString("dimension",a.dimension());n.putInt("x",a.x());n.putInt("y",a.y());n.putInt("z",a.z());anchors.add(n);});t.put("anchors",anchors);
            var zones=new ListTag();registry.zones(l.id()).forEach((name,z)->{var n=new CompoundTag();n.putString("name",name);n.putString("id",z.id());n.putString("kind",z.kind().name());n.put("bounds",bounds(z.bounds()));zones.add(n);});t.put("zones",zones);locations.add(t);
        }root.put("entries",locations);return root;
    }
    public static LocationRegistry load(CompoundTag root){
        if(root.getInt("schema")!=1||!root.contains("revision",Tag.TAG_LONG)||!root.contains("entries",Tag.TAG_LIST))throw new IllegalStateException("Unknown/corrupt location registry schema");
        var list=compounds(root,"entries");if(list.size()>LocationRegistry.MAX_LOCATIONS)throw new IllegalStateException("Location registry size bound");
        var locations=new ArrayList<LocationDefinition>();var zones=new TreeMap<String,Map<String,LocationZone>>();var allowed=new TreeSet<String>();
        for(var entry:list){
            var t=(CompoundTag)entry;var id=t.getString("id");var anchors=new TreeMap<String,LocationDefinition.Anchor>();
            if(!t.contains("transformAllowed",Tag.TAG_BYTE)||(t.getByte("transformAllowed")!=0&&t.getByte("transformAllowed")!=1))throw new IllegalStateException("Missing/invalid location transform authorization");
            var anchorList=compounds(t,"anchors");if(anchorList.size()>LocationRegistry.MAX_ANCHORS)throw new IllegalStateException("Anchor size bound");
            for(var value:anchorList){var a=(CompoundTag)value;var name=a.getString("name");LocationRegistry.name(name);require(a,"x","y","z");if(anchors.putIfAbsent(name,new LocationDefinition.Anchor(a.getString("dimension"),a.getInt("x"),a.getInt("y"),a.getInt("z")))!=null)throw new IllegalStateException("Duplicate persisted anchor");}
            locations.add(new LocationDefinition(id,LocationDefinition.Type.valueOf(t.getString("type")),bounds(id,t.getCompound("bounds")),anchors));
            var zoneList=compounds(t,"zones");if(zoneList.size()>LocationRegistry.MAX_ZONES)throw new IllegalStateException("Zone size bound");var children=new TreeMap<String,LocationZone>();
            for(var value:zoneList){var z=(CompoundTag)value;var name=z.getString("name");LocationRegistry.name(name);var zoneId=z.getString("id");if(children.putIfAbsent(name,new LocationZone(zoneId,bounds(zoneId,z.getCompound("bounds")),LocationZone.Kind.valueOf(z.getString("kind"))))!=null)throw new IllegalStateException("Duplicate persisted zone");}
            if(!children.isEmpty()&&zones.putIfAbsent(id,children)!=null)throw new IllegalStateException("Duplicate persisted zone owner");if(t.getBoolean("transformAllowed"))allowed.add(id);
        }return LocationRegistry.restore(root.getLong("revision"),locations,zones,allowed);
    }
    private static CompoundTag bounds(WorldPrepArea a){var t=new CompoundTag();t.putString("dimension",a.dimension());t.putInt("minX",a.minX());t.putInt("minZ",a.minZ());t.putInt("maxX",a.maxX());t.putInt("maxZ",a.maxZ());return t;}
    private static WorldPrepArea bounds(String id,CompoundTag t){require(t,"minX","minZ","maxX","maxZ");if(!t.contains("dimension",Tag.TAG_STRING))throw new IllegalStateException("Missing location dimension");return new WorldPrepArea(id,t.getString("dimension"),t.getInt("minX"),t.getInt("minZ"),t.getInt("maxX"),t.getInt("maxZ"));}
    private static void require(CompoundTag tag,String... integers){for(var key:integers)if(!tag.contains(key,Tag.TAG_INT))throw new IllegalStateException("Missing location integer "+key);}
    private static ListTag compounds(CompoundTag tag,String key){if(!(tag.get(key) instanceof ListTag list)||(!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND))throw new IllegalStateException("Invalid location compound list "+key);return list;}
}
