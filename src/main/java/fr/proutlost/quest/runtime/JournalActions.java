package fr.proutlost.quest.runtime;

import fr.proutlost.quest.content.Investigation;
import java.util.*;

/** Only called for an already visible page: future support IDs never enter the player's journal. */
public final class JournalActions {
    private JournalActions(){}
    public static List<String> forQuest(String id){
        var actions=new ArrayList<String>();
        Investigation.content().supports().stream().filter(s->s.quest().equals(id)).forEach(s->actions.add("/proutlost quest inspect "+s.id()+" ("+s.siteKind()+")"));
        Investigation.content().comparisons().stream().filter(s->s.quest().equals(id)).forEach(s->actions.add("/proutlost quest relate "+s.id()+" <relevé_1> <relevé_2>, puis test_relation ("+s.siteKind()+")"));
        String command=switch(id){
            case "P0"->"reach_shore";case "P1"->"claim_camp";case "P2"->"activate_distress_signal";
            case "P3"->"examine wreck_motif / examine camp_motif ; share ; compare_prepare ; compare_here";
            case "B1"->"journal — fréquentez le feu ; sa routine peut se manifester";
            case "B2"->"campaign record_arrangement — trois conteneurs, premier emplacement, ouest/centre/est";
            case "B3"->"campaign ancient_arrangement ; campaign prepare_arrangement <relevé> ; campaign compare_arrangement";
            case "C1","C2"->"scene ; recall_perception (reprise sans collecte) ; record_perception <fragment> ; share ; compare_perceptions <relevé_1> <relevé_2>";
            case "C3"->"supports ; sequence_input <supports dans l’ordre choisi>";
            case "J1","J2","F1"->"synthesis "+id;
            case "D1"->"campaign observe_scene — trois occurrences espacées au camp";
            case "D3"->"campaign explore REPAIR / DETOUR ; choice D3 dossier ; choice D3 open/confirm/close ; campaign work <option gagnante>";
            case "E1"->"campaign listen ; campaign record_signal ; campaign compare_signals <relevé_1> <relevé_2>";
            case "E3"->"campaign transmit FILTER / AMPLIFY — plusieurs activations ; le repli doit être préparé";
            case "R1"->"campaign reach_retreat ; campaign repair_transmitter ; campaign transmit AMPLIFY";
            case "F2"->"campaign transmit <méthode maîtrisée> ; campaign record_transmission";
            case "F3"->"choice F3 dossier ; choice F3 open/confirm/close ; choice F3 acknowledge (prendre connaissance du résultat publié)";
            case "F4"->"campaign final_link <relevé sonore> <relevé visuel> ; campaign final_evolve ; campaign final_reply <séquence complète>";
            case "F5"->"campaign epilogue";
            default->"share <relevé> <destinataires>";
        };
        actions.add("/proutlost quest "+command);return List.copyOf(actions);
    }
}
