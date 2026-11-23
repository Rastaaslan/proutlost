import java.nio.file.*;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.LocationImport;
import fr.proutlost.director.integration.locations.LocationTransferFiles;
public final class PrepareCampaignTransfer {
 public static void main(String[] args)throws Exception{
  var input=new ModelCodec<>(LocationImport.class).decode(Files.readAllBytes(Path.of(args[0])));
  var published=LocationTransferFiles.writeImmutable(Path.of(args[1]),input);
  System.out.println("CAMPAIGN_G0_AUTHENTIC_PAGED_TRANSPORT pages="+published.pages()+" source="+published.sourceSha256().value());
 }
}
