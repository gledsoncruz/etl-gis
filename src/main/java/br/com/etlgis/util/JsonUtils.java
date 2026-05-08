package br.com.etlgis.util;

import org.geotools.api.feature.simple.SimpleFeature;

public final class JsonUtils {
    private JsonUtils() {
    }

    public static String attributesToJson(SimpleFeature feature) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var prop : feature.getProperties()) {
            String name = prop.getName().toString();
            if ("the_geom".equalsIgnoreCase(name) || "geom".equalsIgnoreCase(name)) {
                continue;
            }

            Object value = prop.getValue();
            if (!first) {
                json.append(',');
            }
            json.append('"').append(escapeJson(name)).append('"').append(':');
            if (value == null) {
                json.append("null");
            } else {
                json.append('"').append(escapeJson(String.valueOf(value))).append('"');
            }
            first = false;
        }

        json.append('}');
        return json.toString();
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
