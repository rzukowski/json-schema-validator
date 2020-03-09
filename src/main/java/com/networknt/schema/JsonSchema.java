/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.schema;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * This is the core of json constraint implementation. It parses json constraint
 * file and generates JsonValidators. The class is thread safe, once it is
 * constructed, it can be used to validate multiple json data concurrently.
 */
public class JsonSchema extends BaseJsonValidator {
    private static final Pattern intPattern = Pattern.compile("^[0-9]+$");
    protected final Map<String, JsonValidator> validators;
    private final ValidationContext validationContext;

    /**
     * This is the current uri of this schema. This uri could refer to the uri of this schema's file
     * or it could potentially be a uri that has been altered by an id. An 'id' is able to completely overwrite
     * the current uri or add onto it. This is necessary so that '$ref's are able to be relative to a
     * combination of the current schema file's uri and 'id' uris visible to this schema.
     *
     * This can be null. If it is null, then the creation of relative uris will fail. However, an absolute
     * 'id' would still be able to specify an absolute uri.
     */
    private final URI currentUri;

    private final String idKeyword;

    private JsonValidator requiredValidator = null;

    public JsonSchema(ValidationContext validationContext, URI baseUri, JsonNode schemaNode) {
        this(validationContext, "#", baseUri, schemaNode, null);
    }

    public JsonSchema(ValidationContext validationContext, String schemaPath, URI currentUri, JsonNode schemaNode,
                JsonSchema parent) {
        this(validationContext,schemaPath, currentUri, schemaNode, parent, false);
    }

    public JsonSchema(ValidationContext validationContext, URI baseUri, JsonNode schemaNode, boolean suppressSubSchemaRetrieval) {
        this(validationContext, "#", baseUri, schemaNode, null, suppressSubSchemaRetrieval);
    }

    private JsonSchema(ValidationContext validationContext, String schemaPath, URI currentUri, JsonNode schemaNode,
                       JsonSchema parent, boolean suppressSubSchemaRetrieval) {
        super(schemaPath, schemaNode, parent, null, suppressSubSchemaRetrieval,
                validationContext.getConfig() != null && validationContext.getConfig().isFailFast());
        this.validationContext = validationContext;
        this.config = validationContext.getConfig();
        this.idKeyword = validationContext.getMetaSchema().getIdKeyword();
        this.currentUri = this.combineCurrentUriWithIds(currentUri, schemaNode);
        this.validators = Collections.unmodifiableMap(this.read(schemaNode));
    }

    private URI combineCurrentUriWithIds(URI currentUri, JsonNode schemaNode) {
        final String id = validationContext.resolveSchemaId(schemaNode);
        if (id == null) {
            return currentUri;
        } else {
            try {
                return this.validationContext.getURIFactory().create(currentUri, id);
            } catch (IllegalArgumentException e) {
                throw new JsonSchemaException(ValidationMessage.of(ValidatorTypeCode.ID.getValue(), ValidatorTypeCode.ID, id, currentUri.toString()));
            }
        }
    }

    public URI getCurrentUri()
    {
        return this.currentUri;
    }

    /**
     * Find the schema node for $ref attribute.
     *
     * @param ref String
     * @return JsonNode
     */
    public JsonNode getRefSchemaNode(String ref) {
        JsonSchema schema = findAncestor();
        JsonNode node = schema.getSchemaNode();

        if (ref.startsWith("#/")) {
            // handle local ref
            String[] keys = ref.substring(2).split("/");
            for (String key : keys) {
                try {
                    key = URLDecoder.decode(key, "utf-8");
                } catch (UnsupportedEncodingException e) {
                }
                Matcher matcher = intPattern.matcher(key);
                if (matcher.matches()) {
                    node = node.get(Integer.parseInt(key));
                } else {
                    node = node.get(key);
                }
                if (node == null) {
                    node = handleNullNode(ref, schema);
                }
                if (node == null) {
                    break;
                }
            }
        } else if (ref.startsWith("#") && ref.length() > 1) {
            node = getNodeById(ref, node);
            if(node == null) {
                node = handleNullNode(ref, schema);
            }
        }
        return node;
    }

    private JsonNode handleNullNode(String ref, JsonSchema schema) {
        JsonSchema subSchema = schema.fetchSubSchemaNode(validationContext);
        if (subSchema != null) {
            return subSchema.getRefSchemaNode(ref);
        }
        return null;
    }

    private JsonNode getNodeById(String ref, JsonNode node) {
        if (nodeContainsRef(ref, node)) {
            return node;
        } else {
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                JsonNode refNode = getNodeById(ref, children.next());
                if (refNode != null) {
                    return refNode;
                }
            }
        }
        return null;
    }

    private boolean nodeContainsRef(String ref, JsonNode node) {
        JsonNode id = node.get(idKeyword);
        if (id != null) {
            return ref.equals(id.asText());
        }
        return false;
    }

    public JsonSchema findAncestor() {
        JsonSchema ancestor = this;
        if (this.getParentSchema() != null) {
            ancestor = this.getParentSchema().findAncestor();
        }
        return ancestor;
    }

    private Map<String, JsonValidator> read(JsonNode schemaNode) {
        Map<String, JsonValidator> validators = new HashMap<String, JsonValidator>();
        if (schemaNode.isBoolean()) {
            if (schemaNode.booleanValue()) {
                JsonValidator validator = validationContext.newValidator(getSchemaPath(), "true", schemaNode, this);
                validators.put(getSchemaPath() + "/true", validator);
            } else {
                JsonValidator validator = validationContext.newValidator(getSchemaPath(), "false", schemaNode, this);
                validators.put(getSchemaPath() + "/false", validator);
            }
        } else {
            Iterator<String> pnames = schemaNode.fieldNames();
            while (pnames.hasNext()) {
                String pname = pnames.next();
                JsonNode nodeToUse = pname.equals("if") ? schemaNode : schemaNode.get(pname);

                JsonValidator validator = validationContext.newValidator(getSchemaPath(), pname, nodeToUse, this);
                if (validator != null) {
                    validators.put(getSchemaPath() + "/" + pname, validator);

                    if (pname.equals("required"))
                        requiredValidator = validator;
                }

            }
        }
        return validators;
    }

    public Set<ValidationMessage> validate(JsonNode jsonNode, JsonNode rootNode, String at) {
        Set<ValidationMessage> errors = new LinkedHashSet<ValidationMessage>();
        for (JsonValidator v : validators.values()) {
            errors.addAll(v.validate(jsonNode, rootNode, at));
        }
        return errors;
    }

    @Override
    public String toString() {
        return "\"" + getSchemaPath() + "\" : " + getSchemaNode().toString();
    }

    public boolean hasRequiredValidator() {
        return requiredValidator != null ? true : false;
    }

    public JsonValidator getRequiredValidator() {
        return requiredValidator;
    }
}
