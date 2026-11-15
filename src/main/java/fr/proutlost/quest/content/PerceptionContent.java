package fr.proutlost.quest.content;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record PerceptionContent(String assetStatus,List<String> sounds,List<String> symbols,Map<String,String> supportPitch,List<String> sequence){
    public PerceptionContent{sounds=List.copyOf(sounds);symbols=List.copyOf(symbols);supportPitch=Map.copyOf(supportPitch);sequence=List.copyOf(sequence);}
    public static PerceptionContent load(){
        try(var in=PerceptionContent.class.getResourceAsStream("/data/proutlost/quest/perception.json")){
            if(in==null)throw new IllegalStateException("Missing perception assets");
            return new Gson().fromJson(new InputStreamReader(in,StandardCharsets.UTF_8),PerceptionContent.class);
        }catch(IOException e){throw new IllegalStateException(e);}
    }
}
