package eu.wohlben.qits.cli.access.database.fixtures.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ColorConverter implements AttributeConverter<Color, String> {

    @Override
    public String convertToDatabaseColumn(Color color) {
        return color == null ? null : color.hex();
    }

    @Override
    public Color convertToEntityAttribute(String hex) {
        return hex == null ? null : new Color(hex);
    }
}
