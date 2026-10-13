package enterfind.server.scan.treatment;

import static enterfind.server.model.FieldNameConstants.FIELD_DATASOURCE_NAME;
import static enterfind.server.model.FieldNameConstants.FIELD_DATASOURCE_TYPE;
import static enterfind.server.model.FieldNameConstants.FIELD_NAME;
import static enterfind.server.model.FieldNameConstants.FIELD_PATH;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import enterfind.server.model.DataSourceInterface;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;

/**
 * Treatment handler that extracts name and path metadata from items.
 */
@Component
public class NameAndPathTreatmentHandler implements TreatmentHandler {

    @Override
    public TreatmentOutput apply(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        return TreatmentOutput.of(applyResults(item, dataProvider, dataSource, treatmentName));
    }

    private List<TreatmentResult> applyResults(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        List<TreatmentResult> results = new ArrayList<>();

        // Add treatment type identifier
        results.add(new TreatmentResult(FIELD_TREATMENT_TYPE, treatmentName));

        results.add(new TreatmentResult(FIELD_NAME, item.getName()));
        
        String path = item.getPath();
        if (path != null && !path.isEmpty()) {
            String[] pathComponents = path.split("/");
            for (String component : pathComponents) {
                String trimmedComponent = component.trim();
                if (!trimmedComponent.isEmpty()) {
                    results.add(new TreatmentResult(FIELD_PATH, trimmedComponent));
                }
            }
        }
        
        // Add datasource name and type
        if (dataSource != null) {
            if (dataSource.getName() != null && !dataSource.getName().isEmpty()) {
                results.add(new TreatmentResult(FIELD_DATASOURCE_NAME, dataSource.getName()));
            }
            if (dataSource.getType() != null) {
                results.add(new TreatmentResult(FIELD_DATASOURCE_TYPE, dataSource.getType().name()));
            }
        }
        
        return results;
    }
}
