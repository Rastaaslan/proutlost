package fr.proutlost.worldprep;

import fr.proutlost.Proutlost;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GameTestRegistrationTest {
    private boolean isTest(Method method){
        return method.isAnnotationPresent(GameTest.class)||method.isAnnotationPresent(GameTestGenerator.class);
    }
    @Test void eventRegistersEveryGameTestDeclaredInTheProductionPackage()throws Exception{
        var expected=new HashSet<Method>();
        // Discover independently of Proutlost's explicit list so a future omitted class fails this test.
        try(var files=Files.list(Path.of("src/main/java/fr/proutlost/worldprep/gametest"))){
            for(var file:files.filter(p->p.toString().endsWith(".java")).toList()){
                var name=file.getFileName().toString().replace(".java","");
                var type=Class.forName("fr.proutlost.worldprep.gametest."+name,false,getClass().getClassLoader());
                for(var method:type.getDeclaredMethods())if(isTest(method))expected.add(method);
            }
        }
        assertFalse(expected.isEmpty(),"No GameTests discovered");
        var methods=new HashSet<Method>();
        Proutlost.registerGameTests(new RegisterGameTestsEvent(methods));
        var actual=methods.stream().filter(this::isTest).collect(java.util.stream.Collectors.toSet());
        assertEquals(expected,actual,"GameTest methods missing from the actual event callback");
        var classes=actual.stream().map(m->m.getDeclaringClass().getSimpleName()).collect(java.util.stream.Collectors.toSet());
        assertTrue(classes.containsAll(Set.of("WorldPrepCommandGameTests","WorldPrepExistingChunkGameTests",
                "WorldPrepDiskLoadingGameTests","WorldPrepEcologyGameTests","WorldPrepDiscoveryGameTests")));
        // Inventory of registration evidence, explicitly NOT a server execution result.
        var inventory=new com.google.gson.JsonObject();
        inventory.addProperty("schemaVersion",1);
        inventory.addProperty("status","REGISTRATION_VERIFIED_NOT_EXECUTED");
        inventory.addProperty("classes",classes.size());inventory.addProperty("methods",actual.size());
        var names=new com.google.gson.JsonArray();
        actual.stream().map(m->m.getDeclaringClass().getSimpleName()+"."+m.getName()).sorted().forEach(names::add);
        inventory.add("registeredMethods",names);
        var directory=Path.of("build/reports/cb200");Files.createDirectories(directory);
        Files.writeString(directory.resolve("gametest-registration.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(inventory));
    }
    @Test void duplicateRegistrationDoesNotDuplicateMethods(){
        var methods=new HashSet<Method>();
        var event=new RegisterGameTestsEvent(methods);
        Proutlost.registerGameTests(event);
        var once=Set.copyOf(methods);
        Proutlost.registerGameTests(event);
        assertEquals(once,methods);
    }
}
