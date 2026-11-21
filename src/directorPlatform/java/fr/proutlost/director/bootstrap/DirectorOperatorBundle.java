package fr.proutlost.director.bootstrap;

import fr.proutlost.director.api.codec.*;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.catalog.*;
import fr.proutlost.director.config.ApprovedConfig;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded IO/CPU loader called only on the bootstrap worker. No Minecraft
 * object, test-pack conversion or implicit approval enters this path. */
public record DirectorOperatorBundle(ResourceId defaultProfile,Map<ResourceId,ConfigView> profiles,
                                    CatalogSnapshot catalog,ContentReferences references,
                                    Fingerprint manifestHash,String approvalId) {
 public DirectorOperatorBundle {profiles=Map.copyOf(profiles);Objects.requireNonNull(catalog);Objects.requireNonNull(references);}
 public ConfigView configuration(){return Objects.requireNonNull(profiles.get(defaultProfile));}
 public static DirectorOperatorBundle load(Path directory,Fingerprint expected) throws IOException {
  var reader=new Reader(directory);var manifestBytes=reader.read("manifest.json",1024*1024);
  if(!BlobContent.hash(manifestBytes).equals(expected))throw new IllegalArgumentException("operator manifest hash differs from explicit server pin");
  var root=object(CanonicalJsonV1.decode(manifestBytes));
  require(root.keySet().equals(Set.of("schema_version","status","production_approved","approval_id","approved_by",
   "approved_at","default_profile","profiles","primitives","references","documents")),"operator manifest fields");
  require(((JsonValue.Number)root.get("schema_version")).value().intValueExact()==1,"operator manifest version");
  require(text(root.get("status")).equals("APPROVED")&&((JsonValue.Bool)root.get("production_approved")).value(),"explicit operator approval required");
  String approval=text(root.get("approval_id"));nonblank(approval);nonblank(text(root.get("approved_by")));
  java.time.Instant.parse(text(root.get("approved_at")));
  Map<ResourceId,ConfigView> profiles=new HashMap<>();
  var profileFiles=array(root.get("profiles"));require(!profileFiles.isEmpty()&&profileFiles.size()<=16,"approved profile count");
  for(var file:profileFiles){var profile=ApprovedConfig.load(reader.pinned(file));
   require(profile.approvalId().equals(approval),"profile approval differs from manifest");
   require(profiles.putIfAbsent(profile.id(),profile.config())==null,"duplicate approved profile");}
  var primary=new ResourceId(text(root.get("default_profile")));
  var config=Objects.requireNonNull(profiles.get(primary),"default approved profile missing");
  var descriptors=new ArrayList<PrimitiveDescriptor>();
  for(var value:array(CanonicalJsonV1.decode(reader.pinned(root.get("primitives")))))
   descriptors.add(decode(PrimitiveDescriptor.class,value));
  require(descriptors.size()>=10&&descriptors.size()<=128,"native primitive inventory");
  require(descriptors.stream().allMatch(d->d.mode()==ProviderMode.PRODUCTION),"test primitive inventory");
  var refs=references(CanonicalJsonV1.decode(reader.pinned(root.get("references"))));
  var loader=new DefinitionLoader(config,new PrimitiveRegistry(descriptors,ProviderMode.PRODUCTION),refs,
   ProviderMode.PRODUCTION,new DefinitionLoader.Limits(128,1024*1024,16*1024*1024,128,128));
  var files=array(root.get("documents"));require(files.size()>=11&&files.size()<=128,"operator catalogue count");
  var documents=new ArrayList<DefinitionLoader.Source>();
  for(var file:files){var descriptor=object(file);documents.add(new DefinitionLoader.Source(text(descriptor.get("path")),reader.pinned(file)));}
  var result=loader.reload(documents);
  require(result.installed()&&result.diagnostics().isEmpty(),"operator catalogue rejected: "+result.diagnostics());
  require(result.snapshot().events().size()>=9&&result.snapshot().chains().size()>=2,"incomplete operator catalogue");
  return new DirectorOperatorBundle(primary,profiles,result.snapshot(),refs,expected,approval);
 }
 private static ContentReferences references(JsonValue input){
  var root=object(input);require(root.keySet().equals(Set.of("mode","known","mutations","warnings","damages","capabilities","claims")),"reference registry fields");
  require(text(root.get("mode")).equals("PRODUCTION"),"test reference registry");
  var known=new EnumMap<ContentReferences.Kind,Set<ResourceId>>(ContentReferences.Kind.class);
  object(root.get("known")).forEach((kind,ids)->{var values=new HashSet<ResourceId>();for(var id:array(ids))require(values.add(new ResourceId(text(id))),"duplicate known reference");known.put(ContentReferences.Kind.valueOf(kind),Set.copyOf(values));});
  var mutations=new HashMap<ResourceId,MutationProfile>();for(var value:array(root.get("mutations"))){var p=decode(MutationProfile.class,value);
   require(!p.requiredGameTests().isEmpty()&&p.requiredGameTests().stream().noneMatch(s->s.contains("TEST_ONLY")),"unqualified mutation profile");
   require(mutations.putIfAbsent(p.recipeId(),p)==null,"duplicate mutation profile");}
  var warnings=new HashMap<ResourceId,WarningProfile>();for(var value:array(root.get("warnings"))){var p=decode(WarningProfile.class,value);require(warnings.putIfAbsent(p.id(),p)==null,"duplicate warning profile");}
  var damages=new HashMap<ResourceId,DamageProfile>();for(var value:array(root.get("damages"))){var p=decode(DamageProfile.class,value);require(damages.putIfAbsent(p.id(),p)==null,"duplicate damage profile");}
  var caps=new HashSet<CapabilityId>();for(var value:array(root.get("capabilities")))require(caps.add(new CapabilityId(text(value))),"duplicate capability");
  var claims=new HashSet<String>();for(var value:array(root.get("claims")))require(claims.add(text(value)),"duplicate claim");
  return new ContentReferences(ProviderMode.PRODUCTION,known,mutations,warnings,damages,caps,claims);
 }
 private static <T>T decode(Class<T> type,JsonValue value){return new ModelCodec<>(type).decode(CanonicalJsonV1.encode(value));}
 private static Map<String,JsonValue> object(JsonValue value){return ((JsonValue.ObjectValue)value).values();}
 private static List<JsonValue> array(JsonValue value){return ((JsonValue.Array)value).values();}
 private static String text(JsonValue value){return ((JsonValue.Text)value).value();}
 private static void nonblank(String value){require(!value.isBlank()&&value.length()<=256,"operator attestation");}
 private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
 private static final class Reader {
  private final Path root;private long total;private final Set<String> seen=new HashSet<>();
  Reader(Path directory)throws IOException {root=directory.toAbsolutePath().normalize();
   for(Path part=root;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IOException("symlink operator directory");
   if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("operator directory missing");}
  byte[] pinned(JsonValue value)throws IOException {var file=object(value);require(file.keySet().equals(Set.of("path","sha256")),"operator file fields");
   var bytes=read(text(file.get("path")),1024*1024);require(BlobContent.hash(bytes).equals(new Fingerprint(text(file.get("sha256")))),"operator file hash mismatch");return bytes;}
  byte[] read(String relative,int maximum)throws IOException {
   require(relative.matches("[a-z0-9_./-]+\\.json")&&!relative.startsWith("/")&&!relative.contains(".."),"operator relative path");
   require(seen.add(relative)&&seen.size()<=148,"duplicate operator file or file count");
   var path=root.resolve(relative).normalize();require(path.startsWith(root),"operator path escape");
   for(var part=path;!part.equals(root);part=part.getParent())if(Files.isSymbolicLink(part))throw new IOException("symlink operator file");
   try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){var bytes=in.readNBytes(maximum+1);
    require(bytes.length<=maximum,"operator file byte limit");total=Math.addExact(total,bytes.length);require(total<=24*1024*1024,"operator bundle byte limit");return bytes;}
  }
 }
}
