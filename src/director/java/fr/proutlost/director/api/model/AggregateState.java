package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface AggregateState permits WorldAggregate, RegionAggregate, RegionMemoryAggregate, LocationAggregate, AnchorAggregate, PlayerOperationalAggregate, PlayerSignificantAggregate, EventAggregate, ChainAggregate, DisasterAggregate, EntityOwnershipAggregate, CooldownAggregate, OwnershipAggregate, MutationAggregate, UniqueAggregate, RequestAggregate { AggregateHeader header(); AggregateKey key(); }
