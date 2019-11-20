/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019-2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.graph.rest.impl.renderer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opennms.netmgt.graph.api.Edge;
import org.opennms.netmgt.graph.api.ImmutableGraph;
import org.opennms.netmgt.graph.api.ImmutableGraphContainer;
import org.opennms.netmgt.graph.api.Vertex;
import org.opennms.netmgt.graph.api.VertexRef;
import org.opennms.netmgt.graph.api.focus.Focus;
import org.opennms.netmgt.graph.api.generic.GenericEdge;
import org.opennms.netmgt.graph.api.generic.GenericGraphContainer;
import org.opennms.netmgt.graph.api.generic.GenericVertex;
import org.opennms.netmgt.graph.api.info.GraphContainerInfo;
import org.opennms.netmgt.graph.api.info.GraphInfo;
import org.opennms.netmgt.graph.api.info.IpInfo;
import org.opennms.netmgt.graph.api.info.NodeInfo;
import org.opennms.netmgt.graph.api.renderer.GraphRenderer;

public class JsonGraphRenderer implements GraphRenderer {

    @Override
    public String getContentType() {
        return "application/json";
    }

    @Override
    public String render(int identation, List<GraphContainerInfo> containerInfos) {
        final JSONArray graphContainerJsonArray = new JSONArray();
        containerInfos.stream()
            .sorted(Comparator.comparing(GraphContainerInfo::getId))
            .forEach(containerInfo -> {
                final JSONObject jsonGraphContainerInfoObject = new JSONObject();
                jsonGraphContainerInfoObject.put("id", containerInfo.getId());
                jsonGraphContainerInfoObject.put("label", containerInfo.getLabel());
                jsonGraphContainerInfoObject.put("description", containerInfo.getDescription());

                final JSONArray graphInfoArray = new JSONArray();
                containerInfo.getGraphInfos().stream()
                    .sorted(Comparator.comparing(GraphInfo::getNamespace))
                    .forEach(graphInfo -> {
                        final JSONObject jsonGraphInfoObject = new JSONObject();
                        jsonGraphInfoObject.put("namespace", graphInfo.getNamespace());
                        jsonGraphInfoObject.put("label", graphInfo.getLabel());
                        jsonGraphInfoObject.put("description", graphInfo.getDescription());
                        graphInfoArray.put(jsonGraphInfoObject);
                });
                jsonGraphContainerInfoObject.put("graphs", graphInfoArray);
                graphContainerJsonArray.put(jsonGraphContainerInfoObject);
        });
        return graphContainerJsonArray.toString(identation);
    }

    @Override
    public String render(int identation, ImmutableGraphContainer<?> graphContainer) {
        final JSONObject jsonContainer = new JSONObject();
        final JSONArray jsonGraphArray = new JSONArray();
        jsonContainer.put("graphs", jsonGraphArray);

        final GenericGraphContainer genericGraphContainer = graphContainer.asGenericGraphContainer();
        genericGraphContainer.getProperties().forEach((key, value) -> jsonContainer.put(key, value));
        graphContainer.getGraphs()
            .stream()
            .sorted(Comparator.comparing(GraphInfo::getNamespace))
            .forEach(graph -> {
               final JSONObject jsonGraph = convert(graph);
               jsonGraphArray.put(jsonGraph);
            }
        );
        return jsonContainer.toString(identation);
    }

    @Override
    public String render(int identation, ImmutableGraph<?, ?> graph) {
        final JSONObject jsonGraph = convert(graph);
        return jsonGraph.toString(identation);
    }

    @Override
    public String render(int identation, Vertex vertex) {
        final JSONObject jsonVertex = convert(vertex);
        return jsonVertex.toString(identation);
    }

    public static JSONObject convert(ImmutableGraph<?, ?> graph) {
        final JSONObject jsonGraph = new JSONObject();
        final JSONArray jsonEdgesArray = new JSONArray();
        final JSONArray jsonVerticesArray = new JSONArray();
        jsonGraph.put("edges", jsonEdgesArray);
        jsonGraph.put("vertices", jsonVerticesArray);

        if (graph != null) {
            final Map<String, Object> properties = graph.asGenericGraph().getProperties();
            final JSONObject convertedProperties = convert(properties);
            convertedProperties.toMap().forEach(jsonGraph::put);

            // Convert Edges
            graph.getEdges().stream()
                .sorted(Comparator.comparing(Edge::getId))
                .forEach(edge -> {
                    final GenericEdge genericEdge = edge.asGenericEdge();
                    final Map<String, Object> edgeProperties = new HashMap<>(genericEdge.getProperties());
                    edgeProperties.put("source", genericEdge.getSource());
                    edgeProperties.put("target", genericEdge.getTarget());
                    final JSONObject jsonEdge = convert(edgeProperties);
                    jsonEdgesArray.put(jsonEdge);
                });

            // Convert Vertices
            graph.getVertices().stream()
                .sorted(Comparator.comparing(Vertex::getId))
                .forEach(vertex -> {
                    final JSONObject jsonVertex = convert(vertex);
                    jsonVerticesArray.put(jsonVertex);
            });

            // Convert the focus
            final Focus defaultFocus = graph.getDefaultFocus();
            final JSONObject jsonFocus = new JSONObject();
            jsonFocus.put("type", defaultFocus.getId());
            jsonFocus.put("vertexIds", new JSONArray(defaultFocus.getVertexRefs()));
            jsonGraph.put("defaultFocus", jsonFocus);
        }
        return jsonGraph;
    }

    private static JSONObject convert(NodeInfo nodeInfo) {
        final JSONObject jsonNodeInfo = new JSONObject();
        jsonNodeInfo.put("id", nodeInfo.getId());
        jsonNodeInfo.put("label", nodeInfo.getLabel());
        jsonNodeInfo.put("location", nodeInfo.getLocation());
        jsonNodeInfo.put("foreignId", nodeInfo.getForeignId());
        jsonNodeInfo.put("foreignSource", nodeInfo.getForeignSource());
        jsonNodeInfo.put("categories", new JSONArray(nodeInfo.getCategories()));
        jsonNodeInfo.put("ipInterfaces", new JSONArray());

        for (IpInfo eachInterface : nodeInfo.getIpInterfaces()) {
            final JSONObject jsonInterfaceInfo = convert(eachInterface);
            jsonNodeInfo.getJSONArray("ipInterfaces").put(jsonInterfaceInfo);
        }
        return jsonNodeInfo;
    }

    private static JSONObject convert(IpInfo ipInfo) {
        final JSONObject jsonObject = new JSONObject();
        jsonObject.put("address", ipInfo.getIpAddress());
        jsonObject.put("managed", ipInfo.isManaged());
        jsonObject.put("primary", ipInfo.isPrimary());
        return jsonObject;
    }

    private static JSONObject convert(Vertex vertex) {
        final GenericVertex genericVertex = vertex.asGenericVertex();
        final Map<String, Object> properties = genericVertex.getProperties();
        final JSONObject jsonVertex = convert(properties);
        return jsonVertex;
    }

    private static JSONObject convert(VertexRef vertexRef) {
        final JSONObject jsonObject = new JSONObject();
        jsonObject.put("namespace", vertexRef.getNamespace());
        jsonObject.put("id", vertexRef.getId());
        return jsonObject;
    }

    private static JSONObject convert(Map<String, Object> properties) {
        final JSONObject jsonObject = new JSONObject();
        for (Map.Entry<String, Object> eachProperty : properties.entrySet()) {
            final Object value = eachProperty.getValue();
            // TODO MVR make this more generic (ConverterService, etc.) :)
            if (value != null) {
                if (value.getClass().isPrimitive() || value.getClass().isEnum() || value.getClass() == String.class) {
                    jsonObject.put(eachProperty.getKey(), value.toString());
                } else if (value.getClass() == NodeInfo.class) {
                    jsonObject.put(eachProperty.getKey(), convert((NodeInfo) value));
                } else if (value.getClass() == IpInfo.class) {
                    jsonObject.put(eachProperty.getKey(), convert((IpInfo) value));
                } else if (value.getClass() == Class.class) {
                    jsonObject.put(eachProperty.getKey(), ((Class) value).getName());
                } else if (value.getClass() == VertexRef.class) {
                    jsonObject.put(eachProperty.getKey(), convert((VertexRef) value));
                } else {
                    jsonObject.put(eachProperty.getKey(), value);
                }
            }
        }
        return jsonObject;
    }
}