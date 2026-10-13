package enterfind.server.model;

import enterfind.server.scan.Treatments;

/**
 * Information about an available treatment type.
 */
public record TreatmentInfo(
    Treatments name,
    String label,
    String description
) {
    public static TreatmentInfo from(Treatments treatment) {
        return new TreatmentInfo(
            treatment,
            treatment.getLabel(),
            treatment.getDescription()
        );
    }
}
