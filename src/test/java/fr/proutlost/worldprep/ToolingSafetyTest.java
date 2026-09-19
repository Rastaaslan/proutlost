package fr.proutlost.worldprep;
import fr.proutlost.worldprep.preview.PreviewScale;
import fr.proutlost.worldprep.validation.ValidationReport;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ToolingSafetyTest {
 @Test void previewsAreBounded(){var s=PreviewScale.fit(100000,100000,1_000_000);assertTrue((long)s.outputWidth()*s.outputHeight()<=1_000_000);assertTrue(s.blocksPerPixel()>1);}
 @Test void fatalFindingsGateProduction(){var r=new ValidationReport(List.of(new ValidationReport.Finding(ValidationReport.Severity.FATAL,"STALE_PLAN","stale")));assertFalse(r.allowsProductionApply());}
}
