package fr.proutlost.director.api.model;
import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;
/** Ephemeral captured/hydrated bytes, never embedded in PreparedEffect/PREPARE/WAL. Each BlobContent is copied and hash verified. Transport large contents via BlobCodec, not JSON. */
public record BlobBundle(List<BlobContent> contents) {
 public BlobBundle { contents=list(contents); require(contents.size()<=256,"blob bundle count"); require(contents.stream().mapToLong(b->b.reference().bytes()).sum()<=BlobContent.MAX_BATCH_BYTES,"blob bundle bytes"); require(contents.stream().map(BlobContent::reference).distinct().count()==contents.size(),"duplicate blob reference"); }
}
