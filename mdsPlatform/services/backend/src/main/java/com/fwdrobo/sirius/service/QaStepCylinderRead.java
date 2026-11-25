package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads explicit Part 21 cylinder declarations without resolving external references. */
final class QaStepCylinderRead {
    static final String OPERATION = "read_step_cylinders";
    static final String REPRESENTATION_OPERATION = "read_step_representation";
    private static final String RECIPE = "step-part21-cylinder-declarations-1";
    private static final String REPRESENTATION_RECIPE = "step-part21-representation-declarations-1";
    private static final Pattern LABEL = Pattern.compile("#[1-9][0-9]{0,17}");
    private static final Pattern ENTITY = Pattern.compile("(?s)^(#[1-9][0-9]{0,17})\\s*=\\s*(.+)$");
    private static final Pattern STRING = Pattern.compile("'(?:[^']++|'')*+'|\"[0-9a-fA-F]*+\"");
    private static final Pattern CYLINDER = Pattern.compile("(?i)^CYLINDRICAL_SURFACE\\s*\\(\\s*''\\s*,\\s*"
            + LABEL.pattern() + "\\s*,\\s*([-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:E[-+]?[0-9]+)?)\\s*\\)$");
    private static final Pattern UNITS = Pattern.compile("(?i)\\bGLOBAL_UNIT_ASSIGNED_CONTEXT\\s*\\(\\s*\\(([^()]*)\\)\\s*\\)");
    private static final Pattern LENGTH = Pattern.compile("(?i)\\bLENGTH_UNIT\\s*\\(\\s*\\)");
    private static final Pattern SHAPE = Pattern.compile("(?i)^(ADVANCED_BREP_SHAPE_REPRESENTATION|MANIFOLD_SURFACE_SHAPE_REPRESENTATION|"
            + "FACETED_BREP_SHAPE_REPRESENTATION|GEOMETRICALLY_BOUNDED_SURFACE_SHAPE_REPRESENTATION|"
            + "GEOMETRICALLY_BOUNDED_WIREFRAME_SHAPE_REPRESENTATION|TESSELLATED_SHAPE_REPRESENTATION|"
            + "EDGE_BASED_WIREFRAME_SHAPE_REPRESENTATION|SHELL_BASED_WIREFRAME_SHAPE_REPRESENTATION|SHAPE_REPRESENTATION)\\s*\\(");
    private static final Pattern METRE = Pattern.compile("(?i)\\bSI_UNIT\\s*\\(\\s*(\\$|\\.[A-Z]+\\.)\\s*,\\s*\\.METRE\\.\\s*\\)");
    private static final Map<String, String> LENGTH_SYMBOLS = Map.of("$", "m", ".KILO.", "km", ".DECI.", "dm",
            ".CENTI.", "cm", ".MILLI.", "mm", ".MICRO.", "µm", ".NANO.", "nm");

    private record Entity(String body, int line) {}
    private record Unit(String symbol, Set<String> declarations) {}

    private QaStepCylinderRead() {}

    static ObjectNode readRepresentation(ObjectMapper json, JsonNode identity, JsonNode request, byte[] raw) throws Exception {
        if (request.size() != 1) {
            throw failure(HttpStatus.BAD_REQUEST, "EVIDENCE_STEP_SELECTOR_INVALID: 表示声明读取不接受外部选择器");
        }
        var entities = entities(new String(raw, StandardCharsets.ISO_8859_1));
        var unit = lengthUnit(entities);
        var result = json.createObjectNode();
        result.put("schemaVersion", "qa-evidence-bundle-2").put("recipeVersion", REPRESENTATION_RECIPE);
        result.set("identity", identity.deepCopy());
        result.put("representationKind", "step_representation_declarations");
        String source = identity.path("sourceSha256").asText();
        String asset = identity.path("assetId").asText();
        result.put("evidenceId", QaNativeEvidenceProjection.stable(asset, source, REPRESENTATION_RECIPE));
        result.put("representationId", QaNativeEvidenceProjection.stable(asset, source, REPRESENTATION_RECIPE, "projection"));
        result.putObject("locator").put("section", "DATA");
        result.putArray("qualityFlags").add("STEP_SOURCE_DECLARATION_NOT_FIELD_MEASUREMENT");
        result.putArray("capabilities").add(REPRESENTATION_OPERATION);
        var content = result.putObject("content");
        content.put("lengthUnit", unit.symbol());
        var statements = content.putArray("representations");
        var facts = result.putArray("facts");
        var unitFact = facts.addObject().put("factId", QaNativeEvidenceProjection.stable(asset, source, REPRESENTATION_RECIPE, "length"))
                .put("field", "lengthUnit").put("value", unit.symbol()).put("evidenceNature", "source_declaration").put("calculationEligible", false);
        unitFact.putObject("locator").set("entityIds", json.valueToTree(unit.declarations()));
        for (var entry : entities.entrySet()) {
            var match = SHAPE.matcher(entry.getValue().body());
            if (!match.find()) continue;
            if (statements.size() == 16) {
                throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_STEP_REPRESENTATION_LIMIT: 表示声明超过当前可完整读取的范围");
            }
            String label = entry.getKey();
            String type = match.group(1).toUpperCase(Locale.ROOT);
            int line = entry.getValue().line();
            statements.addObject().put("entityId", label).put("line", line).put("type", type);
            var fact = facts.addObject().put("factId", QaNativeEvidenceProjection.stable(asset, source, REPRESENTATION_RECIPE, label))
                    .put("field", "shapeRepresentation").put("value", type).put("evidenceNature", "source_declaration").put("calculationEligible", false);
            fact.putObject("locator").put("entityId", label).put("line", line);
        }
        if (statements.isEmpty()) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_STEP_REPRESENTATION_UNRESOLVED: 未找到支持读取的形状表示声明");
        }
        result.putObject("coverage").put("partial", true).put("truncated", false).put("readEntityCount", statements.size());
        return result;
    }

    static ObjectNode read(ObjectMapper json, JsonNode identity, JsonNode request, byte[] raw) throws Exception {
        var labels = request.path("entityIds");
        if (request.size() != 2 || !labels.isArray() || labels.isEmpty() || labels.size() > 8) {
            throw failure(HttpStatus.BAD_REQUEST, "EVIDENCE_STEP_SELECTOR_INVALID: 需要一至八个明确的 STEP 实体号");
        }
        var selected = new LinkedHashSet<String>();
        for (var label : labels) {
            if (!label.isTextual() || !LABEL.matcher(label.asText()).matches() || !selected.add(label.asText())) {
                throw failure(HttpStatus.BAD_REQUEST, "EVIDENCE_STEP_SELECTOR_INVALID: STEP 实体号无效或重复");
            }
        }

        var entities = entities(new String(raw, StandardCharsets.ISO_8859_1));
        var unit = lengthUnit(entities);
        var result = json.createObjectNode();
        result.put("schemaVersion", "qa-evidence-bundle-2").put("recipeVersion", RECIPE);
        result.set("identity", identity.deepCopy());
        result.put("representationKind", "step_cylindrical_surface_declarations");
        String source = identity.path("sourceSha256").asText();
        String asset = identity.path("assetId").asText();
        String selector = String.join(",", selected);
        result.put("evidenceId", QaNativeEvidenceProjection.stable(asset, source, RECIPE, selector));
        result.put("representationId", QaNativeEvidenceProjection.stable(asset, source, RECIPE, "projection", selector));
        result.putObject("locator").set("entityIds", json.valueToTree(selected));
        result.putArray("qualityFlags").add("STEP_SOURCE_DECLARATION_NOT_FIELD_MEASUREMENT");
        result.putArray("capabilities").add(OPERATION);
        result.putObject("coverage").put("partial", true).put("truncated", false).put("readEntityCount", selected.size());
        var content = result.putObject("content");
        content.put("lengthUnit", unit.symbol());
        content.set("lengthUnitEntityIds", json.valueToTree(unit.declarations()));
        content.put("diameterCalculation", "diameter = 2 × radius");
        var rows = content.putArray("cylinders");
        var facts = result.putArray("facts");
        for (String label : selected) {
            var entity = entities.get(label);
            if (entity == null) throw failure(HttpStatus.NOT_FOUND, "EVIDENCE_STEP_ENTITY_NOT_FOUND: 指定实体不属于该源");
            var match = CYLINDER.matcher(STRING.matcher(entity.body()).replaceAll("''"));
            if (!match.matches()) throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_STEP_CYLINDER_REQUIRED: 指定实体不是可读取的圆柱面声明");
            var radius = radius(match.group(1));
            var diameter = radius.multiply(BigDecimal.valueOf(2));
            rows.addObject().put("entityId", label).put("radius", radius).put("diameter", diameter).put("line", entity.line());
            var location = json.createObjectNode().put("entityId", label).put("line", entity.line());
            location.set("lengthUnitEntityIds", json.valueToTree(unit.declarations()));
            String radiusId = QaNativeEvidenceProjection.stable(asset, source, RECIPE, label, "radius");
            var radiusFact = facts.addObject().put("factId", radiusId).put("field", label + " radius").put("value", radius)
                    .put("sourceValue", radius).put("numberSourceExpression", match.group(1)).put("unit", unit.symbol())
                    .put("evidenceNature", "source_declaration").put("calculationEligible", false);
            radiusFact.set("locator", location);
            var diameterFact = facts.addObject().put("factId", QaNativeEvidenceProjection.stable(asset, source, RECIPE, label, "diameter"))
                    .put("field", label + " diameter").put("value", diameter)
                    .put("numberSourceExpression", diameter.toPlainString()).put("unit", unit.symbol())
                    .put("evidenceNature", "program_calculation").put("calculationEligible", true);
            diameterFact.set("locator", location.deepCopy());
            diameterFact.putArray("inputFactIds").add(radiusId);
        }
        return result;
    }

    private static Map<String, Entity> entities(String source) {
        var result = new LinkedHashMap<String, Entity>();
        var record = new StringBuilder();
        int line = 1;
        int startLine = 1;
        char quote = 0;
        boolean envelope = false;
        boolean data = false;
        boolean closed = false;
        for (int offset = 0; offset < source.length(); offset++) {
            char ch = source.charAt(offset);
            if (quote != 0) {
                record.append(ch);
                if (ch == quote) {
                    if (quote == '\'' && offset + 1 < source.length() && source.charAt(offset + 1) == quote) {
                        record.append(source.charAt(++offset));
                    } else {
                        quote = 0;
                    }
                }
            } else if (source.startsWith("/*", offset)) {
                int end = source.indexOf("*/", offset + 2);
                if (end < 0) throw malformed();
                for (; offset <= end; offset++) if (source.charAt(offset) == '\n') line++;
                if (!record.isEmpty()) record.append(' ');
                continue;
            } else if (ch == ';') {
                String statement = record.toString().strip();
                record.setLength(0);
                if (closed) throw malformed();
                if (!envelope) {
                    if (!statement.equalsIgnoreCase("ISO-10303-21")) throw malformed();
                    envelope = true;
                } else if (statement.equalsIgnoreCase("DATA")) {
                    if (data) throw malformed();
                    data = true;
                } else if (statement.equalsIgnoreCase("ENDSEC")) {
                    data = false;
                } else if (statement.equalsIgnoreCase("END-ISO-10303-21")) {
                    if (data) throw malformed();
                    closed = true;
                } else if (data) {
                    var match = ENTITY.matcher(statement);
                    if (!match.matches() || result.putIfAbsent(match.group(1), new Entity(match.group(2), startLine)) != null) {
                        throw malformed();
                    }
                }
            } else if (record.length() > 0 || !Character.isWhitespace(ch)) {
                if (record.length() == 0) startLine = line;
                if (ch == '\'' || ch == '"') quote = ch;
                record.append(ch);
            }
            if (ch == '\n') line++;
        }
        if (!closed || quote != 0 || !record.toString().isBlank() || result.isEmpty()) throw malformed();
        return result;
    }

    private static Unit lengthUnit(Map<String, Entity> entities) {
        String symbol = null;
        var declarations = new LinkedHashSet<String>();
        for (var record : entities.values()) {
            if (!record.body().toUpperCase(Locale.ROOT).contains("GLOBAL_UNIT_ASSIGNED_CONTEXT")) continue;
            String structure = STRING.matcher(record.body()).replaceAll("''");
            if (!structure.toUpperCase(Locale.ROOT).contains("GLOBAL_UNIT_ASSIGNED_CONTEXT")) continue;
            var assignments = UNITS.matcher(structure);
            if (!assignments.find()) throw unknownUnit();
            do {
                String declaration = null;
                for (String ref : assignments.group(1).split(",", -1)) {
                    String label = ref.strip();
                    if (!LABEL.matcher(label).matches() || !entities.containsKey(label)) throw unknownUnit();
                    String body = STRING.matcher(entities.get(label).body()).replaceAll("''");
                    if (!LENGTH.matcher(body).find()) continue;
                    var si = METRE.matcher(body);
                    if (declaration != null || !si.find()) throw unknownUnit();
                    String candidate = LENGTH_SYMBOLS.get(si.group(1).toUpperCase(Locale.ROOT));
                    if (candidate == null || (symbol != null && !symbol.equals(candidate))) throw unknownUnit();
                    symbol = candidate;
                    declaration = label;
                }
                if (declaration == null) throw unknownUnit();
                declarations.add(declaration);
                if (declarations.size() > 8) throw unknownUnit();
            } while (assignments.find());
        }
        if (symbol == null) throw unknownUnit();
        return new Unit(symbol, declarations);
    }

    private static BigDecimal radius(String literal) {
        if (literal.length() > 64) throw malformed();
        try {
            var value = new BigDecimal(literal);
            if (value.signum() <= 0 || Math.abs((long) value.scale()) > 30) throw malformed();
            return value.stripTrailingZeros();
        } catch (NumberFormatException invalid) {
            throw malformed();
        }
    }

    private static ResponseStatusException malformed() {
        return failure(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_STEP_SOURCE_INVALID: STEP 声明无法完整读取");
    }

    private static ResponseStatusException unknownUnit() {
        return failure(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_STEP_LENGTH_UNIT_UNRESOLVED: 无法确定一致的源长度单位");
    }

    private static ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
