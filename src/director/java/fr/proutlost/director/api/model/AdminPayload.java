package fr.proutlost.director.api.model;

import java.util.*;
import static fr.proutlost.director.api.model.Checks.*;

public sealed interface AdminPayload permits ControlPayload, TensionPayload, IntensityPayload, TriggerPayload, ReplayPayload, AuditExportPayload, StabilizePayload, SimulationPayload, RecordObservationPayload, RegionInspectionPayload, PlayerInspectionPayload, ChainInspectionPayload, EventInspectionPayload, MutationInspectionPayload, StopPayload, RescuePayload, ReportPayload, EmptyAdminPayload {}
