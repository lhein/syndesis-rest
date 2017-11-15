/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.connector.generator.swagger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.swagger.models.ArrayModel;
import io.swagger.models.HttpMethod;
import io.swagger.models.Model;
import io.swagger.models.ModelImpl;
import io.swagger.models.Operation;
import io.swagger.models.Path;
import io.swagger.models.Response;
import io.swagger.models.Swagger;
import io.swagger.models.parameters.BodyParameter;
import io.swagger.models.parameters.Parameter;
import io.swagger.models.parameters.RefParameter;
import io.swagger.models.parameters.SerializableParameter;
import io.swagger.models.properties.ArrayProperty;
import io.swagger.models.properties.MapProperty;
import io.swagger.models.properties.Property;
import io.swagger.models.properties.RefProperty;
import io.swagger.models.properties.StringProperty;
import io.swagger.parser.SwaggerParser;
import io.syndesis.connector.generator.ConnectorGenerator;
import io.syndesis.core.Json;
import io.syndesis.model.connection.Action;
import io.syndesis.model.connection.ActionDefinition;
import io.syndesis.model.connection.ActionDefinition.ActionDefinitionStep;
import io.syndesis.model.connection.ConfigurationProperty;
import io.syndesis.model.connection.ConfigurationProperty.PropertyValue;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.connection.ConnectorTemplate;
import io.syndesis.model.connection.DataShape;

@SuppressWarnings("PMD.ExcessiveImports")
public class SwaggerConnectorGenerator implements ConnectorGenerator {

    private static final DataShape DATA_SHAPE_NONE = new DataShape.Builder().kind("none").build();

    @Override
    public Connector generate(final ConnectorTemplate connectorTemplate, final Connector template) {
        final Map<String, String> configuredProperties = template.getConfiguredProperties();

        final String specification = configuredProperties.get("specification");

        if (specification == null) {
            throw new IllegalStateException(
                "Configured properties of the given Connector template does not include `specification` property");
        }

        final Connector baseConnector = baseConnectorFrom(connectorTemplate, template);

        final Connector connector = new Connector.Builder()//
            .createFrom(baseConnector)//
            .putConfiguredProperty("specification", specification)//
            .build();

        return configureConnector(connectorTemplate, connector, specification);
    }

    /* default */ static void addGlobalParameters(final Connector.Builder builder, final Swagger swagger) {
        final Map<String, Parameter> globalParameters = swagger.getParameters();
        if (globalParameters != null) {
            globalParameters.forEach((name, parameter) -> {
                createPropertyFromParameter(parameter).ifPresent(property -> {
                    builder.putProperty(name, property);
                });
            });
        }
    }

    /* default */ static Connector configureConnector(final ConnectorTemplate connectorTemplate,
        final Connector connector, final String specification) {

        final Connector.Builder builder = new Connector.Builder().createFrom(connector);

        final Swagger swagger = new SwaggerParser().parse(specification);
        addGlobalParameters(builder, swagger);

        final Map<String, Path> paths = swagger.getPaths();

        final String connectorId = connector.getId().get();
        final String connectorGav = connectorTemplate.getCamelConnectorGAV();
        final String connectorScheme = connectorTemplate.getCamelConnectorPrefix();

        int idx = 0;
        for (final Entry<String, Path> pathEntry : paths.entrySet()) {
            final Path path = pathEntry.getValue();

            final Map<HttpMethod, Operation> operationMap = path.getOperationMap();

            for (final Entry<HttpMethod, Operation> entry : operationMap.entrySet()) {
                final Operation operation = entry.getValue();
                if (operation.getOperationId() == null) {
                    operation.operationId("operation-" + idx++);
                }

                final ActionDefinition actionDefinition = createActionDefinition(specification, operation);

                final Action action = new Action.Builder()//
                    .id(createActionId(connectorId, connectorGav, operation))//
                    .name(Optional.ofNullable(operation.getSummary())
                        .orElseGet(() -> entry.getKey() + " " + pathEntry.getKey()))//
                    .description(Optional.ofNullable(operation.getDescription()).orElse(""))//
                    .camelConnectorGAV(connectorGav)//
                    .camelConnectorPrefix(connectorScheme)//
                    .connectorId(connectorId)//
                    .tags(Optional.ofNullable(operation.getTags()).orElse(Collections.emptyList()))//
                    .definition(actionDefinition)//
                    .build();

                builder.addAction(action);
            }
        }

        if (idx != 0) {
            // we changed the Swagger specification by adding missing
            // operationIds
            builder.putConfiguredProperty("specification", serialize(swagger));
        }

        return builder.build();
    }

    /* default */ static ActionDefinition createActionDefinition(final String specification,
        final Operation operation) {
        final ActionDefinition.Builder actionDefinition = new ActionDefinition.Builder();

        final Optional<BodyParameter> maybeRequestBody = operation.getParameters().stream()
            .filter(p -> p instanceof BodyParameter && ((BodyParameter) p).getSchema() != null)
            .map(BodyParameter.class::cast).findFirst();
        final DataShape inputDataShape = maybeRequestBody
            .map(requestBody -> createShapeFromModel(specification, requestBody.getSchema())).orElse(DATA_SHAPE_NONE);
        actionDefinition.inputDataShape(inputDataShape);

        final Optional<Response> maybeResponse = operation.getResponses().values().stream()
            .filter(r -> r.getSchema() != null).findFirst();
        final DataShape outputDataShape = maybeResponse
            .map(response -> createShapeFromResponse(specification, response)).orElse(DATA_SHAPE_NONE);
        actionDefinition.outputDataShape(outputDataShape);

        final ActionDefinitionStep.Builder step = new ActionDefinitionStep.Builder().name("Query parameters")
            .description("Specify query parameters");

        for (final Parameter parameter : operation.getParameters()) {
            final Optional<ConfigurationProperty> property = createPropertyFromParameter(parameter);

            if (property.isPresent()) {
                step.putProperty(parameter.getName(), property.get());
            }
        }

        step.putProperty("operationId",
            new ConfigurationProperty.Builder()//
                .kind("property")//
                .displayName("operationId")//
                .group("producer")//
                .required(true)//
                .type("hidden")//
                .javaType("java.lang.String")//
                .deprecated(false)//
                .secret(false)//
                .componentProperty(false)//
                .defaultValue(operation.getOperationId())//
                .build());

        actionDefinition.addPropertyDefinitionStep(step.build());

        return actionDefinition.build();
    }

    /* default */ static String createActionId(final String connectorId, final String connectorGav,
        final Operation operation) {
        return connectorGav + ":" + connectorId + ":" + operation.getOperationId();
    }

    /* default */ static List<PropertyValue> createEnums(final List<String> enums) {
        return enums.stream().map(SwaggerConnectorGenerator::createPropertyValue).collect(Collectors.toList());
    }

    /* default */ static Optional<ConfigurationProperty> createPropertyFromParameter(final Parameter parameter) {
        if (parameter instanceof RefParameter || parameter instanceof BodyParameter) {
            // Reference parameters are not supported, body parameters are
            // handled in createShape* methods

            return Optional.empty();
        }

        if (!(parameter instanceof SerializableParameter)) {
            throw new IllegalStateException(
                "Unexpected parameter type received, neither ref, body nor serializable: " + parameter);
        }

        final String name = parameter.getName();
        final String description = parameter.getDescription();
        final boolean required = parameter.getRequired();

        final ConfigurationProperty.Builder propertyBuilder = new ConfigurationProperty.Builder()//
            .kind("property")//
            .displayName(name)//
            .description(description)//
            .group("producer")//
            .required(required)//
            .componentProperty(false)//
            .deprecated(false)//
            .secret(false);

        final SerializableParameter serializableParameter = (SerializableParameter) parameter;

        final String type = serializableParameter.getType();
        propertyBuilder.type(type).javaType(JsonSchemaHelper.javaTypeFor(serializableParameter));

        final List<String> enums = serializableParameter.getEnum();
        if (enums != null) {
            propertyBuilder.addAllEnum(createEnums(enums));
        }

        return Optional.of(propertyBuilder.build());
    }

    /* default */ static PropertyValue createPropertyValue(final String value) {
        return new PropertyValue.Builder().label(value).value(value).build();
    }

    /* default */ static DataShape createShapeFromModel(final String specification, final Model schema) {
        if (schema instanceof ArrayModel) {
            final Property items = ((ArrayModel) schema).getItems();

            return createShapeFromProperty(specification, items);
        } else if (schema instanceof ModelImpl) {
            return createShapeFromModelImpl(schema);
        }

        final String title = Optional.ofNullable(schema.getTitle())
            .orElse(schema.getReference().replaceAll("^.*/", ""));

        return createShapeFromReference(specification, title, schema.getReference());
    }

    /* default */ static DataShape createShapeFromModelImpl(final Model schema) {
        try {
            final String schemaString = Json.mapper().writeValueAsString(schema);

            return new DataShape.Builder().kind("json-schema").specification(schemaString).build();
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException(
                "Unable to serialize given JSON specification in response schema: " + schema, e);
        }
    }

    /* default */ static DataShape createShapeFromProperty(final String specification, final Property schema) {
        if (schema instanceof MapProperty) {
            try {
                final String schemaString = Json.mapper().writeValueAsString(schema);

                return new DataShape.Builder().kind("json-schema").specification(schemaString).build();
            } catch (final JsonProcessingException e) {
                throw new IllegalStateException(
                    "Unable to serialize given JSON specification in response schema: " + schema, e);
            }
        } else if (schema instanceof StringProperty) {
            return DATA_SHAPE_NONE;
        }

        final String reference = determineSchemaReference(schema);

        final String title = Optional.ofNullable(schema.getTitle()).orElse(reference.replaceAll("^.*/", ""));

        return createShapeFromReference(specification, title, reference);
    }

    /* default */ static DataShape createShapeFromReference(final String specification, final String title,
        final String reference) {
        final String jsonSchema = JsonSchemaHelper.resolveSchemaForReference(specification, title, reference);

        return new DataShape.Builder().kind("json-schema").specification(jsonSchema).build();
    }

    /* default */ static DataShape createShapeFromResponse(final String specification, final Response response) {
        final Property schema = response.getSchema();

        return createShapeFromProperty(specification, schema);
    }

    /* default */ static String determineSchemaReference(final Property schema) {
        if (schema instanceof RefProperty) {
            return ((RefProperty) schema).get$ref();
        } else if (schema instanceof ArrayProperty) {
            final Property property = ((ArrayProperty) schema).getItems();

            return determineSchemaReference(property);
        }

        throw new IllegalArgumentException("Only references to schemas are supported");
    }

    /* default */ static String serialize(final Swagger swagger) {
        try {
            return Json.mapper().writeValueAsString(swagger);
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize Swagger specification", e);
        }
    }

}
