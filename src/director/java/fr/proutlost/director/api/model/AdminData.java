package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface AdminData permits WorldAdminData, EventAdminData, MutationAdminData, ChainAdminData, DisasterAdminData, RegionAdminData, PlayerAdminData, DecisionAdminData, LaboratoryAdminData, LaboratoryResultPageData, OwnershipAdminData, PublicStatusData, RegionMemoryDetailData, MemoryDocumentPageData {}
