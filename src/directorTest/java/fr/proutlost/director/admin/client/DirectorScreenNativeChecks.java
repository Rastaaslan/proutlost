package fr.proutlost.director.admin.client;

import fr.proutlost.director.api.model.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import java.util.*;

/** Operator-invoked native widget checks on an initialized test client only.
 * NOT a network test, GameTest, or proof of multiplayer rendering. Never invoked by the
 * standalone J07 runner. The operator must reserve a Work slot before invoking run(). */
public final class DirectorScreenNativeChecks {
    private DirectorScreenNativeChecks() {}
    public static int run(Minecraft client) {
        if(!client.isSameThread()) throw new IllegalStateException("Client main thread required");
        if(client.screen!=null) throw new IllegalStateException("Close existing screen before isolated widget check");
        UUID id=UUID.randomUUID();var actor=new SessionId(id);
        var session=new SessionIdentity(new WorldId(id),new Epoch(1),new Epoch(1),new DimensionId("minecraft:overworld"),new PlayerId(id));
        List<AdminQuery> queries=new ArrayList<>();List<AdminCommand> commands=new ArrayList<>();
        var screen=new DirectorScreen(actor,session,queries::add,commands::add,(operation,text)-> {
            if(operation!=AdminOperation.PAUSE) throw new AssertionError("Unexpected widget operation");
            return new ControlPayload(RunControl.PAUSED);
        },40,10,50);
        int assertions=0;
        try {
            client.setScreen(screen);
            String[] tabs={"MONDE","ACTIFS","MÉMOIRE","DÉCISIONS","LABORATOIRE"};
            for(var panel:AdminPanel.values()) {
                button(screen,tabs[panel.ordinal()]).onPress();
                for(int tick=0;tick<40;tick++) screen.tick();
                var query=queries.getLast();
                if(query.panel()!=panel) throw new AssertionError("Wrong tab query");assertions++;
                screen.receive(session,new AdminSnapshot(query.requestId(),new Revision(panel.ordinal()+1),new Tick(100),new Sequence(10),List.of(),Optional.empty(),EnumSet.allOf(AdminOperation.class)));
            }
            button(screen,"MONDE").onPress();for(int tick=0;tick<40;tick++)screen.tick();
            var query=queries.getLast();screen.receive(session,new AdminSnapshot(query.requestId(),new Revision(6),new Tick(100),new Sequence(10),List.of(),Optional.empty(),EnumSet.allOf(AdminOperation.class)));
            button(screen,"STATUS").onPress();button(screen,"Envoyer").onPress();
            if(commands.size()!=1||commands.getFirst().operation()!=AdminOperation.PAUSE||commands.getFirst().expectedRevision().value()!=6) throw new AssertionError("Typed pause dispatch");assertions++;
            button(screen,"DÉCISIONS").onPress();
            String decision=UUID.randomUUID().toString(),action=UUID.randomUUID()+" without_ai";
            fields(screen).get(0).setValue(decision);fields(screen).get(1).setValue(action);
            for(int tick=0;tick<40;tick++)screen.tick();
            var filtered=queries.getLast();
            screen.receive(session,new AdminSnapshot(filtered.requestId(),new Revision(9),new Tick(110),new Sequence(11),List.of(),Optional.of("page-two"),EnumSet.allOf(AdminOperation.class)));
            screen.resize(client,screen.width+20,screen.height+20);
            if(!fields(screen).get(0).getValue().equals(decision)||!fields(screen).get(1).getValue().equals(action))
                throw new AssertionError("Resize lost filter/action inputs");assertions++;
            for(int tick=0;tick<40;tick++)screen.tick();
            button(screen,"Suivante").onPress();var next=queries.getLast();
            if(!next.filter().equals(filtered.filter())||!next.cursor().equals(Optional.of("page-two"))
                    ||!next.expectedSnapshotRevision().equals(Optional.of(new Revision(9))))
                throw new AssertionError("Resize broke filtered page continuation");assertions++;
            if(screen.isPauseScreen()) throw new AssertionError("Dashboard must not pause Minecraft");assertions++;
            screen.onClose();if(client.screen!=null) throw new AssertionError("Screen did not close");assertions++;
            return assertions;
        } finally {client.setScreen(null);}
    }
    private static List<EditBox> fields(DirectorScreen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList();
    }
    private static Button button(DirectorScreen screen,String label) {
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow(()->new AssertionError("Missing native button "+label));
    }
}
