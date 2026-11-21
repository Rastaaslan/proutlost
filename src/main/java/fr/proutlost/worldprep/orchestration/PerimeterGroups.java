package fr.proutlost.worldprep.orchestration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Lazy complete partition: each ordinal yields <=8x8 existing-chunk candidates, no world access.
 * Durable callers retain the ordinal and the frozen identity, never serialize all groups per tick.
 */
public record PerimeterGroups(UUID batch,String configurationSha256,String dimension,int minX,int minZ,int maxX,int maxZ) {
    public record Group(long ordinal,UUID operation,int minX,int minZ,int maxX,int maxZ,long chunks) {}
    public PerimeterGroups {
        if(batch==null||configurationSha256==null||!configurationSha256.matches("[0-9a-f]{64}")||dimension==null||dimension.isBlank()
            ||minX>maxX||minZ>maxZ)throw new IllegalArgumentException("Frozen bounded perimeter required");
    }
    private long width(){return (long)(maxX>>4)-(minX>>4)+1;}
    private long depth(){return (long)(maxZ>>4)-(minZ>>4)+1;}
    public long chunks(){return Math.multiplyExact(width(),depth());}
    public long count(){return Math.multiplyExact((width()+7)/8,(depth()+7)/8);}
    public Group group(long ordinal) {
        if(ordinal<0||ordinal>=count())throw new IllegalArgumentException("Group cursor outside perimeter");
        long columns=(width()+7)/8;
        long firstX=(minX>>4)+(ordinal%columns)*8,firstZ=(minZ>>4)+(ordinal/columns)*8;
        long lastX=Math.min(firstX+7,maxX>>4),lastZ=Math.min(firstZ+7,maxZ>>4);
        int x0=(int)Math.max(minX,firstX*16),z0=(int)Math.max(minZ,firstZ*16);
        int x1=(int)Math.min(maxX,lastX*16+15),z1=(int)Math.min(maxZ,lastZ*16+15);
        UUID operation=UUID.nameUUIDFromBytes((batch+"/"+configurationSha256+"/"+dimension+"/"+minX+"/"+minZ+"/"+maxX+"/"+maxZ+"/"+ordinal).getBytes(StandardCharsets.UTF_8));
        return new Group(ordinal,operation,x0,z0,x1,z1,(lastX-firstX+1)*(lastZ-firstZ+1));
    }
}
