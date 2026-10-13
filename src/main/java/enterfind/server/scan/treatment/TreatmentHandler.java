package enterfind.server.scan.treatment;

import enterfind.server.model.DataSourceInterface;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;

/**
 * Interface for treatment handlers that extract metadata and content from items.
 *
 * <p>Handlers compute; they do not persist. Everything a handler produces is returned to
 * {@link enterfind.server.scan.TreatmentProcessor}, which writes it in one transaction. A handler
 * that writes to the database itself commits outside that transaction and creates a window where
 * its rows and its treatment results disagree.
 */
public interface TreatmentHandler {

    /**
     * Apply the treatment to the given item and return everything it produced.
     *
     * @param item the item to process
     * @param dataProvider the data provider to access file contents
     * @param dataSource the data source containing the item
     * @param treatmentName the name of the treatment being applied (from Treatments enum)
     * @return the treatment's key-value results, plus any typed side data it owns
     */
    TreatmentOutput apply(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName);

    /**
     * Returns true if this handler can process the given item.
     * Handlers that only support certain file types should override this to avoid
     * creating item_treatments rows for files they will silently skip.
     * Defaults to true (process all items).
     */
    default boolean canProcess(Item item) {
        return true;
    }
}
