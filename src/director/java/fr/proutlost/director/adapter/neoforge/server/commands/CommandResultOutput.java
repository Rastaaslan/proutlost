package fr.proutlost.director.adapter.neoforge.server.commands;

import fr.proutlost.director.api.model.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded command feedback; current server authority must be supplied at delivery. */
public final class CommandResultOutput {
    public static final int MAX_LINES=32, MAX_CHARS=240;
    private CommandResultOutput() {}
    public static void deliver(AdminCommand command,AdminResult result,ActorIdentity current,Consumer<String> output) {
        if(!AdminContracts.authorized(command,current)||!command.requestId().equals(result.requestId())) return;
        List<String> lines=new ArrayList<>();
        add(lines,result.status()+" "+result.requestId().value()+result.error().map(e->" "+e.code()).orElse(""));
        result.data().ifPresent(row->{
            if(current.permissionLevel()>=4 || row.data() instanceof PublicStatusData) {
                add(lines,"Révision "+row.revision().value()+" • tick "+row.capturedTick().value());
                append(row.data(),"",lines,0);
            }
        });
        if(lines.size()==MAX_LINES) lines.set(MAX_LINES-1,"Détail limité : poursuivre dans le dashboard ou l'export audit.");
        lines.forEach(output);
    }
    private static void add(List<String> lines,String value) {
        if(lines.size()<MAX_LINES) {
            String clean=value.replaceAll("[\\p{Cntrl}§]"," ");
            lines.add(clean.length()>MAX_CHARS?clean.substring(0,MAX_CHARS-1)+"…":clean);
        }
    }
    private static void append(Object value,String path,List<String> lines,int depth) {
        if(lines.size()>=MAX_LINES||depth>16) return;
        if(value instanceof Optional<?> optional) { optional.ifPresent(v->append(v,path,lines,depth+1));return; }
        if(value instanceof Iterable<?> values) {
            int index=0;for(Object item:values) {if(lines.size()>=MAX_LINES) break;append(item,path+"["+(index++)+"]",lines,depth+1);}return;
        }
        if(value instanceof Map<?,?> map) {
            for(var entry:map.entrySet()) {if(lines.size()>=MAX_LINES) break;append(entry.getValue(),path+"["+entry.getKey()+"]",lines,depth+1);}return;
        }
        if(value!=null && value.getClass().isRecord()) {
            for(var field:value.getClass().getRecordComponents()) {
                if(lines.size()>=MAX_LINES) break;
                try {append(field.getAccessor().invoke(value),path.isEmpty()?field.getName():path+" / "+field.getName(),lines,depth+1);}
                catch(ReflectiveOperationException failure) {add(lines,"Détail indisponible");}
            }
        } else add(lines,path+" : "+value);
    }
}
