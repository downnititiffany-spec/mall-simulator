import com.graduation.analytics.metric.*;
import java.util.*;

var defs = QualityRuleCatalog.DEFAULT.definitions();
System.out.println("DEFS=" + defs.size());
System.out.println("FINGERPRINT=" + QualityRuleCatalog.DEFAULT.fingerprint());
for (var d : defs) {
    System.out.println(String.join("\u0001",
        d.ruleCode(), String.valueOf(d.version()), d.sourceScope(), d.stage(),
        d.severity(), d.severityMode().name(),
        d.thresholdJson() == null ? "" : d.thresholdJson(),
        d.enabled() ? "1" : "0",
        d.effectiveFrom() == null ? "" : d.effectiveFrom(),
        d.effectiveTo() == null ? "" : d.effectiveTo(),
        d.checksum()));
}
/exit
