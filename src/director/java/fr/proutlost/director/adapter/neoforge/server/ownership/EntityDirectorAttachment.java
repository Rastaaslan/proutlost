package fr.proutlost.director.adapter.neoforge.server.ownership;

import com.mojang.serialization.Codec;
import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** World-local provenance survives unload; absence never grants ownership or permits respawn. */
public final class EntityDirectorAttachment {
 private static final DeferredRegister<AttachmentType<?>> TYPES=DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES,"proutlost_director");
 private static final Supplier<AttachmentType<String>> OWNER=TYPES.register("entity_ownership",()->AttachmentType.builder(()->"").serialize(Codec.STRING).build());
 private static final ModelCodec<EntityOwnershipRecord> CODEC=new ModelCodec<>(EntityOwnershipRecord.class);
 private EntityDirectorAttachment(){}
 public static void register(IEventBus bus){TYPES.register(bus);}
 private static void actor(Entity entity){if(entity.level().isClientSide()||entity.getServer()==null||!entity.getServer().isSameThread())throw new IllegalStateException("server actor required");}
 public static Optional<EntityOwnershipRecord> read(Entity entity){
  actor(entity);if(!entity.hasData(OWNER.get()))return Optional.empty();
  String value=entity.getData(OWNER.get());if(value.isEmpty())return Optional.empty();
  var record=CODEC.decode(value.getBytes(StandardCharsets.UTF_8));
  if(!record.entityUuid().equals(entity.getUUID()))throw new IllegalStateException("entity provenance mismatch");
  return Optional.of(record);
 }
 public static void initialize(Entity entity,EntityOwnershipRecord record){
  actor(entity);if(read(entity).isPresent()||!record.entityUuid().equals(entity.getUUID()))throw new IllegalArgumentException("existing or mismatched provenance");
  entity.setData(OWNER.get(),new String(CODEC.encode(record),StandardCharsets.UTF_8));
 }
 public static void compareAndSet(Entity entity,EntityOwnershipRecord expected,EntityOwnershipRecord next){
  actor(entity);if(!read(entity).equals(Optional.of(expected))||!expected.entityUuid().equals(next.entityUuid())
    ||!expected.ownerToken().equals(next.ownerToken())||next.header().revision().compareTo(expected.header().revision())<=0
    ||next.generation().compareTo(expected.generation())<0)throw new IllegalArgumentException("stale entity ownership");
  entity.setData(OWNER.get(),new String(CODEC.encode(next),StandardCharsets.UTF_8));
 }
 public static EntityOwnershipRecord adopt(Entity entity,PlayerId player,Tick now){
  var old=read(entity).orElseThrow();
  if(old.adoptedBy().isPresent())throw new IllegalStateException("already adopted");
  var next=new EntityOwnershipRecord(new AggregateHeader(old.header().schemaVersion(),old.header().id(),old.header().revision().plus(1),old.header().lastSequence(),now),old.ownerToken(),old.entityUuid(),old.instanceId(),old.cohortId(),old.slotId(),EntityPhase.ADOPTED,OwnershipDisposition.YIELDED_TO_PLAYER,old.lastKnownChunk(),now,old.persistMode(),old.cleanupPolicy(),Optional.of(player),old.generation().plus(1));
  compareAndSet(entity,old,next);
  if(entity instanceof net.minecraft.world.entity.Mob mob)mob.getNavigation().stop();
  return next;
 }
}
